package com.wireguard.android.olcrtc

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.cancellation.CancellationException

enum class OlcRtcTransportState {
    IDLE, STARTING, READY, STOPPING, ERROR
}

/**
 * Lifecycle events from [OlcRtcVpnService] communicated back to [OlcRtcManager].
 */
enum class VpnStatusEvent {
    /** TUN interface has been established */
    TUN_ESTABLISHED,
    /** tun2socks has started successfully */
    TUN2SOCKS_STARTED,
    /** tun2socks exited (expected or unexpected) */
    TUN2SOCKS_EXITED,
    /** VPN service stopped */
    VPN_STOPPED,
    /** An error occurred */
    ERROR
}

class StartupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thin IPC client for OlcRTC transport in the main process.
 *
 * **Process separation (Task 3):** All Mobile.* calls, libgojni.so loading,
 * and Go client lifecycle now live inside [OlcRtcVpnService] running in the
 * `:olcrtc` process. This Transport communicates with the VpnService via
 * standard Intents (commands) and [Messenger] (status callbacks).
 *
 * The main process never loads libgojni.so and never calls `mobile.Mobile.*`.
 * WireGuard's libwg-go.so remains safely isolated in the main process.
 */
class OlcRtcTransport(private val appContext: Context) {

    private val _state = MutableStateFlow(OlcRtcTransportState.IDLE)
    val state: StateFlow<OlcRtcTransportState> = _state.asStateFlow()

    private var config: OlcRtcConfig? = null

    /** Session-scoped startup signal completed by VpnService via Messenger IPC. */
    private var startupSignalDeferred: CompletableDeferred<Result<Unit>>? = null

    /**
     * Messenger that the VpnService in :olcrtc process uses to send status
     * messages back to the main process.
     */
    private var replyMessenger: Messenger? = null

    /**
     * Local deferred to wait for VpnService creation confirmation.
     * Completed when the VpnService sends MSG_SERVICE_READY via Messenger.
     */
    private var serviceReadyDeferred: CompletableDeferred<Unit>? = null

    // ── Task 2: Convert timeouts to StartupException ──

