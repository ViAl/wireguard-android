/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.storage

import com.wireguard.android.jail.model.JailTunnelMode
import com.wireguard.android.jail.model.LaunchProfile
import com.wireguard.android.jail.model.SterileLaunchPreset
import org.json.JSONObject

internal object LaunchPresetCodec {
    fun encode(presets: Map<String, SterileLaunchPreset>): String {
        val root = JSONObject()
        presets.forEach { (pkg, p) ->
            root.put(pkg, toJson(p))
        }
        return root.toString()
    }

    fun decode(raw: String?): Map<String, SterileLaunchPreset> {
        if (raw.isNullOrBlank()) return emptyMap()
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return emptyMap()
        }
        val out = HashMap<String, SterileLaunchPreset>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val pkg = keys.next()
            val obj = root.optJSONObject(pkg) ?: continue
            fromJson(pkg, obj)?.let { out[pkg] = it }
        }
        return out
    }

    private fun toJson(p: SterileLaunchPreset): JSONObject =
        JSONObject().apply {
            put("requiredProfile", p.requiredProfile.name)
            put("requiredTunnelMode", p.requiredTunnelMode.name)
            put("warnLocationEnabled", p.warnLocationEnabled)
            put("warnBluetoothEnabled", p.warnBluetoothEnabled)
            put("warnClearClipboard", p.warnClearClipboard)
            put("warnIfNoWorkProfileCopy", p.warnIfNoWorkProfileCopy)
            put("warnIfRiskyPermissions", p.warnIfRiskyPermissions)
        }

    private fun fromJson(packageName: String, o: JSONObject): SterileLaunchPreset? = try {
        SterileLaunchPreset(
            packageName = packageName,
            requiredProfile = LaunchProfile.valueOf(o.optString("requiredProfile", "ANY")),
            requiredTunnelMode = JailTunnelMode.valueOf(o.optString("requiredTunnelMode", "DEFAULT")),
            warnLocationEnabled = o.optBoolean("warnLocationEnabled", true),
            warnBluetoothEnabled = o.optBoolean("warnBluetoothEnabled", false),
            warnClearClipboard = o.optBoolean("warnClearClipboard", false),
            warnIfNoWorkProfileCopy = o.optBoolean("warnIfNoWorkProfileCopy", true),
            warnIfRiskyPermissions = o.optBoolean("warnIfRiskyPermissions", true),
        )
    } catch (_: Exception) {
        null
    }
}
