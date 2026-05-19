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

        // Split on @ to separate carrier+transport+payload from room+key+client+mimo
        val atIdx = body.indexOf('@')
        if (atIdx < 0) return null
        val beforeAt = body.substring(0, atIdx)
        val afterAt = body.substring(atIdx + 1)

        // Step 1: Parse upstream payload position (between transport and @room)
        // Format: carrier?transport<payload>@room#key%client[$mimo]
        var carrierTransport: String
        var payload: Map<String, String>
        val openBracket = beforeAt.indexOf('<')
        val closeBracket = beforeAt.lastIndexOf('>')
        if (openBracket >= 0 && closeBracket > openBracket && closeBracket == beforeAt.length - 1) {
            // Upstream payload found before @room
            carrierTransport = beforeAt.substring(0, openBracket)
            val payloadContent = beforeAt.substring(openBracket + 1, closeBracket)
            payload = parsePayloadMap(payloadContent)
        } else {
            carrierTransport = beforeAt
            payload = emptyMap()
        }

        // Parse carrier?transport
        val ct = carrierTransport.split("?", limit = 2)
        if (ct.size < 2) return null
        val carrier = ct[0]
        val transport = ct[1]

        // Parse afterAt: room#key%client[$mimo]
        val withMimo = afterAt.split("$", limit = 2)
        val core = withMimo[0]
        var mimo = withMimo.getOrNull(1)

        val withClient = core.split("%", limit = 2)
        if (withClient.size < 2) return null
        val beforeClient = withClient[0]
        var clientId = withClient[1]

        val withKey = beforeClient.split("#", limit = 2)
        if (withKey.size < 2) return null
        val roomId = withKey[0]
        val keyHex = withKey[1]

        // Step 2: Backward compat — if no payload found upstream, check for trailing <...>
        if (payload.isEmpty()) {
            // Trailing payload could be appended to MIMO (e.g. "$RU/test<vp8-fps=60>")
            if (mimo != null && mimo.endsWith(">")) {
                val trailingOpen = mimo.lastIndexOf('<')
                if (trailingOpen >= 0) {
                    val payloadContent = mimo.substring(trailingOpen + 1, mimo.length - 1)
                    payload = parsePayloadMap(payloadContent)
                    mimo = mimo.substring(0, trailingOpen)
                }
            }
            // Or appended to clientId (e.g. "client<vp8-fps=60>")
            if (payload.isEmpty() && clientId.endsWith(">")) {
                val trailingOpen = clientId.lastIndexOf('<')
                if (trailingOpen >= 0) {
                    val payloadContent = clientId.substring(trailingOpen + 1, clientId.length - 1)
                    payload = parsePayloadMap(payloadContent)
                    clientId = clientId.substring(0, trailingOpen)
                }
            }
        }

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
        // Upstream style: payload goes BEFORE @room, not after
        return "olcrtc://${config.carrier}?${config.transport}${payloadPart}@${config.roomId}#${config.keyHex}%${config.clientId}$mimoPart"
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
