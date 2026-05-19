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
        assertTrue(result.payload.isEmpty())
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
        assertTrue(result.payload.isEmpty())
    }

    @Test
    fun `parse URI with payload`() {
        val uri = "olcrtc://wbstream?datachannel@room1#${validHexKey}%client2<vp8-fps=60&vp8-batch=64>"
        val result = OlcRtcUriParser.parse(uri)
        assertNotNull(result)
        assertEquals("wbstream", result!!.carrier)
        assertEquals(2, result.payload.size)
        assertEquals("60", result.payload["vp8-fps"])
        assertEquals("64", result.payload["vp8-batch"])
        assertEquals(60, result.vp8Fps)
        assertEquals(64, result.vp8BatchSize)
    }

    @Test
    fun `parse URI with payload and mimo`() {
        val uri = "olcrtc://jazz?datachannel@room1#${validHexKey}%client3\$RU/test<vp8-fps=30&vp8-batch=128>"
        val result = OlcRtcUriParser.parse(uri)
        assertNotNull(result)
        assertEquals("jazz", result!!.carrier)
        assertEquals("RU/test", result.mimo)
        assertEquals(2, result.payload.size)
        assertEquals("30", result.payload["vp8-fps"])
        assertEquals(30, result.vp8Fps)
        assertEquals(128, result.vp8BatchSize)
    }

    @Test
    fun `parse URI with unknown payload keys`() {
        val uri = "olcrtc://wbstream?datachannel@room1#${validHexKey}%client4<unknown-key=42>"
        val result = OlcRtcUriParser.parse(uri)
        assertNotNull(result)
        assertEquals(1, result!!.payload.size)
        assertEquals("42", result.payload["unknown-key"])
    }

    @Test
    fun `parse URI with empty payload brackets`() {
        val uri = "olcrtc://wbstream?datachannel@room1#${validHexKey}%client5<>"
        val result = OlcRtcUriParser.parse(uri)
        assertNotNull(result)
        assertTrue(result!!.payload.isEmpty())
    }

    @Test
    fun `applyPayload for vp8channel sets vp8Fps and vp8BatchSize`() {
        val uri = "olcrtc://wbstream?vp8channel@room1#${validHexKey}%client<vp8-fps=15&vp8-batch=32>"
        val parsed = OlcRtcUriParser.parse(uri)!!
        val config = OlcRtcConfig(
            name = "test", carrier = "wbstream", roomId = "room1",
            clientId = "client", keyHex = validHexKey, transport = "vp8channel"
        )
        val applied = OlcRtcUriParser.applyPayload(config, parsed)
        assertEquals(15, applied.vp8Fps)
        assertEquals(32, applied.vp8BatchSize)
    }

    @Test
    fun `applyPayload for seichannel ignores payload`() {
        val uri = "olcrtc://wbstream?seichannel@room1#${validHexKey}%client<vp8-fps=99>"
        val parsed = OlcRtcUriParser.parse(uri)!!
        val config = OlcRtcConfig(
            name = "test", carrier = "wbstream", roomId = "room1",
            clientId = "client", keyHex = validHexKey, transport = "seichannel"
        )
        val applied = OlcRtcUriParser.applyPayload(config, parsed)
        assertEquals(60, applied.vp8Fps) // unchanged default
    }

    @Test
    fun `toUri with payload`() {
        val config = OlcRtcConfig(
            name = "test", carrier = "wbstream", roomId = "room1",
            clientId = "c", keyHex = validHexKey, transport = "datachannel"
        )
        val uri = OlcRtcUriParser.toUri(config, payload = mapOf("vp8-fps" to "60"))
        assertTrue(uri.endsWith("<vp8-fps=60>"))
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

    @Test
    fun `round trip with payload`() {
        val config = OlcRtcConfig(
            name = "t", carrier = "wbstream", roomId = "r",
            clientId = "c", keyHex = validHexKey, transport = "vp8channel"
        )
        val uri = OlcRtcUriParser.toUri(config, payload = mapOf("vp8-fps" to "60", "vp8-batch" to "64"))
        val parsed = OlcRtcUriParser.parse(uri)
        assertNotNull(parsed)
        assertEquals("60", parsed!!.payload["vp8-fps"])
        assertEquals("64", parsed.payload["vp8-batch"])
        assertEquals(60, parsed.vp8Fps)
        assertEquals(64, parsed.vp8BatchSize)
    }
}
