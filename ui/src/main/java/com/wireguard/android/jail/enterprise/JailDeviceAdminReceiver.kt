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
        Log.i(TAG, "Profile provisioning complete — running PostProvisioningHandler")
        WorkProfileLogger.d("JailDeviceAdminReceiver: provisioning complete, running PostProvisioningHandler")

        // Delegate to PostProvisioningHandler which handles all post-provisioning
        // steps including profile naming, enabling, cross-profile intent filters,
        // and URI permission grants.
        PostProvisioningHandler.run(context)
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
