/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import com.wireguard.android.jail.model.ManagedProfileOwnershipState
import com.wireguard.android.jail.model.WorkProfileInstallEnvironmentReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallEnvironmentInspectorTest {
    @Test
    fun reason_alreadyInstalledInWork() {
        val environment = inspect(inWork = true)
        assertEquals(WorkProfileInstallEnvironmentReason.ALREADY_INSTALLED_IN_WORK, environment.environmentReason)
    }

    @Test
    fun reason_ownedByUs_parentPresent_supportedApi() {
        val environment = inspect(
            ownership = ManagedProfileOwnershipState.MANAGED_PROFILE_OURS,
            inParent = true,
            inWork = false,
            sdkInt = 34,
        )
        assertTrue(environment.autoInstallAllowedByEnvironment)
        assertEquals(WorkProfileInstallEnvironmentReason.PROFILE_OWNER_CONFIRMED, environment.environmentReason)
    }

    @Test
    fun reason_managedProfileNotOurs() {
        val environment = inspect(ownership = ManagedProfileOwnershipState.MANAGED_PROFILE_PRESENT_NOT_OURS)
        assertEquals(WorkProfileInstallEnvironmentReason.MANAGED_PROFILE_NOT_OURS, environment.environmentReason)
    }

    @Test
    fun reason_secondaryOnly() {
        val environment = inspect(ownership = ManagedProfileOwnershipState.SECONDARY_PROFILE_PRESENT_NOT_OURS)
        assertEquals(WorkProfileInstallEnvironmentReason.SECONDARY_PROFILE_PRESENT_ONLY, environment.environmentReason)
    }

    @Test
    fun reason_ownershipUncertain() {
        val environment = inspect(ownership = ManagedProfileOwnershipState.OWNERSHIP_UNCERTAIN)
        assertEquals(WorkProfileInstallEnvironmentReason.OWNERSHIP_UNCERTAIN, environment.environmentReason)
    }

    @Test
    fun reason_parentMissing() {
        val environment = inspect(
            ownership = ManagedProfileOwnershipState.MANAGED_PROFILE_OURS,
            inParent = false,
            sdkInt = 34,
        )
        assertFalse(environment.autoInstallAllowedByEnvironment)
        assertEquals(WorkProfileInstallEnvironmentReason.PARENT_PACKAGE_MISSING, environment.environmentReason)
    }

    @Test
    fun reason_manualFallbackOnly() {
        val environment = inspect(
            ownership = ManagedProfileOwnershipState.MANAGED_PROFILE_OURS,
            inParent = true,
            fallback = true,
            sdkInt = 27,
        )
        assertFalse(environment.installExistingPackageApiAvailable)
        assertEquals(WorkProfileInstallEnvironmentReason.MANUAL_FALLBACK_ONLY, environment.environmentReason)
    }

    @Test
    fun reason_noFallbackAvailable() {
        val environment = inspect(
            ownership = ManagedProfileOwnershipState.MANAGED_PROFILE_OURS,
            inParent = true,
            fallback = false,
            sdkInt = 27,
        )
        assertEquals(WorkProfileInstallEnvironmentReason.NO_FALLBACK_AVAILABLE, environment.environmentReason)
    }

    @Test
    fun reason_unsupportedApiPath() {
        val environment = inspect(
            ownership = ManagedProfileOwnershipState.UNSUPPORTED,
            inParent = true,
            sdkInt = 34,
        )
        assertEquals(WorkProfileInstallEnvironmentReason.API_LEVEL_UNSUPPORTED, environment.environmentReason)
    }

    private fun inspect(
        ownership: ManagedProfileOwnershipState = ManagedProfileOwnershipState.MANAGED_PROFILE_OURS,
        inParent: Boolean = true,
        inWork: Boolean = false,
        hasProfiles: Boolean = true,
        fallback: Boolean = true,
        sdkInt: Int = 34,
    ) = InstallEnvironmentInspector(
        ownershipService = object : ManagedProfileOwnershipStateProvider {
            override fun state(): ManagedProfileOwnershipState = ownership
        },
        packageInspector = object : WorkProfileAppInstallCapabilityChecker.PackageInspector {
            override fun isInstalledInParent(packageName: String): Boolean = inParent
            override fun isInstalledInWorkProfile(packageName: String): Boolean = inWork
            override fun hasTargetProfiles(): Boolean = hasProfiles
            override fun appLabel(packageName: String): String = "Label"
        },
        fallbackLauncher = object : WorkProfileAppInstallCapabilityChecker.FallbackLauncher {
            override fun canLaunchStoreIntent(packageName: String): Boolean = fallback
            override fun launchStoreIntent(packageName: String): Boolean = true
        },
        systemSignals = object : InstallEnvironmentInspector.SystemSignals {
            override fun sdkInt(): Int = sdkInt
        },
    ).inspect("com.example.app")
}
