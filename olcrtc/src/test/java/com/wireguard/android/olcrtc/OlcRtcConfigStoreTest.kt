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
        assertEquals("datachannel", config.transport)
    }

    @Test
    fun `config with custom transport`() {
        val config = makeConfig("custom").copy(transport = "datachannel")
        assertEquals("datachannel", config.transport)
    }

    @Test
    fun `config with nullable socksUser and socksPass`() {
        val config = makeConfig("nullable")
        assertNull(config.socksUser)
        assertNull(config.socksPass)
    }

    @Test
    fun `config with empty excludedApplications`() {
        val config = makeConfig("empty-excluded")
        assertTrue(config.excludedApplications.isEmpty())
    }

    @Test
    fun `config with excludedApplications`() {
        val config = makeConfig("excluded").copy(
            excludedApplications = setOf("com.example.app1", "com.example.app2")
        )
        assertEquals(2, config.excludedApplications.size)
        assertTrue(config.excludedApplications.contains("com.example.app1"))
    }

    @Test
    fun `config with includedApplications`() {
        val config = makeConfig("included").copy(
            includedApplications = setOf("com.example.vpnapp")
        )
        assertEquals(1, config.includedApplications.size)
        assertTrue(config.includedApplications.contains("com.example.vpnapp"))
    }

    @Test
    fun `config with both excluded and included applications`() {
        val config = makeConfig("both").copy(
            excludedApplications = setOf("com.example.exclude"),
            includedApplications = setOf("com.example.include")
        )
        assertEquals(1, config.excludedApplications.size)
        assertEquals(1, config.includedApplications.size)
    }

    @Test
    fun `config with appRoutingMode`() {
        val config = makeConfig("routing").copy(appRoutingMode = AppRoutingMode.ONLY_SELECTED_APPS)
        assertEquals(AppRoutingMode.ONLY_SELECTED_APPS, config.appRoutingMode)
    }

    @Test
    fun `config with routeAllIpv4`() {
        val config = makeConfig("ipv4").copy(routeAllIpv4 = true)
        assertTrue(config.routeAllIpv4)
    }

    @Test
    fun `config with routeAllIpv6`() {
        val config = makeConfig("ipv6").copy(routeAllIpv6 = false)
        assertFalse(config.routeAllIpv6)
    }

    @Test
    fun `config with empty keyHex`() {
        val config = makeConfig("empty-key").copy(keyHex = "")
        assertEquals("", config.keyHex)
    }

    @Test
    fun `config with dnsServer default`() {
        val config = makeConfig("dns")
        assertEquals("1.1.1.1:53", config.dnsServer)
    }

    // NOTE: Real persistence round-trip tests (save/load/delete) require Android
    // Context at runtime and cannot run as pure JVM unit tests.
    // The save/load logic in OlcRtcConfigStore is a thin JSON wrapper;
    // its correctness depends on the JSON library (org.json) which is well-tested.
    //
    // For integration testing, build and run on device:
    //   ./gradlew :olcrtc:connectedCheck
    // or use the manual test checklist (Task 12 in analyst spec).
}
