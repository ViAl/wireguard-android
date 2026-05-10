/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Policy Controller receiver for managed-profile provisioning.
 *
 * After provisioning completes, this receiver activates the DPC and records
 * provisioning success in the app's logs and shared preferences so that
 * [DpcPackageInstaller] can detect profile ownership and use installExistingPackage.
 */
class JailDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        val admin = ComponentName(context, JailDeviceAdminReceiver::class.java)
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return

        runCatching {
            dpm.setProfileName(admin, PROFILE_NAME)
            dpm.setProfileEnabled(admin)
            Log.i(TAG, "Profile provisioning complete: name=$PROFILE_NAME")
            WorkProfileLogger.d("JailDeviceAdminReceiver: provisioning complete, profile=$PROFILE_NAME")

            // Record provisioning success
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_PROVISIONED, true).apply()
            WorkProfileLogger.d("JailDeviceAdminReceiver: provisioning recorded in prefs")
        }.onFailure {
            Log.w(TAG, "Failed to finish profile provisioning", it)
            WorkProfileLogger.e("JailDeviceAdminReceiver: provisioning failed", it)
        }
    }

    /**
     * After provisioning, the DPC is the profile owner and can install packages.
     */
    fun isProvisioned(context: Context): Boolean {
        val isOwner = runCatching {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            dpm?.isProfileOwnerApp(context.packageName) == true
        }.getOrDefault(false)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefsFlag = prefs.getBoolean(KEY_PROVISIONED, false)
        WorkProfileLogger.d("JailDeviceAdminReceiver: isProvisioned: isOwner=$isOwner, prefsFlag=$prefsFlag")
        return isOwner
    }

    companion object {
        private const val TAG = "JailDeviceAdmin"
        private const val PROFILE_NAME = "Jail"
        const val PREFS_NAME = "jail_dpc_state"
        const val KEY_PROVISIONED = "profile_provisioned"
    }
}
