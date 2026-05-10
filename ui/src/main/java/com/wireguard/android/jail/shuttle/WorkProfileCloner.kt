/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.shuttle

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.UserHandle
import android.util.Log

/**
 * Clones an app from the parent profile into the work profile.
 * Uses DPC-based install if available; otherwise defers to the caller.
 *
 * IMPORTANT: This does NOT use Shuttle to execute code in the work profile.
 * Shuttle is a separate concern for general cross-profile lambda execution.
 * For app cloning, we use the direct DPC pipeline (installExistingPackage)
 * when possible, and rely on other strategies in the caller (like
 * LauncherApps-backed activity proxy) when DPC is unavailable.
 */
object WorkProfileCloner {

    private const val TAG = "WG.WorkProfileCloner"

    // Result codes
    const val RESULT_ALREADY_CLONED = 0
    const val RESULT_OK_INSTALL = 1
    const val RESULT_OK_INSTALL_EXISTING = 2
    const val RESULT_OK_GOOGLE_PLAY = 10
    const val RESULT_UNKNOWN_SYS_MARKET = 11
    const val RESULT_NO_SYS_MARKET = -1

    /**
     * Clone [packageName] into [targetProfile].
     * Called from the parent profile context.
     *
     * @return one of the RESULT_* constants
     */
    fun clone(context: Context, packageName: String,
              appInfo: ApplicationInfo?,
              targetProfile: UserHandle): Int {
        Log.i(TAG, "clone: pkg=$packageName target=$targetProfile")

        // Strategy 0: DPC install (profile owner — can install directly)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dpm != null) {
            val adminComponent = try {
                ComponentName(
                    context,
                    Class.forName("com.wireguard.android.jail.enterprise.JailDeviceAdminReceiver")
                )
            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "JailDeviceAdminReceiver class not found", e)
                null
            }
            if (adminComponent != null) {
                try {
                    if (dpm.installExistingPackage(adminComponent, packageName)) {
                        Log.i(TAG, "DPC installExistingPackage succeeded for $packageName")
                        return RESULT_OK_INSTALL_EXISTING
                    } else {
                        // installExistingPackage returned false — package may not
                        // exist in parent profile to clone, or already exists
                        Log.d(TAG, "DPC installExistingPackage returned false for $packageName")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "DPC installExistingPackage failed", e)
                }
            }
        }

        // Strategy 1: Direct install via ACTION_INSTALL_PACKAGE in the
        // CURRENT (parent) profile. This prompts the user with the system
        // package installer to install the existing app into the work profile.
        // Note: This runs in the parent profile and may not install into
        // the work profile — it's a fallback.
        Log.i(TAG, "Strategy 1: ACTION_INSTALL_PACKAGE in parent profile")
        try {
            val installIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    data = Uri.parse("package:$packageName")
                    putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (appInfo != null) {
                        putExtra("android.content.pm.extra.APP_INFO", appInfo)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                Intent(Intent.ACTION_INSTALL_PACKAGE,
                       Uri.fromParts("package", packageName, null)).apply {
                    putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(installIntent)
            return RESULT_OK_INSTALL
        } catch (e: Exception) {
            Log.e(TAG, "ACTION_INSTALL_PACKAGE failed", e)
        }

        // No DPC available and ACTION_INSTALL_PACKAGE failed.
        // Return NO_SYS_MARKET to let the caller try other strategies
        // (e.g., LauncherApps-backed proxy activity in work profile).
        Log.d(TAG, "No clone strategy available — deferring to caller")
        return RESULT_NO_SYS_MARKET
    }
}
