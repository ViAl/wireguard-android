/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.domain

import android.content.Context
import android.os.Process
import android.os.UserManager
import com.wireguard.android.jail.model.WorkProfileState

/**
 * Derives coarse work-profile capability from [UserManager]. Does not provision profiles — only
 * observes what Android exposes.
 */
class WorkProfileManager(private val context: Context) {

    fun currentState(): WorkProfileState {
        val um = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return WorkProfileState.UNKNOWN
        return try {
            when {
                !UserManager.supportsMultipleUsers() -> WorkProfileState.UNSUPPORTED
                um.userProfiles?.size ?: 0 <= 1 -> WorkProfileState.NONE_DETECTED
                um.userProfiles!!.any { it != Process.myUserHandle() } -> WorkProfileState.PROFILE_DETECTED
                else -> WorkProfileState.NONE_DETECTED
            }
        } catch (_: Throwable) {
            WorkProfileState.UNKNOWN
        }
    }
}
