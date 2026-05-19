package com.wireguard.android.olcrtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import kotlinx.coroutines.CompletableDeferred

class OlcRtcVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var initPhase = false  // true during ACTION_PREPARE phase (before TUN established)

    /**
     * Flag to prevent concurrent calls to [stopVpn] from ACTION_STOP (async, stopExecutor)
     * and onDestroy (sync, main thread).
     */
    @Volatile
    private var stopInProgress = false

    @Volatile
    private var tunThread: Thread? = null

    /** Single-thread executor for non-blocking stopVpn shutdown. */
    private val stopExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * Set to true if the tun2socks thread exits with an error before the
     * 500ms startup delay fires. Used to suppress a false TUN2SOCKS_STARTED.
     */
    @Volatile
    private var tunThreadErrored: Boolean = false

    // JNI native declarations — instance methods (not in companion object)
    private external fun startTun2socksNative(configPath: String, fd: Int): Int
    private external fun stopTun2socksNative()
    private external fun getTun2socksStatsNative(): LongArray

    companion object {
        var currentInstance: OlcRtcVpnService? = null

        /**
         * Monotonically increasing counter to detect stale [Handler.postDelayed]
         * callbacks from previous VpnService sessions.
         */
        val sessionCounter = java.util.concurrent.atomic.AtomicLong(0)

        /**
         * Signaled by [onCreate] for deterministic VpnService creation wait.
         * Replaced with a fresh deferred each session by [OlcRtcTransport].
         */
        @Volatile
        var serviceReady: CompletableDeferred<Unit> = CompletableDeferred()

        /**
         * Set by [OlcRtcTransport] before ACTION_PREPARE, read by [onCreate]
         * to verify that the service instance that was created matches the
         * expected prepare session.
         */
        @Volatile
        var expectedServiceReadySession: Long = -1L

        /**
         * Wait duration before confirming TUN2SOCKS_STARTED.
         * Prevents premature connected state if tun2socks fails immediately.
         */
        private const val TUN2SOCKS_STARTUP_DELAY_MS = 500L

        /** Callback invoked when VPN lifecycle events occur. Set by Transport. */
        @Volatile
        var onVpnStatus: ((VpnStatusEvent) -> Unit)? = null

        const val ACTION_PREPARE = "com.wireguard.android.olcrtc.PREPARE"
        const val ACTION_START = "com.wireguard.android.olcrtc.START"
        const val ACTION_STOP = "com.wireguard.android.olcrtc.STOP"

        const val EXTRA_CONFIG_NAME = "olcrtc_name"
        const val EXTRA_CONFIG_CARRIER = "olcrtc_carrier"
        const val EXTRA_CONFIG_ROOM = "olcrtc_room"
        const val EXTRA_CONFIG_CLIENT = "olcrtc_client"
        const val EXTRA_CONFIG_KEY = "olcrtc_key"
        const val EXTRA_CONFIG_TRANSPORT = "olcrtc_transport"
        const val EXTRA_CONFIG_SOCKS_PORT = "olcrtc_socks_port"
        const val EXTRA_CONFIG_DNS = "olcrtc_dns"
        const val EXTRA_CONFIG_APP_ROUTING_MODE = "olcrtc_app_routing_mode"
        const val EXTRA_CONFIG_EXCLUDED_APPS = "olcrtc_excluded_apps"
        const val EXTRA_CONFIG_INCLUDED_APPS = "olcrtc_included_apps"
        const val EXTRA_CONFIG_ROUTE_ALL_IPV4 = "olcrtc_route_all_ipv4"
        const val EXTRA_CONFIG_ROUTE_ALL_IPV6 = "olcrtc_route_all_ipv6"
        const val EXTRA_CONFIG_SOCKS_USER = "olcrtc_socks_user"
        const val EXTRA_CONFIG_SOCKS_PASS = "olcrtc_socks_pass"
        const val NOTIFICATION_CHANNEL_ID = "olcrtc_vpn"
        const val FOREGROUND_SERVICE_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        val session = sessionCounter.get()
        if (session == expectedServiceReadySession || expectedServiceReadySession == -1L) {
            serviceReady.complete(Unit)
        } else {
            android.util.Log.w("OlcRtcVpnService",
                "onCreate: session=$session != expected=$expectedServiceReadySession, serviceReady NOT signaled")
        }
        android.util.Log.d("OlcRtcVpnService",
            "VpnService created, session=$session, expected=$expectedServiceReadySession, " +
            "currentInstance set")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> {
                android.util.Log.d("OlcRtcVpnService", "ACTION_PREPARE received")
                // Phase 1: Create VpnService instance with notification so
                // startForegroundService lifecycle is satisfied.
                // Does NOT establish TUN or start tun2socks — just sets up
                // currentInstance for socket protection.
                if (!isRunning && !initPhase) {
                    initPhase = true
                    createNotificationChannel()
                    val notification = buildNotification("OlcRTC")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(FOREGROUND_SERVICE_ID, notification,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else {
                        startForeground(FOREGROUND_SERVICE_ID, notification)
                    }
                    android.util.Log.d("OlcRtcVpnService", "VpnService initialized for socket protection")
                }
            }
            ACTION_START -> {
                android.util.Log.d("OlcRtcVpnService", "ACTION_START received for ${intent.getStringExtra(EXTRA_CONFIG_NAME)}")
                val config = extractConfig(intent)
                if (config != null) {
                    startVpn(config)
                }
            }
            ACTION_STOP -> stopVpn(async = true)
        }
        return Service.START_NOT_STICKY
    }

    private fun extractConfig(intent: Intent): OlcRtcConfig? {
        val name = intent.getStringExtra(EXTRA_CONFIG_NAME) ?: return null
        val carrier = intent.getStringExtra(EXTRA_CONFIG_CARRIER) ?: return null
        val room = intent.getStringExtra(EXTRA_CONFIG_ROOM) ?: return null
        val client = intent.getStringExtra(EXTRA_CONFIG_CLIENT) ?: return null
        val key = intent.getStringExtra(EXTRA_CONFIG_KEY) ?: return null
        val transport = intent.getStringExtra(EXTRA_CONFIG_TRANSPORT) ?: "datachannel"
        val socksPort = intent.getIntExtra(EXTRA_CONFIG_SOCKS_PORT, 1080)
        val dns = intent.getStringExtra(EXTRA_CONFIG_DNS) ?: "1.1.1.1:53"
        // Restore missing fields
        val appRoutingMode = intent.getStringExtra(EXTRA_CONFIG_APP_ROUTING_MODE)
            ?.let { try { AppRoutingMode.valueOf(it) } catch (_: Exception) { null } }
            ?: AppRoutingMode.ALL_APPS
        val excludedApps = intent.getStringArrayListExtra(EXTRA_CONFIG_EXCLUDED_APPS)
            ?.toSet() ?: emptySet()
        val includedApps = intent.getStringArrayListExtra(EXTRA_CONFIG_INCLUDED_APPS)
            ?.toSet() ?: emptySet()
        val routeAllIpv4 = intent.getBooleanExtra(EXTRA_CONFIG_ROUTE_ALL_IPV4, true)
        val routeAllIpv6 = intent.getBooleanExtra(EXTRA_CONFIG_ROUTE_ALL_IPV6, false)
        val socksUser = intent.getStringExtra(EXTRA_CONFIG_SOCKS_USER)
        val socksPass = intent.getStringExtra(EXTRA_CONFIG_SOCKS_PASS)
        return OlcRtcConfig(
            name = name, carrier = carrier, roomId = room,
            clientId = client, keyHex = key, transport = transport,
            socksPort = socksPort, dnsServer = dns,
            appRoutingMode = appRoutingMode,
            excludedApplications = excludedApps,
            includedApplications = includedApps,
            routeAllIpv4 = routeAllIpv4,
            routeAllIpv6 = routeAllIpv6,
            socksUser = socksUser,
            socksPass = socksPass
        )
    }

    private fun startVpn(config: OlcRtcConfig) {
        if (isRunning) return

        // Capture session counter to ignore stale postDelayed from previous sessions
        val currentSession = sessionCounter.incrementAndGet()

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

        val builder = Builder()
        builder.setSession(config.name)
        builder.setMtu(1500)

        // Apply IPv4 routing (default: full-tunnel)
        if (config.routeAllIpv4) {
            builder.addAddress("10.0.2.1", 24)
            builder.addRoute("0.0.0.0", 0)
        }

        // Apply IPv6 routing if enabled (MVP: IPv4-only by default)
        if (config.routeAllIpv6) {
            builder.addAddress("fd00:1:2:3::1", 64)
            builder.addRoute("::", 0)
        }

        // DNS
        val dnsParts = config.dnsServer.split(":")
        builder.addDnsServer(dnsParts[0])

        // App routing — use AppRoutingMode to prevent conflicting addAllowed/DisallowedApplication calls
        when (config.appRoutingMode) {
            AppRoutingMode.ALL_APPS -> {
                // No per-app rules — all traffic routes through VPN
            }
            AppRoutingMode.ONLY_SELECTED_APPS -> {
                config.includedApplications.forEach { builder.addAllowedApplication(it) }
            }
            AppRoutingMode.EXCLUDE_SELECTED_APPS -> {
                config.excludedApplications.forEach { builder.addDisallowedApplication(it) }
            }
        }

        createNotificationChannel()
        val notification = buildNotification(config.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_SERVICE_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }

        vpnInterface = builder.establish()
            ?: throw IllegalStateException("Failed to establish VPN interface")

        val fd = vpnInterface!!.fd
        android.util.Log.d("OlcRtcVpnService", "TUN fd=$fd established for ${config.name}")
        onVpnStatus?.invoke(VpnStatusEvent.TUN_ESTABLISHED)

        // Socket-level protection is handled by Transport via Mobile.setProtector
        // No process-wide bindProcessToNetwork — that would conflict with WireGuard

        // Generate hev-socks5-tunnel config and start tun2socks in background
        try {
            val hevConfig = generateHevConfig(config)
            val hevConfigFile = File(filesDir, "hev-socks5-tunnel.conf")
            hevConfigFile.writeText(hevConfig)
            android.util.Log.d("OlcRtcVpnService", "Saving Hev config to ${hevConfigFile.absolutePath}")

            // Track the thread for lifecycle management (#16: FD lifetime)
            tunThreadErrored = false
            tunThread = Thread {
                try {
                    val result = startTun2socksNative(hevConfigFile.absolutePath, fd)
                    android.util.Log.d("OlcRtcVpnService", "tun2socks exited: result=$result")
                    // tun2socks exited cleanly — signal exit
                    if (!tunThreadErrored) {
                        onVpnStatus?.invoke(VpnStatusEvent.TUN2SOCKS_EXITED)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("OlcRtcVpnService", "tun2socks failed", e)
                    tunThreadErrored = true
                    onVpnStatus?.invoke(VpnStatusEvent.ERROR)
                }
            }.apply { isDaemon = true }.also { it.start() }

            // Delay TUN2SOCKS_STARTED by 500ms to verify the thread actually starts running.
            // Without this delay, the signal fires immediately after thread.start()
            // even if tun2socks fails to initialize (e.g. bad config or socket conflict).
            //
            // NOTE: TUN2SOCKS_STARTED only means "native thread is alive after 500ms" —
            // a best-effort readiness signal, NOT a full traffic health check.
            // tun2socks could be looping, spinning, or internally broken but still report
            // as alive. A stronger native readiness callback or stat-based probe should
            // be added in a future iteration (e.g. querying tun2socks connection stats
            // or waiting for an explicit "ready" signal from the native layer).
            // The 500ms delay is empirical and may need adjustment per-device.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Ignore stale callbacks from previous sessions
                if (currentSession != sessionCounter.get()) return@postDelayed

                if (!tunThreadErrored && tunThread?.isAlive == true) {
                    onVpnStatus?.invoke(VpnStatusEvent.TUN2SOCKS_STARTED)
                } else if (!tunThreadErrored) {
                    // Thread died within the delay window without signaling ERROR
                    android.util.Log.e("OlcRtcVpnService", "tun2socks died before startup delay elapsed")
                    onVpnStatus?.invoke(VpnStatusEvent.ERROR)
                }
                // If tunThreadErrored is already true, ERROR was already signaled
            }, TUN2SOCKS_STARTUP_DELAY_MS)
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcVpnService", "Failed to configure tun2socks", e)
            onVpnStatus?.invoke(VpnStatusEvent.ERROR)
        }

        isRunning = true
    }

    /**
     * Stop VPN and clean up resources.
     * @param async if true, blocking operations (native stop, thread join) run
     *              on a background executor to avoid ANR on the main thread.
     *              stopForeground() and stopSelf() are posted back to the main thread.
     */
    private fun stopVpn(async: Boolean = false) {
        if (!isRunning && !initPhase) {
            android.util.Log.d("OlcRtcVpnService", "stopVpn: nothing to stop (isRunning=$isRunning, initPhase=$initPhase)")
            return
        }
        // Prevent concurrent stop from ACTION_STOP (async, stopExecutor) and onDestroy (sync, main thread)
        if (stopInProgress) return
        stopInProgress = true
        android.util.Log.d("OlcRtcVpnService", "Stopping VPN (async=$async, session=${sessionCounter.get()})")
        onVpnStatus?.invoke(VpnStatusEvent.VPN_STOPPED)

        val runnable = Runnable {
            try {
                // 1. Signal tun2socks to quit
                try {
                    stopTun2socksNative()
                    android.util.Log.d("OlcRtcVpnService", "tun2socks stop signal sent")
                } catch (e: Exception) {
                    android.util.Log.w("OlcRtcVpnService", "stopTun2socksNative failed", e)
                }

                // 2. Wait for tun2socks thread to exit
                val thread = tunThread
                if (thread != null && thread.isAlive) {
                    try {
                        thread.join(5_000)
                        android.util.Log.d("OlcRtcVpnService", "tun2socks thread joined")
                    } catch (e: InterruptedException) {
                        android.util.Log.w("OlcRtcVpnService", "Interrupted waiting for tun2socks exit", e)
                        Thread.currentThread().interrupt()
                    }
                }

                // 3. Close TUN fd after tun2socks has stopped
                vpnInterface?.close()
                vpnInterface = null
                tunThread = null

                // 4. stopForeground + stopSelf must be on the main thread.
                //    Reset stopInProgress INSIDE the main-thread post so no other
                //    stopVpn call can race ahead before the fields are cleaned up.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    isRunning = false
                    initPhase = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    stopInProgress = false
                }
            } catch (e: Exception) {
                // If an unexpected error happens before the main-thread post,
                // reset stopInProgress so we don't lock the service permanently.
                android.util.Log.e("OlcRtcVpnService", "stopVpn runnable failed", e)
                stopInProgress = false
            }
        }

        if (async) {
            stopExecutor.execute(runnable)
        } else {
            runnable.run()
        }
    }

    override fun onDestroy() {
        val currentSession = sessionCounter.get()
        android.util.Log.d("OlcRtcVpnService", "onDestroy, session=$currentSession")
        currentInstance = null
        // Defer blocking cleanup (native stop, thread join) to background
        // so we never block the main thread and risk ANR.
        // The stopExecutor runnable will post stopForeground/stopSelf back
        // to the main thread. We schedule executor shutdown after the
        // runnable is posted so pending work completes.
        stopVpn(async = true)
        stopExecutor.execute { stopExecutor.shutdownNow() }
        super.onDestroy()
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
