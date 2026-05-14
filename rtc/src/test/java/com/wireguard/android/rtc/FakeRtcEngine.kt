package com.wireguard.android.rtc

import com.wireguard.android.rtc.config.OlcRtcTunnelConfig

class FakeRtcEngine : RtcEngine {
    var shouldFailStart = false
    var running = false

    override fun start(config: OlcRtcTunnelConfig): RtcRunInfo {
        if (shouldFailStart) throw IllegalStateException("boom")
        running = true
        return RtcRunInfo(config.effectiveDisplayName, 10808)
    }

    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean = running
}
