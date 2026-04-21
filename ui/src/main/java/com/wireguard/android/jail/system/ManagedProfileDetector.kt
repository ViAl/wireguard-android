/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.system

import android.content.Context
import android.content.pm.CrossProfileApps
import android.os.Build
import android.os.Process
import android.os.UserManager
import com.wireguard.android.jail.model.WorkProfileState

/**
 * Best-effort profile detector with conservative semantics:
 *  * "secondary profile present" is factual (another profile handle exists).
 *  * "managed profile confirmed" is only used when Android confirms the current profile is managed.
 *    This is a **current-user** fact, not proof that any secondary profile is managed.
 *  * "managed profile uncertain" is used for hints that suggest profile isolation, without certainty.
 */
open class ManagedProfileDetector(private val context: Context) {

    fun detectState(): WorkProfileState {
        val snapshot = runCatching { readSnapshot() }.getOrElse { return WorkProfileState.UNSUPPORTED }
        if (!snapshot.supportsMultipleUsers) return WorkProfileState.UNSUPPORTED
        if (!snapshot.hasSecondaryProfile) return WorkProfileState.NO_SECONDARY_PROFILE
        if (snapshot.currentProfileManaged) return WorkProfileState.MANAGED_PROFILE_CONFIRMED
        return if (snapshot.hasCrossProfileTargets)
            WorkProfileState.MANAGED_PROFILE_UNCERTAIN
        else
            WorkProfileState.SECONDARY_PROFILE_PRESENT
    }

    fun hasSecondaryProfile(): Boolean = when (detectState()) {
        WorkProfileState.NO_SECONDARY_PROFILE, WorkProfileState.UNSUPPORTED -> false
        else -> true
    }

    internal open fun readSnapshot(): DetectionSnapshot {
        val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
            ?: return DetectionSnapshot(
                supportsMultipleUsers = false,
                hasSecondaryProfile = false,
                currentProfileManaged = false,
                hasCrossProfileTargets = false,
            )

        val profiles = um.userProfiles.orEmpty()
        return DetectionSnapshot(
            supportsMultipleUsers = UserManager.supportsMultipleUsers(),
            hasSecondaryProfile = profiles.any { it != Process.myUserHandle() },
            currentProfileManaged = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && um.isManagedProfile,
            hasCrossProfileTargets = crossProfileTargetsAvailable(),
        )
    }

    private fun crossProfileTargetsAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val crossProfileApps = context.getSystemService(CrossProfileApps::class.java) ?: return false
        return runCatching { crossProfileApps.targetUserProfiles.isNotEmpty() }.getOrDefault(false)
    }

    internal data class DetectionSnapshot(
        val supportsMultipleUsers: Boolean,
        val hasSecondaryProfile: Boolean,
        val currentProfileManaged: Boolean,
        val hasCrossProfileTargets: Boolean,
    )
}
