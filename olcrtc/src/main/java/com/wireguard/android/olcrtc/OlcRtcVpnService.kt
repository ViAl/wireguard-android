package com.wireguard.android.olcrtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android VpnService for OlcRTC tunnel.
 *
 * Manages:
 * - TUN interface lifecycle
 * - tun2socks native process lifecycle (with double-start guard)
 * - Network handover with configurable timeout and graceful degradation
 * - Network callback for connectivity validation before Go client startup
 */
class OlcRtcVpnService : android.net.VpnService() {

    companion object {
        private const val TAG = "OlcRtcVpnService"

        private const val NOTIFICATION_CHANNEL_ID = "olcrtc_vpn"
        private const val NOTIFICATION_ID = 1
        private const val FOREGROUND_SERVICE_ID = 1001

        // Intent actions
        const val ACTION_START = "com.wireguard.android.olcrtc.START"
        const val ACTION_STOP = "com.wireguard.android.olcrtc.STOP"

        // Handover timeout — increased from 3s to 15s for MIUI network switching
        const val HANDOVER_TIMEOUT_MS = 15_000L

        // Delay before starting Go client after network validates
        private const val GO_CLIENT_START_DELAY_MS = 500L

        /**
         * Start the OlcRTC VPN service.
         */
        fun startIntent(context: Context, config: OlcRtcConfig? = null): Intent {
            return Intent(context, OlcRtcVpnService::class.java).apply {
                action = ACTION_START
                config?.let { putExtra("config_name", it.name) }
            }
        }

        /**
         * Stop the OlcRTC VPN service.
         */
        fun stopIntent(context: Context): Intent {
            return Intent(context, OlcRtcVpnService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }

    // === tun2socks double-start guard ===
    private val tun2socksStarted = AtomicBoolean(false)

    // === Coroutine scope for async operations ===
    private var serviceScope: CoroutineScope? = null
    private var goClientJob: Job? = null
    private var handoverJob: Job? = null
    private var tun2socksThread: Thread? = null

    // === VPN state ===
    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile
    private var isRunning = false
    @Volatile
    private var config: OlcRtcConfig? = null

    // === Network management ===
    private var connectivityManager: ConnectivityManager? = null
    private var activeNetwork: Network? = null
    private var pendingNetwork: Network? = null
    private var previousNetwork: Network? = null
    @Volatile
    private var networkValidated = false
    @Volatile
    private var isNetworkDegraded = false
    private var handoverTimeoutMs = HANDOVER_TIMEOUT_MS

    // === Network callback ===
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available: ${network}")
            // Don't immediately switch — wait for validation
            pendingNetwork = network
            networkValidated = false
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            Log.d(TAG, "Network capabilities changed for ${network}: " +
                    "validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}, " +
                    "internet=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}")

            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                // Network is validated and has internet — proceed
                networkValidated = true
                onNetworkReady(network)
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost: ${network}")
            if (network == activeNetwork) {
                // Current network is lost — start handover grace period
                startHandover(network)
            }
        }

        override fun onUnavailable() {
            Log.w(TAG, "Network unavailable")
        }
    }

