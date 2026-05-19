package com.wireguard.android.olcrtc

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

enum class OlcRtcConnectionState {
    DISCONNECTED, CONNECTING, LOCAL_READY, VERIFYING_REMOTE, CONNECTED, DISCONNECTING, ERROR
}

object OlcRtcManager {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()

    /**
     * Callback invoked before connect(). The host app (ui module) sets this
     * to stop active WireGuard tunnels, preventing TUN fd conflicts.
     */
    var onBeforeConnect: (suspend () -> Unit)? = null

    private var transport: OlcRtcTransport? = null
    private var config: OlcRtcConfig? = null
    private var connectJob: Job? = null
    private var watchdogJob: Job? = null

    private val _vpnStatus = MutableStateFlow<VpnStatusEvent?>(null)
    /** Observes lifecycle events from [OlcRtcVpnService]. */
    val vpnStatus: StateFlow<VpnStatusEvent?> = _vpnStatus.asStateFlow()

    private val _connectionState = MutableStateFlow(OlcRtcConnectionState.DISCONNECTED)
    val connectionState: StateFlow<OlcRtcConnectionState> = _connectionState.asStateFlow()

    private val _currentTunnelName = MutableStateFlow<String?>(null)
    val currentTunnelName: StateFlow<String?> = _currentTunnelName.asStateFlow()

    private val _errorReason = MutableStateFlow<String?>(null)
    val errorReason: StateFlow<String?> = _errorReason.asStateFlow()

    /**
     * Start connecting asynchronously. Non-blocking — returns immediately.
     * The connection state is tracked via [connectionState] flow.
     *
     * Guards against duplicate connects:
     * - If CONNECTING, ignores the request (prevents duplicate Go start)
     * - If CONNECTED with the same config name, ignores (already connected to this tunnel)
     * - If CONNECTED with different config, allows restart (serialized stop-then-start)
     */
    fun connect(appContext: Context, cfg: OlcRtcConfig) {
        val currentState = _connectionState.value
        val currentConfig = config

        // Guard: don't restart while connecting to the same tunnel
        if (currentState == OlcRtcConnectionState.CONNECTING) {
            android.util.Log.d("OlcRtcManager", "connect ignored: already CONNECTING to ${cfg.name}")
            return
        }

        // Guard: already connected to this exact tunnel, ignore
        if (currentState == OlcRtcConnectionState.CONNECTED && currentConfig?.name == cfg.name) {
            android.util.Log.d("OlcRtcManager", "connect ignored: already CONNECTED to ${cfg.name}")
            return
        }

        // For a CONNECTED→different-config restart, do NOT cancel the running
        // connectJob.  The previous job may be mid-native-startup (Mobile.waitReady)
        // and cancelling it would leave the Go runtime in an inconsistent state.
        // Instead, the mutex serialization ensures clean stop-then-start:
        //   - the new job waits for the mutex
        //   - connectInternal calls cleanupOldTransport() which stops the transport
        // For DISCONNECTED/ERROR, normal cancel is safe and desired.
        if (currentState != OlcRtcConnectionState.CONNECTED) {
            connectJob?.cancel()
        } else {
            android.util.Log.d("OlcRtcManager",
                "restarting from CONNECTED (${currentConfig?.name} → ${cfg.name}), skipping cancel")
        }

        connectJob = managerScope.launch {
            mutex.withLock {
                connectInternal(appContext.applicationContext, cfg)
            }
        }
    }

    /**
     * Stop only the old transport without touching public state (connectionState, tunnelName).
     * Prevents state reset in the middle of a connect sequence.
     */
    private suspend fun cleanupOldTransport() {
        watchdogJob?.cancel()
        transport?.stop()
        transport = null
        config = null
    }

