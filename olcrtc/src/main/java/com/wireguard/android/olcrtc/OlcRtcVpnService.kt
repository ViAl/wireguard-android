package com.wireguard.android.olcrtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector

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

    /** Whether the native tun2socks bridge has been loaded via System.loadLibrary. */
    @Volatile
    private var nativeBridgeLoaded = false

    /**
     * Whether tun2socks has actually started (thread created and running).
     * Used to guard stopTun2socksNative from crashing when cleanup happens
     * before tun2socks was ever launched.
     */
    @Volatile
    private var tun2socksStarted = false

    // ── Go client lifecycle fields (moved from OlcRtcTransport for process separation) ──

    /** Dedicated single-thread dispatcher for ALL Mobile.* calls (Task 4). */
    private val goExecutor = Executors.newSingleThreadExecutor { Thread(it, "OlcRTC-Go").also { it.isDaemon = true } }

    /** Whether the Go OlcRTC client has been started and is running. */
    @Volatile
    private var goClientStarted = false

    /**
     * Set before [Mobile.startWithTransport] to indicate the native Go runtime
     * may have begun even if [goClientStarted] is not yet true (waitReady phase).
     * Used by [stopGoClient] to decide whether [Mobile.stop] is needed.
     */
    @Volatile
    private var goClientStarting = false

    /** Tracked by SocketProtector — set to true if any protect(fd) returns false. */
    private val protectFailed = AtomicBoolean(false)

    /** Reply Messenger passed by Transport in the main process for IPC status messages. */
    private var replyMessenger: Messenger? = null

    /**
     * Config extracted from ACTION_START intent. Stored so we can reconfigure
     * Mobile after a soft-reset if needed.
     */
    private var currentConfig: OlcRtcConfig? = null

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
         * In the process-separated world, this is local to the :olcrtc process
         * and is NOT accessible from the main process.
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

        /** Callback invoked when VPN lifecycle events occur. Set by Transport.
         *  In process-separated world, this is overridden by Messenger IPC. */
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

        // ── Messenger IPC message codes (Task 3: process separation) ──
        const val MSG_SERVICE_READY = 1
        const val MSG_STARTUP_STARTED = 2
        const val MSG_GO_READY = 3
        const val MSG_TUN_ESTABLISHED = 6
        const val MSG_TUN2SOCKS_STARTED = 4
        const val MSG_TUN2SOCKS_EXITED = 5
        const val MSG_ERROR = 7
        const val MSG_VPN_STOPPED = 8
        const val MSG_PROBE_START = 9
        const val MSG_PROBE_SUCCESS = 10
        const val MSG_PROBE_FAILURE = 11

        // ── Task 7: Debug kill-switch ──
        private const val OLCRTC_SKIP_MOBILE_STOP_ON_STARTUP_FAILURE = false // DEBUG ONLY
    }

    /**
     * Check whether this service instance is usable for socket protection.
     * Returns false if the service is stopping or was never properly initialized.
     */
    fun isUsableForProtect(): Boolean {
        return currentInstance === this && !stopInProgress && (initPhase || isRunning || nativeBridgeLoaded)
    }

    /**
     * Send a status message back to the main process via Messenger IPC.
     */
    private fun sendStatus(what: Int, errorMsg: String? = null) {
        val messenger = replyMessenger ?: return
        try {
            val msg = Message.obtain(null, what)
            errorMsg?.let { msg.data.putString("error", it) }
            messenger.send(msg)
        } catch (e: Exception) {
            android.util.Log.w("OlcRtcVpnService", "Failed to send status $what via Messenger", e)
        }
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
        // Load native bridge early so native methods are safe even before ACTION_START
        ensureNativeBridgeLoaded()
        // PRELOAD libgojni.so early in :olcrtc process lifecycle (Task 3)
        ensureGoJniLoaded()
        android.util.Log.d("OlcRtcVpnService",
            "VpnService created, session=$session, expected=$expectedServiceReadySession, " +
            "currentInstance set")
    }

    /**
     * Load libgojni.so early in the :olcrtc process lifecycle.
     * This is the ONLY place in the process where this library is loaded,
     * preventing Go runtime conflict with libwg-go.so in the main process.
     */
    private fun ensureGoJniLoaded(): Boolean {
        return try {
            System.loadLibrary("gojni")
            android.util.Log.d("OlcRtcVpnService", "libgojni.so loaded in :olcrtc process")
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("OlcRtcVpnService", "Failed to load libgojni.so", e)
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> {
                val currentSession = sessionCounter.get()
                android.util.Log.d("OlcRtcVpnService",
                    "ACTION_PREPARE received, session=$currentSession, " +
                    "expected=$expectedServiceReadySession")

                // Extract reply Messenger from Transport (main process)
                extractReplyMessenger(intent)

                // Load native bridge early so cleanup (ACTION_STOP) before ACTION_START
                // can safely bypass native methods.
                val bridgeLoaded = ensureNativeBridgeLoaded()
                android.util.Log.d("OlcRtcVpnService",
                    "ACTION_PREPARE: native bridge loaded=$bridgeLoaded")

                // Ensure libgojni is loaded (defensive call — onCreate should have loaded it)
                ensureGoJniLoaded()

                if (!isRunning && !initPhase) {
                    initPhase = true
                }

                // Always ensure notification + foreground is active
                createNotificationChannel()
                val notification = buildNotification("OlcRTC")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(FOREGROUND_SERVICE_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(FOREGROUND_SERVICE_ID, notification)
                }
                android.util.Log.d("OlcRtcVpnService", "VpnService initialized for socket protection")

                // Complete serviceReady locally and signal main process
                if (currentInstance === this) {
                    val session = sessionCounter.get()
                    if (expectedServiceReadySession == -1L || session == expectedServiceReadySession) {
                        if (serviceReady.complete(Unit)) {
                            android.util.Log.d("OlcRtcVpnService",
                                "ACTION_PREPARE: serviceReady completed (session=$session)")
                        } else {
                            android.util.Log.d("OlcRtcVpnService",
                                "ACTION_PREPARE: serviceReady already completed (session=$session)")
                        }
                    } else {
                        android.util.Log.w("OlcRtcVpnService",
                            "ACTION_PREPARE: session=$session != expected=$expectedServiceReadySession, " +
                            "not completing serviceReady")
                    }
                }

                // Notify Transport in main process that service is ready
                sendStatus(MSG_SERVICE_READY)
            }
            ACTION_START -> {
                android.util.Log.d("OlcRtcVpnService", "ACTION_START received for ${intent.getStringExtra(EXTRA_CONFIG_NAME)}")
                val config = extractConfig(intent)
                if (config != null) {
                    // Extract reply Messenger (may differ from PREPARE's)
                    extractReplyMessenger(intent)
                    // Handle full startup including Go client + TUN + tun2socks
                    onStartVpn(config)
                }
            }
            ACTION_STOP -> {
                // Notify transport that stop is beginning
                sendStatus(MSG_VPN_STOPPED)
                stopVpn(async = true)
            }
        }
        return Service.START_NOT_STICKY
    }

    /**
     * Extract the reply-to Messenger from the intent extras.
     * Used for IPC status communication back to OlcRtcTransport in the main process.
     */
    private fun extractReplyMessenger(intent: Intent?) {
        try {
            replyMessenger = intent?.getParcelableExtra("reply_to", android.os.Messenger::class.java)
        } catch (e: Exception) {
            android.util.Log.w("OlcRtcVpnService", "Failed to create reply Messenger", e)
        }
    }

    /**
     * Full VPN startup sequence in the :olcrtc process:
     * 1. Configure Mobile providers + socket protector (uses goDispatcher)
     * 2. Start Go OlcRTC client (uses goDispatcher)
     * 3. Wait for Go ready (uses goDispatcher)
     * 4. Establish TUN
     * 5. Start tun2socks
     *
     * All Mobile.* calls run on the dedicated goDispatcher (Task 4).
     * libgojni.so and Mobile.* are NEVER loaded/called in the main process (Task 3).
     */
    private fun onStartVpn(config: OlcRtcConfig) {
        goExecutor.execute {
            try {
                currentConfig = config
                sendStatus(MSG_STARTUP_STARTED)

                // ── Task 2+6: Log full safe config summary ──
                android.util.Log.d("OlcRtcVpnService", """
                    Config summary:
                      carrier: ${config.carrier}
                      transport: ${config.transport}
                      roomId: ${config.roomId}
                      clientId: ${config.clientId}
                      key_fingerprint: ${config.keyHex.take(8)}...
                      socksPort: ${config.socksPort}
                      vp8_fps: ${config.vp8Fps}
                      vp8_batch: ${config.vp8BatchSize}
                      dnsServer: ${config.dnsServer}
                      routeAllIpv4: ${config.routeAllIpv4}
                      routeAllIpv6: ${config.routeAllIpv6}
                      appRoutingMode: ${config.appRoutingMode}
                """.trimIndent())

                android.util.Log.d("OlcRtcVpnService", "Go client start begin: carrier=${config.carrier}")

                // Step 1: Configure Mobile providers
                configureMobileProviders()

                // Step 2: Start Go client
                startGoClient(config)

                // Step 3: Go ready confirmed → proceed
                android.util.Log.d("OlcRtcVpnService", "Go client start end: success, carrier=${config.carrier}")
                sendStatus(MSG_GO_READY)

                // Task 1+5+6: After Go waitReady succeeds, proceed directly to ACTION_START / TUN.
                // No blocking SOCKS probe. If we keep a probe, it runs async as diagnostic on IO.
                android.util.Log.d("OlcRtcVpnService", "Proceeding to establish TUN (skipping SOCKS probe gate)")

                // Post TUN + tun2socks setup to main thread (VpnService.Builder must be on main)
                Handler(Looper.getMainLooper()).post {
                    establishTunAndStartTun2socks(config)
                }

            } catch (e: Exception) {
                android.util.Log.e("OlcRtcVpnService", "Go client startup failed", e)
                sendStatus(MSG_ERROR, e.message ?: "Go startup failed")
                // Cleanup: stop Go client, stop foreground
                cleanupOnStartupFailure()
            }
        }
    }

    /**
     * Configure Mobile providers (socket protector, log writer).
     * Called on goDispatcher thread (Task 4: dedicated single-thread dispatcher).
     */
    private fun configureMobileProviders() {
        try {
            android.util.Log.d("OlcRtcVpnService", "configureMobileProviders begin (thread=${Thread.currentThread().name})")

            // Socket protector uses THIS VpnService instance (in :olcrtc process)
            Mobile.setProtector(object : SocketProtector {
                override fun protect(fd: Long): Boolean {
                    val result = this@OlcRtcVpnService.protect(fd.toInt())
                    if (!result) protectFailed.set(true)
                    android.util.Log.d("OlcRtcVpnService", "protect(fd=$fd) returned $result")
                    return result
                }
            })

            Mobile.setLogWriter(object : LogWriter {
                override fun writeLog(msg: String?) {
                    android.util.Log.d("OlcRtcVpnService", "Go: ${msg ?: ""}")
                }
            })

            Mobile.setProviders()
            android.util.Log.d("OlcRtcVpnService", "Mobile providers configured")
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcVpnService", "configureMobileProviders failed", e)
            throw e
        }
    }

    /**
     * Start the Go OlcRTC client on the dedicated goDispatcher thread.
     * Called from goExecutor — thread affinity for all Mobile.* calls (Task 4).
     */
    private fun startGoClient(config: OlcRtcConfig) {
        if (goClientStarted) {
            throw IllegalStateException("OlcRTC Go client is already running")
        }

        goClientStarting = true
        var nativeStarted = false
        try {
            android.util.Log.d("OlcRtcVpnService", "Go client start: configuring (thread=${Thread.currentThread().name})")
            Mobile.setLink("direct")
            Mobile.setTransport(config.transport)
            Mobile.setDNS(config.dnsServer)
            Mobile.setVP8Options(config.vp8Fps.toLong(), config.vp8BatchSize.toLong())
            Mobile.setDebug(false)

            Mobile.startWithTransport(
                config.carrier,
                config.transport,
                config.roomId,
                config.clientId,
                config.keyHex,
                config.socksPort.toLong(),
                config.socksUser ?: "",
                config.socksPass ?: ""
            )
            android.util.Log.d("OlcRtcVpnService", "Go startWithTransport completed")

            nativeStarted = true
            goClientStarted = true

            android.util.Log.d("OlcRtcVpnService", "Go waitReady begin (25s timeout)")
            Mobile.waitReady(25_000L)
            android.util.Log.d("OlcRtcVpnService", "Go waitReady completed, protectFailed=${protectFailed.get()}")

            if (protectFailed.get()) {
                Mobile.stop()
                throw IllegalStateException("Failed to protect OlcRTC socket — VpnService.protect() returned false")
            }
        } catch (e: Throwable) {
            if (nativeStarted) {
                runCatching { Mobile.stop() }
            }
            goClientStarted = false
            throw e
        } finally {
            goClientStarting = false
        }
    }

    /**
     * Establish TUN interface and start tun2socks.
     * Called on main thread (VpnService.Builder requires it).
     */
    private fun establishTunAndStartTun2socks(config: OlcRtcConfig) {
        if (isRunning) return

        val currentSession = sessionCounter.incrementAndGet()

        val builder = Builder()
        builder.setSession(config.name)
        builder.setMtu(1500)

        if (config.routeAllIpv4) {
            builder.addAddress("10.0.2.1", 24)
            builder.addRoute("0.0.0.0", 0)
        }
        if (config.routeAllIpv6) {
            builder.addAddress("fd00:1:2:3::1", 64)
            builder.addRoute("::", 0)
        }

        val dnsParts = config.dnsServer.split(":")
        builder.addDnsServer(dnsParts[0])

        when (config.appRoutingMode) {
            AppRoutingMode.ALL_APPS -> { /* no per-app rules */ }
            AppRoutingMode.ONLY_SELECTED_APPS -> {
                config.includedApplications.forEach { builder.addAllowedApplication(it) }
            }
            AppRoutingMode.EXCLUDE_SELECTED_APPS -> {
                config.excludedApplications.forEach { builder.addDisallowedApplication(it) }
            }
        }

        // ── Task 4+5+6: Log routing config at ACTION_START ──
        android.util.Log.d("OlcRtcVpnService", """
            Routing config:
              appRoutingMode: ${config.appRoutingMode}
              includedApps (${config.includedApplications.size}): ${config.includedApplications}
              excludedApps (${config.excludedApplications.size}): ${config.excludedApplications}
              routeAllIpv4: ${config.routeAllIpv4}
              routeAllIpv6: ${config.routeAllIpv6}
              dnsServer: ${config.dnsServer}
        """.trimIndent())

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
        sendStatus(MSG_TUN_ESTABLISHED)

        try {
            val hevConfig = generateHevConfig(config)
            val hevConfigFile = File(filesDir, "hev-socks5-tunnel.conf")
            hevConfigFile.writeText(hevConfig)
            android.util.Log.d("OlcRtcVpnService", "Saving Hev config to ${hevConfigFile.absolutePath}")

            tunThreadErrored = false
            tunThread = Thread {
                try {
                    val result = startTun2socksNative(hevConfigFile.absolutePath, fd)
                    android.util.Log.d("OlcRtcVpnService", "tun2socks exited: result=$result")
                    if (!tunThreadErrored) {
                        sendStatus(MSG_TUN2SOCKS_EXITED)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("OlcRtcVpnService", "tun2socks failed", e)
                    tunThreadErrored = true
                    sendStatus(MSG_ERROR, "tun2socks failed: ${e.message}")
                }
            }.apply { isDaemon = true }.also { it.start() }

            tun2socksStarted = true
            android.util.Log.d("OlcRtcVpnService", "tun2socks thread started, tun2socksStarted=true")

            Handler(Looper.getMainLooper()).postDelayed({
                if (currentSession != sessionCounter.get()) return@postDelayed

                if (!tunThreadErrored && tunThread?.isAlive == true) {
                    android.util.Log.d("OlcRtcVpnService", "TUN2SOCKS_STARTED confirmed")
                    sendStatus(MSG_TUN2SOCKS_STARTED)

                    // ── Task 1+9: Start e2e data-path probe ──
                    sendStatus(MSG_PROBE_START)
                    startE2eProbe(config)

                    // ── Task 7: Start tun2socks stats polling ──
                    startTun2socksStatsPolling()
                } else if (!tunThreadErrored) {
                    android.util.Log.e("OlcRtcVpnService", "tun2socks died before startup delay elapsed")
                    sendStatus(MSG_ERROR, "tun2socks died before startup delay")
                }
            }, TUN2SOCKS_STARTUP_DELAY_MS)
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcVpnService", "Failed to configure tun2socks", e)
            sendStatus(MSG_ERROR, "tun2socks config failed: ${e.message}")
        }

        isRunning = true

        // ── Task 5: Log ConnectivityManager + VPN network state ──
        logNetworkState()
    }

    /**
     * Log ConnectivityManager active network, VPN transport, routes, DNS (Task 5).
     */
    private fun logNetworkState() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.activeNetwork?.let { network ->
                val caps = cm.getNetworkCapabilities(network)
                val lp = cm.getLinkProperties(network)
                android.util.Log.d("OlcRtcVpnService", """
                    Active network: $network
                    VPN transport active: ${caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)}
                    Routes: ${lp?.routes?.joinToString()}
                    DNS: ${lp?.dnsServers?.joinToString()}
                """.trimIndent())
            } ?: android.util.Log.w("OlcRtcVpnService", "No active network from ConnectivityManager")
        } catch (e: Exception) {
            android.util.Log.w("OlcRtcVpnService", "Failed to log network state", e)
        }
    }

    // ── Task 7: tun2socks stats polling control ──
    @Volatile
    private var statsPollingActive = false
    private var statsPollThread: Thread? = null

    /**
     * e2e data-path probe: perform SOCKS5 CONNECT through 127.0.0.1:socksPort
     * to 1.1.1.1:443. This exercises the full path:
     * SOCKS → Go client → OpenStream → carrier → server peer → internet.
     *
     * Successful TCP + HTTP handshake → MSG_PROBE_SUCCESS.
     * Any failure → MSG_PROBE_FAILURE.
     */
    private fun startE2eProbe(config: OlcRtcConfig) {
        val socksPort = config.socksPort
        Thread {
            try {
                android.util.Log.d("OlcRtcVpnService", "e2e SOCKS5 probe begin (port=$socksPort)")
                val success = performSocks5Probe(socksPort)
                if (success) {
                    android.util.Log.d("OlcRtcVpnService", "e2e SOCKS5 probe SUCCESS")
                    sendStatus(MSG_PROBE_SUCCESS)
                } else {
                    android.util.Log.e("OlcRtcVpnService", "e2e SOCKS5 probe FAILED")
                    sendStatus(MSG_PROBE_FAILURE, "OlcRTC remote stream timeout")
                }
            } catch (e: Exception) {
                android.util.Log.e("OlcRtcVpnService", "e2e SOCKS5 probe error", e)
                sendStatus(MSG_PROBE_FAILURE, "e2e probe error: ${e.message}")
            }
        }.apply { isDaemon = true; name = "OlcRTC-E2eProbe" }.start()
    }

    /**
     * Perform a minimal SOCKS5 CONNECT probe through 127.0.0.1:socksPort
     * to 1.1.1.1:80, then send a bare HTTP request to confirm data flow.
     *
     * Returns true if the full path works, false otherwise.
     */
    private fun performSocks5Probe(socksPort: Int): Boolean {
        try {
            val sock = java.net.Socket()
            sock.connect(java.net.InetSocketAddress("127.0.0.1", socksPort), 15_000)

            // ── SOCKS5 greeting: version 5, 1 auth method (no auth) ──
            sock.outputStream.write(byteArrayOf(0x05, 0x01, 0x00))
            val greetingResp = ByteArray(2)
            if (!readFully(sock, greetingResp, 0, 2)) { sock.close(); return false }
            if (greetingResp[0] != 0x05.toByte() || greetingResp[1] != 0x00.toByte()) {
                android.util.Log.w("OlcRtcVpnService",
                    "SOCKS5 greeting rejected: ${greetingResp[0]},${greetingResp[1]}")
                sock.close(); return false
            }

            // ── SOCKS5 CONNECT to 1.1.1.1:80 ──
            val targetHost = "1.1.1.1"
            val targetPort = 80
            val hostBytes = targetHost.toByteArray()
            val req = ByteArray(7 + hostBytes.size)
            req[0] = 0x05  // version
            req[1] = 0x01  // CONNECT
            req[2] = 0x00  // reserved
            req[3] = 0x03  // domain name
            req[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
            req[5 + hostBytes.size] = ((targetPort shr 8) and 0xFF).toByte()
            req[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
            sock.outputStream.write(req)

            // Read CONNECT response header (4 bytes: VER, REP, RSV, ATYP)
            val respHdr = ByteArray(4)
            if (!readFully(sock, respHdr, 0, 4)) { sock.close(); return false }
            if (respHdr[1] != 0x00.toByte()) {
                val repCode = respHdr[1].toInt() and 0xFF
                android.util.Log.w("OlcRtcVpnService", "SOCKS5 CONNECT rejected: REP=$repCode")
                sock.close(); return false
            }

            // Read BND.ADDR + BND.PORT based on address type
            val addrType = respHdr[3].toInt() and 0xFF
            val addrLen: Int = when (addrType) {
                0x01 -> 4 + 2   // IPv4 + port
                0x03 -> {
                    val lenByte = sock.inputStream.read()
                    if (lenByte < 0) { sock.close(); return false }
                    lenByte + 2   // domain name + port
                }
                0x04 -> 16 + 2  // IPv6 + port
                else -> 0
            }
            val bindAddr = ByteArray(addrLen)
            if (!readFully(sock, bindAddr, 0, addrLen)) { sock.close(); return false }

            android.util.Log.d("OlcRtcVpnService", "SOCKS5 CONNECT to $targetHost:$targetPort succeeded")

            // ── Send minimal HTTP request to confirm data flow ──
            val httpReq = "GET / HTTP/1.0\r\nHost: $targetHost\r\n\r\n"
            sock.outputStream.write(httpReq.toByteArray())

            // Read some response bytes — just need to confirm data arrives
            sock.soTimeout = 10_000  // 10s read timeout
            val httpResp = ByteArray(64)
            val n = sock.inputStream.read(httpResp)
            sock.close()

            if (n > 0) {
                val preview = String(httpResp, 0, n.coerceAtMost(64))
                android.util.Log.d("OlcRtcVpnService", "HTTP response preview: ${preview.take(64)}")
                return true
            }
            android.util.Log.w("OlcRtcVpnService", "No HTTP response from $targetHost")
            return false
        } catch (e: Exception) {
            android.util.Log.w("OlcRtcVpnService", "SOCKS5 probe failed: ${e.message}")
            return false
        }
    }

    /**
     * Read exactly [len] bytes from the socket into [buf] starting at [offset].
     * Returns false if EOF/error before reading all bytes.
     */
    private fun readFully(sock: java.net.Socket, buf: ByteArray, offset: Int, len: Int): Boolean {
        var remaining = len
        var off = offset
        while (remaining > 0) {
            val n = try {
                sock.inputStream.read(buf, off, remaining)
            } catch (e: Exception) {
                return false
            }
            if (n < 0) return false
            remaining -= n
            off += n
        }
        return true
    }

    /**
     * Start periodic tun2socks stats polling (Task 7).
     * Logs tx_packets, tx_bytes, rx_packets, rx_bytes every 10 seconds.
     */
    private fun startTun2socksStatsPolling() {
        if (statsPollingActive) return
        statsPollingActive = true
        statsPollThread = Thread {
            android.util.Log.d("OlcRtcVpnService", "tun2socks stats polling started")
            while (statsPollingActive) {
                try {
                    Thread.sleep(10_000L)
                    if (!statsPollingActive) break
                    logTun2socksStats()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    android.util.Log.w("OlcRtcVpnService", "Stats polling error", e)
                }
            }
            android.util.Log.d("OlcRtcVpnService", "tun2socks stats polling stopped")
        }.apply { isDaemon = true; name = "OlcRTC-StatsPoll" }.also { it.start() }
    }

    private fun stopStatsPolling() {
        statsPollingActive = false
        statsPollThread?.interrupt()
        statsPollThread = null
    }

    /**
     * Log current tun2socks traffic counters (Task 7).
     */
    private fun logTun2socksStats() {
        try {
            val stats = getTun2socksStatsNative()
            if (stats != null && stats.size >= 4) {
                android.util.Log.d("OlcRtcVpnService", """
                    tun2socks stats:
                      tx_packets: ${stats[0]}
                      tx_bytes: ${stats[1]}
                      rx_packets: ${stats[2]}
                      rx_bytes: ${stats[3]}
                """.trimIndent())
            }
        } catch (e: Exception) {
            android.util.Log.w("OlcRtcVpnService", "Failed to read tun2socks stats", e)
        }
    }

    /**
     * Clean up on Go client startup failure before TUN is established.
     * Called from goExecutor thread — avoids ANR and ensures thread affinity.
     */
    private fun cleanupOnStartupFailure() {
        try {
            android.util.Log.d("OlcRtcVpnService", "cleanupOnStartupFailure begin")
            stopGoClient()
        } catch (_: Exception) { }
        // Defer foreground/service stop to main thread
        Handler(Looper.getMainLooper()).post {
            try {
                isRunning = false
                initPhase = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (_: Exception) { }
        }
    }

    /**
     * Stop the Go OlcRTC client.
     *
     * May be called from [goExecutor] (startGoClient catch, cleanupOnStartupFailure)
     * or from stopExecutor (stopVpn). When called from a non-goExecutor thread,
     * dispatches to [goExecutor] for thread affinity (Task 4).
     *
     * Respects OLCRTC_SKIP_MOBILE_STOP_ON_STARTUP_FAILURE debug flag (Task 7).
     */
    private fun stopGoClient() {
        if (!goClientStarting && !goClientStarted) {
            android.util.Log.d("OlcRtcVpnService", "stopGoClient: not running, skipping")
            return
        }

        if (Thread.currentThread().name == "OlcRTC-Go") {
            // Already on goExecutor thread — call directly to avoid deadlock
            doStopGoClient()
        } else {
            // Dispatch to goExecutor for thread affinity (Task 4)
            android.util.Log.d("OlcRtcVpnService",
                "stopGoClient: dispatching to goExecutor (caller thread=${Thread.currentThread().name})")
            try {
                goExecutor.submit { doStopGoClient() }.get(10, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                android.util.Log.w("OlcRtcVpnService", "stopGoClient dispatch failed", e)
                goClientStarted = false
                goClientStarting = false
            }
        }
    }

    /**
     * Execute the actual Mobile.stop() call.
     * Must be called from [goExecutor] thread for thread affinity (Task 4).
     */
    private fun doStopGoClient() {
        android.util.Log.d("OlcRtcVpnService",
            "stopGoClient begin (starting=$goClientStarting, started=$goClientStarted, thread=${Thread.currentThread().name})")

        // Task 7: Kill-switch for debugging
        if (OLCRTC_SKIP_MOBILE_STOP_ON_STARTUP_FAILURE) {
            android.util.Log.w("OlcRtcVpnService",
                "OLCRTC_SKIP_MOBILE_STOP_ON_STARTUP_FAILURE=true: SKIPPING Mobile.stop(). " +
                "Go runtime may leak. Process will need restart to clean up.")
            goClientStarted = false
            goClientStarting = false
            return
        }

        try {
            Mobile.stop()
            android.util.Log.d("OlcRtcVpnService", "Go client stopped")
        } catch (e: Exception) {
            android.util.Log.w("OlcRtcVpnService", "stopGoClient failed", e)
        } finally {
            goClientStarted = false
            goClientStarting = false
        }
    }

    /**
     * Stop VPN and clean up resources.
     * @param async if true, blocking operations (native stop, Mobile.stop, thread join) run
     *              on a background executor to avoid ANR on the main thread.
     */
    private fun stopVpn(async: Boolean = false) {
        if (!isRunning && !initPhase) {
            android.util.Log.d("OlcRtcVpnService", "stopVpn: nothing to stop (isRunning=$isRunning, initPhase=$initPhase)")
            return
        }
        if (stopInProgress) return
        stopInProgress = true
        android.util.Log.d("OlcRtcVpnService", "Stopping VPN (async=$async, session=${sessionCounter.get()})")

        val runnable = Runnable {
            try {
                // 1. Stop stats polling (Task 7)
                stopStatsPolling()

                // 2. Stop Go client on dedicated thread (Task 4)
                stopGoClient()

                // 2. Signal tun2socks to quit (only if actually started and bridge loaded)
                val shouldStopTun2socks = nativeBridgeLoaded && tun2socksStarted && tunThread != null
                if (shouldStopTun2socks) {
                    try {
                        stopTun2socksNative()
                        android.util.Log.d("OlcRtcVpnService", "tun2socks stop signal sent")
                    } catch (e: UnsatisfiedLinkError) {
                        android.util.Log.e("OlcRtcVpnService", "Native stopTun2socksNative unavailable", e)
                    } catch (t: Throwable) {
                        android.util.Log.w("OlcRtcVpnService", "stopTun2socksNative failed", t)
                    }
                } else {
                    android.util.Log.d("OlcRtcVpnService",
                        "Skipping stopTun2socksNative: nativeLoaded=$nativeBridgeLoaded, " +
                        "tun2socksStarted=$tun2socksStarted, tunThread=${tunThread != null}")
                }

                // 3. Wait for tun2socks thread to exit
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

                // 4. Close TUN fd after tun2socks has stopped
                vpnInterface?.close()
                vpnInterface = null
                tunThread = null

                // 5. stopForeground + stopSelf must be on the main thread
                Handler(Looper.getMainLooper()).post {
                    isRunning = false
                    initPhase = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    stopInProgress = false
                }
            } catch (t: Throwable) {
                android.util.Log.e("OlcRtcVpnService", "stopVpn runnable failed", t)
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
        stopVpn(async = true)
        stopExecutor.execute { stopExecutor.shutdown() }
        super.onDestroy()
    }

    // ── Helpers (unchanged) ──

    private fun extractConfig(intent: Intent): OlcRtcConfig? {
        val name = intent.getStringExtra(EXTRA_CONFIG_NAME) ?: return null
        val carrier = intent.getStringExtra(EXTRA_CONFIG_CARRIER) ?: return null
        val room = intent.getStringExtra(EXTRA_CONFIG_ROOM) ?: return null
        val client = intent.getStringExtra(EXTRA_CONFIG_CLIENT) ?: return null
        val key = intent.getStringExtra(EXTRA_CONFIG_KEY) ?: return null
        val transport = intent.getStringExtra(EXTRA_CONFIG_TRANSPORT) ?: "datachannel"
        val socksPort = intent.getIntExtra(EXTRA_CONFIG_SOCKS_PORT, 1080)
        val dns = intent.getStringExtra(EXTRA_CONFIG_DNS) ?: "1.1.1.1:53"
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

    /**
     * Load the native tun2socks bridge libraries (hev-socks5-tunnel + olcrtc_tun2socks)
     * early in the lifecycle so they are available before any native method is called.
     * Safe to call multiple times — once the bridge is loaded, it returns immediately.
     */
    private fun ensureNativeBridgeLoaded(): Boolean {
        if (nativeBridgeLoaded) return true
        return try {
            System.loadLibrary("hev-socks5-tunnel")
            android.util.Log.d("OlcRtcVpnService", "libhev-socks5-tunnel.so loaded")
            System.loadLibrary("olcrtc_tun2socks")
            android.util.Log.d("OlcRtcVpnService", "libolcrtc_tun2socks.so loaded")
            nativeBridgeLoaded = true
            android.util.Log.d("OlcRtcVpnService", "Native tun2socks bridge loaded")
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("OlcRtcVpnService", "Failed to load native tun2socks bridge", e)
            false
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
