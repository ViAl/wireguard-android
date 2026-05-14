package com.wireguard.android.rtc.config

object OlcRtcUriParser {
    private const val PREFIX = "olcrtc://"

    fun parse(rawInput: String): OlcRtcTunnelConfig {
        val input = rawInput.trim()
        if (!input.startsWith(PREFIX, ignoreCase = true)) throw OlcRtcConfigParseException("OlcRTC URI must start with $PREFIX")
        val body = input.substring(PREFIX.length)
        if (body.isBlank()) throw OlcRtcConfigParseException("OlcRTC URI body is empty")

        val questionIndex = body.indexOf('?')
        if (questionIndex <= 0) throw OlcRtcConfigParseException("Missing carrier or '?' separator")
        val carrierRaw = body.substring(0, questionIndex)
        val afterCarrier = body.substring(questionIndex + 1)

        val atIndex = afterCarrier.indexOf('@')
        if (atIndex <= 0) throw OlcRtcConfigParseException("Missing transport or '@' separator")
        val transportRaw = afterCarrier.substring(0, atIndex)
        val afterTransport = afterCarrier.substring(atIndex + 1)

        val hashIndex = afterTransport.indexOf('#')
        if (hashIndex <= 0) throw OlcRtcConfigParseException("Missing room ID or '#' separator")
        val roomIdRaw = afterTransport.substring(0, hashIndex)
        val afterRoomId = afterTransport.substring(hashIndex + 1)

        val percentIndex = afterRoomId.indexOf('%')
        if (percentIndex <= 0) throw OlcRtcConfigParseException("Missing key or '%' separator")
        val keyHexRaw = afterRoomId.substring(0, percentIndex)
        val afterKey = afterRoomId.substring(percentIndex + 1)

        val dollarIndex = afterKey.indexOf('$')
        val clientIdRaw: String
        val displayNameRaw: String
        if (dollarIndex >= 0) {
            clientIdRaw = afterKey.substring(0, dollarIndex)
            displayNameRaw = afterKey.substring(dollarIndex + 1)
        } else {
            clientIdRaw = afterKey
            displayNameRaw = ""
        }

        val roomId = roomIdRaw.trim()
        val keyHex = keyHexRaw.trim()
        val clientId = clientIdRaw.trim()
        val displayName = OlcRtcUriCodec.decode(displayNameRaw.trim())

        validateRoomId(roomId)
        validateKeyHex(keyHex)
        validateClientId(clientId)

        return OlcRtcTunnelConfig(
            carrier = OlcRtcTunnelConfig.Carrier.fromUriValue(carrierRaw),
            transport = OlcRtcTunnelConfig.Transport.fromUriValue(transportRaw),
            roomId = roomId,
            keyHex = keyHex.lowercase(),
            clientId = clientId,
            displayName = displayName,
        )
    }

    private fun validateRoomId(roomId: String) {
        if (roomId.isBlank()) throw OlcRtcConfigParseException("Room ID is empty")
        if (roomId.any { it.isWhitespace() }) throw OlcRtcConfigParseException("Room ID must not contain spaces")
    }

    private fun validateKeyHex(keyHex: String) {
        if (keyHex.length != 64) throw OlcRtcConfigParseException("Key must contain exactly 64 hex characters")
        val isHex = keyHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        if (!isHex) throw OlcRtcConfigParseException("Key must be a valid hexadecimal string")
    }

    private fun validateClientId(clientId: String) {
        if (clientId.isBlank()) throw OlcRtcConfigParseException("Client ID is empty")
        if (clientId.any { it.isWhitespace() }) throw OlcRtcConfigParseException("Client ID must not contain spaces")
    }
}
