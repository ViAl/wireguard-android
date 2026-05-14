/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.storage

import com.wireguard.android.jail.model.AuditConfidence
import com.wireguard.android.jail.model.AuditRiskLevel
import com.wireguard.android.jail.model.AuditRiskScore
import com.wireguard.android.jail.model.AuditSignal
import com.wireguard.android.jail.model.AuditSnapshot
import com.wireguard.android.jail.model.BackgroundAuditResult
import com.wireguard.android.jail.model.PermissionAuditResult
import com.wireguard.android.jail.model.RiskReason
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * JSON codec for [AuditSnapshot] — used as the cache format in
 * [com.wireguard.android.jail.storage.JailStore].
 *
 * The encoding is designed to be forward-compatible: new fields are added with
 * safe defaults on read, and unknown signal ids are silently dropped. This keeps
 * the Jail tab usable even across schema drifts.
 */
internal object AuditSnapshotCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(snapshot: AuditSnapshot): String = JSONObject().apply {
        put("v", SCHEMA_VERSION)
        put("pkg", snapshot.packageName)
        put("at", snapshot.generatedAtMillis)
        put("perm", encodePermissions(snapshot.permissionAudit))
        put("bg", encodeBackground(snapshot.backgroundAudit))
        put("score", encodeScore(snapshot.score))
    }.toString()

    fun decode(raw: String): AuditSnapshot? = try {
        val obj = JSONObject(raw)
        val perm = decodePermissions(obj.getJSONObject("perm"))
        val bg = decodeBackground(obj.getJSONObject("bg"))
        val score = decodeScore(obj.getJSONObject("score"), perm.packageName)
        AuditSnapshot(
            packageName = obj.getString("pkg"),
            generatedAtMillis = obj.optLong("at", 0L),
            permissionAudit = perm,
            backgroundAudit = bg,
            score = score,
        )
    } catch (_: JSONException) {
        null
    }

    private fun encodePermissions(p: PermissionAuditResult): JSONObject = JSONObject().apply {
        put("pkg", p.packageName)
        put("declared", JSONArray(p.declaredPermissions))
        put("mic", p.grantedMicrophone)
        put("cam", p.grantedCamera)
        put("locF", p.grantedFineLocation)
        put("locC", p.grantedCoarseLocation)
        put("locB", p.grantedBackgroundLocation)
        put("contacts", p.grantedContacts)
        put("sms", p.grantedSms)
        put("callLog", p.grantedCallLog)
        put("phone", p.grantedPhoneState)
        put("sensors", p.grantedBodySensors)
        put("overlay", p.declaresOverlay)
        put("mes", p.declaresManageExternalStorage)
        put("notifDecl", p.declaresNotificationListener)
        putNullable("notifEnabled", p.notificationListenerEnabled)
        put("a11yDecl", p.declaresAccessibilityService)
        putNullable("a11yEnabled", p.accessibilityServiceEnabled)
        put("usageStats", p.declaresUsageStatsAccess)
    }

    private fun decodePermissions(o: JSONObject): PermissionAuditResult {
        val declaredArr = o.optJSONArray("declared")
        val declared = if (declaredArr == null) emptyList()
        else List(declaredArr.length()) { i -> declaredArr.optString(i) }
        return PermissionAuditResult(
            packageName = o.getString("pkg"),
            declaredPermissions = declared,
            grantedMicrophone = o.optBoolean("mic", false),
            grantedCamera = o.optBoolean("cam", false),
            grantedFineLocation = o.optBoolean("locF", false),
            grantedCoarseLocation = o.optBoolean("locC", false),
            grantedBackgroundLocation = o.optBoolean("locB", false),
            grantedContacts = o.optBoolean("contacts", false),
            grantedSms = o.optBoolean("sms", false),
            grantedCallLog = o.optBoolean("callLog", false),
            grantedPhoneState = o.optBoolean("phone", false),
            grantedBodySensors = o.optBoolean("sensors", false),
            declaresOverlay = o.optBoolean("overlay", false),
            declaresManageExternalStorage = o.optBoolean("mes", false),
            declaresNotificationListener = o.optBoolean("notifDecl", false),
            notificationListenerEnabled = o.optBooleanOrNull("notifEnabled"),
            declaresAccessibilityService = o.optBoolean("a11yDecl", false),
            accessibilityServiceEnabled = o.optBooleanOrNull("a11yEnabled"),
            declaresUsageStatsAccess = o.optBoolean("usageStats", false),
        )
    }

    private fun encodeBackground(b: BackgroundAuditResult): JSONObject = JSONObject().apply {
        put("pkg", b.packageName)
        putNullable("batteryIgnored", b.isIgnoringBatteryOptimizations)
        put("hasFg", b.hasForegroundServices)
        put("fgTypes", JSONArray(b.foregroundServiceTypes.toList()))
    }

    private fun decodeBackground(o: JSONObject): BackgroundAuditResult {
        val arr = o.optJSONArray("fgTypes")
        val types = if (arr == null) emptySet<String>()
        else buildSet { for (i in 0 until arr.length()) add(arr.optString(i)) }
        return BackgroundAuditResult(
            packageName = o.getString("pkg"),
            isIgnoringBatteryOptimizations = o.optBooleanOrNull("batteryIgnored"),
            hasForegroundServices = o.optBoolean("hasFg", false),
            foregroundServiceTypes = types,
        )
    }

    private fun encodeScore(s: AuditRiskScore): JSONObject = JSONObject().apply {
        put("pkg", s.packageName)
        put("score", s.score)
        put("level", s.level.name)
        val reasons = JSONArray()
        s.reasons.forEach { r ->
            reasons.put(JSONObject().apply {
                put("id", r.signalId)
                put("w", r.weight)
                put("c", r.confidence.name)
            })
        }
        put("reasons", reasons)
    }

    private fun decodeScore(o: JSONObject, packageName: String): AuditRiskScore {
        val arr = o.optJSONArray("reasons")
        val reasons = mutableListOf<RiskReason>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val id = r.optString("id")
                val signal = AuditSignal.fromId(id)
                val confidence = runCatching { AuditConfidence.valueOf(r.optString("c")) }
                    .getOrDefault(signal?.confidence ?: AuditConfidence.UNKNOWN)
                reasons += RiskReason(
                    signalId = id,
                    weight = r.optInt("w", signal?.baseWeight ?: 0),
                    confidence = confidence,
                    signal = signal,
                )
            }
        }
        val level = runCatching { AuditRiskLevel.valueOf(o.optString("level")) }
            .getOrDefault(AuditRiskLevel.fromScore(o.optInt("score", 0)))
        return AuditRiskScore(
            packageName = o.optString("pkg", packageName),
            score = o.optInt("score", 0),
            level = level,
            reasons = reasons,
        )
    }

    private fun JSONObject.putNullable(key: String, value: Boolean?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else optBoolean(key)
}
