/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.system

import android.Manifest
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PackageInfoFlags
import android.os.Build
import com.wireguard.android.jail.model.PermissionAuditResult

/**
 * Reads permission-related signals straight from [PackageManager].
 *
 * What this can observe reliably (→ KNOWN signals):
 *  * `requestedPermissions` with their `REQUESTED_PERMISSION_GRANTED` flag. That flag
 *    is the source of truth for runtime-dangerous permissions on API 23+.
 *
 * What this only infers from the manifest (→ LIKELY signals):
 *  * `SYSTEM_ALERT_WINDOW` (overlay) — the runtime-granted state is not reliably
 *    queryable for foreign packages across vendors, so we mark the declaration
 *    as LIKELY rather than pretending to know.
 *  * `BIND_NOTIFICATION_LISTENER_SERVICE` / `BIND_ACCESSIBILITY_SERVICE` — here we only
 *    flag *declaration*; actual enablement is computed by
 *    [NotificationAccessInspector] / [AccessibilityInspector] respectively.
 *  * `MANAGE_EXTERNAL_STORAGE` and `PACKAGE_USAGE_STATS` — appop-gated, no stable
 *    cross-vendor read API from a regular app.
 */
object PermissionInspector {
    fun inspect(packageManager: PackageManager, packageName: String): PermissionAuditResult? {
        val packageInfo = fetchPackageInfo(packageManager, packageName) ?: return null

        val requested = packageInfo.requestedPermissions?.toList().orEmpty()
        val flags = packageInfo.requestedPermissionsFlags ?: IntArray(0)
        val granted = HashSet<String>(requested.size)
        requested.forEachIndexed { index, perm ->
            val flag = flags.getOrNull(index) ?: 0
            if ((flag and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) granted.add(perm)
        }

        val declaresAccessibility = declaresBindingTo(packageManager, packageName, Manifest.permission.BIND_ACCESSIBILITY_SERVICE)
        val declaresNotificationListener = declaresBindingTo(packageManager, packageName, Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE)

        return PermissionAuditResult(
            packageName = packageName,
            declaredPermissions = requested,
            grantedMicrophone = Manifest.permission.RECORD_AUDIO in granted,
            grantedCamera = Manifest.permission.CAMERA in granted,
            grantedFineLocation = Manifest.permission.ACCESS_FINE_LOCATION in granted,
            grantedCoarseLocation = Manifest.permission.ACCESS_COARSE_LOCATION in granted,
            grantedBackgroundLocation = BACKGROUND_LOCATION in granted,
            grantedContacts = Manifest.permission.READ_CONTACTS in granted || Manifest.permission.WRITE_CONTACTS in granted,
            grantedSms = Manifest.permission.READ_SMS in granted || Manifest.permission.SEND_SMS in granted || Manifest.permission.RECEIVE_SMS in granted,
            grantedCallLog = Manifest.permission.READ_CALL_LOG in granted || Manifest.permission.WRITE_CALL_LOG in granted,
            grantedPhoneState = Manifest.permission.READ_PHONE_STATE in granted || READ_PHONE_NUMBERS in granted,
            grantedBodySensors = Manifest.permission.BODY_SENSORS in granted,
            declaresOverlay = Manifest.permission.SYSTEM_ALERT_WINDOW in requested,
            declaresManageExternalStorage = MANAGE_EXTERNAL_STORAGE in requested,
            declaresNotificationListener = declaresNotificationListener,
            notificationListenerEnabled = null, // Filled by caller from NotificationAccessInspector.
            declaresAccessibilityService = declaresAccessibility,
            accessibilityServiceEnabled = null, // Filled by caller from AccessibilityInspector.
            declaresUsageStatsAccess = PACKAGE_USAGE_STATS in requested,
        )
    }

    /**
     * Iterates this package's declared services and checks whether any of them
     * gates on [bindingPermission] (e.g. `BIND_ACCESSIBILITY_SERVICE`). We need
     * this instead of a simple `requestedPermissions` scan because bind-style
     * permissions appear on `ServiceInfo.permission`, not in the `<uses-permission>`
     * list.
     */
    private fun declaresBindingTo(
        packageManager: PackageManager,
        packageName: String,
        bindingPermission: String,
    ): Boolean {
        val info = fetchPackageServicesInfo(packageManager, packageName) ?: return false
        return info.services?.any { it.permission == bindingPermission } == true
    }

    private fun fetchPackageInfo(packageManager: PackageManager, packageName: String): PackageInfo? = try {
        val flags = PackageManager.GET_PERMISSIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, flags)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun fetchPackageServicesInfo(packageManager: PackageManager, packageName: String): PackageInfo? = try {
        val flags = PackageManager.GET_SERVICES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, flags)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    // These constants are API-gated string literals; hard-coding avoids a min-SDK bump just to
    // reference them symbolically.
    private const val BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"
    private const val READ_PHONE_NUMBERS = "android.permission.READ_PHONE_NUMBERS"
    private const val MANAGE_EXTERNAL_STORAGE = "android.permission.MANAGE_EXTERNAL_STORAGE"
    private const val PACKAGE_USAGE_STATS = "android.permission.PACKAGE_USAGE_STATS"
}
