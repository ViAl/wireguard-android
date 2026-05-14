package com.wireguard.android.olcrtc

import org.junit.Assert.*
import org.junit.Test

class OlcRtcUriParserTest {

    private val validHexKey = "e6d84186c09e576a7a07c97b84603ce5778be8809695a9f4eddfa96e381c9603"

    @Test
    fun `parse valid URI with mimo`() {
        val uri = "olcrtc://telemost?vp8channel@room123#aabbccdd00112233aabbccdd00112233aabbccdd00112233aabbccdd00112233%myclient\$test"
        val result = OlcRtcUriParser.parse(uri)
        assertNotNull(result)
        assertEquals("telemost", result!!.carrier)
        assertEquals("vp8channel", result.transport)
        assertEquals("room123", result.roomId)
        assertEquals("aabbccdd00112233aabbccdd00112233aabbccdd00112233aabbccdd00112233", result.keyHex)
        assertEquals("myclient", result.clientId)
        assertEquals("test", result.mimo)
    }

    @Test
    fun `parse valid URI without mimo`() {
        val uri = "olcrtc://wbstream?datachannel@room456#${validHexKey}%client1"
        val result = OlcRtcUriParser.parse(uri)
        assertNotNull(result)
        assertEquals("wbstream", result!!.carrier)
        assertEquals("datachannel", result.transport)
        assertEquals("room456", result.roomId)
        assertEquals("client1", result.clientId)
        assertNull(result.mimo)
    }

    @Test
    fun `invalid prefix returns null`() {
        assertNull(OlcRtcUriParser.parse("invalid://test"))
        assertNull(OlcRtcUriParser.parse("wg://config"))
    }

    @Test
    fun `empty string returns null`() {
        assertNull(OlcRtcUriParser.parse(""))
    }

    @Test
    fun `missing fields returns null`() {
        assertNull(OlcRtcUriParser.parse("olcrtc://carrier?transport"))
        assertNull(OlcRtcUriParser.parse("olcrtc://carrier?transport@room"))
        assertNull(OlcRtcUriParser.parse("olcrtc://carrier?transport@room#key"))
    }

    @Test
    fun `round trip`() {
        val config = OlcRtcConfig(
            name = "test",
            carrier = "wbstream",
            roomId = "myroom",
            clientId = "myclient",
            keyHex = validHexKey,
            transport = "vp8channel"
        )
        val uri = OlcRtcUriParser.toUri(config)
        val parsed = OlcRtcUriParser.parse(uri)
        assertNotNull(parsed)
        assertEquals(config.carrier, parsed!!.carrier)
        assertEquals(config.roomId, parsed.roomId)
        assertEquals(config.clientId, parsed.clientId)
        assertEquals(config.keyHex, parsed.keyHex)
        assertEquals("vp8channel", parsed.transport)
    }

    @Test
    fun `round trip with mimo`() {
        val config = OlcRtcConfig(
            name = "t", carrier = "telemost", roomId = "r",
            clientId = "c", keyHex = validHexKey
        )
        val uri = OlcRtcUriParser.toUri(config, "RU / test")
        val parsed = OlcRtcUriParser.parse(uri)
        assertNotNull(parsed)
        assertEquals("RU / test", parsed!!.mimo)
    }
}
