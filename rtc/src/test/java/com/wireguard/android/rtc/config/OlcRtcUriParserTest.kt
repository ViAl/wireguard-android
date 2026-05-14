package com.wireguard.android.rtc.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OlcRtcUriParserTest {
    private val validKey = "e6d8410000000000000000000000000000000000000000000000000000c9603"

    @Test
    fun parseValidTelemostVp8() {
        val config = OlcRtcUriParser.parse("olcrtc://telemost?vp8channel@96222553289507#$validKey%peps-test\$Test_VPS")
        assertEquals(OlcRtcTunnelConfig.Carrier.TELEMOST, config.carrier)
        assertEquals(OlcRtcTunnelConfig.Transport.VP8_CHANNEL, config.transport)
        assertEquals("Test_VPS", config.displayName)
        assertEquals("e6d841...0c9603", config.maskedKey())
    }

    @Test
    fun parseWithoutDisplayName() {
        val config = OlcRtcUriParser.parse("olcrtc://jazz?datachannel@room-1#$validKey%client-1")
        assertEquals("jazz:room-1:client-1", config.effectiveDisplayName)
    }

    @Test
    fun parsePercentEncodedDisplayName() {
        val config = OlcRtcUriParser.parse("olcrtc://wbstream?seichannel@room123#$validKey%client\$My%20Server")
        assertEquals("My Server", config.displayName)
    }

    @Test
    fun serializeConfigToUri() {
        val config = OlcRtcTunnelConfig(
            carrier = OlcRtcTunnelConfig.Carrier.TELEMOST,
            transport = OlcRtcTunnelConfig.Transport.VIDEO_CHANNEL,
            roomId = "96222553289507",
            keyHex = validKey,
            clientId = "peps-test",
            displayName = "Test VPS",
        )
        assertEquals(
            "olcrtc://telemost?videochannel@96222553289507#$validKey%peps-test\$Test%20VPS",
            config.toUri(),
        )
    }

    @Test(expected = OlcRtcConfigParseException::class)
    fun rejectInvalidScheme() { OlcRtcUriParser.parse("http://telemost?vp8channel@room#$validKey%client") }

    @Test(expected = OlcRtcConfigParseException::class)
    fun rejectUnsupportedCarrier() { OlcRtcUriParser.parse("olcrtc://zoom?vp8channel@room#$validKey%client") }

    @Test(expected = OlcRtcConfigParseException::class)
    fun rejectUnsupportedTransport() { OlcRtcUriParser.parse("olcrtc://telemost?udp@room#$validKey%client") }

    @Test(expected = OlcRtcConfigParseException::class)
    fun rejectShortKey() { OlcRtcUriParser.parse("olcrtc://telemost?vp8channel@room#abcd%client") }

    @Test(expected = OlcRtcConfigParseException::class)
    fun rejectNonHexKey() { OlcRtcUriParser.parse("olcrtc://telemost?vp8channel@room#${"g".repeat(64)}%client") }

    @Test(expected = OlcRtcConfigParseException::class)
    fun rejectEmptyClientId() { OlcRtcUriParser.parse("olcrtc://telemost?vp8channel@room#$validKey%") }

    @Test(expected = OlcRtcConfigParseException::class)
    fun rejectRoomIdWithSpaces() { OlcRtcUriParser.parse("olcrtc://telemost?vp8channel@bad room#$validKey%client") }

    @Test
    fun rejectClientIdWithSpaces() {
        try {
            OlcRtcUriParser.parse("olcrtc://telemost?vp8channel@room#$validKey%bad client")
        } catch (e: OlcRtcConfigParseException) {
            assertTrue(e.message?.contains("Client ID") == true)
            return
        }
        error("Expected exception")
    }
}
