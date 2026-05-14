package com.wireguard.android.rtc

import com.wireguard.android.rtc.config.OlcRtcTunnelConfig

class FakeRtcEngine : RtcEngine {
    var shouldFailStart = false
    var shouldFailStop = false
    var running = false
    var startCalls = 0

    override fun start(config: OlcRtcTunnelConfig): RtcRunInfo {
        startCalls++
        if (shouldFailStart) throw IllegalStateException("boom")
        running = true
        return RtcRunInfo(config.effectiveDisplayName, 10808)
    }

    override fun stop() {
        if (shouldFailStop) throw IllegalStateException("stop-boom")
        running = false
    }

    override fun isRunning(): Boolean = running
}
