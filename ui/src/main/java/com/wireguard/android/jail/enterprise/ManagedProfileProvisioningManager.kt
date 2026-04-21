/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wireguard.android.jail.model.WorkProfileState
import com.wireguard.android.jail.system.ManagedProfileDetector

/**
 * Handles capability checks and intent construction for managed profile provisioning.
 */
class ManagedProfileProvisioningManager(private val context: Context) {
    private val detector = ManagedProfileDetector(context)
    private val adminComponent = ComponentName(context, JailDeviceAdminReceiver::class.java)

    fun snapshot(): ProvisioningSnapshot {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && dpm != null
        if (!supported || dpm == null) {
            return ProvisioningSnapshot(
                isProvisioningSupported = false,
                isProvisioningAllowed = false,
                profileReady = detector.hasSecondaryProfile(),
                profileState = detector.detectState(),
            )
        }

        val isAllowed = runCatching {
            dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
        }.getOrDefault(false)

        return ProvisioningSnapshot(
            isProvisioningSupported = true,
            isProvisioningAllowed = isAllowed,
            profileReady = detector.hasSecondaryProfile(),
            profileState = detector.detectState(),
        )
    }

    fun createProvisioningIntent(): Intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
        putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, adminComponent)
    }

    data class ProvisioningSnapshot(
        val isProvisioningSupported: Boolean,
        val isProvisioningAllowed: Boolean,
        val profileReady: Boolean,
        val profileState: WorkProfileState,
    )
}
