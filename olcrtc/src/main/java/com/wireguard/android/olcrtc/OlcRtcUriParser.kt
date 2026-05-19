package com.wireguard.android.olcrtc

data class ParsedUri(
    val carrier: String,
    val transport: String,
    val roomId: String,
    val keyHex: String,
    val clientId: String,
    val mimo: String?,
    /** Payload key=value pairs, e.g. vp8-fps=60&vp8-batch=64 */
    val payload: Map<String, String> = emptyMap()
) {
    /** Upstream-style key aliases. */
    val vp8Fps: Int get() = parsePayloadInt("vp8Fps", payload["vp8-fps"], 60)
    val vp8BatchSize: Int get() = parsePayloadInt("vp8BatchSize", payload["vp8-batch"], 64)

    companion object {
        private fun parsePayloadInt(fieldName: String, raw: String?, default: Int): Int {
            if (raw == null) return default
            return raw.toIntOrNull() ?: default.also {
                android.util.Log.w("OlcRtcUriParser", "Invalid $fieldName in payload: '$raw'")
            }
        }
    }
}

object OlcRtcUriParser {

    /** Keys that map upstream hyphen-notation to OlcRtcConfig field names */
    private val KNOWN_PAYLOAD_KEYS = setOf("vp8-fps", "vp8-batch")

    fun parse(uri: String): ParsedUri? {
        if (!uri.startsWith("olcrtc://")) return null
        val body = uri.removePrefix("olcrtc://")

        // Extract payload from <...> suffix
        val payload: Map<String, String>
        val afterPayload: String
        val openAngle = body.lastIndexOf('<')
        if (openAngle >= 0 && body.endsWith(">")) {
            val payloadContent = body.substring(openAngle + 1, body.length - 1)
            afterPayload = body.substring(0, openAngle)
            payload = parsePayloadMap(payloadContent)
        } else {
            afterPayload = body
            payload = emptyMap()
        }

        val withMimo = afterPayload.split("$", limit = 2)
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
            mimo = mimo,
            payload = payload
        )
    }

    fun toUri(config: OlcRtcConfig, mimo: String? = null, payload: Map<String, String>? = null): String {
        val mimoPart = if (mimo != null) "$$mimo" else ""
        val payloadPart = if (payload != null && payload.isNotEmpty()) {
            "<" + payload.entries.joinToString("&") { "${it.key}=${it.value}" } + ">"
        } else ""
        return "olcrtc://${config.carrier}?${config.transport}@${config.roomId}#${config.keyHex}%${config.clientId}$mimoPart$payloadPart"
    }

    private fun parsePayloadMap(content: String): Map<String, String> {
        return content.split("&").mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) return@mapNotNull null
            val key = pair.substring(0, eq)
            val value = pair.substring(eq + 1)
            key to value
        }.toMap()
    }

    /**
     * Applies parsed payload values to an [OlcRtcConfig] builder copy.
     * For seichannel/videochannel, payload is parsed but not yet configurable.
     */
    fun applyPayload(config: OlcRtcConfig, parsed: ParsedUri): OlcRtcConfig {
        if (parsed.payload.isEmpty()) return config
        when (parsed.transport) {
            "seichannel", "videochannel" -> {
                // Parse but log that these are not configurable yet
                android.util.Log.d("OlcRtcUriParser",
                    "${parsed.transport} payload parsed but not configurable yet: ${parsed.payload}")
                return config
            }
        }
        var result = config
        parsed.payload.forEach { (key, value) ->
            when (key) {
                "vp8-fps" -> value.toIntOrNull()?.let { result = result.copy(vp8Fps = it) }
                "vp8-batch" -> value.toIntOrNull()?.let { result = result.copy(vp8BatchSize = it) }
            }
        }
        return result
    }
}
