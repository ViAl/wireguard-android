/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import com.wireguard.android.jail.model.WorkProfileAppCatalogEntry

class WorkProfileAppCatalogService(
    private val capabilityChecker: WorkProfileAppInstallCapabilityChecker,
) {
    fun buildCatalog(packageNames: Collection<String>): List<WorkProfileAppCatalogEntry> =
        packageNames.distinct().sorted().map { packageName ->
            val capability = capabilityChecker.capabilityFor(packageName)
            WorkProfileAppCatalogEntry(
                packageName = packageName,
                label = capability.label,
                installedInParentProfile = capability.installedInParentProfile,
                installedInWorkProfile = capability.installedInWorkProfile,
                availability = capability.availability,
                action = capability.action,
                environmentReason = capability.environment.environmentReason,
                actionReason = capability.reason,
            )
        }
}
