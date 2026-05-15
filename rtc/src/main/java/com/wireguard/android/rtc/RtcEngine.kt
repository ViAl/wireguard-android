package com.wireguard.android.rtc

import com.wireguard.android.rtc.config.OlcRtcTunnelConfig

data class RtcRunInfo(
    val displayName: String,
    val socksPort: Int,
)

interface RtcEngine {
    fun start(config: OlcRtcTunnelConfig): RtcRunInfo
    fun stop()
    fun isRunning(): Boolean
}
