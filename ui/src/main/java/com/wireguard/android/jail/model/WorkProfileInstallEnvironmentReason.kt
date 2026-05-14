/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

enum class WorkProfileInstallEnvironmentReason {
    ALREADY_INSTALLED_IN_WORK,
    PROFILE_OWNER_CONFIRMED,
    MANAGED_PROFILE_NOT_OURS,
    SECONDARY_PROFILE_PRESENT_ONLY,
    OWNERSHIP_UNCERTAIN,
    NO_MANAGED_PROFILE,
    API_LEVEL_UNSUPPORTED,
    PARENT_PACKAGE_MISSING,
    MANUAL_FALLBACK_ONLY,
    NO_FALLBACK_AVAILABLE,
    UNKNOWN,
}
