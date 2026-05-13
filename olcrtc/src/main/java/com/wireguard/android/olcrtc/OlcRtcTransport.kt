package com.wireguard.android.olcrtc

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
            MobileBridge.load()

            // Set required callbacks BEFORE start
            MobileBridge.setProtector(SocketProtectorProxy { fd ->
                android.util.Log.d("OlcRtcTransport", "Protect fd=$fd")
                true
            })
            MobileBridge.setLogWriter(LogWriterProxy { msg ->
                android.util.Log.d("OlcRTC", msg)
            })

            MobileBridge.setLink("direct")
            MobileBridge.setTransport(config.transport)
            MobileBridge.setDNS(config.dnsServer)
            MobileBridge.setVP8Options(config.vp8Fps, config.vp8BatchSize)
            MobileBridge.setDebug(false)

            val err = MobileBridge.startWithTransport(
                carrierName = config.carrier,
                transportName = config.transport,
                roomID = config.roomId,
                clientID = config.clientId,
                keyHex = config.keyHex,
                socksPort = config.socksPort,
                socksUser = config.socksUser ?: "",
                socksPass = config.socksPass ?: ""
            )
            if (err != null) {
                throw RuntimeException("Mobile.Start failed: $err")
            }
            android.util.Log.d("OlcRtcTransport", "Go client started: carrier=${config.carrier}")
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcTransport", "startGoClient failed", e)
            throw e
        }
    }

    private fun stopGoClient() {
        try {
            if (MobileBridge.load()) {
                MobileBridge.stop()
            }
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
