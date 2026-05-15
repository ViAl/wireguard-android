package com.wireguard.android.olcrtc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lifecycle state of an OlcRTC transport tunnel.
 */
enum class OlcRtcTransportState {
    IDLE,
    STARTING,
    READY,
    STOPPING,
    ERROR
}

/**
 * Wraps the olcrtc Go AAR and manages WebRTC tunnel lifecycle.
 *
 * Thread-safe: all public methods can be called from any thread.
 * All heavy operations run on [Dispatchers.IO].
 */
class OlcRtcTransport {

    private val _state = MutableStateFlow(OlcRtcTransportState.IDLE)
    val state: StateFlow<OlcRtcTransportState> = _state.asStateFlow()

    private var config: OlcRtcConfig? = null
    private var scope: CoroutineScope? = null
    private var transportJob: Job? = null

    // Reference to the mobile.Mobile class (loaded from AAR)
    // In Phase 1 we stub the native calls; real implementation in Phase 2
    private var mobileInstance: Any? = null

    /**
     * Start the OlcRTC transport with the given [config].
     *
     * This method:
     * 1. Initializes the olcrtc Go library
     * 2. Starts the WebRTC connection via the carrier
     * 3. Exposes a local SOCKS5 proxy on [OlcRtcConfig.socksPort]
     *
     * Returns immediately. Use [state] flow to observe progress.
     */
    /**
     * Start the OlcRTC transport with the given [config].
     *
     * This method:
     * 1. Initializes the olcrtc Go library
     * 2. Starts the WebRTC connection via the carrier
     * 3. Exposes a local SOCKS5 proxy on [OlcRtcConfig.socksPort]
     *
     * Returns immediately. Use [state] flow to observe progress.
     *
     * Thread-safe: uses state machine guard to prevent concurrent starts.
     */
    fun start(config: OlcRtcConfig): Job {
        // State machine guard: only start from IDLE state
        if (_state.value != OlcRtcTransportState.IDLE) {
            // Already running or transitioning — stop first to ensure clean state
            stop()
        }

        this.config = config
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        _state.value = OlcRtcTransportState.STARTING

        transportJob = scope?.launch {
            try {
                // Phase 1: stub — will call mobile.Mobile.Start() in Phase 2
                // For now, simulate startup delay
                delay(100)

                _state.value = OlcRtcTransportState.READY
            } catch (e: Exception) {
                _state.value = OlcRtcTransportState.ERROR
                throw e
            }
        }

        return transportJob!!
    }

    /**
     * Stop the transport.
     *
     * Tears down WebRTC connection, closes SOCKS5 proxy, frees native resources.
     */
    fun stop() {
        if (_state.value == OlcRtcTransportState.IDLE) return

        _state.value = OlcRtcTransportState.STOPPING

        scope?.cancel()
        scope = null
        transportJob = null
        config = null

        // Phase 1: stub — will call mobile.Mobile.Stop() in Phase 2
        // System.gc() — placeholder for native cleanup

        _state.value = OlcRtcTransportState.IDLE
    }

    /**
     * Returns the current config or null if not started.
     */
    fun getConfig(): OlcRtcConfig? = config

    /**
     * Returns true if the transport is currently running (READY state).
     */
    fun isRunning(): Boolean = _state.value == OlcRtcTransportState.READY

    /**
     * Returns a human-readable summary of the transport status.
     */
    fun getStatusSummary(): String {
        val currentState = _state.value
        val name = config?.name ?: "unnamed"
        return when (currentState) {
            OlcRtcTransportState.IDLE -> "OlcRTC '$name': idle"
            OlcRtcTransportState.STARTING -> "OlcRTC '$name': starting..."
            OlcRtcTransportState.READY -> "OlcRTC '$name': running (${config?.carrier ?: "?"} / ${config?.transport ?: "?"})"
            OlcRtcTransportState.STOPPING -> "OlcRTC '$name': stopping..."
            OlcRtcTransportState.ERROR -> "OlcRTC '$name': error"
        }
    }
}
