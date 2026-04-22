/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

data class WorkProfileInstallEnvironment(
    val ownershipState: ManagedProfileOwnershipState,
    val installedInParentProfile: Boolean,
    val installedInWorkProfile: Boolean,
    val installExistingPackageApiAvailable: Boolean,
    val manualStoreFallbackResolvable: Boolean,
    val hasTargetUserProfiles: Boolean,
    val autoInstallAllowedByEnvironment: Boolean,
    val environmentReason: WorkProfileInstallEnvironmentReason,
)
