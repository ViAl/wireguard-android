/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.system

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PackageInfoFlags
import android.content.pm.ResolveInfo
import android.os.Build
import com.wireguard.android.jail.model.JailAppInfo

/**
 * Enumerates installed applications that are relevant to the Jail feature.
 *
 * Scope of enumeration:
 *  * Apps that declare a launcher activity (reachable via the app's manifest `<queries>` block).
 *  * Any package already tracked by the caller (typically the current Jail selection), even if
 *    it is no longer launchable, so it can still be un-selected in the UI.
 *
 * This deliberately avoids requesting `QUERY_ALL_PACKAGES`: that permission is policy-heavy on
 * modern Google Play distributions and not needed for Jail's MVP.
 *
 * Heavy work (icon loading, label resolution) happens synchronously here; callers must invoke
 * this off the main thread (the current consumers do so via `Dispatchers.Default`).
 */
object InstalledAppsSource {
    /**
     * @param packageManager platform package manager.
     * @param selectedPackages the packages currently selected for Jail. Included even if
     *  they no longer appear in the launcher query result so the user can still remove them.
     */
    fun load(packageManager: PackageManager, selectedPackages: Set<String>): List<JailAppInfo> {
        val launcherPackages = queryLauncherPackages(packageManager).toMutableSet()
        launcherPackages.addAll(selectedPackages)
        if (launcherPackages.isEmpty()) return emptyList()

        val results = ArrayList<JailAppInfo>(launcherPackages.size)
        launcherPackages.forEach { packageName ->
            val info = buildInfoOrNull(packageManager, packageName, selectedPackages) ?: return@forEach
            results.add(info)
        }
        results.sortWith(
            compareBy<JailAppInfo> { !it.isSelectedForJail }
                .thenBy { it.isSystemApp }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.packageName }
        )
        return results
    }

    private fun queryLauncherPackages(packageManager: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        val packages = HashSet<String>(resolveInfos.size)
        resolveInfos.forEach { ri ->
            ri.activityInfo?.packageName?.let { packages.add(it) }
        }
        return packages
    }

    private fun buildInfoOrNull(
        packageManager: PackageManager,
        packageName: String,
        selectedPackages: Set<String>
    ): JailAppInfo? {
        val packageInfo = getPackageInfoOrNull(packageManager, packageName) ?: return null
        val appInfo = packageInfo.applicationInfo ?: return null
        val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val hasInternet = packageInfo.requestedPermissions?.any { it == Manifest.permission.INTERNET } == true
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        return JailAppInfo(
            label = runCatching { appInfo.loadLabel(packageManager).toString() }.getOrDefault(packageName),
            packageName = packageName,
            icon = runCatching { appInfo.loadIcon(packageManager) }.getOrNull(),
            versionName = packageInfo.versionName,
            versionCode = versionCode,
            isSystemApp = isSystemApp,
            hasInternetPermission = hasInternet,
            installedInMainProfile = true,
            // Work-profile detection is a Phase 7 concern; mark as "unknown" until then so the
            // classifier can render honestly rather than guessing presence.
            installedInWorkProfile = null,
            isSelectedForJail = packageName in selectedPackages
        )
    }

    private fun getPackageInfoOrNull(pm: PackageManager, packageName: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
