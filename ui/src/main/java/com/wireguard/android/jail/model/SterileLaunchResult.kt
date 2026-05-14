/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

/** Outcome of attempting a profile-aware launch (never claims a sandbox). */
sealed class SterileLaunchResult {
    data class Launched(val viaWorkProfile: Boolean) : SterileLaunchResult()
    data class NeedsUserAction(val message: String) : SterileLaunchResult()
    data class Failed(val message: String) : SterileLaunchResult()
}
