/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

import android.graphics.drawable.Drawable

/**
 * Immutable snapshot of a single installed app as seen by the Jail feature.
 *
 * [isSelectedForJail] is a snapshot view: the canonical selection set is stored in
 * [com.wireguard.android.jail.storage.JailSelectionStore]. The repository rebuilds the info
 * list whenever the selection or the system's installed apps change, so the UI can treat
 * this class as read-only.
 *
 * [installedInOtherProfile] encodes profile presence without over-claiming profile type:
 *  * `true` — confirmed present in another profile.
 *  * `false` — another profile exists, but the app is not installed there.
 *  * `null` — no secondary profile is exposed (or detection is unsupported).
 *
 * Avoid deriving "risk" here; that belongs to [com.wireguard.android.jail.domain.AppAuditManager]
 * (Phase 3) so callers do not confuse presence signals with risk signals.
 */
data class JailAppInfo(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
    val versionName: String?,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val hasInternetPermission: Boolean,
    val installedInMainProfile: Boolean,
    val installedInOtherProfile: Boolean?,
    val isSelectedForJail: Boolean
)
