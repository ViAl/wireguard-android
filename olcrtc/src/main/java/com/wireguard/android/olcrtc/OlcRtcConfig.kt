package com.wireguard.android.olcrtc

/**
 * Configuration for an OlcRTC tunnel.
 */
data class OlcRtcConfig(
    val name: String,
    val carrier: String,
    val roomId: String,
    val clientId: String,
    val keyHex: String,
    val transport: String = "vp8channel",
    val socksPort: Int = 10808,
    val vp8Fps: Int = 60,
    val vp8BatchSize: Int = 64,
    val dnsServer: String = "1.1.1.1:53",
    val excludedApplications: Set<String> = emptySet(),
    val includedApplications: Set<String> = emptySet(),
    val socksUser: String? = null,
    val socksPass: String? = null
) {
    companion object {
        const val MAX_NAME_LENGTH = 15
        val NAME_PATTERN = Regex("[a-zA-Z0-9_=+.-]{1,15}")

        /**
         * Validates the config and returns an error message or null if valid.
         */
        fun validate(config: OlcRtcConfig): String? {
            if (config.name.isBlank()) return "Name is required"
            if (config.name.length > MAX_NAME_LENGTH) return "Name too long (max $MAX_NAME_LENGTH)"
            if (!NAME_PATTERN.matches(config.name)) return "Name contains invalid characters"
            if (config.carrier.isBlank()) return "Carrier is required"
            if (config.carrier !in VALID_CARRIERS) return "Unsupported carrier: ${config.carrier}. Supported: ${VALID_CARRIERS.joinToString()}"
            if (config.transport !in VALID_TRANSPORTS) return "Unsupported transport: ${config.transport}. Supported: ${VALID_TRANSPORTS.joinToString()}"
            if (config.roomId.isBlank()) return "Room ID is required"
            if (config.clientId.isBlank()) return "Client ID is required"
            if (config.keyHex.isBlank()) return "Encryption key is required"
            if (config.keyHex.length != 64) return "Key must be 64 hex characters"
            if (!config.keyHex.all { it in "0123456789abcdefABCDEF" }) return "Key must be hex"
            if (config.socksPort !in 1024..65535) return "Port must be between 1024-65535"
            if (config.vp8Fps !in 1..120) return "FPS must be 1-120"
            if (config.vp8BatchSize !in 1..256) return "Batch size must be 1-256"
            return null
        }

        /** Allowed carriers */
        val VALID_CARRIERS = setOf("telemost", "jazz", "wbstream")
        val VALID_TRANSPORTS = setOf("vp8channel", "datachannel", "seichannel", "videochannel")
    }
}
