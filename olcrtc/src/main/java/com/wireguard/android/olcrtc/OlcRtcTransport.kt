package com.wireguard.android.olcrtc

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector

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

    @Volatile
    private var lastRtcConnectedAtMs = 0L
    @Volatile
    private var lastRtcFailureAtMs = 0L
    @Volatile
    private var rtcFailureCount = 0

    fun start(config: OlcRtcConfig) {
        stop()
        this.config = config
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        _state.value = OlcRtcTransportState.STARTING

        scope?.launch {
            try {
                // Step 0: Check for active VPN and take over if needed
                // Prevents crash when WireGuard or another VPN tunnel is already active
                withContext(Dispatchers.IO) {
                    val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val activeNetwork = cm.activeNetwork
                    val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
                    val vpnActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                    if (vpnActive) {
                        android.util.Log.w("OlcRtcTransport", "VPN tunnel detected active — taking over to prevent crash")
                        val prepareIntent = VpnService.prepare(appContext)
                        if (prepareIntent != null) {
                            // Another app owns the VPN — launch system dialog to revoke it
                            prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            appContext.startActivity(prepareIntent)
                            // Give the system time to revoke the old VPN tunnel
                            delay(1000)
                        }
                    }
                }

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
                        putExtra(OlcRtcVpnService.EXTRA_CONFIG_SOCKS_USER, config.socksUser ?: "")
                        putExtra(OlcRtcVpnService.EXTRA_CONFIG_SOCKS_PASS, config.socksPass ?: "")
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
            // Mobile.load() not needed — libgojni.so is loaded automatically by the AAR's static initializer.
            // Wire SocketProtector to make Go sockets go through the VPN interface.
            // Read protectFn fresh each time to avoid capturing a stale reference
            Mobile.setProtector(object : SocketProtector {
                override fun protect(fd: Long): Boolean {
                    val fn = OlcRtcVpnService.protectFn
                    if (fn != null) {
                        return fn(fd.toInt())
                    }
                    android.util.Log.w("OlcRtcTransport", "protect() called but no VpnService available")
                    return true
                }
            })

            // setProviders BEFORE setLogWriter — order from olcbox
            Mobile.setProviders()

            // Wire LogWriter to forward Go log output to Android logcat and monitor RTC health.
            resetRtcHealthState()
            Mobile.setLogWriter(object : LogWriter {
                override fun writeLog(msg: String?) {
                    val line = (msg ?: "").trimEnd()
                    android.util.Log.d("OlcRtcTransport", "Go: $line")
                    handleRtcLine(line)
                }
            })

            // Configure
            Mobile.setLink("direct")
            Mobile.setTransport(config.transport)
            Mobile.setDNS(config.dnsServer)
            Mobile.setVP8Options(config.vp8Fps.toLong(), config.vp8BatchSize.toLong())
            Mobile.setDebug(false)

            resetRtcHealthState()
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

    private fun stopGoClient() {
        try {
            Mobile.stop()
            android.util.Log.d("OlcRtcTransport", "Go client stopped")
        } catch (e: Exception) {
            android.util.Log.w("OlcRtcTransport", "stopGoClient failed", e)
        }
    }

    private fun handleRtcLine(line: String) {
        val lowerLine = line.lowercase()

        if (lowerLine.contains("ice connection state changed: connected") ||
            lowerLine.contains("peer connection state changed: connected") ||
            lowerLine.contains("socks5 server listening")
        ) {
            markRtcConnected()
            return
        }

        if (lowerLine.contains("ice connection state changed: failed") ||
            lowerLine.contains("peer connection state changed: failed") ||
            lowerLine.contains("ice connection state changed: closed") ||
            lowerLine.contains("peer connection state changed: closed")
        ) {
            noteRtcFailure(lowerLine)
            return
        }

        if (lowerLine.contains("network is unreachable") ||
            lowerLine.contains("use of closed network connection") ||
            lowerLine.contains("read/write on closed pipe")
        ) {
            noteRtcFailure(lowerLine)
        }
    }

    private fun markRtcConnected() {
        lastRtcConnectedAtMs = System.currentTimeMillis()
        lastRtcFailureAtMs = 0L
        rtcFailureCount = 0
    }

    private fun resetRtcHealthState() {
        lastRtcConnectedAtMs = System.currentTimeMillis()
        lastRtcFailureAtMs = 0L
        rtcFailureCount = 0
    }

    private fun noteRtcFailure(line: String) {
        if (_state.value != OlcRtcTransportState.READY) return

        val now = System.currentTimeMillis()
        if (now - lastRtcConnectedAtMs < 2_500L) return

        rtcFailureCount = if (now - lastRtcFailureAtMs <= 15_000L) {
            rtcFailureCount + 1
        } else {
            1
        }
        lastRtcFailureAtMs = now

        android.util.Log.w("OlcRtcTransport", "RTC health issue #$rtcFailureCount: $line")

        if (rtcFailureCount >= 5) {
            android.util.Log.w("OlcRtcTransport", "RTC failures threshold reached, requesting reconnect")
            // Signal to the manager via state that recovery is needed
            _state.value = OlcRtcTransportState.ERROR
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
