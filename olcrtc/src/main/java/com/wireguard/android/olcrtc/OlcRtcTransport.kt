package com.wireguard.android.olcrtc

import android.content.Context
import android.content.Intent
import mobile.Mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.cancellation.CancellationException

enum class OlcRtcTransportState {
    IDLE, STARTING, READY, STOPPING, ERROR
}

class OlcRtcTransport(private val appContext: Context) {

    private val _state = MutableStateFlow(OlcRtcTransportState.IDLE)
    val state: StateFlow<OlcRtcTransportState> = _state.asStateFlow()

    private var config: OlcRtcConfig? = null
    private var scope: CoroutineScope? = null

    fun start(config: OlcRtcConfig) {
        stop()
        this.config = config
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        _state.value = OlcRtcTransportState.STARTING

        scope?.launch {
            try {
                // Step 1: Start VpnService — must be on main thread
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
                delay(500)
                // Step 2: Start Go client (heavy work, IO is fine)
                startGoClient(config)
                _state.value = OlcRtcTransportState.READY
            } catch (e: CancellationException) {
                _state.value = OlcRtcTransportState.IDLE
                throw e
            } catch (e: Exception) {
                android.util.Log.e("OlcRtcTransport", "Failed to start", e)
                _state.value = OlcRtcTransportState.ERROR
            }
        }
    }

    fun stop() {
        if (_state.value == OlcRtcTransportState.IDLE) return
        _state.value = OlcRtcTransportState.STOPPING

        try {
            stopGoClient()
            val intent = Intent(appContext, OlcRtcVpnService::class.java).apply {
                action = OlcRtcVpnService.ACTION_STOP
            }
            appContext.startService(intent)
        } catch (e: Exception) {
            android.util.Log.w("OlcRtcTransport", "Error during stop", e)
        }

        scope?.cancel()
        scope = null
        config = null
        _state.value = OlcRtcTransportState.IDLE
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

            android.util.Log.d("OlcRtcTransport", "⏳ Waiting for Go client ready (60s timeout)...")
            Mobile.waitReady(60_000L)
            android.util.Log.d("OlcRtcTransport", "✅ Go client ready!")

            // WG tunnel stopping will be implemented in UI layer (cross-module)

            android.util.Log.d("OlcRtcTransport", "Go client ready: carrier=${config.carrier}")
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcTransport", "startGoClient failed", e)
            throw e
        }
    }

    private fun stopGoClient() {
        try {
            Mobile.stop()
            android.util.Log.d("OlcRtcTransport", "Go client stopped")
        } catch (e: Exception) {
            android.util.Log.w("OlcRtcTransport", "stopGoClient failed", e)
        }
    }

    /**
     * Stop all running WireGuard tunnels to avoid conflict with OlcRTC VPN.
     */
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
