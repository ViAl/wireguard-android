/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.system

import android.app.NotificationManager
import android.content.Context

/**
 * Reads the set of packages that are allowed to receive notifications as a
 * notification listener. Uses [NotificationManager.getEnabledListenerPackages],
 * which does not require any additional runtime permission and is stable since
 * API 21.
 *
 * Returning `false` here for a package that *declares* a listener service is
 * meaningful — it means the service exists but the user has not enabled it — so
 * we still score the manifest-only case via
 * [com.wireguard.android.jail.model.AuditSignal.NOTIFICATION_LISTENER_DECLARED].
 */
open class NotificationAccessInspector(context: Context) {
    private val notificationManager: NotificationManager? =
        context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    open fun isListenerEnabled(packageName: String): Boolean? {
        val nm = notificationManager ?: return null
        return try {
            nm.enabledListenerPackages?.contains(packageName) == true
        } catch (_: Exception) {
            null
        }
    }
}
