/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.domain

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.wireguard.android.jail.model.AuditRiskLevel
import com.wireguard.android.jail.model.AuditSignal
import com.wireguard.android.jail.model.BackgroundAuditResult
import com.wireguard.android.jail.model.PermissionAuditResult
import com.wireguard.android.jail.system.AccessibilityInspector
import com.wireguard.android.jail.system.CrossProfileAppsWrapper
import com.wireguard.android.jail.system.NotificationAccessInspector
import com.wireguard.android.jail.system.PowerManagerWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Scoring table coverage without PackageManager (uses [AppAuditManager.score]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppAuditManagerScoreTest {

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    private fun manager(): AppAuditManager {
        val cross = object : CrossProfileAppsWrapper(app) {
            override fun hasManagedProfile(): Boolean = false
            override fun isInstalledInWorkProfile(packageName: String): Boolean? = null
        }
        return AppAuditManager(
            AccessibilityInspector(app.contentResolver),
            NotificationAccessInspector(app),
            PowerManagerWrapper(app),
            cross,
        )
    }

    private fun emptyPermissions(pkg: String) = PermissionAuditResult(
        packageName = pkg,
        declaredPermissions = emptyList(),
        grantedMicrophone = false,
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

    private fun emptyBackground(pkg: String) = BackgroundAuditResult(
        packageName = pkg,
        isIgnoringBatteryOptimizations = false,
        hasForegroundServices = false,
        foregroundServiceTypes = emptySet(),
    )

    @Test
    fun microphoneGrant_emitsMicrophoneSignal() {
        val perm = emptyPermissions("com.example.a").copy(grantedMicrophone = true)
        val score = manager().score(perm, emptyBackground("com.example.a"))
        assertTrue(score.reasons.any { it.signalId == AuditSignal.MICROPHONE_GRANTED.id })
        assertTrue(score.score >= AuditSignal.MICROPHONE_GRANTED.baseWeight)
    }

    @Test
    fun accessibilityEnabled_hitsCriticalFloor() {
        val perm = emptyPermissions("com.example.b").copy(
            accessibilityServiceEnabled = true,
            declaresAccessibilityService = true,
        )
        val score = manager().score(perm, emptyBackground("com.example.b"))
        assertEquals(AuditRiskLevel.CRITICAL, score.level)
    }

    @Test
    fun emptySignals_lowBucket() {
        val score = manager().score(emptyPermissions("com.example.c"), emptyBackground("com.example.c"))
        assertEquals(AuditRiskLevel.LOW, score.level)
        assertEquals(0, score.score)
    }
}