    /**
     * Wrap [withTimeout] so that internal readiness timeouts throw
     * [StartupException] instead of [TimeoutCancellationException].
     *
     * This prevents the catch(CancellationException) handler from treating
     * a service-non-ready timeout the same as user/job cancellation.
     */
    private suspend fun <T> timeoutAsStartupException(
        timeoutMs: Long,
        message: String,
        block: suspend () -> T
    ): T {
        return try {
            withTimeout(timeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            throw StartupException(message, e)
        }
    }

    /**
     * Start the OlcRTC transport with the correct startup order:
     *
     * 1. Send ACTION_PREPARE to VpnService in :olcrtc process
     *    → VpnService loads libgojni.so, libhev-socks5-tunnel.so, libolcrtc_tun2socks.so
     *    → VpnService signals MSG_SERVICE_READY via Messenger
     * 2. Send ACTION_START with full config + reply Messenger
     *    → VpnService configures Mobile providers, starts Go client, establishes TUN, starts tun2socks
     *    → VpnService signals MSG_TUN2SOCKS_STARTED via Messenger
     * 3. Mark READY
     *
     * This is a suspending function — it returns only when the transport
     * is fully ready or has failed.
     */
    suspend fun startAndWait(config: OlcRtcConfig, onVpnStatus: ((VpnStatusEvent) -> Unit)? = null) {
        stop()
        this.config = config
        _state.value = OlcRtcTransportState.STARTING

        try {
            startupSignalDeferred = CompletableDeferred()
            val signal = startupSignalDeferred!!

            // Create reply Messenger for VpnService status from :olcrtc process
            val handler = Handler(Looper.getMainLooper()) { msg ->
                val event = mapMessageToEvent(msg)
                if (event != null) {
                    onVpnStatus?.invoke(event)
                }
                handleStatusMessage(msg, signal)
                true
            }
            replyMessenger = Messenger(handler)

            // ── Step 1: ACTION_PREPARE ──
            // Creates VpnService instance in :olcrtc process, loads native libs
            serviceReadyDeferred = CompletableDeferred()
            val serviceReadySignal = serviceReadyDeferred!!

            withContext(Dispatchers.Main) {
                val prepareIntent = Intent(appContext, OlcRtcVpnService::class.java).apply {
                    action = OlcRtcVpnService.ACTION_PREPARE
                    replyMessenger?.let { putExtra("reply_to", it) }
                }
                appContext.startForegroundService(prepareIntent)
                android.util.Log.d("OlcRtcTransport", "ACTION_PREPARE sent")
            }

            // Task 2: Use timeoutAsStartupException for service readiness
            timeoutAsStartupException(10_000L, "VpnService ready timeout (ACTION_PREPARE)") {
                serviceReadySignal.await()
            }
            android.util.Log.d("OlcRtcTransport", "VpnService READY (MSG_SERVICE_READY received)")

            // ── Step 2: ACTION_START ──
            // Task 1+5: No SOCKS probe blocking — go directly to ACTION_START after VpnService is ready.
            // The VpnService in :olcrtc handles all Go lifecycle + TUN + tun2socks internally.
            // Task 6: Log the transition.
            android.util.Log.d("OlcRtcTransport", "Proceeding to ACTION_START (no SOCKS probe gate)")
            withContext(Dispatchers.Main) {
                val intent = Intent(appContext, OlcRtcVpnService::class.java).apply {
                    action = OlcRtcVpnService.ACTION_START
                    putExtra(OlcRtcVpnService.EXTRA_CONFIG_NAME, config.name)
                    putExtra(OlcRtcVpnService.EXTRA_CONFIG_CARRIER, config.carrier)
                    putExtra(OlcRtcVpnService.EXTRA_CONFIG_ROOM, config.roomId)
                    putExtra(OlcRtcVpnService.EXTRA_CONFIG_CLIENT, config.clientId)
                    putExtra(OlcRtcVpnService.EXTRA_CONFIG_KEY, config.keyHex)
                    putExtra(OlcRtcVpnService.EXTRA_CONFIG_TRANSPORT, config.transport)
                    putExtra(OlcRtcVpnService.EXTRA_CONFIG_SOCKS_PORT, config.socksPort)
                    putExtra(OlcRtcVpnService.EXTRA_CONFIG_DNS, config.dnsServer)
                    putExtra(OlcRtcVpnService.EXTRA_CONFIG_APP_ROUTING_MODE, config.appRoutingMode.name)
                    putExtra(OlcRtcVpnService.EXTRA_CONFIG_EXCLUDED_APPS, ArrayList(config.excludedApplications))
                    putExtra(OlcRtcVpnService.EXTRA_CONFIG_INCLUDED_APPS, ArrayList(config.includedApplications))
                    putExtra(OlcRtcVpnService.EXTRA_CONFIG_ROUTE_ALL_IPV4, config.routeAllIpv4)
                    putExtra(OlcRtcVpnService.EXTRA_CONFIG_ROUTE_ALL_IPV6, config.routeAllIpv6)
                    config.socksUser?.let { putExtra(OlcRtcVpnService.EXTRA_CONFIG_SOCKS_USER, it) }
                    config.socksPass?.let { putExtra(OlcRtcVpnService.EXTRA_CONFIG_SOCKS_PASS, it) }
                    replyMessenger?.let { putExtra("reply_to", it) }
                }
                appContext.startForegroundService(intent)
                android.util.Log.d("OlcRtcTransport", "ACTION_START sent for ${config.name}")
            }

            // ── Step 3: Wait for TUN2SOCKS_STARTED ──
            // Task 2: Use timeoutAsStartupException
            timeoutAsStartupException(30_000L, "VpnService startup timeout (TUN2SOCKS_STARTED)") {
                val result = signal.await()
                result.getOrThrow()
            }
            android.util.Log.d("OlcRtcTransport", "VpnService startup confirmed (TUN2SOCKS_STARTED)")

            // Step 4: Mark READY
            _state.value = OlcRtcTransportState.READY
        } catch (e: CancellationException) {
            android.util.Log.d("OlcRtcTransport", "startAndWait cancelled during startup")
            cleanupStartupFailure()
            _state.value = OlcRtcTransportState.IDLE
            throw e
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcTransport", "Failed to start", e)
            cleanupStartupFailure()
            _state.value = OlcRtcTransportState.ERROR
            throw e
        }
    }

    /**
     * Map a Messenger message to a [VpnStatusEvent] for the callback.
     */
    private fun mapMessageToEvent(msg: Message): VpnStatusEvent? {
        return when (msg.what) {
            OlcRtcVpnService.MSG_GO_READY -> null          // internal signal
            OlcRtcVpnService.MSG_SERVICE_READY -> null     // internal signal
            OlcRtcVpnService.MSG_STARTUP_STARTED -> null   // internal signal
            OlcRtcVpnService.MSG_TUN_ESTABLISHED -> VpnStatusEvent.TUN_ESTABLISHED
            OlcRtcVpnService.MSG_TUN2SOCKS_STARTED -> VpnStatusEvent.TUN2SOCKS_STARTED
            OlcRtcVpnService.MSG_TUN2SOCKS_EXITED -> VpnStatusEvent.TUN2SOCKS_EXITED
            OlcRtcVpnService.MSG_ERROR -> VpnStatusEvent.ERROR
            OlcRtcVpnService.MSG_VPN_STOPPED -> VpnStatusEvent.VPN_STOPPED
            else -> null
        }
    }

    /**
     * Handle status messages from VpnService in :olcrtc process.
     * - Completes [serviceReadyDeferred] on MSG_SERVICE_READY
     * - Completes [startupSignalDeferred] on MSG_TUN2SOCKS_STARTED / MSG_ERROR / MSG_TUN2SOCKS_EXITED
     */
    private fun handleStatusMessage(
        msg: Message,
        signal: CompletableDeferred<Result<Unit>>
    ) {
        when (msg.what) {
            OlcRtcVpnService.MSG_SERVICE_READY -> {
                android.util.Log.d("OlcRtcTransport", "MSG_SERVICE_READY received from :olcrtc")
                serviceReadyDeferred?.complete(Unit)
            }
            OlcRtcVpnService.MSG_GO_READY -> {
                android.util.Log.d("OlcRtcTransport", "Go client ready (MSG_GO_READY from :olcrtc)")
            }
            OlcRtcVpnService.MSG_TUN_ESTABLISHED -> {
                android.util.Log.d("OlcRtcTransport", "TUN established (MSG_TUN_ESTABLISHED from :olcrtc)")
            }
            OlcRtcVpnService.MSG_TUN2SOCKS_STARTED -> {
                android.util.Log.d("OlcRtcTransport", "TUN2SOCKS_STARTED received from :olcrtc")
                signal.complete(Result.success(Unit))
            }
            OlcRtcVpnService.MSG_TUN2SOCKS_EXITED -> {
                android.util.Log.d("OlcRtcTransport", "MSG_TUN2SOCKS_EXITED received from :olcrtc")
                val errorMsg = msg.data.getString("error") ?: "tun2socks exited unexpectedly"
                signal.complete(Result.failure(StartupException(errorMsg)))
            }
            OlcRtcVpnService.MSG_ERROR -> {
                val errorMsg = msg.data.getString("error") ?: "VpnService reported error"
                android.util.Log.e("OlcRtcTransport", "MSG_ERROR from :olcrtc: $errorMsg")
                signal.complete(Result.failure(StartupException(errorMsg)))
            }
            OlcRtcVpnService.MSG_VPN_STOPPED -> {
                android.util.Log.d("OlcRtcTransport", "MSG_VPN_STOPPED received from :olcrtc")
            }
        }
    }

    suspend fun stop() {
        if (_state.value == OlcRtcTransportState.IDLE) return
        _state.value = OlcRtcTransportState.STOPPING
        android.util.Log.d("OlcRtcTransport", "stop begin")

        try {
            withContext(Dispatchers.IO) {
                val intent = Intent(appContext, OlcRtcVpnService::class.java).apply {
                    action = OlcRtcVpnService.ACTION_STOP
                }
                appContext.startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.w("OlcRtcTransport", "Error during stop", e)
        }

        config = null
        replyMessenger = null
        startupSignalDeferred = null
        serviceReadyDeferred = null
        _state.value = OlcRtcTransportState.IDLE
        android.util.Log.d("OlcRtcTransport", "stop complete")
    }

    /**
     * Shared cleanup for startup failures (both cancellation and generic exceptions).
     * Sends ACTION_STOP to tear down the VpnService in :olcrtc process.
     * Safe to call multiple times.
     */
    private suspend fun cleanupStartupFailure() {
        try {
            withContext(Dispatchers.Main) {
                val stopIntent = Intent(appContext, OlcRtcVpnService::class.java).apply {
                    action = OlcRtcVpnService.ACTION_STOP
                }
                appContext.startService(stopIntent)
            }
        } catch (_: Exception) { }
        startupSignalDeferred = null
        serviceReadyDeferred = null
    }

    fun getConfig(): OlcRtcConfig? = config
    fun isRunning(): Boolean = _state.value == OlcRtcTransportState.READY

    fun getStatusSummary(): String {
        val s = _state.value
        val n = config?.name ?: "unnamed"
        return when (s) {
            OlcRtcTransportState.IDLE -> "OlcRTC '$n': idle"
            OlcRtcTransportState.STARTING -> "OlcRTC '$n': starting..."
            OlcRtcTransportState.READY -> "OlcRTC '$n': running (${config?.carrier ?: "?"})"
            OlcRtcTransportState.STOPPING -> "OlcRTC '$n': stopping..."
            OlcRtcTransportState.ERROR -> "OlcRTC '$n': error"
        }
    }
}
