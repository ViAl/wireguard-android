/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityMatrixTest {
    @Test
    fun from_snapshot_maps_permissions() {
        val perm = PermissionAuditResult(
            packageName = "pkg",
            declaredPermissions = emptyList(),
            grantedMicrophone = true,
            grantedCamera = false,
            grantedFineLocation = false,
            grantedCoarseLocation = false,
            grantedBackgroundLocation = false,
            grantedContacts = false,
            grantedSms = false,
            grantedCallLog = false,
            grantedPhoneState = false,
            grantedBodySensors = false,
            declaresOverlay = false,
            declaresManageExternalStorage = false,
            declaresNotificationListener = false,
            notificationListenerEnabled = null,
            declaresAccessibilityService = false,
            accessibilityServiceEnabled = null,
            declaresUsageStatsAccess = false,
        )
        val bg = BackgroundAuditResult(
            packageName = "pkg",
            isIgnoringBatteryOptimizations = false,
            hasForegroundServices = false,
            foregroundServiceTypes = emptySet(),
        )
        val score = AuditRiskScore(packageName = "pkg", score = 0, level = AuditRiskLevel.LOW, reasons = emptyList())
        val snapshot = AuditSnapshot(
            packageName = "pkg",
            generatedAtMillis = 1L,
            permissionAudit = perm,
            backgroundAudit = bg,
            score = score,
        )
        val matrix = CapabilityMatrix.from(snapshot)
        assertEquals(CapabilityState.GRANTED, matrix.microphone)
        assertEquals(CapabilityState.ABSENT, matrix.camera)
        assertEquals("pkg", matrix.packageName)
    }
}
