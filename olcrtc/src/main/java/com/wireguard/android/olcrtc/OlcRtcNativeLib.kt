package com.wireguard.android.olcrtc

import android.util.Log

/**
 * JNI bridge to hev-socks5-tunnel native library.
 *
 * The library creates a TUN interface from Android VpnService fd
 * and routes traffic through a local SOCKS5 proxy.
 */
object OlcRtcNativeLib {
    private var loaded = false

    fun load(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("hev-socks5-tunnel")
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w("OlcRtcNativeLib", "hev-socks5-tunnel not available: ${e.message}")
            false
        }
    }

    external fun startTun2socksNative(configPath: String, fd: Int): Int
    external fun stopTun2socksNative()
    external fun getTun2socksStatsNative(): LongArray

    fun generateTun2socksConfig(
        configDir: java.io.File,
        socksHost: String = "127.0.0.1",
        socksPort: Int = 10808,
        tunFd: String,
        dnsServer: String = "1.1.1.1:53"
    ): String {
        val config = """
            [tun]
            mtu = 1500
            fd = $tunFd

            [socks5]
            address = "$socksHost:$socksPort"
            udp = "udp"

            [dns]
            upstream = "$dnsServer"

            [log]
            level = "warn"
        """.trimIndent()

        val configFile = java.io.File(configDir, "tun2socks.toml")
        configFile.writeText(config)
        return configFile.absolutePath
    }
}
