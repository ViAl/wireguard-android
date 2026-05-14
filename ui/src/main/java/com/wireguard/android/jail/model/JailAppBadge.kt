/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

import androidx.annotation.StringRes
import com.wireguard.android.R

/**
 * Small visual tags shown under an app's row in the Jail Apps list.
 *
 * Each badge is derived by [com.wireguard.android.jail.domain.JailAppClassifier] from
 * observable system facts (is this app selected? is it a system app?). The
 * [HIGH_RISK] badge is intentionally a placeholder in Phase 2 until the audit engine from
 * Phase 3 lands; the classifier never emits it before that.
 *
 * Profile-installation indicators (Main/Jail chips) are handled separately via
 * [JailAppInfo.installedInMainProfile] and [JailAppInfo.installedInOtherProfile].
 */
enum class JailAppBadge(@StringRes val labelRes: Int) {
    SELECTED(R.string.jail_app_badge_selected),
    HIGH_RISK(R.string.jail_app_badge_high_risk),
    SYSTEM_APP(R.string.jail_app_badge_system)
}
