/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.storage

import com.wireguard.android.jail.model.WorkProfileInstallMode
import com.wireguard.android.jail.model.WorkProfileInstallSessionState
import org.json.JSONException
import org.json.JSONObject

object WorkProfileInstallSessionCodec {
    fun encode(sessions: Map<String, WorkProfileInstallSessionState>): String {
        val root = JSONObject()
        sessions.forEach { (pkg, state) ->
            if (state is WorkProfileInstallSessionState.Idle) return@forEach
            root.put(pkg, encodeState(state))
        }
        return root.toString()
    }

    fun decode(raw: String?): Map<String, WorkProfileInstallSessionState> {
        if (raw.isNullOrBlank()) return emptyMap()
        val root = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            return emptyMap()
        }

        val out = HashMap<String, WorkProfileInstallSessionState>(root.length())
        val keys = root.keys()
        while (keys.hasNext()) {
            val pkg = keys.next()
            val state = decodeState(root.optJSONObject(pkg) ?: continue) ?: continue
            out[pkg] = state
        }
        return out
    }

    private fun encodeState(state: WorkProfileInstallSessionState): JSONObject {
        val obj = JSONObject()
        when (state) {
            is WorkProfileInstallSessionState.InstallAttempted -> {
                obj.put("kind", "attempted")
                obj.put("packageName", state.packageName)
                obj.put("startedAtMillis", state.startedAtMillis)
                obj.put("mode", state.mode.name)
            }
            is WorkProfileInstallSessionState.WaitingForUserAction -> {
                obj.put("kind", "waiting")
                obj.put("packageName", state.packageName)
                obj.put("startedAtMillis", state.startedAtMillis)
                obj.put("mode", state.mode.name)
            }
            is WorkProfileInstallSessionState.Verifying -> {
                obj.put("kind", "verifying")
                obj.put("packageName", state.packageName)
                obj.put("startedAtMillis", state.startedAtMillis)
                obj.put("mode", state.mode.name)
            }
            is WorkProfileInstallSessionState.Installed -> {
                obj.put("kind", "installed")
                obj.put("packageName", state.packageName)
                obj.put("completedAtMillis", state.completedAtMillis)
                obj.put("mode", state.mode.name)
            }
            is WorkProfileInstallSessionState.Failed -> {
                obj.put("kind", "failed")
                obj.put("packageName", state.packageName)
                obj.put("completedAtMillis", state.completedAtMillis)
                obj.put("mode", state.mode.name)
                state.message?.let { obj.put("message", it) }
            }
            WorkProfileInstallSessionState.Idle -> obj.put("kind", "idle")
        }
        return obj
    }

    private fun decodeState(obj: JSONObject): WorkProfileInstallSessionState? {
        val pkg = obj.optString("packageName").takeIf { it.isNotBlank() } ?: return null
        val mode = obj.optString("mode").let { raw ->
            WorkProfileInstallMode.entries.firstOrNull { it.name == raw }
        } ?: return null

        return when (obj.optString("kind")) {
            "attempted" -> WorkProfileInstallSessionState.InstallAttempted(
                packageName = pkg,
                startedAtMillis = obj.optLong("startedAtMillis"),
                mode = mode,
            )
            "waiting" -> WorkProfileInstallSessionState.WaitingForUserAction(
                packageName = pkg,
                startedAtMillis = obj.optLong("startedAtMillis"),
                mode = mode,
            )
            "verifying" -> WorkProfileInstallSessionState.Verifying(
                packageName = pkg,
                startedAtMillis = obj.optLong("startedAtMillis"),
                mode = mode,
            )
            "installed" -> WorkProfileInstallSessionState.Installed(
                packageName = pkg,
                completedAtMillis = obj.optLong("completedAtMillis"),
                mode = mode,
            )
            "failed" -> WorkProfileInstallSessionState.Failed(
                packageName = pkg,
                completedAtMillis = obj.optLong("completedAtMillis"),
                mode = mode,
                message = obj.optString("message").takeIf { it.isNotBlank() },
            )
            else -> null
        }
    }
}
