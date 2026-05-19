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

class OlcRtcTransport(private val appContext: Context) {

    private val _state = MutableStateFlow(OlcRtcTransportState.IDLE)
    val state: StateFlow<OlcRtcTransportState> = _state.asStateFlow()

    private var config: OlcRtcConfig? = null
    private var tunThread: Thread? = null

    /**
     * Start the OlcRTC transport with the correct startup order:
     * 1. Configure Mobile providers and socket protector
     * 2. Start Go OlcRTC client
     * 3. Wait until local SOCKS5 is listening (port probe)
     * 4. Start VpnService (establish TUN)
     * 5. Start tun2socks
     * 6. Verify tun2socks didn't exit immediately
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
            // Step 1: Configure Mobile providers and socket protector — must happen BEFORE Go client starts
            configureMobileProviders()

            // Step 2: Start Go OlcRTC client
            startGoClient(config)

            // Step 3: Wait until local SOCKS5 is listening (port probe with timeout)
            waitForLocalSocks(config.socksPort)

            // Step 4: Start VpnService (establishes TUN and starts tun2socks)
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
                }
                appContext.startForegroundService(intent)
            }

            // Step 5-6: Wait for VpnService to establish TUN and start tun2socks
            waitForTunReady()

            // Step 7: Mark READY
            _state.value = OlcRtcTransportState.READY
        } catch (e: CancellationException) {
            _state.value = OlcRtcTransportState.IDLE
            throw e
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcTransport", "Failed to start", e)
            _state.value = OlcRtcTransportState.ERROR
        }
    }

    private fun configureMobileProviders() {
        try {
            // Set socket protector for Go sockets — routes them through VpnService.protect()
            Mobile.setProtector(object : SocketProtector {
                override fun protect(fd: Long): Boolean {
                    val instance = OlcRtcVpnService.currentInstance
                    return instance?.protect(fd.toInt()) ?: false
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

    private suspend fun waitForTunReady(timeoutMs: Long = 15_000) {
        withTimeout(timeoutMs) {
            while (true) {
                if (OlcRtcVpnService.isTunReady) return@withTimeout
                delay(100)
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
