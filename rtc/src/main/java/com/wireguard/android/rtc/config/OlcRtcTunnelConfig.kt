package com.wireguard.android.rtc.config

data class OlcRtcTunnelConfig(
    val carrier: Carrier,
    val transport: Transport,
    val roomId: String,
    val keyHex: String,
    val clientId: String,
    val displayName: String,
) {
    enum class Carrier(val uriValue: String) {
        TELEMOST("telemost"),
        JAZZ("jazz"),
        WBSTREAM("wbstream");

        companion object {
            fun fromUriValue(value: String): Carrier =
                entries.firstOrNull { it.uriValue == value.lowercase() }
                    ?: throw OlcRtcConfigParseException("Unsupported carrier: $value")
        }
    }

    enum class Transport(val uriValue: String) {
        VP8_CHANNEL("vp8channel"),
        DATA_CHANNEL("datachannel"),
        SEI_CHANNEL("seichannel"),
        VIDEO_CHANNEL("videochannel");

        companion object {
            fun fromUriValue(value: String): Transport =
                entries.firstOrNull { it.uriValue == value.lowercase() }
                    ?: throw OlcRtcConfigParseException("Unsupported transport: $value")
        }
    }

    val effectiveDisplayName: String
        get() = displayName.ifBlank { "${carrier.uriValue}:$roomId:$clientId" }

    fun toUri(): String = buildString {
        append(SCHEME)
        append("://")
        append(carrier.uriValue)
        append("?")
        append(transport.uriValue)
        append("@")
        append(roomId)
        append("#")
        append(keyHex)
        append("%")
        append(clientId)
        if (displayName.isNotBlank()) {
            append("$")
            append(OlcRtcUriCodec.encode(displayName))
        }
    }

    fun maskedKey(): String = if (keyHex.length <= 12) "****" else keyHex.take(6) + "..." + keyHex.takeLast(6)

    companion object {
        const val SCHEME = "olcrtc"
    }
}
