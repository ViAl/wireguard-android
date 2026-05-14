/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.domain

import android.content.Context
import com.wireguard.android.jail.model.AuditRiskLevel
import com.wireguard.android.jail.model.AuditRiskScore
import com.wireguard.android.jail.model.AuditSignal
import com.wireguard.android.jail.model.AuditSnapshot
import com.wireguard.android.jail.model.BackgroundAuditResult
import com.wireguard.android.jail.model.PermissionAuditResult
import com.wireguard.android.jail.model.RiskReason
import com.wireguard.android.jail.system.AccessibilityInspector
import com.wireguard.android.jail.system.CrossProfileAppsWrapper
import com.wireguard.android.jail.system.ForegroundServiceInspector
import com.wireguard.android.jail.system.NotificationAccessInspector
import com.wireguard.android.jail.system.PermissionInspector
import com.wireguard.android.jail.system.PowerManagerWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates the audit of a single package:
 *
 *  1. Runs [PermissionInspector] and fills in the accessibility / notification-listener
 *     flags from the cross-cutting inspectors.
 *  2. Runs [ForegroundServiceInspector] enriched with [PowerManagerWrapper].
 *  3. Derives a [AuditRiskScore] using the weight table from [AuditSignal].
 *
 * The class is deliberately stateless aside from the injected dependencies; it is safe
 * to call [audit] concurrently from `Dispatchers.Default`. Storage of the resulting
 * snapshot is the caller's concern — typically the `JailAppRepository` writes it to the
 * `JailStore` cache and exposes it to the UI.
 */
class AppAuditManager(
    private val accessibilityInspector: AccessibilityInspector,
    private val notificationInspector: NotificationAccessInspector,
    private val powerManager: PowerManagerWrapper,
    private val crossProfile: CrossProfileAppsWrapper,
) {
    /**
     * Collects all signals for [packageName] on a background dispatcher and returns a
     * fully-populated [AuditSnapshot]. Returns `null` only when [PermissionInspector]
     * cannot resolve the package (e.g. it was just uninstalled).
     */
    suspend fun audit(context: Context, packageName: String): AuditSnapshot? = withContext(Dispatchers.Default) {
        val pm = context.packageManager
        val permissions = PermissionInspector.inspect(pm, packageName) ?: return@withContext null
        val enriched = permissions.copy(
            accessibilityServiceEnabled = if (permissions.declaresAccessibilityService)
                accessibilityInspector.hasEnabledServiceFor(packageName) else null,
            notificationListenerEnabled = if (permissions.declaresNotificationListener)
                notificationInspector.isListenerEnabled(packageName) else null,
        )
        val background = ForegroundServiceInspector.inspect(
            packageManager = pm,
            packageName = packageName,
            isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(packageName),
        )
        val score = score(enriched, background)
        AuditSnapshot(
            packageName = packageName,
            generatedAtMillis = System.currentTimeMillis(),
            permissionAudit = enriched,
            backgroundAudit = background,
            score = score,
        )
    }

    /**
     * Pure function over the inspected data. Exposed so unit tests can cover the scoring
     * table without touching [PackageManager].
     */
    fun score(
        permissions: PermissionAuditResult,
        background: BackgroundAuditResult,
    ): AuditRiskScore {
        val reasons = mutableListOf<RiskReason>()

        // Accessibility — most impactful signal. If the OS reports the service as enabled
        // we emit the KNOWN variant; otherwise, a manifest declaration is only LIKELY
        // relevant.
        when (permissions.accessibilityServiceEnabled) {
            true -> reasons += reasonFor(AuditSignal.ACCESSIBILITY_ENABLED)
            false, null -> if (permissions.declaresAccessibilityService)
                reasons += reasonFor(AuditSignal.ACCESSIBILITY_DECLARED)
        }

        if (permissions.declaresOverlay)
            reasons += reasonFor(AuditSignal.OVERLAY_DECLARED)

        when (permissions.notificationListenerEnabled) {
            true -> reasons += reasonFor(AuditSignal.NOTIFICATION_LISTENER_ENABLED)
            false, null -> if (permissions.declaresNotificationListener)
                reasons += reasonFor(AuditSignal.NOTIFICATION_LISTENER_DECLARED)
        }

        if (permissions.declaresUsageStatsAccess)
            reasons += reasonFor(AuditSignal.USAGE_STATS_DECLARED)

        if (permissions.grantedMicrophone) reasons += reasonFor(AuditSignal.MICROPHONE_GRANTED)
        if (permissions.grantedCamera) reasons += reasonFor(AuditSignal.CAMERA_GRANTED)
        if (permissions.grantedFineLocation || permissions.grantedCoarseLocation)
            reasons += reasonFor(AuditSignal.LOCATION_FOREGROUND_GRANTED)
        if (permissions.grantedBackgroundLocation)
            reasons += reasonFor(AuditSignal.LOCATION_BACKGROUND_GRANTED)
        if (permissions.grantedContacts) reasons += reasonFor(AuditSignal.CONTACTS_GRANTED)
        if (permissions.grantedSms) reasons += reasonFor(AuditSignal.SMS_GRANTED)
        if (permissions.grantedCallLog) reasons += reasonFor(AuditSignal.CALL_LOG_GRANTED)
        if (permissions.grantedPhoneState) reasons += reasonFor(AuditSignal.PHONE_STATE_GRANTED)
        if (permissions.grantedBodySensors) reasons += reasonFor(AuditSignal.SENSORS_GRANTED)

        if (permissions.declaresManageExternalStorage)
            reasons += reasonFor(AuditSignal.MANAGE_EXTERNAL_STORAGE_DECLARED)

        if (background.isIgnoringBatteryOptimizations == true)
            reasons += reasonFor(AuditSignal.BATTERY_UNRESTRICTED)

        if (ForegroundServiceInspector.TYPE_LOCATION in background.foregroundServiceTypes)
            reasons += reasonFor(AuditSignal.FOREGROUND_SERVICE_LOCATION)
        if (ForegroundServiceInspector.TYPE_MICROPHONE in background.foregroundServiceTypes)
            reasons += reasonFor(AuditSignal.FOREGROUND_SERVICE_MICROPHONE)
        if (ForegroundServiceInspector.TYPE_CAMERA in background.foregroundServiceTypes)
            reasons += reasonFor(AuditSignal.FOREGROUND_SERVICE_CAMERA)

        // Sort so the dominant reasons appear first in the detail screen.
        reasons.sortWith(compareByDescending<RiskReason> { it.weight }.thenBy { it.signalId })

        val rawScore = reasons.sumOf { it.weight }
        val cappedScore = rawScore.coerceAtMost(SCORE_CEILING)
        val floorCritical = reasons.any { it.signal?.criticalFloor == true }
        val level = if (floorCritical) AuditRiskLevel.CRITICAL else AuditRiskLevel.fromScore(cappedScore)

        return AuditRiskScore(
            packageName = permissions.packageName,
            score = cappedScore,
            level = level,
            reasons = reasons.toList(),
        )
    }

    private fun reasonFor(signal: AuditSignal): RiskReason = RiskReason(
        signalId = signal.id,
        weight = signal.baseWeight,
        confidence = signal.confidence,
        signal = signal,
    )

    companion object {
        /** Score cap so the UI never shows "score 152 / 100". */
        const val SCORE_CEILING = 100
    }
}
