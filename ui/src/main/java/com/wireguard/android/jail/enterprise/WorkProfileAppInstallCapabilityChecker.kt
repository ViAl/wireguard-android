/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.content.Context
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
            WorkProfileLogger.d("canLaunchStoreIntent($packageName)")
            if (canLaunchStoreInOtherProfile()) {
                WorkProfileLogger.d("canLaunchStoreInOtherProfile = true")
                return true
            }
            val primary = WorkProfileInstallGuide.playStoreDetailsIntent(packageName)
            val fallback = WorkProfileInstallGuide.playStoreHttpsIntent(packageName)
            val result = resolvable(primary) || resolvable(fallback)
            WorkProfileLogger.d("resolvable primary=$primary = ${resolvable(primary)}, fallback=$fallback = ${resolvable(fallback)}")
            return result
        }

        override fun launchStoreIntent(packageName: String): Boolean {
            WorkProfileLogger.d("launchStoreIntent($packageName)")
            if (launchStoreInOtherProfile(packageName)) {
                WorkProfileLogger.d("launchStoreInOtherProfile returned true")
                return true
            }

            val intents = listOf(
                WorkProfileInstallGuide.playStoreDetailsIntent(packageName),
                WorkProfileInstallGuide.playStoreHttpsIntent(packageName),
            ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

            val candidate = intents.firstOrNull { resolvable(it) } ?: return false
            WorkProfileLogger.d("launchStoreIntent: primary fallback candidate=$candidate")
            return runCatching {
                appContext.startActivity(candidate)
                true
            }.getOrDefault(false)
        }

        private fun canLaunchStoreInOtherProfile(): Boolean {
            val profiles = otherProfiles()
            WorkProfileLogger.d("canLaunchStoreInOtherProfile: profiles=$profiles (${profiles.size})")
            return profiles.any { handle ->
                val activities = runCatching { launcherApps?.getActivityList(PLAY_STORE_PACKAGE, handle).orEmpty() }.getOrDefault(emptyList())
                WorkProfileLogger.d("  profile $handle: Play Store activities=${activities.size}")
                activities.isNotEmpty()
            }
        }

        private fun launchStoreInOtherProfile(packageName: String): Boolean {
            val profiles = otherProfiles()
            WorkProfileLogger.d("launchStoreInOtherProfile($packageName): profiles=$profiles (${profiles.size})")
            return profiles.any { handle ->
                launchStoreInProfile(handle, packageName)
            }
        }

        @Suppress("DEPRECATION")
        private fun launchStoreInProfile(handle: UserHandle, packageName: String): Boolean {
            WorkProfileLogger.d("launchStoreInProfile: handle=$handle package=$packageName")

            // Strategy 0: Proxy through our own app copy inside the work profile.
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    val cpa = appContext.getSystemService(CrossProfileApps::class.java)
                    val proxyIntent = PlayStoreProxyActivity.buildProxyIntent(appContext, packageName)
                    WorkProfileLogger.d("Strategy 0a: CrossProfileApps proxy intent=$proxyIntent")
                    cpa?.startActivity(proxyIntent, handle, null, null)
                    WorkProfileLogger.d("Strategy 0a: succeeded (no exception)")
                    return true
                } catch (e: Exception) {
                    WorkProfileLogger.e("Strategy 0a failed: ${e.message}", e)
                }
            }
            try {
                val proxyIntent = PlayStoreProxyActivity.buildProxyIntent(appContext, packageName)
                val optsClass = Class.forName("android.app.ActivityOptions")
                val makeOpenInUser =
                    optsClass.getMethod("makeOpenInUser", UserHandle::class.java)
                val opts = makeOpenInUser.invoke(null, handle)
                val bundle = optsClass.getMethod("toBundle").invoke(opts) as Bundle
                WorkProfileLogger.d("Strategy 0b: makeOpenInUser proxy intent=$proxyIntent")
                appContext.startActivity(proxyIntent, bundle)
                WorkProfileLogger.d("Strategy 0b: succeeded")
                return true
            } catch (e: Exception) {
                WorkProfileLogger.e("Strategy 0b failed: ${e.message}", e)
            }

            // Strategy 1: CrossProfileApps.startActivity() directly to Play Store.
            if (Build.VERSION.SDK_INT >= 34) {
                val ok = try {
                    val cpa = appContext.getSystemService(CrossProfileApps::class.java)
                    val marketIntent = WorkProfileInstallGuide
                        .playStoreDetailsIntent(packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    WorkProfileLogger.d("Strategy 1: CrossProfileApps direct to Play Store intent=$marketIntent")
                    cpa?.startActivity(marketIntent, handle, null, null)
                    WorkProfileLogger.d("Strategy 1: succeeded")
                    true
                } catch (e: Exception) {
                    WorkProfileLogger.e("Strategy 1 failed: ${e.message}", e)
                    false
                }
                if (ok) return true
            }

            // Build the makeOpenInUser bundle for cross-user routing.
            val bundle = try {
                val optsClass = Class.forName("android.app.ActivityOptions")
                val makeOpenInUser =
                    optsClass.getMethod("makeOpenInUser", UserHandle::class.java)
                val opts = makeOpenInUser.invoke(null, handle)
                optsClass.getMethod("toBundle").invoke(opts) as Bundle
            } catch (e: Exception) {
                WorkProfileLogger.e("makeOpenInUser bundle build failed", e)
                null
            }

            if (bundle == null) {
                WorkProfileLogger.e("Bundle is null, all makeOpenInUser strategies skipped")
                return false
            }

            // Strategy 2: open the Play Store URL in a browser running inside the work profile.
            val browserPackage = listOf(
                "com.android.chrome",
                "com.chrome.beta",
                "com.chrome.dev",
                "com.brave.browser",
                "org.mozilla.firefox",
                "org.mozilla.firefox.beta",
                "com.microsoft.emmx",
                "com.sec.android.app.sbrowser",
                "com.opera.browser",
                "com.opera.mini.native",
                "com.duckduckgo.mobile.android",
                "com.vivaldi.browser",
            ).firstOrNull { pkg ->
                val found = runCatching {
                    launcherApps?.getActivityList(pkg, handle).orEmpty().isNotEmpty()
                }.getOrDefault(false)
                if (found) WorkProfileLogger.d("Strategy 2: found browser $pkg in profile")
                found
            }

            if (browserPackage != null) {
                val url = "https://play.google.com/store/apps/details?id=$packageName"
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .setPackage(browserPackage)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                WorkProfileLogger.d("Strategy 2: launching browser=$browserPackage url=$url")
                val ok = runCatching {
                    appContext.startActivity(browserIntent, bundle)
                    true
                }.getOrDefault(false)
                if (ok) { WorkProfileLogger.d("Strategy 2: succeeded"); return true }
                else { WorkProfileLogger.e("Strategy 2 failed") }
            }

            // Strategy 3: https:// URL without setPackage.
            val httpsOk = runCatching {
                val url = "https://play.google.com/store/apps/details?id=$packageName"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                WorkProfileLogger.d("Strategy 3: https intent=$intent")
                appContext.startActivity(intent, bundle)
                true
            }.getOrDefault(false)
            if (httpsOk) { WorkProfileLogger.d("Strategy 3: succeeded"); return true }
            else { WorkProfileLogger.e("Strategy 3 failed") }

            // Strategy 4 (fallback): direct Play Store deep-link.
            val directCandidates = listOf(
                WorkProfileInstallGuide.playStoreDetailsIntent(packageName),
                WorkProfileInstallGuide.playStoreHttpsIntent(packageName),
            ).map { intent ->
                intent.setPackage(PLAY_STORE_PACKAGE)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent
            }

            for (intent in directCandidates) {
                WorkProfileLogger.d("Strategy 4: direct Play Store intent=$intent")
                val ok = runCatching {
                    appContext.startActivity(intent, bundle)
                    true
                }.getOrDefault(false)
                if (ok) { WorkProfileLogger.d("Strategy 4: succeeded"); return true }
                else { WorkProfileLogger.e("Strategy 4 failed") }
            }

            WorkProfileLogger.e("All strategies exhausted for handle=$handle package=$packageName")
            return false
        }

        private fun otherProfiles() = launcherApps?.profiles.orEmpty().filterNot { it == Process.myUserHandle() }

        private fun resolvable(intent: Intent): Boolean = intent.resolveActivity(packageManager) != null

        private companion object {
            const val PLAY_STORE_PACKAGE = "com.android.vending"
        }
    }
}
