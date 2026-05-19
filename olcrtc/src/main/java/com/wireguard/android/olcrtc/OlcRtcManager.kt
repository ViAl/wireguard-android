package com.wireguard.android.olcrtc

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.cancellation.CancellationException

enum class OlcRtcConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR
}

object OlcRtcManager {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Callback invoked before connect(). The host app (ui module) sets this
     * to stop active WireGuard tunnels, preventing TUN fd conflicts.
     */
    var onBeforeConnect: (suspend () -> Unit)? = null

    private var transport: OlcRtcTransport? = null
    private var config: OlcRtcConfig? = null
    private var connectJob: Job? = null
    private var watchdogJob: Job? = null

    private val _connectionState = MutableStateFlow(OlcRtcConnectionState.DISCONNECTED)
    val connectionState: StateFlow<OlcRtcConnectionState> = _connectionState.asStateFlow()

    private val _currentTunnelName = MutableStateFlow<String?>(null)
    val currentTunnelName: StateFlow<String?> = _currentTunnelName.asStateFlow()

    /**
     * Start connecting asynchronously. Non-blocking — returns immediately.
     * The connection state is tracked via [connectionState] flow.
     */
    fun connect(appContext: Context, cfg: OlcRtcConfig) {
        connectJob?.cancel()
        connectJob = managerScope.launch {
            connectInternal(appContext.applicationContext, cfg)
        }
    }

    private suspend fun connectInternal(appContext: Context, cfg: OlcRtcConfig) {
        _connectionState.value = OlcRtcConnectionState.CONNECTING
        _currentTunnelName.value = cfg.name
        try {
            // Let host app stop WireGuard tunnels before we claim the TUN
            onBeforeConnect?.invoke()
            disconnectInternal()
            config = cfg
            val newTransport = OlcRtcTransport(appContext)
            transport = newTransport
            newTransport.startAndWait(cfg)
            _connectionState.value = OlcRtcConnectionState.CONNECTED
            startWatchdog(appContext)
        } catch (e: CancellationException) {
            disconnectInternal()
            throw e
        } catch (e: Throwable) {
            android.util.Log.e("OlcRtcManager", "connectInternal failed", e)
            _connectionState.value = OlcRtcConnectionState.ERROR
            disconnectInternal()
        }
    }

    private fun disconnectInternal() {
        watchdogJob?.cancel()
        transport?.stop()
        transport = null
        config = null
        _connectionState.value = OlcRtcConnectionState.DISCONNECTED
        _currentTunnelName.value = null
    }

    fun disconnect() {
        connectJob?.cancel()
        disconnectInternal()
    }

    fun getConfig(): OlcRtcConfig? = config

    private fun startWatchdog(appContext: Context) {
        watchdogJob?.cancel()
        watchdogJob = managerScope.launch {
            while (isActive) {
                delay(30_000L)
                val t = transport
                if (t?.isRunning() != true && _connectionState.value == OlcRtcConnectionState.CONNECTED) {
                    android.util.Log.w("OlcRtcManager", "Watchdog: reconnecting...")
                    _connectionState.value = OlcRtcConnectionState.ERROR
                    val cfg = config ?: continue
                    connect(appContext, cfg)
                }
            }
        }
    }
}
