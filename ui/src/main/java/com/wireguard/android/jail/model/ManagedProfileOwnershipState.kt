/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

/**
 * Honest ownership signal for enterprise/work-profile operations.
 */
enum class ManagedProfileOwnershipState {
    NO_MANAGED_PROFILE,
    MANAGED_PROFILE_PRESENT_NOT_OURS,
    MANAGED_PROFILE_OURS,
    OWNERSHIP_UNCERTAIN,
    UNSUPPORTED,
}
