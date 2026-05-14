/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.domain

import android.content.Context
import com.wireguard.android.jail.model.WorkProfileState
import com.wireguard.android.jail.system.ManagedProfileDetector

/**
 * Derives coarse work-profile capability from [UserManager]. Does not provision profiles — only
 * observes what Android exposes.
 */
class WorkProfileManager(private val context: Context) {
    private val detector = ManagedProfileDetector(context)

    fun currentState(): WorkProfileState = detector.detectState()
}
