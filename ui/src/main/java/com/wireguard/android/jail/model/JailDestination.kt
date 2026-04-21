/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

import androidx.annotation.StringRes
import com.wireguard.android.R

/**
 * Top-level destinations reachable inside the Jail tab. Keeping them in a single enum lets
 * [com.wireguard.android.jail.ui.JailNavigationController] and the top tab bar stay in sync
 * without duplicating string-tag constants.
 */
enum class JailDestination(val tag: String, @StringRes val titleRes: Int) {
    OVERVIEW("jail_overview", R.string.jail_nav_overview),
    SETUP("jail_setup", R.string.jail_nav_setup),
    APPS("jail_apps", R.string.jail_nav_apps),
    LAUNCH("jail_launch", R.string.jail_nav_launch),
    REPORT("jail_report", R.string.jail_nav_report);

    companion object {
        fun fromPosition(position: Int): JailDestination = entries.getOrElse(position) { OVERVIEW }

        fun fromTag(tag: String?): JailDestination? = entries.firstOrNull { it.tag == tag }
    }
}
