/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.wireguard.android.jail.model.InstallResult
import com.wireguard.android.jail.model.ManagedProfileOwnershipState
import com.wireguard.android.jail.model.UnsupportedReason
import com.wireguard.android.jail.model.WorkProfileAppAction
import com.wireguard.android.jail.model.WorkProfileAppAvailability
import com.wireguard.android.jail.model.WorkProfileAppInstallCapability
import com.wireguard.android.jail.model.WorkProfileInstallEnvironment
import com.wireguard.android.jail.model.WorkProfileInstallEnvironmentReason
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkProfileAppInstallServiceTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun install_alreadyInstalled() {
        val service = service(
            first = capability(installedInWork = true),
            second = capability(installedInWork = true),
            installResult = true,
        )
        assertTrue(service.install(PKG) is InstallResult.AlreadyInstalled)
    }

    @Test
    fun install_autoDeniedManualRequired() {
        val service = service(
            first = capability(canAuto = false, canFallback = true),
            second = capability(canAuto = false, canFallback = true),
            installResult = false,
        )
        assertTrue(service.install(PKG) is InstallResult.UserActionRequired)
    }

    @Test
    fun install_securityExceptionHandled() {
        val service = WorkProfileAppInstallService(
            context = app,
            capabilityChecker = object : WorkProfileAppInstallCapabilityChecker(
                app,
                ownershipService = object : ManagedProfileOwnershipStateProvider {
                    override fun state() = ManagedProfileOwnershipState.MANAGED_PROFILE_OURS
                },
                packageInspector = fakeInspector,
                fallbackLauncher = fakeFallback,
            ) {
                override fun capabilityFor(packageName: String): WorkProfileAppInstallCapability = capability(canAuto = true)
            },
            fallbackLauncher = fakeFallback,
            installer = object : WorkProfileAppInstallService.EnterpriseInstaller {
                override fun installExistingPackage(packageName: String): Boolean = throw SecurityException("denied")
            },
        )
        assertTrue(service.install(PKG) is InstallResult.Unsupported)
    }

    @Test
    fun launchManualInstall_capabilityUnavailable_returnsUnsupported() {
        val service = service(
            first = capability(canAuto = false, canFallback = false),
            second = capability(canAuto = false, canFallback = false),
            installResult = false,
        )

        val result = service.launchManualInstall(PKG)

        assertTrue(result is InstallResult.Unsupported)
        assertTrue((result as InstallResult.Unsupported).reason == UnsupportedReason.UNKNOWN)
    }

    @Test
    fun launchManualInstall_launcherFails_returnsFailed() {
        val checker = object : WorkProfileAppInstallCapabilityChecker(
            app,
            ownershipService = object : ManagedProfileOwnershipStateProvider {
                override fun state() = ManagedProfileOwnershipState.MANAGED_PROFILE_OURS
            },
            packageInspector = fakeInspector,
            fallbackLauncher = fakeFallback,
        ) {
            override fun capabilityFor(packageName: String): WorkProfileAppInstallCapability =
                capability(canAuto = false, canFallback = true)
        }
        val failingFallback = object : WorkProfileAppInstallCapabilityChecker.FallbackLauncher {
            override fun canLaunchStoreIntent(packageName: String): Boolean = true
            override fun launchStoreIntent(packageName: String): Boolean = false
        }
        val service = WorkProfileAppInstallService(
            context = app,
            capabilityChecker = checker,
            fallbackLauncher = failingFallback,
            installer = object : WorkProfileAppInstallService.EnterpriseInstaller {
                override fun installExistingPackage(packageName: String): Boolean = false
            },
        )

        val result = service.launchManualInstall(PKG)

        assertTrue(result is InstallResult.Failed)
    }

    private fun service(
        first: WorkProfileAppInstallCapability,
        second: WorkProfileAppInstallCapability,
        installResult: Boolean,
    ): WorkProfileAppInstallService {
        var calls = 0
        val checker = object : WorkProfileAppInstallCapabilityChecker(
            app,
            ownershipService = object : ManagedProfileOwnershipStateProvider {
                override fun state() = ManagedProfileOwnershipState.MANAGED_PROFILE_OURS
            },
            packageInspector = fakeInspector,
            fallbackLauncher = fakeFallback,
        ) {
            override fun capabilityFor(packageName: String): WorkProfileAppInstallCapability {
                calls += 1
                return if (calls == 1) first else second
            }
        }
        return WorkProfileAppInstallService(
            context = app,
            capabilityChecker = checker,
            fallbackLauncher = fakeFallback,
            installer = object : WorkProfileAppInstallService.EnterpriseInstaller {
                override fun installExistingPackage(packageName: String): Boolean = installResult
            },
        )
    }

    private fun capability(
        installedInWork: Boolean = false,
        canAuto: Boolean = false,
        canFallback: Boolean = false,
    ) = WorkProfileAppInstallCapability(
        label = "Label",
        ownershipState = ManagedProfileOwnershipState.MANAGED_PROFILE_OURS,
        installedInParentProfile = true,
        installedInWorkProfile = installedInWork,
        canInstallAutomatically = canAuto,
        canLaunchManualFallback = canFallback,
        environment = WorkProfileInstallEnvironment(
            ownershipState = ManagedProfileOwnershipState.MANAGED_PROFILE_OURS,
            installedInParentProfile = true,
            installedInWorkProfile = installedInWork,
            installExistingPackageApiAvailable = true,
            manualStoreFallbackResolvable = canFallback,
            hasTargetUserProfiles = true,
            autoInstallAllowedByEnvironment = canAuto,
            environmentReason = if (installedInWork) {
                WorkProfileInstallEnvironmentReason.ALREADY_INSTALLED_IN_WORK
            } else if (canAuto) {
                WorkProfileInstallEnvironmentReason.PROFILE_OWNER_CONFIRMED
            } else if (canFallback) {
                WorkProfileInstallEnvironmentReason.MANUAL_FALLBACK_ONLY
            } else {
                WorkProfileInstallEnvironmentReason.NO_FALLBACK_AVAILABLE
            },
        ),
        availability = when {
            installedInWork -> WorkProfileAppAvailability.INSTALLED_IN_WORK
            canAuto -> WorkProfileAppAvailability.INSTALLABLE_AUTOMATICALLY
            canFallback -> WorkProfileAppAvailability.REQUIRES_MANUAL_INSTALL
            else -> WorkProfileAppAvailability.UNAVAILABLE
        },
        action = when {
            installedInWork -> WorkProfileAppAction.OPEN_IN_WORK
            canAuto -> WorkProfileAppAction.INSTALL_AUTOMATICALLY
            canFallback -> WorkProfileAppAction.OPEN_STORE_MANUALLY
            else -> WorkProfileAppAction.NONE
        },
    )

    private val fakeInspector = object : WorkProfileAppInstallCapabilityChecker.PackageInspector {
        override fun isInstalledInParent(packageName: String): Boolean = true
        override fun isInstalledInWorkProfile(packageName: String): Boolean = false
        override fun hasTargetProfiles(): Boolean = true
        override fun appLabel(packageName: String): String = "Label"
    }

    private val fakeFallback = object : WorkProfileAppInstallCapabilityChecker.FallbackLauncher {
        override fun canLaunchStoreIntent(packageName: String): Boolean = true
        override fun launchStoreIntent(packageName: String): Boolean = true
    }

    companion object {
        private const val PKG = "com.example.app"
    }
}
