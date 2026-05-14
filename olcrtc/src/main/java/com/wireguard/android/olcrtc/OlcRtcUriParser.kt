package com.wireguard.android.olcrtc

data class ParsedUri(
    val carrier: String,
    val transport: String,
    val roomId: String,
    val keyHex: String,
    val clientId: String,
    val mimo: String?
)

object OlcRtcUriParser {

    fun parse(uri: String): ParsedUri? {
        if (!uri.startsWith("olcrtc://")) return null
        val body = uri.removePrefix("olcrtc://")

        val withMimo = body.split("$", limit = 2)
        val core = withMimo[0]
        val mimo = withMimo.getOrNull(1)

        val withClient = core.split("%", limit = 2)
        if (withClient.size < 2) return null
        val beforeClient = withClient[0]
        val clientId = withClient[1]

        val withKey = beforeClient.split("#", limit = 2)
        if (withKey.size < 2) return null
        val beforeKey = withKey[0]
        val keyHex = withKey[1]

        val withRoom = beforeKey.split("@", limit = 2)
        if (withRoom.size < 2) return null
        val carrierTransport = withRoom[0]
        val roomId = withRoom[1]

        val ct = carrierTransport.split("?", limit = 2)
        if (ct.size < 2) return null
        val carrier = ct[0]
        val transport = ct[1]

        return ParsedUri(
            carrier = carrier,
            transport = transport,
            roomId = roomId,
            keyHex = keyHex,
            clientId = clientId,
            mimo = mimo
        )
    }

    fun toUri(config: OlcRtcConfig, mimo: String? = null): String {
        val mimoPart = if (mimo != null) "$$mimo" else ""
        return "olcrtc://${config.carrier}?${config.transport}@${config.roomId}#${config.keyHex}%${config.clientId}$mimoPart"
    }
}
