package com.wireguard.android.olcrtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class OlcRtcVpnService : VpnService() {

    private external fun startTun2socksNative(configPath: String, fd: Int): Int
    private external fun stopTun2socksNative()
    private external fun getTun2socksStatsNative(): LongArray

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var tun2socksThread: Thread? = null
    @Volatile
    private var tun2socksStopRequested = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var currentNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var connectivityManager: ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkLostRunnable = Runnable { clearUnderlyingNetworks() }

    private fun clearUnderlyingNetworks() {
        if (currentNetwork == null) {
            android.util.Log.d("OlcRtcVpnService", "Network handover grace expired, clearing underlying networks")
            setUnderlyingNetworks(null)
        }
    }

    companion object {
        const val ACTION_START = "com.wireguard.android.olcrtc.START"
        const val ACTION_STOP = "com.wireguard.android.olcrtc.STOP"
        const val EXTRA_CONFIG_NAME = "olcrtc_name"
        const val EXTRA_CONFIG_CARRIER = "olcrtc_carrier"
        const val EXTRA_CONFIG_ROOM = "olcrtc_room"
        const val EXTRA_CONFIG_CLIENT = "olcrtc_client"
        const val EXTRA_CONFIG_KEY = "olcrtc_key"
        const val EXTRA_CONFIG_TRANSPORT = "olcrtc_transport"
        const val EXTRA_CONFIG_SOCKS_PORT = "olcrtc_socks_port"
        const val EXTRA_CONFIG_SOCKS_USER = "olcrtc_socks_user"
        const val EXTRA_CONFIG_SOCKS_PASS = "olcrtc_socks_pass"
        const val EXTRA_CONFIG_DNS = "olcrtc_dns"
        const val NOTIFICATION_CHANNEL_ID = "olcrtc_vpn"
        const val FOREGROUND_SERVICE_ID = 1001

        /** Set by OlcRtcVpnService.startVpn() for OlcRtcTransport to use */
        @Volatile
        var protectFn: ((Int) -> Boolean)? = null

        @Volatile
        var tun2socksStarted = false

        fun isTun2socksRunning(): Boolean = tun2socksStarted

        @Volatile
        var tun2socksStats: LongArray? = null

        fun getStats(): LongArray? = tun2socksStats

        @Volatile
        var serviceWakeLock: PowerManager.WakeLock? = null

        fun refreshWakeLock() {
            serviceWakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(24 * 60 * 60 * 1000L)
                }
            }
        }

        fun notifyReconnectExhausted(context: Context, tunnelName: String) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, "OlcRTC VPN",
                    NotificationManager.IMPORTANCE_HIGH
                )
                notificationManager.createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("OlcRTC: $tunnelName")
                .setContentText("Reconnection failed — operation stopped")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setOngoing(false)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(FOREGROUND_SERVICE_ID + 1, notification)
        }

        @Volatile
        private var nativeLibrariesLoaded = false
        private var nativeLibrariesLoadError: Throwable? = null
        private val nativeLibrariesLock = Any()

        fun ensureNativeLibrariesLoaded(): Boolean {
            if (nativeLibrariesLoaded) return true
            nativeLibrariesLoadError?.let { return false }
            return synchronized(nativeLibrariesLock) {
                if (nativeLibrariesLoaded) {
                    true
                } else {
                    try {
                        System.loadLibrary("hev-socks5-tunnel")
                        System.loadLibrary("olcrtc_tun2socks")
                        nativeLibrariesLoaded = true
                        android.util.Log.d("OlcRtcVpnService", "Native libraries loaded")
                        true
                    } catch (e: UnsatisfiedLinkError) {
                        nativeLibrariesLoadError = e
                        android.util.Log.e("OlcRtcVpnService", "Failed to load native libraries", e)
                        false
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OlcRTC::VpnWakeLock")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = extractConfig(intent)
                if (config != null) {
                    startVpn(config)
                }
            }
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }
        }
        return Service.START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        android.util.Log.d("OlcRtcVpnService", "VPN permission revoked")
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    private fun extractConfig(intent: Intent): OlcRtcConfig? {
        val name = intent.getStringExtra(EXTRA_CONFIG_NAME) ?: return null
        val carrier = intent.getStringExtra(EXTRA_CONFIG_CARRIER) ?: return null
        val room = intent.getStringExtra(EXTRA_CONFIG_ROOM) ?: return null
        val client = intent.getStringExtra(EXTRA_CONFIG_CLIENT) ?: return null
        val key = intent.getStringExtra(EXTRA_CONFIG_KEY) ?: return null
        val transport = intent.getStringExtra(EXTRA_CONFIG_TRANSPORT) ?: "vp8channel"
        val socksPort = intent.getIntExtra(EXTRA_CONFIG_SOCKS_PORT, 10808)
        val dns = intent.getStringExtra(EXTRA_CONFIG_DNS) ?: "1.1.1.1:53"
        val socksUser = intent.getStringExtra(EXTRA_CONFIG_SOCKS_USER)?.takeIf { it.isNotEmpty() }
        val socksPass = intent.getStringExtra(EXTRA_CONFIG_SOCKS_PASS)?.takeIf { it.isNotEmpty() }
        return OlcRtcConfig(
            name = name, carrier = carrier, roomId = room,
            clientId = client, keyHex = key, transport = transport,
            socksPort = socksPort, dnsServer = dns,
            socksUser = socksUser, socksPass = socksPass
        )
    }

    private fun startVpn(config: OlcRtcConfig) {
        if (isRunning) return

        // Defensive: verify no other VPN tunnel is active before establishing
        // This prevents a crash if OlcRtcTransport's proactive takeover didn't work
        try {
            val activeNetwork = connectivityManager.activeNetwork
            val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
            val vpnActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            if (vpnActive) {
                android.util.Log.w("OlcRtcVpnService", "Another VPN is still active — attempting prepare()")
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    // Try one more time to revoke the existing VPN
                    prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(prepareIntent)
                    Thread.sleep(500)
                    // Re-check after the attempt
                    val reCaps = connectivityManager.activeNetwork
                        ?.let { connectivityManager.getNetworkCapabilities(it) }
                    if (reCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                        android.util.Log.e("OlcRtcVpnService",
                            "Another VPN is still active — cannot start OlcRTC. Please disconnect the other VPN first.")
                        notifyReconnectExhausted(this, config.name)
                        stopVpn()
                        stopSelf()
                        return
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcVpnService", "Error checking VPN state", e)
        }

        // Acquire wake lock
        wakeLock?.let {
            runCatching {
                if (!it.isHeld) it.acquire(24 * 60 * 60 * 1000L)
            }
        }
        serviceWakeLock = wakeLock

        val builder = Builder()
        builder.setSession(config.name)
        builder.setMtu(1500)
        builder.addAddress("10.0.2.1", 24)
        builder.addRoute("0.0.0.0", 0)

        val dnsParts = config.dnsServer.split(":")
        builder.addDnsServer(dnsParts[0])

        // Exclude our own app from VPN to avoid loop
        addDisallowedApp(builder, packageName)

        config.excludedApplications.forEach { addDisallowedApp(builder, it) }
        config.includedApplications.forEach { addAllowedApp(builder, it) }

        createNotificationChannel()
        val notification = buildNotification(config.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_SERVICE_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }

        vpnInterface = builder.establish()
            ?: throw IllegalStateException("Failed to establish VPN interface")

        val fd = vpnInterface!!.fd
        android.util.Log.d("OlcRtcVpnService", "TUN fd=$fd established for ${config.name}")

        // Expose protect function for OlcRtcTransport to use with Go sockets
        protectFn = { fd -> this.protect(fd) }

        // Bind to upstream network and register monitoring
        bindToUpstreamNetwork()

        // Start hev-socks5-tunnel if native libraries are available
        startTun2socks(vpnInterface!!, config.socksPort, config.socksUser, config.socksPass, config.dnsServer)

        isRunning = true
    }

    private fun stopVpn() {
        if (!isRunning) return
        android.util.Log.d("OlcRtcVpnService", "Stopping VPN")
        isRunning = false
        protectFn = null

        stopTun2socks()
        vpnInterface?.close()
        vpnInterface = null

        serviceWakeLock = null
        wakeLock?.let {
            runCatching { if (it.isHeld) it.release() }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        mainHandler.removeCallbacks(networkLostRunnable)
        unregisterNetworkCallback()
    }

    private fun startTun2socks(pfd: ParcelFileDescriptor, socksPort: Int, socksUser: String?, socksPass: String?, dnsServer: String = "1.1.1.1:53") {
        if (!ensureNativeLibrariesLoaded()) {
            android.util.Log.w("OlcRtcVpnService", "Native libraries not available, skipping tun2socks")
            return
        }

        try {
            val nativeFd = ParcelFileDescriptor.dup(pfd.fileDescriptor).detachFd()
            val configFile = writeTun2socksConfig(socksPort, socksUser, socksPass, dnsServer)
            tun2socksStarted = true
            tun2socksStopRequested = false
            tun2socksThread = thread(name = "OlcRtcTun2Socks", isDaemon = true) {
                try {
                    val result = startTun2socksNative(configFile.absolutePath, nativeFd)
                    if (result != 0) {
                        android.util.Log.w("OlcRtcVpnService", "tun2socks exited with code $result")
                    } else {
                        android.util.Log.d("OlcRtcVpnService", "tun2socks stopped")
                    }
                } finally {
                    tun2socksStarted = false
                    tun2socksStopRequested = false
                }
            }
            // Start periodic stats updater for watchdog stall detection
            thread(name = "OlcRtcTun2Stats", isDaemon = true) {
                while (tun2socksStarted) {
                    try {
                        tun2socksStats = getTun2socksStatsNative()
                    } catch (e: Exception) {
                        tun2socksStats = null
                    }
                    Thread.sleep(5_000L)
                }
                tun2socksStats = null
            }
            android.util.Log.d("OlcRtcVpnService", "tun2socks started on SOCKS port $socksPort")
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcVpnService", "Failed to start tun2socks", e)
        }
    }

    private fun stopTun2socks() {
        if (tun2socksStarted && !tun2socksStopRequested) {
            tun2socksStopRequested = true
            runCatching { stopTun2socksNative() }
        }
        tun2socksStats = null
        tun2socksThread?.interrupt()
        tun2socksThread = null
    }

    private fun writeTun2socksConfig(socksPort: Int, socksUser: String?, socksPass: String?, dnsServer: String = "1.1.1.1:53"): File {
        val user = socksUser.orEmpty()
        val pass = socksPass.orEmpty()
        val dnsParts = dnsServer.split(":")
        val dnsAddr = dnsParts[0]
        val dnsPort = dnsParts.getOrElse(1) { "53" }
        val file = File(filesDir, "olcrtc_tun2socks.yml")
        file.writeText(
            """
            tunnel:
              name: tun0
              mtu: 1500
              multi-queue: false
              ipv4: 10.0.2.1

            socks5:
              address: 127.0.0.1
              port: $socksPort
              udp: 'tcp'
              pipeline: false
              username: '$user'
              password: '$pass'

            mapdns:
              address: $dnsAddr
              port: $dnsPort
              network: 100.64.0.0
              netmask: 255.192.0.0
              cache-size: 10000

            misc:
              task-stack-size: 24576
              tcp-buffer-size: 4096
              max-session-count: 1200
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-file: stderr
              log-level: warn
            """.trimIndent()
        )
        return file
    }

    private fun addAllowedApp(builder: Builder, targetPackage: String) {
        runCatching { builder.addAllowedApplication(targetPackage) }
    }

    private fun addDisallowedApp(builder: Builder, targetPackage: String) {
        runCatching { builder.addDisallowedApplication(targetPackage) }
    }

    private fun bindToUpstreamNetwork() {
        try {
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null) {
                currentNetwork = activeNetwork
                connectivityManager.bindProcessToNetwork(activeNetwork)
                setUnderlyingNetworks(arrayOf(activeNetwork))
                android.util.Log.d("OlcRtcVpnService", "Bound to upstream network: $activeNetwork")
            } else {
                android.util.Log.w("OlcRtcVpnService", "No active upstream network to bind to")
            }

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    android.util.Log.d("OlcRtcVpnService", "New network available: $network")
                    connectivityManager.bindProcessToNetwork(network)
                    currentNetwork = network
                    mainHandler.removeCallbacks(networkLostRunnable)
                    setUnderlyingNetworks(arrayOf(network))
                    // If currently in ERROR/DISCONNECTED state, trigger reconnect
                    OlcRtcManager.notifyNetworkAvailable()
                }

                override fun onLost(network: Network) {
                    android.util.Log.d("OlcRtcVpnService", "Network lost: $network, waiting 3s for handover...")
                    if (network == currentNetwork) {
                        currentNetwork = null
                        // Give 3s grace period for handover — onAvailable may fire with new network
                        mainHandler.removeCallbacks(networkLostRunnable)
                        mainHandler.postDelayed(networkLostRunnable, 3000L)
                    }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    android.util.Log.d("OlcRtcVpnService", "Network capabilities changed: $network")
                }
            }

            networkCallback = callback
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
            android.util.Log.d("OlcRtcVpnService", "Network callback registered")
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcVpnService", "Failed to bind to upstream network", e)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
                networkCallback = null
                android.util.Log.d("OlcRtcVpnService", "Network callback unregistered")
            }
        } catch (e: Exception) {
            android.util.Log.w("OlcRtcVpnService", "Failed to unregister network callback", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "OlcRTC VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(tunnelName: String): Notification {
        val stopIntent = Intent(this, OlcRtcVpnService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPi = if (contentIntent != null) {
            PendingIntent.getActivity(this, 1, contentIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else null

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("OlcRTC: $tunnelName")
            .setContentText("VPN is active")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(contentPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }
}
