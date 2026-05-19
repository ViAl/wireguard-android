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

    // Compatibility matrix tests

    @Test
    fun `telemost only supports vp8channel and videochannel`() {
        // Valid combos
        assertNull(OlcRtcConfig.validate(validConfig.copy(carrier = "telemost", transport = "vp8channel")))
        assertNull(OlcRtcConfig.validate(validConfig.copy(carrier = "telemost", transport = "videochannel")))
        // Invalid combos
        assertNotNull(OlcRtcConfig.validate(validConfig.copy(carrier = "telemost", transport = "datachannel")))
        assertNotNull(OlcRtcConfig.validate(validConfig.copy(carrier = "telemost", transport = "seichannel")))
    }

    @Test
    fun `jazz supports all transports`() {
        assertNull(OlcRtcConfig.validate(validConfig.copy(carrier = "jazz", transport = "datachannel")))
        assertNull(OlcRtcConfig.validate(validConfig.copy(carrier = "jazz", transport = "vp8channel")))
        assertNull(OlcRtcConfig.validate(validConfig.copy(carrier = "jazz", transport = "seichannel")))
        assertNull(OlcRtcConfig.validate(validConfig.copy(carrier = "jazz", transport = "videochannel")))
    }

    @Test
    fun `wbstream supports all transports`() {
        assertNull(OlcRtcConfig.validate(validConfig.copy(carrier = "wbstream", transport = "datachannel")))
        assertNull(OlcRtcConfig.validate(validConfig.copy(carrier = "wbstream", transport = "vp8channel")))
        assertNull(OlcRtcConfig.validate(validConfig.copy(carrier = "wbstream", transport = "seichannel")))
        assertNull(OlcRtcConfig.validate(validConfig.copy(carrier = "wbstream", transport = "videochannel")))
    }

    @Test
    fun `carrier not in VALID_CARRIERS fails`() {
        assertNotNull(OlcRtcConfig.validate(validConfig.copy(carrier = "unknown")))
    }

    @Test
    fun `transport not in VALID_TRANSPORTS fails`() {
        assertNotNull(OlcRtcConfig.validate(validConfig.copy(transport = "invalid-channel")))
    }

    @Test
    fun `compatibility error message includes supported transports`() {
        val error = OlcRtcConfig.validate(validConfig.copy(carrier = "telemost", transport = "datachannel"))
        assertNotNull(error)
        assertTrue(error!!.contains("datachannel"))
        assertTrue(error.contains("telemost"))
        assertTrue(error.contains("vp8channel") || error.contains("videochannel"))
    }

    // Upstream defaults

    @Test
    fun `default transport is datachannel`() {
        val config = OlcRtcConfig(name = "t", carrier = "wbstream", roomId = "r", clientId = "c", keyHex = validConfig.keyHex)
        assertEquals("datachannel", config.transport)
    }

    @Test
    fun `default socksPort is 1080`() {
        val config = OlcRtcConfig(name = "t", carrier = "wbstream", roomId = "r", clientId = "c", keyHex = validConfig.keyHex)
        assertEquals(1080, config.socksPort)
    }

    @Test
    fun `default vp8Fps is 60`() {
        val config = OlcRtcConfig(name = "t", carrier = "wbstream", roomId = "r", clientId = "c", keyHex = validConfig.keyHex)
        assertEquals(60, config.vp8Fps)
    }

    @Test
    fun `default vp8BatchSize is 64`() {
        val config = OlcRtcConfig(name = "t", carrier = "wbstream", roomId = "r", clientId = "c", keyHex = validConfig.keyHex)
        assertEquals(64, config.vp8BatchSize)
    }

    @Test
    fun `default appRoutingMode is ALL_APPS`() {
        val config = OlcRtcConfig(name = "t", carrier = "wbstream", roomId = "r", clientId = "c", keyHex = validConfig.keyHex)
        assertEquals(AppRoutingMode.ALL_APPS, config.appRoutingMode)
    }

    @Test
    fun `default routeAllIpv4 is true`() {
        val config = OlcRtcConfig(name = "t", carrier = "wbstream", roomId = "r", clientId = "c", keyHex = validConfig.keyHex)
        assertTrue(config.routeAllIpv4)
    }

    @Test
    fun `default routeAllIpv6 is false`() {
        val config = OlcRtcConfig(name = "t", carrier = "wbstream", roomId = "r", clientId = "c", keyHex = validConfig.keyHex)
        assertFalse(config.routeAllIpv6)
    }
}
