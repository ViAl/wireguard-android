/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.os.Build
import com.wireguard.android.jail.model.ManagedProfileOwnershipState
import com.wireguard.android.jail.model.WorkProfileInstallEnvironment
import com.wireguard.android.jail.model.WorkProfileInstallEnvironmentReason

class InstallEnvironmentInspector(
    private val ownershipService: ManagedProfileOwnershipStateProvider,
    private val packageInspector: WorkProfileAppInstallCapabilityChecker.PackageInspector,
    private val fallbackLauncher: WorkProfileAppInstallCapabilityChecker.FallbackLauncher,
    private val systemSignals: SystemSignals = AndroidSystemSignals,
) {
    fun inspect(packageName: String): WorkProfileInstallEnvironment {
        val ownership = ownershipService.state()
        val installedInParent = packageInspector.isInstalledInParent(packageName)
        val installedInWork = packageInspector.isInstalledInWorkProfile(packageName)
        val apiAvailable = systemSignals.sdkInt() >= Build.VERSION_CODES.P
        val hasTargetProfiles = packageInspector.hasTargetProfiles()
        val canManualFallback = fallbackLauncher.canLaunchStoreIntent(packageName)
        val autoByEnvironment =
            ownership == ManagedProfileOwnershipState.MANAGED_PROFILE_OURS &&
                installedInParent &&
                apiAvailable

        return WorkProfileInstallEnvironment(
            ownershipState = ownership,
            installedInParentProfile = installedInParent,
            installedInWorkProfile = installedInWork,
            installExistingPackageApiAvailable = apiAvailable,
            manualStoreFallbackResolvable = canManualFallback,
            hasTargetUserProfiles = hasTargetProfiles,
            autoInstallAllowedByEnvironment = autoByEnvironment,
            environmentReason = reasonFor(
                ownership = ownership,
                installedInParent = installedInParent,
                installedInWork = installedInWork,
                apiAvailable = apiAvailable,
                canManualFallback = canManualFallback,
                autoByEnvironment = autoByEnvironment,
            ),
        )
    }

    private fun reasonFor(
        ownership: ManagedProfileOwnershipState,
        installedInParent: Boolean,
        installedInWork: Boolean,
        apiAvailable: Boolean,
        canManualFallback: Boolean,
        autoByEnvironment: Boolean,
    ): WorkProfileInstallEnvironmentReason {
        if (installedInWork) return WorkProfileInstallEnvironmentReason.ALREADY_INSTALLED_IN_WORK
        if (autoByEnvironment) return WorkProfileInstallEnvironmentReason.PROFILE_OWNER_CONFIRMED
        if (ownership == ManagedProfileOwnershipState.MANAGED_PROFILE_OURS) {
            if (!installedInParent) return WorkProfileInstallEnvironmentReason.PARENT_PACKAGE_MISSING
            if (!apiAvailable) {
                return if (canManualFallback) {
                    WorkProfileInstallEnvironmentReason.MANUAL_FALLBACK_ONLY
                } else {
                    WorkProfileInstallEnvironmentReason.NO_FALLBACK_AVAILABLE
                }
            }
            return if (canManualFallback) {
                WorkProfileInstallEnvironmentReason.MANUAL_FALLBACK_ONLY
            } else {
                WorkProfileInstallEnvironmentReason.NO_FALLBACK_AVAILABLE
            }
        }

        if (!canManualFallback) return WorkProfileInstallEnvironmentReason.NO_FALLBACK_AVAILABLE

        return when (ownership) {
            ManagedProfileOwnershipState.MANAGED_PROFILE_PRESENT_NOT_OURS ->
                WorkProfileInstallEnvironmentReason.MANAGED_PROFILE_NOT_OURS
            ManagedProfileOwnershipState.SECONDARY_PROFILE_PRESENT_NOT_OURS ->
                WorkProfileInstallEnvironmentReason.SECONDARY_PROFILE_PRESENT_ONLY
            ManagedProfileOwnershipState.OWNERSHIP_UNCERTAIN ->
                WorkProfileInstallEnvironmentReason.OWNERSHIP_UNCERTAIN
            ManagedProfileOwnershipState.NO_MANAGED_PROFILE ->
                WorkProfileInstallEnvironmentReason.NO_MANAGED_PROFILE
            ManagedProfileOwnershipState.UNSUPPORTED ->
                WorkProfileInstallEnvironmentReason.API_LEVEL_UNSUPPORTED
            ManagedProfileOwnershipState.MANAGED_PROFILE_OURS ->
                WorkProfileInstallEnvironmentReason.UNKNOWN
        }
    }

    interface SystemSignals {
        fun sdkInt(): Int
    }

    object AndroidSystemSignals : SystemSignals {
        override fun sdkInt(): Int = Build.VERSION.SDK_INT
    }
}