    private suspend fun connectInternal(appContext: Context, cfg: OlcRtcConfig) {
        // Defense-in-depth: check again inside mutex
        if (_connectionState.value == OlcRtcConnectionState.CONNECTING) {
            android.util.Log.w("OlcRtcManager", "connectInternal: already CONNECTING, ignoring")
            return
        }
        android.util.Log.d("OlcRtcManager", "connectInternal begin: ${cfg.name}")
        _connectionState.value = OlcRtcConnectionState.CONNECTING
        _currentTunnelName.value = cfg.name
        try {
            // Let host app stop WireGuard tunnels before we claim the TUN
            onBeforeConnect?.invoke()

            // Stop any previous OlcRTC transport cleanly — doesn't reset public state
            cleanupOldTransport()

            config = cfg
            val newTransport = OlcRtcTransport(appContext)
            transport = newTransport
            newTransport.startAndWait(cfg) {
                // This callback fires when VpnService reports a status event
                _vpnStatus.value = it
                when (it) {
                    VpnStatusEvent.TUN2SOCKS_STARTED -> {
                        _connectionState.value = OlcRtcConnectionState.LOCAL_READY
                        android.util.Log.d("OlcRtcManager", "LOCAL_READY: TUN + tun2socks up, verifying remote...")
                    }
                    VpnStatusEvent.PROBE_STARTED -> {
                        _connectionState.value = OlcRtcConnectionState.VERIFYING_REMOTE
                        android.util.Log.d("OlcRtcManager", "VERIFYING_REMOTE: e2e probe in progress")
                    }
                    else -> { /* no state change for other events */ }
                }
            }
            // startAndWait succeeded → e2e probe passed, full data path confirmed
            _errorReason.value = null
            _connectionState.value = OlcRtcConnectionState.CONNECTED
            startWatchdog(appContext)
        } catch (e: CancellationException) {
            android.util.Log.d("OlcRtcManager", "connectInternal cancelled, cleaning up")
            // transport should already be cleaned up by startAndWait's cancellation handler
            // But double-check in case cancellation happened before transport was set up
            if (transport != null) {
                cleanupOldTransport()
            }
            _connectionState.value = OlcRtcConnectionState.DISCONNECTED
            _currentTunnelName.value = null
            throw e
        } catch (e: Throwable) {
            android.util.Log.e("OlcRtcManager", "connectInternal failed", e)
            cleanupOldTransport()
            // Set ERROR with reason — so UI shows the failure
            _errorReason.value = e.message ?: "Connection failed"
            _connectionState.value = OlcRtcConnectionState.ERROR
            _currentTunnelName.value = cfg.name  // keep the tunnel name so user knows which one failed
        }
    }

    private suspend fun disconnectInternal() {
        try {
            cleanupOldTransport()
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcManager", "cleanupOldTransport failed during disconnect", e)
        }
        _errorReason.value = null
        _connectionState.value = OlcRtcConnectionState.DISCONNECTED
        _currentTunnelName.value = null
    }

    fun disconnect() {
        connectJob?.cancel()
        // Set DISCONNECTING immediately so UI shows "Disconnecting…" during cleanup.
        // currentTunnelName stays visible until cleanup completes.
        _connectionState.value = OlcRtcConnectionState.DISCONNECTING
        managerScope.launch {
            mutex.withLock {
                disconnectInternal()
            }
        }
    }

    fun getConfig(): OlcRtcConfig? = config

    /**
     * Restart the current tunnel without recursive connect calls.
     * Called by watchdog or external trigger when a transient error is detected.
     */
    private fun restart(appContext: Context) {
        val cfg = config ?: return
        android.util.Log.d("OlcRtcManager", "restarting tunnel ${cfg.name}")
        connect(appContext, cfg)
    }

    private fun startWatchdog(appContext: Context) {
        watchdogJob?.cancel()
        watchdogJob = managerScope.launch {
            while (isActive) {
                delay(30_000L)
                val t = transport
                val currentState = _connectionState.value
                if (t?.isRunning() != true && (currentState == OlcRtcConnectionState.CONNECTED || currentState == OlcRtcConnectionState.LOCAL_READY)) {
                    android.util.Log.w("OlcRtcManager", "Watchdog: transport lost, restarting...")
                    _errorReason.value = "Transport lost unexpectedly"
                    _connectionState.value = OlcRtcConnectionState.ERROR
                    // Use restart() instead of calling connect() directly to avoid recursive connect
                    restart(appContext)
                }
            }
        }
    }
}
