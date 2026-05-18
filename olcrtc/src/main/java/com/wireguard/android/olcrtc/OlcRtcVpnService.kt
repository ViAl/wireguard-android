package com.wireguard.android.olcrtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector
import java.io.File

class OlcRtcVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    // JNI native declarations — instance methods (not in companion object)
    private external fun startTun2socksNative(configPath: String, fd: Int): Int
    private external fun stopTun2socksNative()
    private external fun getTun2socksStatsNative(): LongArray

    companion object {
        var currentInstance: OlcRtcVpnService? = null

        const val ACTION_START = "com.wireguard.android.olcrtc.START"
        const val ACTION_STOP = "com.wireguard.android.olcrtc.STOP"
        const val ACTION_CREATE_TUN = "com.wireguard.android.olcrtc.CREATE_TUN"

        // Set after waitReady, used by createTunAndStartTun2socks
        var postReadyTunFd: Int = -1

        const val EXTRA_CONFIG_NAME = "olcrtc_name"
        const val EXTRA_CONFIG_CARRIER = "olcrtc_carrier"
        const val EXTRA_CONFIG_ROOM = "olcrtc_room"
        const val EXTRA_CONFIG_CLIENT = "olcrtc_client"
        const val EXTRA_CONFIG_KEY = "olcrtc_key"
        const val EXTRA_CONFIG_TRANSPORT = "olcrtc_transport"
        const val EXTRA_CONFIG_SOCKS_PORT = "olcrtc_socks_port"
        const val EXTRA_CONFIG_DNS = "olcrtc_dns"
        const val NOTIFICATION_CHANNEL_ID = "olcrtc_vpn"
        const val FOREGROUND_SERVICE_ID = 1001
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = extractConfig(intent)
                if (config != null) {
                    startVpn(config)
                }
            }
            ACTION_STOP -> stopVpn()
            ACTION_CREATE_TUN -> {
                val config = extractConfig(intent)
                if (config != null) {
                    createTunAndStartTun2socks(config, postReadyTunFd)
                }
            }
        }
        return Service.START_NOT_STICKY
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
        return OlcRtcConfig(
            name = name, carrier = carrier, roomId = room,
            clientId = client, keyHex = key, transport = transport,
            socksPort = socksPort, dnsServer = dns
        )
    }

    private fun startVpn(config: OlcRtcConfig) {
        if (isRunning) return

        // Force-load native libraries so R8 doesn't strip unused .so files
        try {
            System.loadLibrary("gojni")
            android.util.Log.d("OlcRtcVpnService", "libgojni.so loaded")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w("OlcRtcVpnService", "libgojni.so not available", e)
        }
        try {
            System.loadLibrary("hev-socks5-tunnel")
            android.util.Log.d("OlcRtcVpnService", "libhev-socks5-tunnel.so loaded")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w("OlcRtcVpnService", "libhev-socks5-tunnel.so not available", e)
        }
        try {
            System.loadLibrary("olcrtc_tun2socks")
            android.util.Log.d("OlcRtcVpnService", "libolcrtc_tun2socks.so loaded")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w("OlcRtcVpnService", "libolcrtc_tun2socks.so not available", e)
        }

        createNotificationChannel()
        val notification = buildNotification(config.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_SERVICE_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }

        // Bind to the active upstream network so non-VPN sockets resolve correctly.
        bindToUpstreamNetwork()

        // Wire SocketProtector — protects Go sockets from going through VPN tunnel
        Mobile.setProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean {
                val instance = currentInstance
                val result = instance?.protect(fd.toInt()) ?: false
                android.util.Log.d("OlcRtcVpnService", "🛡️ SocketProtector.protect(fd=$fd) = $result (instance=${instance != null})")
                return result
            }
        })

        // Wire LogWriter — forward Go logs to logcat
        Mobile.setLogWriter(object : LogWriter {
            override fun writeLog(msg: String?) {
                android.util.Log.d("OlcRtcTransport", "Go: ${msg ?: ""}")
            }
        })

        // Call setProviders to register default implementations
        // Order: setProtector + setLogWriter MUST be called first
        Mobile.setProviders()

        isRunning = true
    }

    fun createTunAndStartTun2socks(config: OlcRtcConfig, clientFd: Int) {
        try {
            val builder = Builder()
            builder.setSession(config.name)
            builder.setMtu(1500)
            builder.addAddress("10.0.2.1", 24)
            builder.addRoute("0.0.0.0", 0)

            val dnsParts = config.dnsServer.split(":")
            builder.addDnsServer(dnsParts[0])

            config.excludedApplications.forEach { builder.addDisallowedApplication(it) }
            config.includedApplications.forEach { builder.addAllowedApplication(it) }

            vpnInterface = builder.establish()
            val fd = vpnInterface!!.fd
            android.util.Log.d("OlcRtcVpnService", "TUN fd=$fd established for ${config.name} (post-Go-ready)")

            val hevConfig = generateHevConfig(config)
            val hevConfigFile = File(filesDir, "hev-socks5-tunnel.conf")
            hevConfigFile.writeText(hevConfig)
            android.util.Log.d("OlcRtcVpnService", "Saving Hev config to ${hevConfigFile.absolutePath}")

            Thread {
                try {
                    val result = startTun2socksNative(hevConfigFile.absolutePath, fd)
                    android.util.Log.d("OlcRtcVpnService", "tun2socks started: result=$result")
                } catch (e: Exception) {
                    android.util.Log.e("OlcRtcVpnService", "tun2socks failed", e)
                }
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcVpnService", "Failed to create TUN after Go ready", e)
        }
    }

    private fun stopVpn() {
        if (!isRunning) return
        android.util.Log.d("OlcRtcVpnService", "Stopping VPN")

        // Stop tun2socks
        try {
            stopTun2socksNative()
            android.util.Log.d("OlcRtcVpnService", "tun2socks stopped")
        } catch (e: Exception) {
            android.util.Log.w("OlcRtcVpnService", "stopTun2socks failed", e)
        }

        vpnInterface?.close()
        vpnInterface = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        unregisterNetworkCallback()
        stopSelf()
    }

    override fun onDestroy() {
        currentInstance = null
        stopVpn()
        unregisterNetworkCallback()
        super.onDestroy()
    }

    private fun bindToUpstreamNetwork() {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // Bind current process to active network immediately
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null) {
                connectivityManager.bindProcessToNetwork(activeNetwork)
                android.util.Log.d("OlcRtcVpnService", "Bound to upstream network: $activeNetwork")

                // Validate binding took effect
                try {
                    val boundNetwork = connectivityManager.getBoundNetworkForProcess()
                    android.util.Log.d("OlcRtcVpnService", "📡 bindProcessToNetwork: target=$activeNetwork, actual bound=$boundNetwork")
                } catch (e: Exception) {
                    android.util.Log.w("OlcRtcVpnService", "📡 getBoundNetworkForProcess failed", e)
                }
            } else {
                android.util.Log.w("OlcRtcVpnService", "No active upstream network to bind to")
            }

            // Register callback to track network changes (WiFi <-> Mobile)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    android.util.Log.d("OlcRtcVpnService", "New network available: $network")
                    connectivityManager.bindProcessToNetwork(network)
                }

                override fun onLost(network: Network) {
                    android.util.Log.d("OlcRtcVpnService", "Network lost: $network")
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
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(it)
                networkCallback = null
                android.util.Log.d("OlcRtcVpnService", "Network callback unregistered")
            }
        } catch (e: Exception) {
            android.util.Log.w("OlcRtcVpnService", "Failed to unregister network callback", e)
        }
    }

    private fun generateHevConfig(config: OlcRtcConfig): String = buildString {
        appendLine("tunnel:")
        appendLine("  mtu: 1500")
        appendLine("socks5:")
        appendLine("  address: 127.0.0.1")
        appendLine("  port: ${config.socksPort}")
        config.socksUser?.let {
            if (it.isNotEmpty()) {
                appendLine("  username: $it")
                appendLine("  password: ${config.socksPass ?: ""}")
            }
        }
        appendLine("misc:")
        appendLine("  task-stack-size: 20480")
        appendLine("  event-loop-count: 2")
        appendLine("  udp-timeout: 300")
        appendLine("  tcp-timeout: 2000")
        appendLine("  resolve-timeout: 10000")
        appendLine("  fast-open: true")
        appendLine("  fore-interface: false")
        appendLine("  is-fragment: false")
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

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("OlcRTC: $tunnelName")
            .setContentText("VPN is active")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }
}
