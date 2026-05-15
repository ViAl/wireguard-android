package com.wireguard.android.rtc.config

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object OlcRtcUriCodec {
    fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    fun decode(value: String): String {
        if (value.isBlank()) return ""
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}
