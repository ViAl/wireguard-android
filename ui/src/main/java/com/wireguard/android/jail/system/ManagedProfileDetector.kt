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
 *  * "managed profile uncertain" is used for hints that suggest profile isolation, without certainty.
 */
class ManagedProfileDetector(private val context: Context) {

    fun detectState(): WorkProfileState {
        val um = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return WorkProfileState.UNSUPPORTED
        return try {
            if (!UserManager.supportsMultipleUsers()) return WorkProfileState.UNSUPPORTED

            val profiles = um.userProfiles.orEmpty()
            val hasSecondary = profiles.any { it != Process.myUserHandle() }
            if (!hasSecondary) return WorkProfileState.NO_SECONDARY_PROFILE

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && um.isManagedProfile) {
                return WorkProfileState.MANAGED_PROFILE_CONFIRMED
            }

            if (crossProfileTargetsAvailable()) {
                WorkProfileState.MANAGED_PROFILE_UNCERTAIN
            } else {
                WorkProfileState.SECONDARY_PROFILE_PRESENT
            }
        } catch (_: Throwable) {
            WorkProfileState.UNSUPPORTED
        }
    }

    fun hasSecondaryProfile(): Boolean = when (detectState()) {
        WorkProfileState.NO_SECONDARY_PROFILE, WorkProfileState.UNSUPPORTED -> false
        else -> true
    }

    private fun crossProfileTargetsAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val crossProfileApps = context.getSystemService(CrossProfileApps::class.java) ?: return false
        return runCatching { crossProfileApps.targetUserProfiles.isNotEmpty() }.getOrDefault(false)
    }
}
