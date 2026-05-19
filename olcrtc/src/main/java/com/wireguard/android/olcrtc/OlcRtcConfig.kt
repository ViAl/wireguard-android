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
    val transport: String = "datachannel",
    val socksPort: Int = 1080,
    val vp8Fps: Int = 60,
    val vp8BatchSize: Int = 64,
    val dnsServer: String = "1.1.1.1:53",
    val excludedApplications: Set<String> = emptySet(),
    val includedApplications: Set<String> = emptySet(),
    val socksUser: String? = null,
    val socksPass: String? = null,
    /**
     * How application routing is resolved when both excluded and included are non-empty.
     * - ALL_APPS: route all traffic through VPN (default)
     * - ONLY_SELECTED_APPS: only [includedApplications] go through VPN
     * - EXCLUDE_SELECTED_APPS: [excludedApplications] bypass VPN
     */
    val appRoutingMode: AppRoutingMode = AppRoutingMode.ALL_APPS,
    /** Route all IPv4 traffic through the tunnel (default true for MVP) */
    val routeAllIpv4: Boolean = true,
    /** Route all IPv6 traffic through the tunnel (default false for MVP — IPv4-only) */
    val routeAllIpv6: Boolean = false
) {
    companion object {
        const val MAX_NAME_LENGTH = 15
        val NAME_PATTERN = Regex("[a-zA-Z0-9_=+.-]{1,15}")

        /** Upstream compatibility matrix: carrier → supported transports */
        private val COMPATIBILITY_MATRIX = mapOf(
            "telemost" to setOf("vp8channel", "videochannel"),
            "jazz" to setOf("datachannel", "vp8channel", "seichannel", "videochannel"),
            "wbstream" to setOf("datachannel", "vp8channel", "seichannel", "videochannel")
        )

        /** Allowed carriers */
        val VALID_CARRIERS = COMPATIBILITY_MATRIX.keys
        /** All allowed transports across all carriers */
        val VALID_TRANSPORTS = COMPATIBILITY_MATRIX.values.flatten().toSet()

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
            // Compatibility matrix check
            val allowedTransports = COMPATIBILITY_MATRIX[config.carrier]
            if (allowedTransports != null && config.transport !in allowedTransports) {
                return "Transport '${config.transport}' is not compatible with carrier '${config.carrier}'. Supported: ${allowedTransports.joinToString()}"
            }
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
    }
}

/**
 * How VPN application routing is resolved.
 * Prevents conflicting [addAllowedApplication] + [addDisallowedApplication] calls.
 */
enum class AppRoutingMode {
    /** Route all apps through VPN (default). No per-app rules applied. */
    ALL_APPS,
    /** Only apps in [OlcRtcConfig.includedApplications] go through VPN. Uses [VpnService.Builder.addAllowedApplication]. */
    ONLY_SELECTED_APPS,
    /** Apps in [OlcRtcConfig.excludedApplications] bypass VPN. Uses [VpnService.Builder.addDisallowedApplication]. */
    EXCLUDE_SELECTED_APPS
}
