/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.system

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PackageInfoFlags
import android.os.Build
import com.wireguard.android.jail.model.JailAppInfo

/**
 * Enumerates installed applications for the Jail Apps list.
 *
 * Uses [PackageManager.getInstalledPackages] so **all** installed packages visible to this
 * process are included (launcher-only discovery is insufficient). Listing requires the
 * [android.Manifest.permission.QUERY_ALL_PACKAGES] permission declared in the manifest so the
 * OS exposes the full inventory on Android 11+ package-visibility builds.
 *
 * Packages already tracked in [selectedPackages] are merged in even if introspection skipped
 * them so the user can clear stale selections.
 *
 * Heavy work (icon loading, label resolution) happens synchronously here; callers must invoke
 * this off the main thread (current consumers use [kotlinx.coroutines.Dispatchers.Default]).
 *
 * Sort order is owned by [com.wireguard.android.jail.domain.JailAppRepository]; this layer
 * returns packages in implementation-defined order.
 */
object InstalledAppsSource {
    /**
     * @param packageManager platform package manager.
     * @param selectedPackages the packages currently selected for Jail. Included even when a
     * row could not be built so callers can reconcile selection state.
     */
    fun load(packageManager: PackageManager, selectedPackages: Set<String>): List<JailAppInfo> {
        val merged = LinkedHashMap<String, PackageInfo>(
            selectedPackages.size + APP_LIST_HINT
        )
        queryAllInstalled(packageManager).forEach { pi ->
            merged[pi.packageName] = pi
        }
        selectedPackages.forEach { pkg ->
            if (!merged.containsKey(pkg)) {
                getPackageInfoOrNull(packageManager, pkg)?.let { merged[pkg] = it }
            }
        }

        val results = ArrayList<JailAppInfo>(merged.size)
        merged.values.forEach { pi ->
            buildFromPackageInfo(packageManager, pi, selectedPackages)?.let { results.add(it) }
        }

        results.sortWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.packageName }
        )
        return results
    }

    private fun queryAllInstalled(packageManager: PackageManager): List<PackageInfo> {
        val flags = PackageManager.GET_PERMISSIONS.toLong()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(PackageInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(flags.toInt())
        }
    }

    private fun buildFromPackageInfo(
        packageManager: PackageManager,
        packageInfo: PackageInfo,
        selectedPackages: Set<String>
    ): JailAppInfo? {
        val appInfo = packageInfo.applicationInfo ?: return null
        val packageName = packageInfo.packageName
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

    private const val APP_LIST_HINT = 256
}
