package com.wireguard.android.rtc

import com.wireguard.android.rtc.config.OlcRtcTunnelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RtcController(
    private val engine: RtcEngine,
    val logBuffer: RtcLogBuffer = RtcLogBuffer(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<RtcState>(RtcState.Stopped)
    val state: StateFlow<RtcState> = mutableState.asStateFlow()

    @Synchronized
    fun start(config: OlcRtcTunnelConfig) {
        val current = mutableState.value
        if (current is RtcState.Starting || current is RtcState.Running || current is RtcState.Stopping) return
        mutableState.value = RtcState.Starting
        logBuffer.add("Start requested")
        logBuffer.add("Using carrier=${config.carrier.uriValue}, transport=${config.transport.uriValue}, roomId=${config.roomId}, clientId=${config.clientId}, key=${config.maskedKey()}")

        scope.launch {
            runCatching { engine.start(config) }
                .onSuccess { info -> mutableState.value = RtcState.Running(info.displayName, info.socksPort) }
                .onFailure { throwable ->
                    logBuffer.add("Start failed: ${throwable.message ?: throwable::class.java.simpleName}")
                    mutableState.value = RtcState.Error(throwable.message ?: "Failed to start RTC tunnel", throwable)
                }
        }
    }

    @Synchronized
    fun stop() {
        val current = mutableState.value
        if (current is RtcState.Stopped || current is RtcState.Stopping) return
        mutableState.value = RtcState.Stopping
        logBuffer.add("Stop requested")

        scope.launch {
            runCatching { engine.stop() }
                .onFailure { throwable ->
                    logBuffer.add("Stop error: ${throwable.message ?: throwable::class.java.simpleName}")
                }
            mutableState.value = RtcState.Stopped
        }
    }

    fun close() {
        scope.cancel()
    }
}