    // ─────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "ACTION_START received")
                startVpn()
            }
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP received")
                stopVpn()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopVpn()
        serviceScope?.cancel()
        serviceScope = null
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.d(TAG, "onRevoke — VPN revoked by system")
        stopVpn()
        stopSelf()
    }

    // ─────────────────────────────────────────────────
    // VPN Start / Stop
    // ─────────────────────────────────────────────────

    /**
     * Start the VPN interface and tunnel.
     * Guarded against double invocation via [tun2socksStarted] AtomicBoolean.
     */
    private fun startVpn() {
        if (isRunning) {
            Log.w(TAG, "startVpn called but already running — ignored")
            return
        }

        Log.d(TAG, "startVpn: configuring TUN interface")

        try {
            // Build VpnService.Builder
            val builder = Builder()
            // DNS
            builder.addDnsServer(config?.dnsServer?.substringBefore(":") ?: "1.1.1.1")
            // Routes — default route
            builder.addAddress("10.0.0.2", 32)
            builder.addRoute("0.0.0.0", 0)
            // Optional per-app exclusion/inclusion
            config?.excludedApplications?.forEach { builder.addDisallowedApplication(it) }
            config?.includedApplications?.forEach { builder.addAllowedApplication(it) }
            // MTU
            builder.setMtu(1500)
            // Session name
            builder.setSession(config?.name ?: "OlcRTC")

            // Blocking: establish TUN interface
            val tunFd = builder.establish()
            vpnInterface = tunFd
            isRunning = true

            // Start foreground notification
            startForeground(FOREGROUND_SERVICE_ID, buildNotification())

            // Register network callback to monitor connectivity
            registerNetworkCallback()

            // Attempt to start tun2socks
            startTun2socks(tunFd)

            Log.i(TAG, "VPN started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            isRunning = false
            vpnInterface?.close()
            vpnInterface = null
        }
    }

    /**
     * Stop the VPN interface and tunnel.
     * Ensures full cleanup: tun2socks stopped → thread joined → TUN closed.
     */
    private fun stopVpn() {
        Log.d(TAG, "stopVpn: starting cleanup")

        // Cancel handover job if active
        handoverJob?.cancel()
        handoverJob = null

        // Cancel Go client start if pending
        goClientJob?.cancel()
        goClientJob = null

        // Clear pending networks
        pendingNetwork = null
        previousNetwork = null
        activeNetwork = null
        networkValidated = false
        isNetworkDegraded = false

        // Unregister network callback
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering network callback", e)
        }

        // 1. Stop tun2socks first — signal native thread to stop
        stopTun2socks()

        // 2. Join tun2socks thread — guarantee it has fully exited
        tun2socksThread?.let { thread ->
            if (thread.isAlive) {
                try {
                    Log.d(TAG, "Joining tun2socks thread...")
                    thread.join(TimeUnit.SECONDS.toMillis(5))
                    if (thread.isAlive) {
                        Log.w(TAG, "tun2socks thread did not finish within 5s")
                    } else {
                        Log.d(TAG, "tun2socks thread joined successfully")
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.w(TAG, "Interrupted while joining tun2socks thread", e)
                }
            }
            tun2socksThread = null
        }

        // 3. Reset tun2socks guard
        tun2socksStarted.set(false)

        // 4. Close VPN interface
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
        isRunning = false

        // Stop foreground service
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping foreground", e)
        }

        Log.i(TAG, "VPN stopped — cleanup complete")
    }

    // ─────────────────────────────────────────────────
    // tun2socks Management
    // ─────────────────────────────────────────────────

    /**
     * Start the tun2socks native tunnel on the given TUN file descriptor.
     * Protected by AtomicBoolean to prevent double-start.
     */
    private fun startTun2socks(tunFd: ParcelFileDescriptor) {
        if (!tun2socksStarted.compareAndSet(false, true)) {
            Log.w(TAG, "startTun2socks: already started — guard prevented double-start")
            return
        }

        Log.d(TAG, "startTun2socks: starting native tunnel on fd=${tunFd.fd}")

        // Start native tun2socks in a dedicated thread
        tun2socksThread = Thread({
            try {
                Log.d(TAG, "tun2socks thread started")
                // Phase 1: stub — will call native startTun2socksNative(fd) in Phase 2
                // For now, thread holds the fd reference
                Thread.sleep(Long.MAX_VALUE)
            } catch (e: InterruptedException) {
                Log.d(TAG, "tun2socks thread interrupted — shutting down")
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e(TAG, "tun2socks native error", e)
            } finally {
                Log.d(TAG, "tun2socks thread exiting")
                // Native cleanup happens here in Phase 2
            }
        }, "OlcRtcTun2Socks").also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * Stop the tun2socks native tunnel.
     * Signals the thread to stop; caller should join() to guarantee completion.
     */
    private fun stopTun2socks() {
        Log.d(TAG, "stopTun2socks: signaling stop")
        tun2socksThread?.interrupt()
    }

    // ─────────────────────────────────────────────────
    // Handover Management
    // ─────────────────────────────────────────────────

    /**
     * Start handover grace period when current network is lost.
     * Instead of immediately cleaning up, we wait for [HANDOVER_TIMEOUT_MS]
     * before switching to fallback.
     */
    private fun startHandover(lostNetwork: Network) {
        handoverJob?.cancel()
        handoverJob = null

        Log.d(TAG, "Handover started for lost network ${lostNetwork} — timeout=${handoverTimeoutMs}ms")

        // Mark the lost network as previous, keep it as fallback
        previousNetwork = lostNetwork
        activeNetwork = null
        isNetworkDegraded = true

        handoverJob = serviceScope?.launch {
            delay(handoverTimeoutMs)

            // Handover expired — check if we have a pending validated network
            if (networkValidated && pendingNetwork != null) {
                // New network is ready — switch to it gracefully
                Log.d(TAG, "Handover: switching to validated network ${pendingNetwork}")
                switchToNetwork(pendingNetwork!!)
                isNetworkDegraded = false
            } else {
                // No validated network yet — degrade gracefully instead of dropping
                Log.w(TAG, "Handover expired: no validated network — keeping fallback")
                // Keep previous network as degraded fallback
                // Continue trying to bind to new network as it becomes available
                // Only initiate reconnect if we have no fallback AND no pending network
                if (previousNetwork == null && pendingNetwork == null) {
                    Log.e(TAG, "Handover expired: all networks lost — need reconnect")
                    notifyNetworkLost()
                }
            }
        }
    }

    /**
     * Switch to the given validated network.
     * Graceful transition — doesn't drop existing connections immediately.
     */
    private fun switchToNetwork(network: Network) {
        try {
            connectivityManager?.let { cm ->
                // Bind to new network for new sockets
                cm.bindProcessToNetwork(network)
                activeNetwork = network
                Log.i(TAG, "Switched to new network: ${network}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to new network", e)
        }
    }

    /**
     * Called when a network becomes validated and ready.
     */
    private fun onNetworkReady(network: Network) {
        Log.d(TAG, "Network ready: ${network}")

        // If we were in handover, complete the switch
        if (isNetworkDegraded || activeNetwork == null) {
            switchToNetwork(network)
            isNetworkDegraded = false
            handoverJob?.cancel()
            handoverJob = null
        }

        // Start Go client if not yet running
        if (isRunning && goClientJob?.isActive != true) {
            startGoClient()
        }
    }

    /**
     * Start the Go client (SOCKS5/KCP tunnel) after network is stable.
     * Waits for network validation before launching.
     */
    private fun startGoClient() {
        if (goClientJob?.isActive == true) {
            Log.d(TAG, "Go client already starting/running — skipping")
            return
        }

        if (!networkValidated && activeNetwork == null) {
            Log.d(TAG, "Network not yet validated — deferring Go client start")
            return
        }

        goClientJob = serviceScope?.launch {
            try {
                // Small delay to let network fully stabilize
                delay(GO_CLIENT_START_DELAY_MS)

                // Phase 1: stub — will call Mobile.Start() in Phase 2
                Log.d(TAG, "Go client starting (network validated: $networkValidated)")
            } catch (e: CancellationException) {
                Log.d(TAG, "Go client start cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Go client start failed", e)
            }
        }
    }

    /**
     * Notify manager that all networks are lost and reconnect is needed.
     */
    private fun notifyNetworkLost() {
        Log.w(TAG, "All networks lost — signaling reconnect needed")
        // Phase 2: notify OlcRtcManager via callback/listener
    }

    // ─────────────────────────────────────────────────
    // Network Monitoring
    // ─────────────────────────────────────────────────

    private fun registerNetworkCallback() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
            Log.d(TAG, "Network callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Returns current handover timeout in milliseconds (configurable).
     */
    fun getHandoverTimeoutMs(): Long = handoverTimeoutMs

    /**
     * Set handover timeout (useful for testing or runtime configuration).
     */
    fun setHandoverTimeoutMs(timeoutMs: Long) {
        handoverTimeoutMs = timeoutMs
    }

    // ─────────────────────────────────────────────────
    // Foreground Notification
    // ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "OlcRTC VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "OlcRTC tunnel service notification"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForSelector(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("OlcRTC VPN")
            .setContentText(config?.let { "Connected to ${it.name}" } ?: "Connected")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
