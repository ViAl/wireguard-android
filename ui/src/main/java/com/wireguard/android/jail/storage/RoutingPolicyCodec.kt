/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.storage

import com.wireguard.android.jail.model.JailTunnelMode
import org.json.JSONObject

internal object RoutingPolicyCodec {
    fun encode(policies: Map<String, JailTunnelMode>): String {
        val o = JSONObject()
        policies.forEach { (pkg, mode) -> o.put(pkg, mode.name) }
        return o.toString()
    }

    fun decode(raw: String?): Map<String, JailTunnelMode> {
        if (raw.isNullOrBlank()) return emptyMap()
        val o = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return emptyMap()
        }
        val out = HashMap<String, JailTunnelMode>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val name = o.optString(k, null) ?: continue
            val mode = runCatching { JailTunnelMode.valueOf(name) }.getOrNull() ?: continue
            out[k] = mode
        }
        return out
    }
}
