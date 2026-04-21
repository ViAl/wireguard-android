/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.system

import android.content.Context

/**
 * Thin seam over [android.content.pm.CrossProfileApps] and [android.os.UserManager] so the
 * rest of the Jail code can be tested with a deterministic fake.
 *
 * Phase 2 deliberately ships with a conservative implementation that reports
 * "no managed profile detected". Real work-profile detection lands in Phase 7 alongside
 * the setup wizard; Jail must not guess about the existence of a managed profile before that
 * feature can actually guide the user through setting one up.
 */
open class CrossProfileAppsWrapper(private val context: Context) {
    /** @return `true` when a managed profile is known to exist on this device. */
    open fun hasManagedProfile(): Boolean = false

    /**
     * @return `true` if [packageName] has a confirmed install in the managed/work profile,
     *  `false` if the managed profile is known and the app is absent, `null` if detection is
     *  not yet implemented or unavailable.
     */
    open fun isInstalledInWorkProfile(packageName: String): Boolean? = null
}
