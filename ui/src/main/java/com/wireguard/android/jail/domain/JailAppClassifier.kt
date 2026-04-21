/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.domain

import com.wireguard.android.jail.model.AuditRiskLevel
import com.wireguard.android.jail.model.AuditSnapshot
import com.wireguard.android.jail.model.JailAppBadge
import com.wireguard.android.jail.model.JailAppInfo
import com.wireguard.android.jail.system.CrossProfileAppsWrapper

/**
 * Pure function over [JailAppInfo] and (optionally) an [AuditSnapshot] that decides which
 * small badges are rendered next to an app in the Jail Apps list.
 *
 * Ordering rule: dangerous-signal badges come first (`HIGH_RISK`) so the list stays skimmable,
 * followed by selection / profile state, then `SYSTEM_APP` as a trailing informational tag.
 */
class JailAppClassifier(private val crossProfile: CrossProfileAppsWrapper) {
    fun badgesFor(app: JailAppInfo, audit: AuditSnapshot? = null): List<JailAppBadge> {
        val badges = mutableListOf<JailAppBadge>()
        // Risk badge leads so a user scanning the list notices severe findings without opening detail.
        if (audit != null && (audit.score.level == AuditRiskLevel.HIGH || audit.score.level == AuditRiskLevel.CRITICAL))
            badges += JailAppBadge.HIGH_RISK
        if (app.isSelectedForJail) badges += JailAppBadge.SELECTED
        when (app.installedInWorkProfile) {
            true -> badges += JailAppBadge.WORK_PROFILE_PRESENT
            false -> if (crossProfile.hasSecondaryProfile()) badges += JailAppBadge.WORK_PROFILE_MISSING
            null -> Unit // Unknown — show nothing rather than guessing presence.
        }
        if (app.installedInWorkProfile == null && !crossProfile.hasSecondaryProfile())
            badges += JailAppBadge.MAIN_PROFILE_ONLY
        if (app.isSystemApp) badges += JailAppBadge.SYSTEM_APP
        return badges
    }
}
