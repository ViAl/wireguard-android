/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

/**
 * User-editable checklist defaults for Sterile Launch. Stored as JSON per package in
 * [com.wireguard.android.jail.storage.JailStore].
 */
data class SterileLaunchPreset(
    val packageName: String,
    val requiredProfile: LaunchProfile,
    val requiredTunnelMode: JailTunnelMode,
    val warnLocationEnabled: Boolean,
    val warnBluetoothEnabled: Boolean,
    val warnClearClipboard: Boolean,
    val warnIfNoWorkProfileCopy: Boolean,
    val warnIfRiskyPermissions: Boolean,
) {
    companion object {
        fun defaultFor(packageName: String) = SterileLaunchPreset(
            packageName = packageName,
            requiredProfile = LaunchProfile.ANY,
            requiredTunnelMode = JailTunnelMode.DEFAULT,
            warnLocationEnabled = true,
            warnBluetoothEnabled = false,
            warnClearClipboard = false,
            warnIfNoWorkProfileCopy = true,
            warnIfRiskyPermissions = true,
        )
    }
}
