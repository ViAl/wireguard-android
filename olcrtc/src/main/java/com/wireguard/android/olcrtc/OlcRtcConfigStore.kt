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
                    transport = obj.optString("transport", "vp8channel"),
                    socksPort = obj.optInt("socksPort", 10808),
                    vp8Fps = obj.optInt("vp8Fps", 60),
                    vp8BatchSize = obj.optInt("vp8BatchSize", 8),
                    dnsServer = obj.optString("dnsServer", "1.1.1.1:53"),
                    socksUser = obj.optString("socksUser", null),
                    socksPass = obj.optString("socksPass", null)
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
                    if (c.socksUser != null) put("socksUser", c.socksUser)
                    if (c.socksPass != null) put("socksPass", c.socksPass)
                })
            }
            configFile.parentFile?.mkdirs()
            configFile.writeText(arr.toString(2))
        } catch (e: Exception) {
            android.util.Log.e("OlcRtcConfigStore", "Failed to save configs", e)
        }
    }
}
