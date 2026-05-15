package com.wireguard.android.rtc

sealed class RtcState {
    data object Stopped : RtcState()
    data object Starting : RtcState()
    data class Running(val displayName: String, val socksPort: Int) : RtcState()
    data object Stopping : RtcState()
    data class Error(val message: String, val throwable: Throwable? = null) : RtcState()
}
