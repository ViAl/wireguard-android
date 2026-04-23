/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.content.ComponentName
import android.content.Context
import com.wireguard.android.jail.model.InstallResult
import com.wireguard.android.jail.model.UserActionReason
import com.wireguard.android.workprofile.PackageCloneResult
import com.wireguard.android.workprofile.PlayStoreLauncher
import com.wireguard.android.workprofile.ProfileAdminReceiver
import com.wireguard.android.workprofile.WorkProfileCapabilityChecker
import com.wireguard.android.workprofile.WorkProfileInstallCoordinator
import com.wireguard.android.workprofile.WorkProfileInstaller

class WorkProfileAppInstallService(
    context: Context,
    private val capabilityChecker: WorkProfileAppInstallCapabilityChecker,
    private val fallbackLauncher: WorkProfileAppInstallCapabilityChecker.FallbackLauncher,
) {
    private val appContext = context.applicationContext
    private val coordinator by lazy {
        val admin = ComponentName(appContext, ProfileAdminReceiver::class.java)
        val checker = WorkProfileCapabilityChecker(appContext, admin)
        WorkProfileInstallCoordinator(
            context = appContext,
            capabilityChecker = checker,
            installer = WorkProfileInstaller(appContext, checker),
            playStoreLauncher = PlayStoreLauncher(appContext),
        )
    }

    fun install(packageName: String): InstallResult {
        val before = capabilityChecker.capabilityFor(packageName)
        if (before.installedInWorkProfile) return InstallResult.AlreadyInstalled

        return when (val result = coordinator.installInWorkProfile(packageName, activity = null)) {
            PackageCloneResult.SuccessInstalledExisting,
            PackageCloneResult.SuccessEnabledSystemApp,
            PackageCloneResult.SuccessInstalledFromApkSession,
            -> InstallResult.Installed
            PackageCloneResult.RedirectedToPlayStore ->
                InstallResult.UserActionRequired(UserActionReason.MANUAL_INSTALL_REQUIRED)
            PackageCloneResult.ErrorPackageNotFound -> InstallResult.Failed("Package not found in primary profile")
            PackageCloneResult.ErrorPlayStoreUnavailable -> InstallResult.Failed("Play Store unavailable")
            PackageCloneResult.ErrorInstallSessionFailed -> InstallResult.Failed("Install session failed")
            PackageCloneResult.ErrorUnsupportedAndroidVersion ->
                InstallResult.Failed("Unsupported Android version for work profile install")
            PackageCloneResult.ErrorNoWorkProfileHelper,
            PackageCloneResult.ErrorNotProfileOwner,
            PackageCloneResult.ErrorPermissionDenied,
            -> InstallResult.UserActionRequired(UserActionReason.MANUAL_INSTALL_REQUIRED)
            is PackageCloneResult.ErrorUnknown -> InstallResult.Failed(result.message)
        }
    }

    fun launchManualInstall(packageName: String): InstallResult {
        val capability = capabilityChecker.capabilityFor(packageName)
        if (capability.installedInWorkProfile) return InstallResult.AlreadyInstalled

        val launched = fallbackLauncher.launchStoreIntent(packageName)
        return if (launched) {
            InstallResult.UserActionRequired(UserActionReason.MANUAL_INSTALL_REQUIRED)
        } else {
            InstallResult.Failed("Could not launch store for manual install")
        }
    }
}
