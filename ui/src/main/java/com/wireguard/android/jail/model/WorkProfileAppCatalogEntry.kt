/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

data class WorkProfileAppCatalogEntry(
    val packageName: String,
    val label: String?,
    val installedInParentProfile: Boolean,
    val installedInWorkProfile: Boolean,
    val availability: WorkProfileAppAvailability,
    val action: WorkProfileAppAction,
    val actionReason: String? = null,
)

data class WorkProfileAppInstallCapability(
    val label: String?,
    val ownershipState: ManagedProfileOwnershipState,
    val installedInParentProfile: Boolean,
    val installedInWorkProfile: Boolean,
    val canInstallAutomatically: Boolean,
    val canLaunchManualFallback: Boolean,
    val availability: WorkProfileAppAvailability,
    val action: WorkProfileAppAction,
    val reason: String? = null,
)
