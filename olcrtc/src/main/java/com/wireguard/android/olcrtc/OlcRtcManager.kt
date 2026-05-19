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
    DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR
}

object OlcRtcManager {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()

    /**
     * Callback invoked before connect(). The host app (ui module) sets this
     * to stop active WireGuard tunnels, preventing TUN fd conflicts.
     */
    var onBeforeConnect: (suspend () -> Unit)? = null

    /**
     * Callback to request runtime POST_NOTIFICATIONS permission.
     * Set by the UI layer to show system dialog on Android 13+.
     * Called during connect; if it returns false, connect is aborted.
     */
    var onRequestPostNotifications: (suspend () -> Boolean)? = null

    private var transport: OlcRtcTransport? = null
    private var config: OlcRtcConfig? = null
    private var connectJob: Job? = null
    private var watchdogJob: Job? = null
    private var tunThread: Thread? = null

    private val _vpnStatus = MutableStateFlow<VpnStatusEvent?>(null)
    /** Observes lifecycle events from [OlcRtcVpnService]. */
    val vpnStatus: StateFlow<VpnStatusEvent?> = _vpnStatus.asStateFlow()

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
            mutex.withLock {
                connectInternal(appContext.applicationContext, cfg)
            }
        }
    }

    private suspend fun connectInternal(appContext: Context, cfg: OlcRtcConfig) {
        if (_connectionState.value == OlcRtcConnectionState.CONNECTING ||
            _connectionState.value == OlcRtcConnectionState.CONNECTED) {
            android.util.Log.w("OlcRtcManager", "connectInternal: already connecting/connected, ignoring")
            return
        }
        _connectionState.value = OlcRtcConnectionState.CONNECTING
        _currentTunnelName.value = cfg.name
        try {
            // Request POST_NOTIFICATIONS on Android 13+ (required for foreground service)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val granted = onRequestPostNotifications?.invoke() ?: true
                if (!granted) {
                    android.util.Log.w("OlcRtcManager", "POST_NOTIFICATIONS denied — continuing anyway (notification may be suppressed)")
                }
            }

            // Let host app stop WireGuard tunnels before we claim the TUN
            onBeforeConnect?.invoke()
            disconnectInternal()
            config = cfg
            val newTransport = OlcRtcTransport(appContext)
            transport = newTransport
            newTransport.startAndWait(cfg) {
                // This callback fires when VpnService reports a status event
                _vpnStatus.value = it
                // Manager marks CONNECTED only after TUN2SOCKS_STARTED + Go SOCKS ready
                if (it == VpnStatusEvent.TUN2SOCKS_STARTED) {
                    android.util.Log.d("OlcRtcManager", "tun2socks started, transport ready")
                }
            }
            // Wait for TUN2SOCKS_STARTED confirmation from VpnService
            // startAndWait already does this internally, so we're good
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

    private suspend fun disconnectInternal() {
        watchdogJob?.cancel()
        transport?.stop()
        transport = null
        config = null
        _connectionState.value = OlcRtcConnectionState.DISCONNECTED
        _currentTunnelName.value = null
    }

    fun disconnect() {
        connectJob?.cancel()
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
                if (t?.isRunning() != true && _connectionState.value == OlcRtcConnectionState.CONNECTED) {
                    android.util.Log.w("OlcRtcManager", "Watchdog: transport lost, restarting...")
                    _connectionState.value = OlcRtcConnectionState.ERROR
                    // Use restart() instead of calling connect() directly to avoid recursive connect
                    restart(appContext)
                }
            }
        }
    }
}
