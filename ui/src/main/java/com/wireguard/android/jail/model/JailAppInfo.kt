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
 * [installedInWorkProfile] encodes three states honestly:
 *  * `true` — confirmed present in a managed/work profile.
 *  * `false` — the device exposes a managed profile but the app is not installed there.
 *  * `null` — the device has no managed profile (or detection is unsupported on this SDK).
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
    val installedInWorkProfile: Boolean?,
    val isSelectedForJail: Boolean
)
