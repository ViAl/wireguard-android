package com.wireguard.android.olcrtc

import org.junit.Assert.*
import org.junit.Test

class OlcRtcConfigTest {

    private val validConfig = OlcRtcConfig(
        name = "test-tunnel",
        carrier = "telemost",
        roomId = "96222553289507",
        clientId = "peps-test",
        keyHex = "e6d84186c09e576a7a07c97b84603ce5778be8809695a9f4eddfa96e381c9603",
        transport = "vp8channel"
    )

    @Test
    fun `valid config passes validation`() {
        assertNull(OlcRtcConfig.validate(validConfig))
    }

    @Test
    fun `empty name fails validation`() {
        val config = validConfig.copy(name = "")
        assertNotNull(OlcRtcConfig.validate(config))
    }

    @Test
    fun `name too long fails validation`() {
        val config = validConfig.copy(name = "a".repeat(20))
        assertNotNull(OlcRtcConfig.validate(config))
    }

    @Test
    fun `name with special chars fails validation`() {
        val config = validConfig.copy(name = "test tunnel!")
        assertNotNull(OlcRtcConfig.validate(config))
    }

    @Test
    fun `invalid key length fails`() {
        val config = validConfig.copy(keyHex = "abc123")
        assertNotNull(OlcRtcConfig.validate(config))
    }

    @Test
    fun `non-hex key fails`() {
        val config = validConfig.copy(keyHex = "z".repeat(64))
        assertNotNull(OlcRtcConfig.validate(config))
    }

    @Test
    fun `valid name with special allowed chars passes`() {
        val config = validConfig.copy(name = "my_tunnel.test")
        assertNull(OlcRtcConfig.validate(config))
    }

    @Test
    fun `lowercase hex key passes`() {
        val config = validConfig.copy(keyHex = "a".repeat(64))
        assertNull(OlcRtcConfig.validate(config))
    }

    @Test
    fun `uppercase hex key passes`() {
        val config = validConfig.copy(keyHex = "A".repeat(64))
        assertNull(OlcRtcConfig.validate(config))
    }

    @Test
    fun `empty carrier fails`() {
        val config = validConfig.copy(carrier = "")
        assertNotNull(OlcRtcConfig.validate(config))
    }

    @Test
    fun `port too low fails`() {
        val config = validConfig.copy(socksPort = 80)
        assertNotNull(OlcRtcConfig.validate(config))
    }

    @Test
    fun `port too high fails`() {
        val config = validConfig.copy(socksPort = 70000)
        assertNotNull(OlcRtcConfig.validate(config))
    }

    @Test
    fun `valid port passes`() {
        val config = validConfig.copy(socksPort = 10808)
        assertNull(OlcRtcConfig.validate(config))
    }
}
