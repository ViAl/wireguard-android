package com.wireguard.android.rtc

import com.wireguard.android.rtc.config.OlcRtcTunnelConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class RtcControllerTest {
    private val config = OlcRtcTunnelConfig(
        carrier = OlcRtcTunnelConfig.Carrier.TELEMOST,
        transport = OlcRtcTunnelConfig.Transport.VP8_CHANNEL,
        roomId = "room",
        keyHex = "e6d8410000000000000000000000000000000000000000000000000000c9603",
        clientId = "client",
        displayName = "name",
    )

    @Test
    fun goesToRunningOnSuccess() = runBlocking {
        val controller = RtcController(FakeRtcEngine())
        controller.start(config)
        delay(50)
        assertTrue(controller.state.value is RtcState.Running)
    }

    @Test
    fun goesToErrorOnFailure() = runBlocking {
        val engine = FakeRtcEngine().apply { shouldFailStart = true }
        val controller = RtcController(engine)
        controller.start(config)
        delay(50)
        assertTrue(controller.state.value is RtcState.Error)
    }
}
