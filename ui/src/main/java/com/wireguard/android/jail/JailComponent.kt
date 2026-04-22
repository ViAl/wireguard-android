/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail

import android.content.Context
import com.wireguard.android.jail.domain.AppAuditManager
import com.wireguard.android.jail.domain.JailAppClassifier
import com.wireguard.android.jail.domain.JailAppRepository
import com.wireguard.android.jail.domain.JailAuditRepository
import com.wireguard.android.jail.domain.PerAppVpnManager
import com.wireguard.android.jail.domain.WorkProfileManager
import com.wireguard.android.jail.enterprise.ManagedProfileOwnershipService
import com.wireguard.android.jail.enterprise.WorkProfileAppCatalogService
import com.wireguard.android.jail.enterprise.WorkProfileAppInstallCapabilityChecker
import com.wireguard.android.jail.enterprise.WorkProfileAppInstallService
import com.wireguard.android.jail.enterprise.WorkProfileInstallSessionManager
import com.wireguard.android.jail.storage.JailSelectionStore
import com.wireguard.android.jail.system.AccessibilityInspector
import com.wireguard.android.jail.system.CrossProfileAppsWrapper
import com.wireguard.android.jail.system.NotificationAccessInspector
import com.wireguard.android.jail.system.PowerManagerWrapper
import com.wireguard.android.model.TunnelManager
import kotlinx.coroutines.CoroutineScope

/**
 * Process-scoped composition root for the Jail feature.
 */
class JailComponent(
    context: Context,
    scope: CoroutineScope,
    tunnelManager: TunnelManager,
) {
    private val appContext = context.applicationContext
    val crossProfileApps: CrossProfileAppsWrapper = CrossProfileAppsWrapper(appContext)
    val classifier: JailAppClassifier = JailAppClassifier(crossProfileApps)
    val selectionStore: JailSelectionStore = JailSelectionStore(scope)
    val appRepository: JailAppRepository = JailAppRepository(selectionStore, classifier)
    val workProfileManager: WorkProfileManager = WorkProfileManager(appContext)
    val managedProfileOwnershipService: ManagedProfileOwnershipService = ManagedProfileOwnershipService(appContext)
    val workProfileCapabilityChecker: WorkProfileAppInstallCapabilityChecker = WorkProfileAppInstallCapabilityChecker(
        context = appContext,
        ownershipService = managedProfileOwnershipService,
    )
    val workProfileCatalogService: WorkProfileAppCatalogService =
        WorkProfileAppCatalogService(workProfileCapabilityChecker)
    val workProfileInstallService: WorkProfileAppInstallService = WorkProfileAppInstallService(
        context = appContext,
        capabilityChecker = workProfileCapabilityChecker,
        fallbackLauncher = workProfileCapabilityChecker.fallbackLauncher(),
    )
    val workProfileInstallSessionManager: WorkProfileInstallSessionManager = WorkProfileInstallSessionManager(
        installService = workProfileInstallService,
        capabilityChecker = workProfileCapabilityChecker,
    )


    private val accessibilityInspector: AccessibilityInspector = AccessibilityInspector(appContext.contentResolver)
    private val notificationInspector: NotificationAccessInspector = NotificationAccessInspector(appContext)
    val powerManager: PowerManagerWrapper = PowerManagerWrapper(appContext)

    val appAuditManager: AppAuditManager = AppAuditManager(
        accessibilityInspector = accessibilityInspector,
        notificationInspector = notificationInspector,
        powerManager = powerManager,
        crossProfile = crossProfileApps,
    )
    val auditRepository: JailAuditRepository = JailAuditRepository(
        auditManager = appAuditManager,
        selectionStore = selectionStore,
        scope = scope,
    )
    val perAppVpnManager: PerAppVpnManager = PerAppVpnManager(tunnelManager)
}
