package com.wireguard.android.rtc

import android.content.Context
import com.wireguard.android.rtc.config.OlcRtcTunnelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RtcController(
    @Suppress("UNUSED_PARAMETER")
    private val context: Context,
) {
    private val mutableState = MutableStateFlow<RtcState>(RtcState.Stopped)
    val state: StateFlow<RtcState> = mutableState.asStateFlow()

    @Synchronized
    fun start(config: OlcRtcTunnelConfig) {
        if (mutableState.value == RtcState.Running || mutableState.value == RtcState.Starting) return
        mutableState.value = RtcState.Starting

        try {
            // TODO: Replace with gomobile-generated OlcRTC API call.
            // olcrtc.startWithTransport(config.carrier.uriValue, config.transport.uriValue, config.roomId, config.clientId, config.keyHex)
            mutableState.value = RtcState.Running
        } catch (throwable: Throwable) {
            mutableState.value = RtcState.Error(throwable.message ?: "Failed to start RTC tunnel", throwable)
        }
    }

    @Synchronized
    fun stop() {
        try {
            // TODO: Replace with gomobile-generated OlcRTC stop call.
            // olcrtc.stop()
        } finally {
            mutableState.value = RtcState.Stopped
        }
    }

    fun isRunning(): Boolean = mutableState.value == RtcState.Running
}
