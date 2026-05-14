package com.wireguard.android.rtc

import com.wireguard.android.rtc.config.OlcRtcTunnelConfig
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
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
    fun startSuccessToRunning() {
        val scope = TestScope(StandardTestDispatcher())
        val controller = RtcController(FakeRtcEngine(), scope = scope)
        controller.start(config)
        scope.runCurrent()
        assertTrue(controller.state.value is RtcState.Running)
    }

    @Test
    fun startFailureToError() {
        val scope = TestScope(StandardTestDispatcher())
        val engine = FakeRtcEngine().apply { shouldFailStart = true }
        val controller = RtcController(engine, scope = scope)
        controller.start(config)
        scope.runCurrent()
        assertTrue(controller.state.value is RtcState.Error)
    }

    @Test
    fun doubleStartIgnored() {
        val scope = TestScope(StandardTestDispatcher())
        val engine = FakeRtcEngine()
        val controller = RtcController(engine, scope = scope)
        controller.start(config)
        controller.start(config)
        scope.runCurrent()
        assertEquals(1, engine.startCalls)
    }

    @Test
    fun stopSuccessToStopped() {
        val scope = TestScope(StandardTestDispatcher())
        val engine = FakeRtcEngine()
        val controller = RtcController(engine, scope = scope)
        controller.start(config)
        scope.runCurrent()
        controller.stop()
        scope.runCurrent()
        assertTrue(controller.state.value is RtcState.Stopped)
    }

    @Test
    fun stopFailureToError() {
        val scope = TestScope(StandardTestDispatcher())
        val engine = FakeRtcEngine().apply { shouldFailStop = true }
        val controller = RtcController(engine, scope = scope)
        controller.start(config)
        scope.runCurrent()
        controller.stop()
        scope.runCurrent()
        assertTrue(controller.state.value is RtcState.Error)
    }

    @Test
    fun logsDoNotContainFullKey() {
        val scope = TestScope(StandardTestDispatcher())
        val controller = RtcController(FakeRtcEngine(), scope = scope)
        controller.start(config)
        val logs = controller.logBuffer.snapshot().joinToString("\n")
        assertTrue(logs.contains(config.maskedKey()))
        assertTrue(!logs.contains(config.keyHex))
    }
}
