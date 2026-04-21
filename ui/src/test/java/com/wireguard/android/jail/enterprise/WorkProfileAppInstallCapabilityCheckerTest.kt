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
        )
        val capability = checker.capabilityFor(PKG)
        assertEquals(WorkProfileAppAvailability.INSTALLED_IN_WORK, capability.availability)
        assertEquals(WorkProfileAppAction.OPEN_IN_WORK, capability.action)
    }

    @Test
    fun capability_manualFallbackWhenNotOwned() {
        val checker = checker(
            ownership = ManagedProfileOwnershipState.SECONDARY_PROFILE_PRESENT_NOT_OURS,
            inParent = true,
            inWork = false,
            fallback = true,
        )
        val capability = checker.capabilityFor(PKG)
        assertEquals(WorkProfileAppAvailability.REQUIRES_MANUAL_INSTALL, capability.availability)
        assertEquals(WorkProfileAppAction.OPEN_STORE_MANUALLY, capability.action)
    }

    @Test
    fun capability_unavailableWithoutFallback() {
        val checker = checker(
            ownership = ManagedProfileOwnershipState.NO_MANAGED_PROFILE,
            inParent = false,
            inWork = false,
            fallback = false,
        )
        val capability = checker.capabilityFor(PKG)
        assertEquals(WorkProfileAppAvailability.UNAVAILABLE, capability.availability)
        assertEquals(WorkProfileAppAction.NONE, capability.action)
    }

    private fun checker(
        ownership: ManagedProfileOwnershipState,
        inParent: Boolean,
        inWork: Boolean,
        fallback: Boolean,
    ): WorkProfileAppInstallCapabilityChecker = WorkProfileAppInstallCapabilityChecker(
        context = app,
        ownershipService = object : ManagedProfileOwnershipStateProvider {
            override fun state(): ManagedProfileOwnershipState = ownership
        },
        packageInspector = object : WorkProfileAppInstallCapabilityChecker.PackageInspector {
            override fun isInstalledInParent(packageName: String): Boolean = inParent
            override fun isInstalledInWorkProfile(packageName: String): Boolean = inWork
            override fun appLabel(packageName: String): String = "Label"
        },
        fallbackLauncher = object : WorkProfileAppInstallCapabilityChecker.FallbackLauncher {
            override fun canLaunchStoreIntent(packageName: String): Boolean = fallback
            override fun launchStoreIntent(packageName: String): Boolean = true
        },
    )

    companion object {
        private const val PKG = "com.example.app"
    }
}
