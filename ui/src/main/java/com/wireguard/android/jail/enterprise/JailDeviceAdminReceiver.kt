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
 * Minimal Device Policy Controller receiver for managed-profile provisioning.
 */
class JailDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        val admin = ComponentName(context, JailDeviceAdminReceiver::class.java)
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        runCatching {
            dpm.setProfileName(admin, PROFILE_NAME)
            dpm.setProfileEnabled(admin)
        }.onFailure {
            Log.w(TAG, "Failed to finish profile provisioning", it)
        }
    }

    companion object {
        private const val TAG = "JailDeviceAdmin"
        private const val PROFILE_NAME = "Jail"
    }
}
