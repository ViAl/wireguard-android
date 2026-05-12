/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.content.Context
import android.content.Intent
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
import com.wireguard.android.jail.shuttle.WorkProfileCloner

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

            // Strategy 0: DPC-based install into managed work profile.
            // This is the most direct path — it clones the APK silently,
            // without opening any store UI.
            val dpcInstaller = DpcPackageInstaller(appContext)
            val dpcAvailable = dpcInstaller.isAvailable()
            WorkProfileLogger.d("launchStoreIntent: DPC available=${dpcAvailable.isAvailable}")
            if (dpcAvailable.isAvailable) {
                val result = dpcInstaller.install(packageName)
                WorkProfileLogger.d("launchStoreIntent: DPC result=$result")
                if (result.isSuccess) {
                    WorkProfileLogger.d("launchStoreIntent: DPC install succeeded, skipping store UI")
                    return true
                }
            }

            // Strategy 0.5: WorkProfileCloner — try DPC install or
            // ACTION_INSTALL_PACKAGE directly in parent profile.
            val profiles = otherProfiles()
            if (profiles.isNotEmpty()) {
            val cloned = WorkProfileCloner.clone(appContext, packageName, null, profiles.first())
                WorkProfileLogger.d("launchStoreIntent: WorkProfileCloner clone result=$cloned")
                if (cloned != WorkProfileCloner.RESULT_NO_SYS_MARKET) {
                    WorkProfileLogger.d("launchStoreIntent: WorkProfileCloner succeeded (result=$cloned)")
                    if (cloned == WorkProfileCloner.RESULT_OK_GOOGLE_PLAY) {
                        WorkProfileLogger.d("launchStoreIntent: Shuttle opened Play Store inside work profile")
                    }
                    return true
                }
            }

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

        private fun launchStoreInProfile(handle: UserHandle, packageName: String): Boolean {
            WorkProfileLogger.d("launchStoreInProfile: handle=$handle package=$packageName")

            // Strategy 1 (primary, API 36+): LauncherApps.getAppMarketActivityIntent.
            // Returns an IntentSender that opens Play Store for the given package
            // in the target profile.
            if (Build.VERSION.SDK_INT >= 36) {
                val ok = try {
                    val la = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                    if (la != null) {
                        val sender = la.getAppMarketActivityIntent(packageName, handle)
                        if (sender != null) {
                            WorkProfileLogger.d("Strategy 1a: getAppMarketActivityIntent handle=$handle")
                            sender.sendIntent(appContext, 0, null, null, null)
                            WorkProfileLogger.d("Strategy 1a: succeeded")
                            true
                        } else {
                            WorkProfileLogger.e("Strategy 1a: getAppMarketActivityIntent returned null")
                            false
                        }
                    } else {
                        WorkProfileLogger.e("Strategy 1a: LauncherApps service is null")
                        false
                    }
                } catch (e: Exception) {
                    WorkProfileLogger.e("Strategy 1a failed: ${e.message}", e)
                    false
                }
                if (ok) return true
            }

            // Strategy 1b (API 33-35): LauncherApps.startActivity(Intent, UserHandle, Rect, Bundle)
            // via reflection — the method exists at runtime but was removed from the compile-only
            // API 36+ stubs in favor of more specific methods like startMainActivity.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val ok = try {
                    val la = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE)
                    if (la != null) {
                        val launcherClass = la.javaClass
                        val storeIntent = Intent(Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=$packageName"))
                            .setPackage(PLAY_STORE_PACKAGE)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val startMethod = launcherClass.getMethod(
                            "startActivity",
                            Intent::class.java,
                            UserHandle::class.java,
                            android.graphics.Rect::class.java,
                            Bundle::class.java
                        )
                        WorkProfileLogger.d("Strategy 1b: LauncherApps startActivity(Intent) ref handle=$handle")
                        startMethod.invoke(la, storeIntent, handle, null, null)
                        WorkProfileLogger.d("Strategy 1b: succeeded")
                        true
                    } else {
                        WorkProfileLogger.e("Strategy 1b: LauncherApps service is null")
                        false
                    }
                } catch (e: Exception) {
                    WorkProfileLogger.e("Strategy 1b failed: ${e.message}", e)
                    false
                }
                if (ok) return true
            }

            // Strategy 2 (fallback): market:// details intent without setPackage.
            val fallbackOk = runCatching {
                val intent = Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                WorkProfileLogger.d("Strategy 2: market:// fallback intent=$intent")
                appContext.startActivity(intent)
                true
            }.getOrDefault(false)
            if (fallbackOk) { WorkProfileLogger.d("Strategy 2: succeeded"); return true }
            else { WorkProfileLogger.e("Strategy 2 failed") }

            // Strategy 3 (last resort): https://play.google.com URL.
            val httpsOk = runCatching {
                val url = "https://play.google.com/store/apps/details?id=$packageName"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                WorkProfileLogger.d("Strategy 3: https fallback intent=$intent")
                appContext.startActivity(intent)
                true
            }.getOrDefault(false)
            if (httpsOk) { WorkProfileLogger.d("Strategy 3: succeeded"); return true }
            else { WorkProfileLogger.e("Strategy 3 failed") }

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
