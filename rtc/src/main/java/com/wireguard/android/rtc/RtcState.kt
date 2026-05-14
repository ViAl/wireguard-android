package com.wireguard.android.rtc

sealed class RtcState {
    data object Stopped : RtcState()
    data object Starting : RtcState()
    data object Running : RtcState()
    data class Error(val message: String, val throwable: Throwable? = null) : RtcState()
}
