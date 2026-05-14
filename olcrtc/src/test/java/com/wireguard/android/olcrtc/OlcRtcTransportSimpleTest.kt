package com.wireguard.android.olcrtc

import org.junit.Assert.*
import org.junit.Test

/**
 * Simple unit tests that don't need Android Context.
 * Tests the state machine without starting the real transport.
 */
class OlcRtcTransportSimpleTest {

    @Test
    fun `config validation works`() {
        val valid = OlcRtcConfig(
            name = "test",
            carrier = "telemost",
            roomId = "room",
            clientId = "client",
            keyHex = "a".repeat(64)
        )
        assertNull(OlcRtcConfig.validate(valid))

        val invalid = valid.copy(keyHex = "short")
        assertNotNull(OlcRtcConfig.validate(invalid))
    }

    @Test
    fun `socksUser and socksPass defaults are null`() {
        val config = OlcRtcConfig(
            name = "test",
            carrier = "telemost",
            roomId = "room",
            clientId = "client",
            keyHex = "a".repeat(64)
        )
        assertNull(config.socksUser)
        assertNull(config.socksPass)
    }

    @Test
    fun `valid carriers are defined`() {
        assertTrue(OlcRtcConfig.VALID_CARRIERS.contains("telemost"))
        assertTrue(OlcRtcConfig.VALID_CARRIERS.contains("wbstream"))
        assertTrue(OlcRtcConfig.VALID_CARRIERS.contains("jazz"))
    }
}
