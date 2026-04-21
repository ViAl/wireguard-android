/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.app.admin.DevicePolicyManager
import android.content.Context
import com.wireguard.android.jail.model.ManagedProfileOwnershipState
import com.wireguard.android.jail.model.WorkProfileState
import com.wireguard.android.jail.system.ManagedProfileDetector

/**
 * Ownership/environment checks for managed profile operations.
 */
class ManagedProfileOwnershipService(
    context: Context,
    private val detector: ManagedProfileDetector = ManagedProfileDetector(context),
    private val systemApi: OwnershipSystemApi = AndroidOwnershipSystemApi(context),
) : ManagedProfileOwnershipStateProvider {
    private val appPackageName = context.packageName

    override fun state(): ManagedProfileOwnershipState {
        val profileState = runCatching { detector.detectState() }
            .getOrElse { return ManagedProfileOwnershipState.OWNERSHIP_UNCERTAIN }
        val secondaryExists = profileState != WorkProfileState.NO_SECONDARY_PROFILE &&
            profileState != WorkProfileState.UNSUPPORTED

        if (profileState == WorkProfileState.UNSUPPORTED) return ManagedProfileOwnershipState.UNSUPPORTED
        if (!secondaryExists) return ManagedProfileOwnershipState.NO_MANAGED_PROFILE

        val profileOwner = runCatching { systemApi.isProfileOwnerApp(appPackageName) }.getOrNull()
            ?: return ManagedProfileOwnershipState.OWNERSHIP_UNCERTAIN

        return when {
            profileOwner -> ManagedProfileOwnershipState.MANAGED_PROFILE_OURS
            profileState == WorkProfileState.MANAGED_PROFILE_CONFIRMED ||
                profileState == WorkProfileState.MANAGED_PROFILE_UNCERTAIN ||
                profileState == WorkProfileState.SECONDARY_PROFILE_PRESENT ->
                ManagedProfileOwnershipState.MANAGED_PROFILE_PRESENT_NOT_OURS
            else -> ManagedProfileOwnershipState.OWNERSHIP_UNCERTAIN
        }
    }

    interface OwnershipSystemApi {
        fun isProfileOwnerApp(packageName: String): Boolean
    }

    private class AndroidOwnershipSystemApi(context: Context) : OwnershipSystemApi {
        private val dpm = context.getSystemService(DevicePolicyManager::class.java)

        override fun isProfileOwnerApp(packageName: String): Boolean =
            dpm?.isProfileOwnerApp(packageName) == true
    }
}

interface ManagedProfileOwnershipStateProvider {
    fun state(): ManagedProfileOwnershipState
}
