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
import com.wireguard.android.jail.storage.JailSelectionStore
import com.wireguard.android.jail.system.AccessibilityInspector
import com.wireguard.android.jail.system.CrossProfileAppsWrapper
import com.wireguard.android.jail.system.NotificationAccessInspector
import com.wireguard.android.jail.system.PowerManagerWrapper
import kotlinx.coroutines.CoroutineScope

/**
 * Process-scoped composition root for the Jail feature.
 *
 * Held by [com.wireguard.android.Application] and surfaced as a single handle so Fragments
 * can ask for exactly what they need rather than reaching into a growing static surface.
 * Keeping the wiring here makes it obvious which collaborators depend on each other and lets
 * tests substitute seams ([CrossProfileAppsWrapper], inspectors) at a single point.
 */
class JailComponent(context: Context, scope: CoroutineScope) {
    private val appContext = context.applicationContext
    val crossProfileApps: CrossProfileAppsWrapper = CrossProfileAppsWrapper(appContext)
    val classifier: JailAppClassifier = JailAppClassifier(crossProfileApps)
    val selectionStore: JailSelectionStore = JailSelectionStore(scope)
    val appRepository: JailAppRepository = JailAppRepository(selectionStore, classifier)

    private val accessibilityInspector: AccessibilityInspector = AccessibilityInspector(appContext.contentResolver)
    private val notificationInspector: NotificationAccessInspector = NotificationAccessInspector(appContext)
    private val powerManager: PowerManagerWrapper = PowerManagerWrapper(appContext)
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
}
