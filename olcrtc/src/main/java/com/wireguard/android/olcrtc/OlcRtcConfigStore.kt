package com.wireguard.android.olcrtc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class OlcRtcConfigStore(private val appContext: Context) {

    private val configFile = File(appContext.filesDir, "olcrtc_configs.json")

    fun loadAll(): List<OlcRtcConfig> {
        if (!configFile.exists()) return emptyList()
        return try {
            val text = configFile.readText()
            val arr = JSONArray(text)
            val result = mutableListOf<OlcRtcConfig>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(OlcRtcConfig(
                    name = obj.getString("name"),
                    carrier = obj.getString("carrier"),
                    roomId = obj.getString("roomId"),
                    clientId = obj.getString("clientId"),
                    keyHex = obj.getString("keyHex"),
                    transport = obj.optString("transport", "datachannel"),
                    socksPort = obj.optInt("socksPort", 1080),
                    vp8Fps = obj.optInt("vp8Fps", 60),
                    vp8BatchSize = obj.optInt("vp8BatchSize", 64),
                    dnsServer = obj.optString("dnsServer", "1.1.1.1:53"),
                    excludedApplications = parseStringSet(obj, "excludedApplications"),
                    includedApplications = parseStringSet(obj, "includedApplications"),
                    socksUser = optStringOrNull(obj, "socksUser"),
                    socksPass = optStringOrNull(obj, "socksPass"),
                    appRoutingMode = try {
                        AppRoutingMode.valueOf(obj.optString("appRoutingMode", "ALL_APPS"))
                    } catch (_: Exception) { AppRoutingMode.ALL_APPS },
                    routeAllIpv4 = obj.optBoolean("routeAllIpv4", true),
                    routeAllIpv6 = obj.optBoolean("routeAllIpv6", false)
                ))
            }
            result
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcConfigStore", "Failed to load configs", e)
            emptyList()
        }
    }

    fun save(config: OlcRtcConfig) {
        val configs = loadAll().toMutableList()
        val idx = configs.indexOfFirst { it.name == config.name }
        if (idx >= 0) configs[idx] = config else configs.add(config)
        writeAll(configs)
    }

    fun delete(name: String) {
        writeAll(loadAll().filter { it.name != name })
    }

    private fun writeAll(configs: List<OlcRtcConfig>) {
        try {
            val arr = JSONArray()
            configs.forEach { c ->
                arr.put(JSONObject().apply {
                    put("name", c.name)
                    put("carrier", c.carrier)
                    put("roomId", c.roomId)
                    put("clientId", c.clientId)
                    put("keyHex", c.keyHex)
                    put("transport", c.transport)
                    put("socksPort", c.socksPort)
                    put("vp8Fps", c.vp8Fps)
                    put("vp8BatchSize", c.vp8BatchSize)
                    put("dnsServer", c.dnsServer)
                    put("excludedApplications", JSONArray(c.excludedApplications.toList()))
                    put("includedApplications", JSONArray(c.includedApplications.toList()))
                    if (c.socksUser != null) put("socksUser", c.socksUser) else put("socksUser", JSONObject.NULL)
                    if (c.socksPass != null) put("socksPass", c.socksPass) else put("socksPass", JSONObject.NULL)
                    put("appRoutingMode", c.appRoutingMode.name)
                    put("routeAllIpv4", c.routeAllIpv4)
                    put("routeAllIpv6", c.routeAllIpv6)
                })
            }
            configFile.parentFile?.mkdirs()
            configFile.writeText(arr.toString(2))
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcConfigStore", "Failed to save configs", e)
        }
    }

    /**
     * Safely parses a JSON string set, handling missing keys and nulls.
     */
    private fun parseStringSet(json: JSONObject, key: String): Set<String> {
        return if (json.has(key) && !json.isNull(key)) {
            val arr = json.getJSONArray(key)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } else {
            emptySet()
        }
    }

    /**
     * Returns the string value for [key] or null if the key is missing or explicitly null.
     * Unlike optString(), this never returns an empty string when null was intended.
     */
    private fun optStringOrNull(json: JSONObject, key: String): String? {
        return if (json.has(key) && !json.isNull(key)) json.getString(key) else null
    }
}
