package com.wireguard.android.olcrtc

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OlcRtcConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR
}

object OlcRtcManager {
    /**
     * Callback invoked before connect(). The host app (ui module) sets this
     * to stop active WireGuard tunnels, preventing TUN fd conflicts.
     */
    var onBeforeConnect: (suspend () -> Unit)? = null

    private var transport: OlcRtcTransport? = null
    private var config: OlcRtcConfig? = null
    private var scope: CoroutineScope? = null
    private var watchdogJob: Job? = null

    private val _connectionState = MutableStateFlow(OlcRtcConnectionState.DISCONNECTED)
    val connectionState: StateFlow<OlcRtcConnectionState> = _connectionState.asStateFlow()

    private val _currentTunnelName = MutableStateFlow<String?>(null)
    val currentTunnelName: StateFlow<String?> = _currentTunnelName.asStateFlow()

    fun connect(appContext: Context, cfg: OlcRtcConfig) {
        // Stop any active WireGuard tunnels to prevent TUN fd conflict
        onBeforeConnect?.let { runBlocking { it() } }
        disconnect()
        config = cfg
        _connectionState.value = OlcRtcConnectionState.CONNECTING
        _currentTunnelName.value = cfg.name

        transport = OlcRtcTransport(appContext)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope?.launch {
            transport?.start(cfg)
            transport?.state?.collect { state ->
                when (state) {
                    OlcRtcTransportState.READY -> {
                        _connectionState.value = OlcRtcConnectionState.CONNECTED
                        startWatchdog(appContext)
                    }
                    OlcRtcTransportState.ERROR -> {
                        _connectionState.value = OlcRtcConnectionState.ERROR
                    }
                    else -> {}
                }
            }
        }
    }

    fun disconnect() {
        watchdogJob?.cancel()
        scope?.cancel()
        transport?.stop()
        transport = null
        config = null
        _connectionState.value = OlcRtcConnectionState.DISCONNECTED
        _currentTunnelName.value = null
    }

    fun getConfig(): OlcRtcConfig? = config

    private fun startWatchdog(appContext: Context) {
        watchdogJob?.cancel()
        watchdogJob = scope?.launch {
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
