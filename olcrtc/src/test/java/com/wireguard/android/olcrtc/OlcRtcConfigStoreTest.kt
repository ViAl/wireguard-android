package com.wireguard.android.olcrtc

import org.junit.Assert.*
import org.junit.Test

class OlcRtcConfigStoreTest {

    private val validHexKey = "e6d84186c09e576a7a07c97b84603ce5778be8809695a9f4eddfa96e381c9603"

    private fun makeConfig(name: String) = OlcRtcConfig(
        name = name,
        carrier = "telemost",
        roomId = "room-$name",
        clientId = "client-$name",
        keyHex = validHexKey
    )

    @Test
    fun `config has expected fields`() {
        val config = makeConfig("test1")
        assertEquals("test1", config.name)
        assertEquals("telemost", config.carrier)
        assertEquals("room-test1", config.roomId)
        assertEquals("client-test1", config.clientId)
        assertEquals(validHexKey, config.keyHex)
    }

    @Test
    fun `config with socks auth`() {
        val config = makeConfig("auth-test").copy(
            socksUser = "user",
            socksPass = "pass"
        )
        assertEquals("user", config.socksUser)
        assertEquals("pass", config.socksPass)
    }

    @Test
    fun `config can be listed`() {
        val configs = listOf(makeConfig("a"), makeConfig("b"), makeConfig("c"))
        assertEquals(3, configs.size)
        assertEquals("a", configs[0].name)
        assertEquals("b", configs[1].name)
        assertEquals("c", configs[2].name)
    }

    @Test
    fun `config with default transport`() {
        val config = makeConfig("defaults")
        assertEquals("vp8channel", config.transport)
    }

    @Test
    fun `config with custom transport`() {
        val config = makeConfig("custom").copy(transport = "datachannel")
        assertEquals("datachannel", config.transport)
    }
}
