package com.wireguard.android.olcrtc

import android.content.Context
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

    private const val WATCHDOG_INTERVAL_MS = 5_000L
    private const val BASE_RECONNECT_DELAY_MS = 2_000L
    private const val MAX_RECONNECT_DELAY_MS = 60_000L
    private const val MAX_RECONNECT_ATTEMPTS = 10

    fun connect(appContext: Context, cfg: OlcRtcConfig) {
        disconnect()
        config = cfg
        _connectionState.value = OlcRtcConnectionState.CONNECTING
        _currentTunnelName.value = cfg.name
        reconnectAttempt = 0

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
        _connectionState.value = OlcRtcConnectionState.DISCONNECTED
        _currentTunnelName.value = null
    }

    fun getConfig(): OlcRtcConfig? = config

    private fun scheduleReconnect(appContext: Context) {
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            android.util.Log.w("OlcRtcManager", "Max reconnect attempts reached, giving up")
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
            }
        }
    }
}
