package com.wireguard.android.olcrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lifecycle state of the OlcRTC manager.
 */
enum class OlcRtcManagerState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    NETWORK_UNSTABLE,
    ERROR
}

/**
 * Manages the OlcRTC tunnel lifecycle:
 * - Connection / reconnection with exponential backoff
 * - Watchdog that verifies actual traffic flow
 * - State machine for UI status reporting
 */
class OlcRtcManager(
    private val context: Context,
    private val transport: OlcRtcTransport = OlcRtcTransport()
) {

    companion object {
        private const val TAG = "OlcRtcManager"

        // Backoff configuration
        private const val INITIAL_BACKOFF_MS = 10_000L
        private const val MAX_BACKOFF_MS = 120_000L
        private const val BACKOFF_MULTIPLIER = 2
        private const val STABLE_CONNECTION_THRESHOLD_MS = 30_000L

        // Watchdog configuration
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val TRAFFIC_CHECK_INTERVAL_MS = 10_000L
        private const val TRAFFIC_STALL_THRESHOLD_MS = 20_000L

        // Max reconnects before escalating to NETWORK_UNSTABLE
        private const val MAX_RECONNECTS_BEFORE_DEGRADE = 3
    }

    private val _state = MutableStateFlow(OlcRtcManagerState.DISCONNECTED)
    val state: StateFlow<OlcRtcManagerState> = _state.asStateFlow()

    @Volatile
    private var reconnectAttempts = 0
    @Volatile
    private var isReconnecting = false
    @Volatile
    private var config: OlcRtcConfig? = null
    @Volatile
    private var lastTrafficTimeMs = 0L
    @Volatile
    private var stableSinceMs = 0L

    private var managerScope: CoroutineScope? = null
    private var watchdogJob: Job? = null
    private var trafficCheckJob: Job? = null
    private var reconnectJob: Job? = null

    // ─────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────

    /**
     * Connect to the OlcRTC service with the given [config].
     */
    fun connect(config: OlcRtcConfig) {
        Log.d(TAG, "connect: ${config.name}")
        this.config = config

        if (managerScope == null) {
            managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        _state.value = OlcRtcManagerState.CONNECTING
        resetReconnectAttempts()
        startTunnel(config)
    }

    /**
     * Disconnect from the OlcRTC service.
     */
    fun disconnect() {
        Log.d(TAG, "disconnect")
        isReconnecting = false
        stopWatchdog()
        stopTrafficCheck()
        reconnectJob?.cancel()
        reconnectJob = null
        transport.stop()
        _state.value = OlcRtcManagerState.DISCONNECTED
    }

    /**
     * Returns true if the manager is in a connected or connecting state.
     */
    fun isActive(): Boolean = _state.value in listOf(
        OlcRtcManagerState.CONNECTING,
        OlcRtcManagerState.CONNECTED,
        OlcRtcManagerState.RECONNECTING
    )

    /**
     * Returns the current reconnect attempt count.
     */
    fun getReconnectAttempts(): Int = reconnectAttempts

    /**
     * Returns true if currently in a reconnect cycle.
     */
    fun getIsReconnecting(): Boolean = isReconnecting

    /**
     * Record traffic activity — called by transport layer when data flows.
     */
    fun recordTraffic() {
        lastTrafficTimeMs = System.currentTimeMillis()
    }

    // ─────────────────────────────────────────────────
    // Tunnel Management
    // ─────────────────────────────────────────────────

    private fun startTunnel(config: OlcRtcConfig) {
        Log.d(TAG, "startTunnel")

        managerScope?.launch {
            try {
                // Start the transport
                transport.start(config)

                // Wait for transport to become ready or error
                transport.state.collect { transportState ->
                    when (transportState) {
                        OlcRtcTransportState.READY -> {
                            Log.i(TAG, "Transport ready")
                            _state.value = OlcRtcManagerState.CONNECTED
                            stableSinceMs = System.currentTimeMillis()
                            isReconnecting = false
                            resetReconnectAttempts()
                            recordTraffic()

                            // Start watchdog and traffic monitoring
                            startWatchdog()
                            startTrafficCheck()
                        }
                        OlcRtcTransportState.ERROR -> {
                            Log.e(TAG, "Transport error — initiating reconnect")
                            handleReconnect()
                        }
                        OlcRtcTransportState.IDLE -> {
                            // Transport stopped normally
                            if (_state.value != OlcRtcManagerState.DISCONNECTED) {
                                _state.value = OlcRtcManagerState.DISCONNECTED
                            }
                        }
                        else -> { /* ignore intermediate states */ }
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Tunnel start cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Tunnel start failed", e)
                if (isActive()) {
                    handleReconnect()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────
    // Reconnect with Exponential Backoff
    // ─────────────────────────────────────────────────

    /**
     * Handle transport failure with exponential backoff reconnect.
     */
    private fun handleReconnect() {
        if (!isActive() || reconnectJob?.isActive == true) {
            Log.d(TAG, "Reconnect already in progress or not active — skipping")
            return
        }

        isReconnecting = true
        reconnectAttempts++

        val backoffDelay = getBackoffDelay()
        Log.w(TAG, "Reconnect #$reconnectAttempts — backoff=${backoffDelay}ms")

        // Update state for UI
        _state.value = if (reconnectAttempts >= MAX_RECONNECTS_BEFORE_DEGRADE) {
            OlcRtcManagerState.NETWORK_UNSTABLE
        } else {
            OlcRtcManagerState.RECONNECTING
        }

        reconnectJob = managerScope?.launch {
            delay(backoffDelay)

            Log.d(TAG, "Reconnect #$reconnectAttempts — executing")

            // Stop watchdog/traffic check during reconnect
            stopWatchdog()
            stopTrafficCheck()

            // Ensure clean transport state before restart
            transport.stop()

            config?.let { cfg ->
                _state.value = OlcRtcManagerState.CONNECTING
                startTunnel(cfg)
            }
        }
    }

    /**
     * Calculate exponential backoff delay.
     * Sequence: 10s, 20s, 40s, 80s, 120s (capped)
     */
    private fun getBackoffDelay(): Long {
        val delay = INITIAL_BACKOFF_MS * (1 shl (reconnectAttempts - 1).coerceAtMost(4))
        return delay.coerceAtMost(MAX_BACKOFF_MS)
    }

    /**
     * Reset reconnect attempts counter.
     * Called after successful stable connection (>30s).
     */
    private fun resetReconnectAttempts() {
        reconnectAttempts = 0
    }

    // ─────────────────────────────────────────────────
    // Watchdog — Traffic-Based Health Check
    // ─────────────────────────────────────────────────

    /**
     * Watchdog that monitors transport health.
     * Instead of just checking [OlcRtcTransport.isRunning],
     * it verifies that actual traffic is flowing.
     */
    private fun startWatchdog() {
        stopWatchdog()
        watchdogJob = managerScope?.launch {
            while (isActive()) {
                delay(WATCHDOG_INTERVAL_MS)

                // Check: transport must be in READY state
                if (!transport.isRunning()) {
                    Log.w(TAG, "Watchdog: transport not running — reconnecting")
                    handleReconnect()
                    break
                }

                // Check stable connection threshold for resetting backoff
                val uptime = System.currentTimeMillis() - stableSinceMs
                if (uptime >= STABLE_CONNECTION_THRESHOLD_MS && reconnectAttempts > 0) {
                    Log.d(TAG, "Watchdog: stable for ${uptime}ms — resetting backoff counter")
                    resetReconnectAttempts()
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    // ─────────────────────────────────────────────────
    // Traffic Monitoring
    // ─────────────────────────────────────────────────

    /**
     * Monitors actual traffic flow.
     * Triggers reconnect if no traffic detected for [TRAFFIC_STALL_THRESHOLD_MS].
     */
    private fun startTrafficCheck() {
        stopTrafficCheck()
        trafficCheckJob = managerScope?.launch {
            while (isActive()) {
                delay(TRAFFIC_CHECK_INTERVAL_MS)

                // If no traffic recorded since last check, it might be stalled
                val idleTime = System.currentTimeMillis() - lastTrafficTimeMs
                if (idleTime > TRAFFIC_STALL_THRESHOLD_MS && transport.isRunning()) {
                    Log.w(TAG, "Traffic check: stalled for ${idleTime}ms — reconnecting")
                    handleReconnect()
                    break
                }
            }
        }
    }

    private fun stopTrafficCheck() {
        trafficCheckJob?.cancel()
        trafficCheckJob = null
    }

    /**
     * Returns a human-readable status summary.
     */
    fun getStatusSummary(): String {
        val stateStr = when (_state.value) {
            OlcRtcManagerState.DISCONNECTED -> "Disconnected"
            OlcRtcManagerState.CONNECTING -> "Connecting..."
            OlcRtcManagerState.CONNECTED -> "Connected"
            OlcRtcManagerState.RECONNECTING -> "Reconnecting... ($reconnectAttempts)"
            OlcRtcManagerState.NETWORK_UNSTABLE -> "Network unstable (attempt $reconnectAttempts)"
            OlcRtcManagerState.ERROR -> "Error"
        }
        return stateStr
    }
}
