/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.wireguard.android.jail.model.ManagedProfileOwnershipState
import com.wireguard.android.jail.model.WorkProfileAppAction
import com.wireguard.android.jail.model.WorkProfileAppAvailability
import com.wireguard.android.jail.model.WorkProfileInstallEnvironmentReason
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkProfileAppInstallCapabilityCheckerTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun capability_installedInWork() {
        val checker = checker(
            ownership = ManagedProfileOwnershipState.MANAGED_PROFILE_OURS,
            inParent = true,
            inWork = true,
            fallback = true,
            sdkInt = 34,
        )
        val capability = checker.capabilityFor(PKG)
        assertEquals(WorkProfileAppAvailability.INSTALLED_IN_WORK, capability.availability)
        assertEquals(WorkProfileAppAction.OPEN_IN_WORK, capability.action)
        assertEquals(WorkProfileInstallEnvironmentReason.ALREADY_INSTALLED_IN_WORK, capability.environment.environmentReason)
    }

    @Test
    fun capability_autoInstallWhenOwnedAndParentPresent() {
        val checker = checker(
            ownership = ManagedProfileOwnershipState.MANAGED_PROFILE_OURS,
            inParent = true,
            inWork = false,
            fallback = true,
            sdkInt = 34,
        )
        val capability = checker.capabilityFor(PKG)
        assertEquals(WorkProfileAppAvailability.INSTALLABLE_AUTOMATICALLY, capability.availability)
        assertEquals(WorkProfileAppAction.INSTALL_AUTOMATICALLY, capability.action)
        assertEquals(WorkProfileInstallEnvironmentReason.PROFILE_OWNER_CONFIRMED, capability.environment.environmentReason)
    }

    @Test
    fun capability_manualWhenNotOwned() {
        val checker = checker(
            ownership = ManagedProfileOwnershipState.MANAGED_PROFILE_PRESENT_NOT_OURS,
            inParent = true,
            inWork = false,
            fallback = true,
            sdkInt = 34,
        )
        val capability = checker.capabilityFor(PKG)
        assertEquals(WorkProfileAppAvailability.REQUIRES_MANUAL_INSTALL, capability.availability)
        assertEquals(WorkProfileAppAction.OPEN_STORE_MANUALLY, capability.action)
        assertEquals(WorkProfileInstallEnvironmentReason.MANAGED_PROFILE_NOT_OURS, capability.environment.environmentReason)
    }

    @Test
    fun capability_manualWhenSecondaryOnly() {
        val checker = checker(
            ownership = ManagedProfileOwnershipState.SECONDARY_PROFILE_PRESENT_NOT_OURS,
            inParent = true,
            inWork = false,
            fallback = true,
            sdkInt = 34,
        )
        val capability = checker.capabilityFor(PKG)
        assertEquals(WorkProfileAppAvailability.REQUIRES_MANUAL_INSTALL, capability.availability)
        assertEquals(WorkProfileInstallEnvironmentReason.SECONDARY_PROFILE_PRESENT_ONLY, capability.environment.environmentReason)
    }

    @Test
    fun capability_unavailableWithoutFallback() {
        val checker = checker(
            ownership = ManagedProfileOwnershipState.NO_MANAGED_PROFILE,
            inParent = false,
            inWork = false,
            fallback = false,
            sdkInt = 34,
        )
        val capability = checker.capabilityFor(PKG)
        assertEquals(WorkProfileAppAvailability.UNAVAILABLE, capability.availability)
        assertEquals(WorkProfileAppAction.NONE, capability.action)
        assertEquals(WorkProfileInstallEnvironmentReason.NO_MANAGED_PROFILE, capability.environment.environmentReason)
    }

    @Test
    fun capability_manualUnavailableWhenWorkProfileMarketUnavailable() {
        val checker = checker(
            ownership = ManagedProfileOwnershipState.MANAGED_PROFILE_PRESENT_NOT_OURS,
            inParent = true,
            inWork = false,
            fallback = false,
            sdkInt = 34,
        )
        val capability = checker.capabilityFor(PKG)
        assertEquals(WorkProfileAppAvailability.UNAVAILABLE, capability.availability)
        assertEquals(WorkProfileAppAction.NONE, capability.action)
        assertEquals(WorkProfileInstallEnvironmentReason.NO_FALLBACK_AVAILABLE, capability.environment.environmentReason)
    }

    private fun checker(
        ownership: ManagedProfileOwnershipState,
        inParent: Boolean,
        inWork: Boolean,
        fallback: Boolean,
        sdkInt: Int,
    ): WorkProfileAppInstallCapabilityChecker {
        val packageInspector = object : WorkProfileAppInstallCapabilityChecker.PackageInspector {
            override fun isInstalledInParent(packageName: String): Boolean = inParent
            override fun isInstalledInWorkProfile(packageName: String): Boolean = inWork
            override fun hasTargetProfiles(): Boolean = true
            override fun appLabel(packageName: String): String = "Label"
        }
        val fallbackLauncher = object : WorkProfileAppInstallCapabilityChecker.FallbackLauncher {
            override fun canLaunchStoreIntent(packageName: String): Boolean = fallback
            override fun launchStoreIntent(packageName: String): Boolean = true
        }
        val environmentInspector = InstallEnvironmentInspector(
            ownershipService = object : ManagedProfileOwnershipStateProvider {
                override fun state(): ManagedProfileOwnershipState = ownership
            },
            packageInspector = packageInspector,
            fallbackLauncher = fallbackLauncher,
            systemSignals = object : InstallEnvironmentInspector.SystemSignals {
                override fun sdkInt(): Int = sdkInt
            },
        )
        return WorkProfileAppInstallCapabilityChecker(
            context = app,
            ownershipService = object : ManagedProfileOwnershipStateProvider {
                override fun state(): ManagedProfileOwnershipState = ownership
            },
            packageInspector = packageInspector,
            fallbackLauncher = fallbackLauncher,
            environmentInspector = environmentInspector,
        )
    }

    companion object {
        private const val PKG = "com.example.app"
    }
}
