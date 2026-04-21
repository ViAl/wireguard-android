/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

/**
 * Capability-level view derived from a [PermissionAuditResult]/[BackgroundAuditResult] pair.
 *
 * Where a permission audit lists the individual OS primitives that are granted, the capability
 * matrix aggregates them into the user-visible categories the risk report speaks about
 * ("can see your location", "can read contacts"). Keeping this transformation pure and in the
 * model layer lets the Phase 4 report builder, the Phase 5 sterile-launch checklist, and tests
 * share the same interpretation of "what can this app do".
 *
 * Each capability falls into three states honest about Android's real answer:
 *  * [CapabilityState.GRANTED]      — directly observable runtime grant.
 *  * [CapabilityState.LIKELY]       — app holds the capability based on a manifest signal
 *    whose runtime state cannot be read reliably (overlay, notification listener declared but
 *    not necessarily enabled, etc.). The report renders this as "likely" rather than "yes".
 *  * [CapabilityState.ABSENT]       — no signal at all that this capability is active.
 */
data class CapabilityMatrix(
    val packageName: String,
    val location: CapabilityState,
    val backgroundLocation: CapabilityState,
    val microphone: CapabilityState,
    val camera: CapabilityState,
    val contacts: CapabilityState,
    val sms: CapabilityState,
    val callLog: CapabilityState,
    val phoneState: CapabilityState,
    val bodySensors: CapabilityState,
    val overlay: CapabilityState,
    val externalStorage: CapabilityState,
    val notificationListener: CapabilityState,
    val accessibilityService: CapabilityState,
    val usageStats: CapabilityState,
    val unrestrictedBackground: CapabilityState,
    val persistentForegroundService: CapabilityState,
) {
    companion object {
        fun from(snapshot: AuditSnapshot): CapabilityMatrix {
            val p = snapshot.permissions
            val b = snapshot.background
            return CapabilityMatrix(
                packageName = snapshot.packageName,
                location = when {
                    p.grantedFineLocation -> CapabilityState.GRANTED
                    p.grantedCoarseLocation -> CapabilityState.GRANTED
                    else -> CapabilityState.ABSENT
                },
                backgroundLocation = when {
                    p.grantedBackgroundLocation -> CapabilityState.GRANTED
                    else -> CapabilityState.ABSENT
                },
                microphone = if (p.grantedMicrophone) CapabilityState.GRANTED else CapabilityState.ABSENT,
                camera = if (p.grantedCamera) CapabilityState.GRANTED else CapabilityState.ABSENT,
                contacts = if (p.grantedContacts) CapabilityState.GRANTED else CapabilityState.ABSENT,
                sms = if (p.grantedSms) CapabilityState.GRANTED else CapabilityState.ABSENT,
                callLog = if (p.grantedCallLog) CapabilityState.GRANTED else CapabilityState.ABSENT,
                phoneState = if (p.grantedPhoneState) CapabilityState.GRANTED else CapabilityState.ABSENT,
                bodySensors = if (p.grantedBodySensors) CapabilityState.GRANTED else CapabilityState.ABSENT,
                overlay = when {
                    p.declaresOverlay -> CapabilityState.LIKELY
                    else -> CapabilityState.ABSENT
                },
                externalStorage = when {
                    p.declaresManageExternalStorage -> CapabilityState.LIKELY
                    else -> CapabilityState.ABSENT
                },
                notificationListener = when {
                    p.notificationListenerEnabled == true -> CapabilityState.GRANTED
                    p.declaresNotificationListener -> CapabilityState.LIKELY
                    else -> CapabilityState.ABSENT
                },
                accessibilityService = when {
                    p.accessibilityServiceEnabled == true -> CapabilityState.GRANTED
                    p.declaresAccessibilityService -> CapabilityState.LIKELY
                    else -> CapabilityState.ABSENT
                },
                usageStats = when {
                    p.declaresUsageStatsAccess -> CapabilityState.LIKELY
                    else -> CapabilityState.ABSENT
                },
                unrestrictedBackground = when (b.isIgnoringBatteryOptimizations) {
                    true -> CapabilityState.GRANTED
                    false -> CapabilityState.ABSENT
                    null -> CapabilityState.ABSENT
                },
                persistentForegroundService = when {
                    b.hasForegroundServices -> CapabilityState.LIKELY
                    else -> CapabilityState.ABSENT
                },
            )
        }
    }
}

enum class CapabilityState {
    GRANTED,
    LIKELY,
    ABSENT,
    ;

    val isActive: Boolean get() = this != ABSENT
}
