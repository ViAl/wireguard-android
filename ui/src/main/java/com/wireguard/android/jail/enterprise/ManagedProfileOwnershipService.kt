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
 *
 * This service intentionally separates "secondary profile present" from "managed profile
 * confirmed" to avoid overclaiming enterprise state.
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
        if (profileState == WorkProfileState.UNSUPPORTED) return ManagedProfileOwnershipState.UNSUPPORTED
        if (profileState == WorkProfileState.NO_SECONDARY_PROFILE) return ManagedProfileOwnershipState.NO_MANAGED_PROFILE

        val profileOwner = runCatching { systemApi.isProfileOwnerApp(appPackageName) }.getOrNull()
            ?: return ManagedProfileOwnershipState.OWNERSHIP_UNCERTAIN

        return when {
            profileOwner -> ManagedProfileOwnershipState.MANAGED_PROFILE_OURS
            profileState == WorkProfileState.MANAGED_PROFILE_CONFIRMED ->
                ManagedProfileOwnershipState.MANAGED_PROFILE_PRESENT_NOT_OURS
            profileState == WorkProfileState.SECONDARY_PROFILE_PRESENT ->
                ManagedProfileOwnershipState.SECONDARY_PROFILE_PRESENT_NOT_OURS
            profileState == WorkProfileState.MANAGED_PROFILE_UNCERTAIN ->
                ManagedProfileOwnershipState.OWNERSHIP_UNCERTAIN
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
