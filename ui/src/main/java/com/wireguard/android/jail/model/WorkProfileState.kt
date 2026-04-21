/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

/** Coarse device capability / presence signal for work-profile guidance. */
enum class WorkProfileState {
    NO_SECONDARY_PROFILE,
    SECONDARY_PROFILE_PRESENT,
    MANAGED_PROFILE_CONFIRMED,
    MANAGED_PROFILE_UNCERTAIN,
    UNSUPPORTED,
}
