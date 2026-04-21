/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import com.wireguard.android.jail.domain.WorkProfileInstallGuide
import com.wireguard.android.jail.model.ManagedProfileOwnershipState
import com.wireguard.android.jail.model.WorkProfileAppAction
import com.wireguard.android.jail.model.WorkProfileAppAvailability
import com.wireguard.android.jail.model.WorkProfileAppInstallCapability

open class WorkProfileAppInstallCapabilityChecker(
    context: Context,
    private val ownershipService: ManagedProfileOwnershipStateProvider,
    private val packageInspector: PackageInspector = AndroidPackageInspector(context),
    private val fallbackLauncher: FallbackLauncher = AndroidFallbackLauncher(context),
) {
    fun fallbackLauncher(): FallbackLauncher = fallbackLauncher

    open fun capabilityFor(packageName: String): WorkProfileAppInstallCapability {
        val ownership = ownershipService.state()
        val inParent = packageInspector.isInstalledInParent(packageName)
        val inWork = packageInspector.isInstalledInWorkProfile(packageName)
        val canFallback = fallbackLauncher.canLaunchStoreIntent(packageName)

        if (inWork) {
            return WorkProfileAppInstallCapability(
                label = packageInspector.appLabel(packageName),
                ownershipState = ownership,
                installedInParentProfile = inParent,
                installedInWorkProfile = true,
                canInstallAutomatically = false,
                canLaunchManualFallback = false,
                availability = WorkProfileAppAvailability.INSTALLED_IN_WORK,
                action = WorkProfileAppAction.OPEN_IN_WORK,
                reason = "Already installed in work profile",
            )
        }

        val ownedByUs = ownership == ManagedProfileOwnershipState.MANAGED_PROFILE_OURS
        val autoInstallPossible = ownedByUs && inParent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

        if (autoInstallPossible) {
            return WorkProfileAppInstallCapability(
                label = packageInspector.appLabel(packageName),
                ownershipState = ownership,
                installedInParentProfile = inParent,
                installedInWorkProfile = false,
                canInstallAutomatically = true,
                canLaunchManualFallback = canFallback,
                availability = WorkProfileAppAvailability.INSTALLABLE_AUTOMATICALLY,
                action = WorkProfileAppAction.INSTALL_AUTOMATICALLY,
                reason = "Profile owner verified and source package present",
            )
        }

        if (canFallback) {
            return WorkProfileAppInstallCapability(
                label = packageInspector.appLabel(packageName),
                ownershipState = ownership,
                installedInParentProfile = inParent,
                installedInWorkProfile = false,
                canInstallAutomatically = false,
                canLaunchManualFallback = true,
                availability = WorkProfileAppAvailability.REQUIRES_MANUAL_INSTALL,
                action = WorkProfileAppAction.OPEN_STORE_MANUALLY,
                reason = when (ownership) {
                    ManagedProfileOwnershipState.MANAGED_PROFILE_PRESENT_NOT_OURS ->
                        "Managed profile is not owned by this app"
                    ManagedProfileOwnershipState.SECONDARY_PROFILE_PRESENT_NOT_OURS ->
                        "Secondary profile is present, but managed ownership is not confirmed"
                    ManagedProfileOwnershipState.NO_MANAGED_PROFILE -> "No managed profile detected"
                    ManagedProfileOwnershipState.UNSUPPORTED -> "Managed profile APIs unsupported"
                    ManagedProfileOwnershipState.OWNERSHIP_UNCERTAIN -> "Ownership could not be verified"
                    ManagedProfileOwnershipState.MANAGED_PROFILE_OURS -> "Package is not available for installExistingPackage"
                },
            )
        }

        return WorkProfileAppInstallCapability(
            label = packageInspector.appLabel(packageName),
            ownershipState = ownership,
            installedInParentProfile = inParent,
            installedInWorkProfile = false,
            canInstallAutomatically = false,
            canLaunchManualFallback = false,
            availability = WorkProfileAppAvailability.UNAVAILABLE,
            action = WorkProfileAppAction.NONE,
            reason = "No automatic or manual flow is available",
        )
    }

    interface PackageInspector {
        fun isInstalledInParent(packageName: String): Boolean
        fun isInstalledInWorkProfile(packageName: String): Boolean
        fun appLabel(packageName: String): String?
    }

    private class AndroidPackageInspector(private val context: Context) : PackageInspector {
        private val packageManager = context.packageManager
        private val launcherApps = context.getSystemService(LauncherApps::class.java)

        override fun isInstalledInParent(packageName: String): Boolean =
            runCatching {
                packageManager.getApplicationInfo(packageName, 0)
                true
            }.getOrDefault(false)

        override fun isInstalledInWorkProfile(packageName: String): Boolean {
            val profiles = launcherApps?.profiles.orEmpty().filterNot { it == android.os.Process.myUserHandle() }
            if (profiles.isEmpty()) return false
            return profiles.any { handle ->
                runCatching {
                    launcherApps?.getApplicationInfo(packageName, 0, handle) != null
                }.getOrDefault(false)
            }
        }

        override fun appLabel(packageName: String): String? = runCatching {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        }.getOrNull()
    }

    interface FallbackLauncher {
        fun canLaunchStoreIntent(packageName: String): Boolean
        fun launchStoreIntent(packageName: String): Boolean
    }

    private class AndroidFallbackLauncher(context: Context) : FallbackLauncher {
        private val packageManager = context.packageManager
        private val appContext = context.applicationContext

        override fun canLaunchStoreIntent(packageName: String): Boolean {
            val primary = WorkProfileInstallGuide.playStoreDetailsIntent(packageName)
            val fallback = WorkProfileInstallGuide.playStoreHttpsIntent(packageName)
            return resolvable(primary) || resolvable(fallback)
        }

        override fun launchStoreIntent(packageName: String): Boolean {
            val intents = listOf(
                WorkProfileInstallGuide.playStoreDetailsIntent(packageName),
                WorkProfileInstallGuide.playStoreHttpsIntent(packageName),
            ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

            val candidate = intents.firstOrNull { resolvable(it) } ?: return false
            return runCatching {
                appContext.startActivity(candidate)
                true
            }.getOrDefault(false)
        }

        private fun resolvable(intent: Intent): Boolean = intent.resolveActivity(packageManager) != null
    }
}
