/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import com.wireguard.android.jail.domain.WorkProfileInstallGuide
import com.wireguard.android.jail.model.WorkProfileAppAction
import com.wireguard.android.jail.model.WorkProfileAppAvailability
import com.wireguard.android.jail.model.WorkProfileAppInstallCapability
import com.wireguard.android.jail.model.WorkProfileInstallEnvironmentReason

open class WorkProfileAppInstallCapabilityChecker(
    context: Context,
    ownershipService: ManagedProfileOwnershipStateProvider,
    private val packageInspector: PackageInspector = AndroidPackageInspector(context),
    private val fallbackLauncher: FallbackLauncher = AndroidFallbackLauncher(context),
    private val environmentInspector: InstallEnvironmentInspector = InstallEnvironmentInspector(
        ownershipService = ownershipService,
        packageInspector = packageInspector,
        fallbackLauncher = fallbackLauncher,
    ),
) {
    fun fallbackLauncher(): FallbackLauncher = fallbackLauncher

    open fun capabilityFor(packageName: String): WorkProfileAppInstallCapability {
        val environment = environmentInspector.inspect(packageName)
        val canFallback = environment.manualStoreFallbackResolvable

        if (environment.installedInWorkProfile) {
            return WorkProfileAppInstallCapability(
                label = packageInspector.appLabel(packageName),
                ownershipState = environment.ownershipState,
                installedInParentProfile = environment.installedInParentProfile,
                installedInWorkProfile = true,
                canInstallAutomatically = false,
                canLaunchManualFallback = false,
                environment = environment,
                availability = WorkProfileAppAvailability.INSTALLED_IN_WORK,
                action = WorkProfileAppAction.OPEN_IN_WORK,
                reason = reasonText(environment.environmentReason),
            )
        }

        val autoInstallPossible = environment.autoInstallAllowedByEnvironment

        if (autoInstallPossible) {
            return WorkProfileAppInstallCapability(
                label = packageInspector.appLabel(packageName),
                ownershipState = environment.ownershipState,
                installedInParentProfile = environment.installedInParentProfile,
                installedInWorkProfile = false,
                canInstallAutomatically = true,
                canLaunchManualFallback = canFallback,
                environment = environment,
                availability = WorkProfileAppAvailability.INSTALLABLE_AUTOMATICALLY,
                action = WorkProfileAppAction.INSTALL_AUTOMATICALLY,
                reason = reasonText(environment.environmentReason),
            )
        }

        if (canFallback) {
            return WorkProfileAppInstallCapability(
                label = packageInspector.appLabel(packageName),
                ownershipState = environment.ownershipState,
                installedInParentProfile = environment.installedInParentProfile,
                installedInWorkProfile = false,
                canInstallAutomatically = false,
                canLaunchManualFallback = true,
                environment = environment,
                availability = WorkProfileAppAvailability.REQUIRES_MANUAL_INSTALL,
                action = WorkProfileAppAction.OPEN_STORE_MANUALLY,
                reason = reasonText(environment.environmentReason),
            )
        }

        return WorkProfileAppInstallCapability(
            label = packageInspector.appLabel(packageName),
            ownershipState = environment.ownershipState,
            installedInParentProfile = environment.installedInParentProfile,
            installedInWorkProfile = false,
            canInstallAutomatically = false,
            canLaunchManualFallback = false,
            environment = environment,
            availability = WorkProfileAppAvailability.UNAVAILABLE,
            action = WorkProfileAppAction.NONE,
            reason = reasonText(environment.environmentReason),
        )
    }

    private fun reasonText(reason: WorkProfileInstallEnvironmentReason): String = when (reason) {
        WorkProfileInstallEnvironmentReason.ALREADY_INSTALLED_IN_WORK ->
            "Already installed in work profile"
        WorkProfileInstallEnvironmentReason.PROFILE_OWNER_CONFIRMED ->
            "Automatic install may be available because this app appears to be profile owner"
        WorkProfileInstallEnvironmentReason.MANAGED_PROFILE_NOT_OURS ->
            "Manual install is required because this profile is not owned by Jail"
        WorkProfileInstallEnvironmentReason.SECONDARY_PROFILE_PRESENT_ONLY ->
            "Only a secondary profile is visible; managed ownership is not confirmed"
        WorkProfileInstallEnvironmentReason.OWNERSHIP_UNCERTAIN ->
            "Manual install is required because ownership could not be verified"
        WorkProfileInstallEnvironmentReason.NO_MANAGED_PROFILE ->
            "No managed profile was detected on this device state"
        WorkProfileInstallEnvironmentReason.API_LEVEL_UNSUPPORTED ->
            "Automatic install is unavailable on this Android/API level"
        WorkProfileInstallEnvironmentReason.PARENT_PACKAGE_MISSING ->
            "Automatic install requires the app to be installed in the parent profile first"
        WorkProfileInstallEnvironmentReason.MANUAL_FALLBACK_ONLY ->
            "Automatic install is not available; manual store install is the best supported path"
        WorkProfileInstallEnvironmentReason.NO_FALLBACK_AVAILABLE ->
            "No automatic or manual install flow appears to be available"
        WorkProfileInstallEnvironmentReason.UNKNOWN ->
            "Install environment could not be diagnosed"
    }

    interface PackageInspector {
        fun isInstalledInParent(packageName: String): Boolean
        fun isInstalledInWorkProfile(packageName: String): Boolean
        fun hasTargetProfiles(): Boolean
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

        override fun hasTargetProfiles(): Boolean =
            launcherApps?.profiles.orEmpty().any { it != android.os.Process.myUserHandle() }

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
        private val launcherApps = context.getSystemService(LauncherApps::class.java)

        override fun canLaunchStoreIntent(packageName: String): Boolean {
            if (canLaunchStoreInOtherProfile()) return true
            val primary = WorkProfileInstallGuide.playStoreDetailsIntent(packageName)
            val fallback = WorkProfileInstallGuide.playStoreHttpsIntent(packageName)
            return resolvable(primary) || resolvable(fallback)
        }

        override fun launchStoreIntent(packageName: String): Boolean {
            val intents = listOf(
                WorkProfileInstallGuide.playStoreDetailsIntent(packageName),
                WorkProfileInstallGuide.playStoreHttpsIntent(packageName),
            ).map { baseIntent ->
                baseIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Intent(baseIntent).setPackage(PLAY_STORE_PACKAGE)
            }

            if (intents.any { launchStoreIntentInOtherProfile(it) }) return true
            if (launchStoreInOtherProfile()) return true

            intents.firstOrNull { resolvable(it) }?.let { candidate ->
                val launched = runCatching {
                    appContext.startActivity(candidate)
                    true
                }.getOrDefault(false)
                if (launched) return true
            }

            return false
        }

        private fun canLaunchStoreInOtherProfile(): Boolean =
            otherProfiles().any { handle ->
                runCatching { launcherApps?.getActivityList(PLAY_STORE_PACKAGE, handle).orEmpty().isNotEmpty() }
                    .getOrDefault(false)
            }

        private fun launchStoreInOtherProfile(): Boolean {
            val candidate = otherProfiles().firstNotNullOfOrNull { handle ->
                val activity = runCatching {
                    launcherApps?.getActivityList(PLAY_STORE_PACKAGE, handle).orEmpty().firstOrNull()
                }.getOrNull() ?: return@firstNotNullOfOrNull null
                handle to activity.componentName
            } ?: return false

            return runCatching {
                launcherApps?.startMainActivity(candidate.second, candidate.first, null, null)
                true
            }.getOrDefault(false)
        }

        /**
         * Best-effort deep link launch into a secondary profile.
         *
         * Uses hidden framework APIs via reflection and falls back safely when unavailable.
         */
        private fun launchStoreIntentInOtherProfile(intent: Intent): Boolean {
            val handles = otherProfiles()
            if (handles.isEmpty()) return false
            return handles.any { handle ->
                launchWithTwoArgSignature(intent, handle) || launchWithThreeArgSignature(intent, handle)
            }
        }

        private fun launchWithTwoArgSignature(intent: Intent, handle: UserHandle): Boolean = runCatching {
            val method = Context::class.java.getMethod(
                "startActivityAsUser",
                Intent::class.java,
                UserHandle::class.java,
            )
            method.invoke(appContext, intent, handle)
            true
        }.getOrDefault(false)

        private fun launchWithThreeArgSignature(intent: Intent, handle: UserHandle): Boolean = runCatching {
            val method = Context::class.java.getMethod(
                "startActivityAsUser",
                Intent::class.java,
                Bundle::class.java,
                UserHandle::class.java,
            )
            method.invoke(appContext, intent, null, handle)
            true
        }.getOrDefault(false)

        private fun otherProfiles() = launcherApps?.profiles.orEmpty().filterNot { it == Process.myUserHandle() }

        private fun resolvable(intent: Intent): Boolean = intent.resolveActivity(packageManager) != null

        private companion object {
            const val PLAY_STORE_PACKAGE = "com.android.vending"
        }
    }
}
