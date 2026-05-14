package com.wireguard.android.olcrtc

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mobile.Mobile

enum class OlcRtcConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR
}

object OlcRtcManager {
    private var transport: OlcRtcTransport? = null
    private var config: OlcRtcConfig? = null
    private var scope: CoroutineScope? = null
    private var watchdogJob: Job? = null

    private val _connectionState = MutableStateFlow(OlcRtcConnectionState.DISCONNECTED)
    val connectionState: StateFlow<OlcRtcConnectionState> = _connectionState.asStateFlow()

    private val _currentTunnelName = MutableStateFlow<String?>(null)
    val currentTunnelName: StateFlow<String?> = _currentTunnelName.asStateFlow()

    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    private var lastSocksPort: Int = 0
    private var lastTxBytes: Long = -1
    private var lastRxBytes: Long = -1
    private var stallCount: Int = 0
    private var socks5FailCount: Int = 0
    private var appContext: Context? = null

    private const val WATCHDOG_INTERVAL_MS = 5_000L
    private const val BASE_RECONNECT_DELAY_MS = 2_000L
    private const val MAX_RECONNECT_DELAY_MS = 60_000L
    private const val MAX_RECONNECT_ATTEMPTS = 10

    fun connect(appContext: Context, cfg: OlcRtcConfig) {
        disconnect()
        this.appContext = appContext
        config = cfg
        _connectionState.value = OlcRtcConnectionState.CONNECTING
        _currentTunnelName.value = cfg.name
        lastSocksPort = cfg.socksPort
        lastTxBytes = -1
        lastRxBytes = -1
        stallCount = 0
        socks5FailCount = 0

        transport = OlcRtcTransport(appContext)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope?.launch {
            transport?.start(cfg)
            transport?.state?.collect { state ->
                when (state) {
                    OlcRtcTransportState.READY -> {
                        _connectionState.value = OlcRtcConnectionState.CONNECTED
                        reconnectAttempt = 0
                        startWatchdog(appContext)
                    }
                    OlcRtcTransportState.ERROR -> {
                        _connectionState.value = OlcRtcConnectionState.ERROR
                        scheduleReconnect(appContext)
                    }
                    else -> {}
                }
            }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        scope?.cancel()
        transport?.stop()
        transport = null
        config = null
        reconnectAttempt = 0
        socks5FailCount = 0
        _connectionState.value = OlcRtcConnectionState.DISCONNECTED
        _currentTunnelName.value = null
    }

    fun getConfig(): OlcRtcConfig? = config

    private fun scheduleReconnect(appContext: Context) {
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            android.util.Log.w("OlcRtcManager", "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up")
            val name = config?.name ?: "unknown"
            OlcRtcVpnService.notifyReconnectExhausted(appContext, name)
            disconnect()
            return
        }

        // Check if there's any active network before attempting reconnect
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        if (activeNetwork == null) {
            android.util.Log.w("OlcRtcManager", "No active network — not reconnecting, will wait for network")
            return
        }

        reconnectJob?.cancel()
        reconnectAttempt++
        val delay = calculateBackoff()

        android.util.Log.d("OlcRtcManager", "Scheduling reconnect #$reconnectAttempt in ${delay}ms")
        _connectionState.value = OlcRtcConnectionState.DISCONNECTED
        reconnectJob = scope?.launch {
            delay(delay)
            val cfg = config ?: return@launch
            android.util.Log.d("OlcRtcManager", "Reconnecting...")
            connect(appContext, cfg)
        }
    }

    private fun calculateBackoff(): Long {
        val delay = BASE_RECONNECT_DELAY_MS * (1L shl (reconnectAttempt - 1).coerceAtMost(5))
        return delay.coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private fun startWatchdog(appContext: Context) {
        watchdogJob?.cancel()
        watchdogJob = scope?.launch {
            while (isActive && _connectionState.value == OlcRtcConnectionState.CONNECTED) {
                delay(WATCHDOG_INTERVAL_MS)
                val t = transport
                if (t == null || !t.isRunning()) {
                    android.util.Log.w("OlcRtcManager", "Watchdog: transport not running, reconnecting...")
                    _connectionState.value = OlcRtcConnectionState.ERROR
                    scheduleReconnect(appContext)
                    return@launch
                }

                if (!Mobile.isRunning()) {
                    android.util.Log.w("OlcRtcManager", "Watchdog: Mobile RTC stopped, reconnecting...")
                    _connectionState.value = OlcRtcConnectionState.ERROR
                    scheduleReconnect(appContext)
                    return@launch
                }

                // IMMEDIATE-1: Check tun2socks
                if (!OlcRtcVpnService.isTun2socksRunning()) {
                    android.util.Log.w("OlcRtcManager", "Watchdog: tun2socks not running, reconnecting")
                    _connectionState.value = OlcRtcConnectionState.ERROR
                    scheduleReconnect(appContext)
                    return@launch
                }

                // IMMEDIATE-2: Check SOCKS5 port — debounced 3 consecutive failures
                if (lastSocksPort > 0) {
                    val socksOk = try {
                        java.net.Socket().use { sock ->
                            sock.connect(java.net.InetSocketAddress("127.0.0.1", lastSocksPort), 1000)
                        }
                        true
                    } catch (e: Exception) {
                        false
                    }
                    if (socksOk) {
                        socks5FailCount = 0
                    } else {
                        socks5FailCount++
                        if (socks5FailCount >= 3) {
                            android.util.Log.w("OlcRtcManager", "Watchdog: SOCKS5 port $lastSocksPort dead for 15s, reconnecting")
                            _connectionState.value = OlcRtcConnectionState.ERROR
                            scheduleReconnect(appContext)
                            return@launch
                        }
                    }
                }

                // HIGH-1: Traffic stall detection
                val stats = OlcRtcVpnService.getStats()
                if (stats != null) {
                    val tx = stats[1]  // tx_bytes
                    val rx = stats[3]  // rx_bytes
                    if (tx == lastTxBytes && rx == lastRxBytes) {
                        stallCount++
                        if (stallCount >= 3) {
                            android.util.Log.w("OlcRtcManager", "Watchdog: traffic stalled for 15s, reconnecting")
                            _connectionState.value = OlcRtcConnectionState.ERROR
                            scheduleReconnect(appContext)
                            return@launch
                        }
                    } else {
                        stallCount = 0
                    }
                    lastTxBytes = tx
                    lastRxBytes = rx
                }

                // LOW-1: Refresh wake lock
                OlcRtcVpnService.refreshWakeLock()
            }
        }
    }

    fun notifyNetworkAvailable() {
        if (_connectionState.value == OlcRtcConnectionState.ERROR ||
            _connectionState.value == OlcRtcConnectionState.DISCONNECTED) {
            val ctx = appContext
            val cfg = config
            if (ctx != null && cfg != null) {
                android.util.Log.d("OlcRtcManager", "Network became available, reconnecting")
                connect(ctx, cfg)
            }
        }
    }
}
