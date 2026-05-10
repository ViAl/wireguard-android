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
 *
 * Strategy sequence:
 * 0. DPC installExistingPackage (executed from parent profile — fastest path)
 * 1. Shuttle into work profile and run [performAppCloningInProfile] which:
 *    a. Tries DPC installExistingPackage from within the profile
 *    b. Opens Play Store via market:// intent inside the work profile
 *    c. Falls back to ACTION_INSTALL_PACKAGE inside the work profile
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

        // Bootstrap the work profile process first, so ShuttleProvider.onCreate()
        // fires and sends the URI grant back to parent before we try to use Shuttle.
        ShuttleCarrierActivity.bootstrap(context, targetProfile)
        // Give the provider a moment to initialize
        try {
            Thread.sleep(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // Strategy 0: DPC install (profile owner — can install directly from parent)
        val dpcResult = tryDpcInstallFromParent(context, packageName)
        if (dpcResult != null) return dpcResult

        // Strategy 1: Shuttle into work profile and execute performAppCloningInProfile
        // inside the target profile. This allows startActivity() to open Play Store
        // within the work profile, similar to how Island does it.
        Log.i(TAG, "Strategy 1: Shuttle into work profile")
        return try {
            val shuttleResult = Shuttle(context, to = targetProfile)
                .invoke(with = packageName) { pkg ->
                    performAppCloningInProfile(this, pkg)
                }
            Log.i(TAG, "Shuttle result=$shuttleResult")
            shuttleResult
        } catch (e: Exception) {
            Log.e(TAG, "Shuttle invoke failed", e)
            RESULT_NO_SYS_MARKET
        }
    }

    /**
     * Try DPC installExistingPackage from the parent profile.
     *
     * @return a positive result code if successful, or null to continue to next strategy
     */
    private fun tryDpcInstallFromParent(context: Context, packageName: String): Int? {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dpm != null) {
            val adminComponent = resolveAdminComponent(context) ?: return null
            try {
                if (dpm.installExistingPackage(adminComponent, packageName)) {
                    Log.i(TAG, "DPC installExistingPackage succeeded for $packageName")
                    return RESULT_OK_INSTALL_EXISTING
                } else {
                    Log.d(TAG, "DPC installExistingPackage returned false for $packageName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "DPC installExistingPackage failed", e)
            }
        }
        return null
    }

    /**
     * Execute app cloning logic inside the work profile context.
     *
     * This runs via Shuttle inside the target profile, so all
     * startActivity() calls will open activities within that profile.
     *
     * Tries, in order:
     * 1. DPC installExistingPackage (from inside the work profile)
     * 2. Play Store market:// deep link (opens inside work profile)
     * 3. ACTION_INSTALL_PACKAGE (within work profile)
     */
    @JvmStatic
    private fun performAppCloningInProfile(context: Context, packageName: String): Int {
        Log.i(TAG, "performAppCloningInProfile: pkg=$packageName")

        // Attempt 1: DPC installExistingPackage from inside the profile
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val adminComponent = resolveAdminComponent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dpm != null && adminComponent != null) {
            try {
                if (dpm.installExistingPackage(adminComponent, packageName)) {
                    Log.i(TAG, "DPC installExistingPackage succeeded inside profile for $packageName")
                    return RESULT_OK_INSTALL_EXISTING
                }
            } catch (e: Exception) {
                Log.e(TAG, "DPC installExistingPackage failed inside profile", e)
            }
        }

        // Attempt 2: Open Google Play Store inside the work profile via market:// intent.
        // This is the key fix — similar to how Island opens store in work profile.
        Log.i(TAG, "Attempt 2: market:// intent inside work profile")
        try {
            val marketIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(marketIntent)
            Log.i(TAG, "market:// intent launched successfully inside work profile")
            return RESULT_OK_GOOGLE_PLAY
        } catch (e: Exception) {
            Log.e(TAG, "market:// intent failed inside work profile", e)
        }

        // Attempt 3: ACTION_INSTALL_PACKAGE inside work profile (fallback)
        Log.i(TAG, "Attempt 3: ACTION_INSTALL_PACKAGE inside work profile")
        try {
            val installIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    data = Uri.parse("package:$packageName")
                    putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
            Log.i(TAG, "ACTION_INSTALL_PACKAGE launched inside work profile")
            return RESULT_OK_INSTALL
        } catch (e: Exception) {
            Log.e(TAG, "ACTION_INSTALL_PACKAGE failed inside work profile", e)
        }

        Log.d(TAG, "All strategies exhausted inside work profile for $packageName")
        return RESULT_NO_SYS_MARKET
    }

    /**
     * Resolve the DeviceAdminReceiver ComponentName.
     */
    private fun resolveAdminComponent(context: Context): ComponentName? {
        return try {
            ComponentName(
                context,
                Class.forName("com.wireguard.android.jail.enterprise.JailDeviceAdminReceiver")
            )
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "JailDeviceAdminReceiver class not found", e)
            null
        }
    }
}
