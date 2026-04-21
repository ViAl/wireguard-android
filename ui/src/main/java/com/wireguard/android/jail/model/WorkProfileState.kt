/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

/**
 * Coarse capability/presence signal used by profile guidance.
 *
 * IMPORTANT: [MANAGED_PROFILE_CONFIRMED] means Android confirmed that the **current**
 * user/profile is managed (via `UserManager.isManagedProfile`). It does not by itself prove
 * that some *other* detected profile is managed.
 */
enum class WorkProfileState {
    NO_SECONDARY_PROFILE,
    SECONDARY_PROFILE_PRESENT,
    MANAGED_PROFILE_CONFIRMED,
    MANAGED_PROFILE_UNCERTAIN,
    UNSUPPORTED,
}
