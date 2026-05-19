package com.wireguard.android.olcrtc

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector
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

class OlcRtcTransport(private val appContext: Context) {

    private val _state = MutableStateFlow(OlcRtcTransportState.IDLE)
    val state: StateFlow<OlcRtcTransportState> = _state.asStateFlow()

    private var config: OlcRtcConfig? = null

    /** Session-scoped startup signal completed by VpnService events. */
    private var startupSignalDeferred: CompletableDeferred<Result<Unit>>? = null

    /**
     * Start the OlcRTC transport with the correct startup order:
     * 1. Start VpnService instance (ACTION_PREPARE) so currentInstance is set
     *    before Go client opens WebRTC sockets that need protection
     * 2. Configure Mobile providers and socket protector
     * 3. Start Go OlcRTC client (sockets are protected via VpnService.protect())
     * 4. Wait until local SOCKS5 is listening (port probe)
     * 5. Start VpnService (establish TUN + start tun2socks)
     * 6. Wait for TUN2SOCKS_STARTED via deferred signal
     * 7. Mark READY
     *
     * This is a suspending function — it returns only when the transport
     * is fully ready or has failed.
     */
    suspend fun startAndWait(config: OlcRtcConfig, onVpnStatus: ((VpnStatusEvent) -> Unit)? = null) {
        stop()
        this.config = config
        _state.value = OlcRtcTransportState.STARTING

        try {
            // Create a fresh startup signal for this session
            // Completed by VpnService events (TUN2SOCKS_STARTED = success, ERROR/EXITED = failure)
            startupSignalDeferred = CompletableDeferred()
            val signal = startupSignalDeferred!!

            // Wire the onVpnStatus callback so VpnService events reach both the Manager
            // and our internal startup signal
            OlcRtcVpnService.onVpnStatus = { event ->
                onVpnStatus?.invoke(event)
                when (event) {
                    VpnStatusEvent.TUN2SOCKS_STARTED -> {
                        signal.complete(Result.success(Unit))
                    }
                    VpnStatusEvent.ERROR, VpnStatusEvent.TUN2SOCKS_EXITED -> {
                        signal.complete(Result.failure(
                            StartupException("VpnService reported ${event.name}")
                        ))
                    }
                    else -> { /* ignore other events for startup signal */ }
                }
            }

            // Step 1: Start VpnService instance (ACTION_PREPARE) so currentInstance is set
            // This must happen BEFORE configureMobileProviders because Go client opens
            // WebRTC sockets during startWithTransport that need VpnService.protect().
            withContext(Dispatchers.Main) {
                val prepareIntent = Intent(appContext, OlcRtcVpnService::class.java).apply {
                    action = OlcRtcVpnService.ACTION_PREPARE
                }
                // If service doesn't exist yet, prepare a fresh deferred for onCreate to complete
                if (OlcRtcVpnService.currentInstance == null) {
                    OlcRtcVpnService.serviceReady = CompletableDeferred()
                }
                appContext.startForegroundService(prepareIntent)
            }
            // Wait deterministically for VpnService to be created (onCreate sets currentInstance)
            if (OlcRtcVpnService.currentInstance == null) {
                OlcRtcVpnService.serviceReady.await()
            }
            android.util.Log.d("OlcRtcTransport", "VpnService prepared, currentInstance=${OlcRtcVpnService.currentInstance != null}")

            // Step 2: Configure Mobile providers and socket protector
            // currentInstance is now set → protect() returns true for Go sockets
            configureMobileProviders()

            // Step 3: Start Go OlcRTC client (sockets now protected)
            startGoClient(config)

            // Step 4: Wait until local SOCKS5 is listening (port probe with timeout)
            waitForLocalSocks(config.socksPort)

            // Step 5: Start VpnService TUN + tun2socks
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
                }
                appContext.startForegroundService(intent)
            }

            // Step 5-6: Wait for VpnService to signal TUN2SOCKS_STARTED (or fail)
            withTimeout(15_000L) {
                val result = signal.await()
                result.getOrThrow()
            }
            android.util.Log.d("OlcRtcTransport", "VpnService startup confirmed")

            // Step 7: Mark READY
            _state.value = OlcRtcTransportState.READY
        } catch (e: CancellationException) {
            _state.value = OlcRtcTransportState.IDLE
            throw e
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcTransport", "Failed to start", e)
            _state.value = OlcRtcTransportState.ERROR
            throw e
        }
    }

    private fun configureMobileProviders() {
        try {
            // Set socket protector for Go sockets — routes them through VpnService.protect()
            Mobile.setProtector(object : SocketProtector {
                override fun protect(fd: Long): Boolean {
                    val instance = OlcRtcVpnService.currentInstance
                    val result = instance?.protect(fd.toInt()) ?: false
                    android.util.Log.d("OlcRtcTransport", "protect(fd=$fd) returned $result (instance=${instance != null})")
                    return result
                }
            })

            Mobile.setLogWriter(object : LogWriter {
                override fun writeLog(msg: String?) {
                    android.util.Log.d("OlcRtcTransport", "Go: ${msg ?: ""}")
                }
            })

            // Call setProviders to register default implementations
            // Order: setProtector + setLogWriter MUST be called first
            Mobile.setProviders()

            android.util.Log.d("OlcRtcTransport", "Mobile providers configured")
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcTransport", "configureMobileProviders failed", e)
            throw e
        }
    }

    private fun startGoClient(config: OlcRtcConfig) {
        try {
            // Configure
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

            Mobile.waitReady(25_000L)

            android.util.Log.d("OlcRtcTransport", "Go client ready: carrier=${config.carrier}")
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcTransport", "startGoClient failed", e)
            throw e
        }
    }

    private suspend fun waitForLocalSocks(port: Int, timeoutMs: Long = 10_000) {
        withTimeout(timeoutMs) {
            while (true) {
                try {
                    java.net.Socket("127.0.0.1", port).use { return@withTimeout }
                } catch (_: Exception) {
                    delay(100)
                }
            }
        }
    }

    suspend fun stop() {
        if (_state.value == OlcRtcTransportState.IDLE) return
        _state.value = OlcRtcTransportState.STOPPING

        try {
            withContext(Dispatchers.IO) {
                stopGoClient()
                val intent = Intent(appContext, OlcRtcVpnService::class.java).apply {
                    action = OlcRtcVpnService.ACTION_STOP
                }
                appContext.startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.w("OlcRtcTransport", "Error during stop", e)
        }

        // Clear VpnService callback on teardown
        OlcRtcVpnService.onVpnStatus = null

        config = null
        _state.value = OlcRtcTransportState.IDLE
    }

    private fun stopGoClient() {
        try {
            Mobile.stop()
            android.util.Log.d("OlcRtcTransport", "Go client stopped")
        } catch (e: Exception) {
            android.util.Log.w("OlcRtcTransport", "stopGoClient failed", e)
        }
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
