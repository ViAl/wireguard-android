/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.shuttle

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.UserHandle
import android.util.Log

/**
 * Clones an app from the parent profile into the work profile.
 * Uses multiple strategies, falling back as needed.
 */
object WorkProfileCloner {

    private const val TAG = "WG.WorkProfileCloner"

    // Result codes (mirroring IslandAppClones)
    const val RESULT_ALREADY_CLONED = 0
    const val RESULT_OK_INSTALL = 1
    const val RESULT_OK_INSTALL_EXISTING = 2
    const val RESULT_OK_GOOGLE_PLAY = 10
    const val RESULT_UNKNOWN_SYS_MARKET = 11
    const val RESULT_NO_SYS_MARKET = -1

    /**
     * Clone [packageName] into [targetProfile].
     * Should be called from the parent profile context.
     *
     * @return one of the RESULT_* constants
     */
    fun clone(context: Context, packageName: String,
              appInfo: ApplicationInfo?,
              targetProfile: UserHandle): Int {
        Log.i(TAG, "clone: pkg=$packageName target=$targetProfile")

        // Strategy 0: DPC install (same profile owner, same process — no shuttle needed)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dpm != null) {
            val adminComponent = android.content.ComponentName(
                context,
                try {
                    Class.forName("com.wireguard.android.jail.enterprise.JailDeviceAdminReceiver")
                } catch (e: ClassNotFoundException) {
                    Log.e(TAG, "JailDeviceAdminReceiver class not found", e)
                    null
                }
            )
            if (adminComponent != null) {
                try {
                    if (dpm.installExistingPackage(adminComponent, packageName)) {
                        Log.i(TAG, "DPC installExistingPackage succeeded for $packageName")
                        return RESULT_OK_INSTALL_EXISTING
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "DPC installExistingPackage failed", e)
                }
            }
        }

        // Strategy 1: Shuttle to work profile — run install logic there
        Log.i(TAG, "Strategy 1: Shuttle to work profile")
        val shuttle = Shuttle(context, targetProfile)
        return try {
            shuttle.invoke(with = appInfo) { info ->
                performCloneInProfile(this, packageName, info)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shuttle clone failed", e)
            RESULT_NO_SYS_MARKET
        }
    }

    /**
     * Runs inside the work profile. Handles installation.
     */
    private fun performCloneInProfile(context: Context,
                                       packageName: String,
                                       appInfo: ApplicationInfo?): Int {
        // Strategy 1a: installExistingPackage via DPM (if affiliated)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            if (dpm != null && dpm.isAffiliatedUser) {
                // Use reflection to call installExistingPackage
                try {
                    val adminComponent = android.content.ComponentName(
                        context,
                        try {
                            Class.forName("com.wireguard.android.jail.enterprise.JailDeviceAdminReceiver")
                        } catch (e: ClassNotFoundException) {
                            Log.e(TAG, "JailDeviceAdminReceiver class not found", e)
                            null
                        }
                    )
                    if (adminComponent != null) {
                        val method = DevicePolicyManager::class.java.getMethod(
                            "installExistingPackage",
                            android.content.ComponentName::class.java,
                            String::class.java
                        )
                        val result = method.invoke(dpm, adminComponent, packageName) as? Boolean
                        if (result == true) {
                            context.sendBroadcast(
                                Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                                    setPackage(context.packageName)
                                }
                            )
                            return RESULT_OK_INSTALL_EXISTING
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "installExistingPackage via reflection failed", e)
                }
            }
        }

        // Strategy 1b: Direct install (ACTION_INSTALL_PACKAGE)
        try {
            val installIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    data = Uri.parse("package:$packageName")
                    putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
                    addFlags(FLAG_ACTIVITY_NEW_TASK)
                    if (appInfo != null) {
                        putExtra("android.content.pm.extra.APP_INFO", appInfo)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                Intent(Intent.ACTION_INSTALL_PACKAGE,
                       Uri.fromParts("package", packageName, null)).apply {
                    putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
                    addFlags(FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(installIntent)
            return RESULT_OK_INSTALL
        } catch (e: Exception) {
            Log.e(TAG, "ACTION_INSTALL_PACKAGE failed", e)
        }

        // Strategy 1c: Market fallback
        return try {
            val marketIntent = Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=$packageName")).apply {
                addFlags(FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(marketIntent)
            RESULT_OK_GOOGLE_PLAY
        } catch (e: Exception) {
            Log.e(TAG, "Market fallback failed", e)
            RESULT_NO_SYS_MARKET
        }
    }
}
