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
class ManagedProfileProvisioningManager(
    private val context: Context,
    private val detector: ManagedProfileDetector = ManagedProfileDetector(context),
    private val capabilityChecker: ProvisioningCapabilityChecker = DefaultProvisioningCapabilityChecker(context),
) {
    private val adminComponent = ComponentName(context, JailDeviceAdminReceiver::class.java)

    fun snapshot(): ProvisioningSnapshot {
        val profileState = detector.detectState()
        // Conservative assumption for Jail UX: treat "uncertain" as likely present so we do not
        // keep prompting users to provision when Android already exposes cross-profile signals.
        val managedProfileLikelyPresent = profileState == WorkProfileState.MANAGED_PROFILE_CONFIRMED ||
            profileState == WorkProfileState.MANAGED_PROFILE_UNCERTAIN

        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val provisioningSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && dpm != null
        val provisioningAllowed = if (provisioningSupported && dpm != null) {
            runCatching {
                dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
            }.getOrDefault(false)
        } else {
            false
        }

        val provisioningIntent = createProvisioningIntent()
        val provisioningLaunchable = capabilityChecker.isProvisioningIntentLaunchable(provisioningIntent)

        return ProvisioningSnapshot(
            isProvisioningSupported = provisioningSupported,
            isProvisioningAllowed = provisioningAllowed,
            isProvisioningLaunchable = provisioningLaunchable,
            canLaunchProvisioning = provisioningSupported && provisioningAllowed && provisioningLaunchable,
            managedProfileLikelyPresent = managedProfileLikelyPresent,
            profileState = profileState,
        )
    }

    fun createProvisioningIntent(): Intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
        putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, adminComponent)
    }

    data class ProvisioningSnapshot(
        val isProvisioningSupported: Boolean,
        val isProvisioningAllowed: Boolean,
        val isProvisioningLaunchable: Boolean,
        val canLaunchProvisioning: Boolean,
        val managedProfileLikelyPresent: Boolean,
        val profileState: WorkProfileState,
    )

    interface ProvisioningCapabilityChecker {
        fun isProvisioningIntentLaunchable(intent: Intent): Boolean
    }

    private class DefaultProvisioningCapabilityChecker(context: Context) : ProvisioningCapabilityChecker {
        private val packageManager = context.packageManager

        override fun isProvisioningIntentLaunchable(intent: Intent): Boolean =
            intent.resolveActivity(packageManager) != null
    }
}
