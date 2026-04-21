/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.system

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Reads whether an app package is enabled as a notification listener. API 34+ exposes
 * [NotificationManager.getEnabledListenerPackages]; older releases parse
 * [Settings.Secure.ENABLED_NOTIFICATION_LISTENERS].
 *
 * Returning `false` here for a package that *declares* a listener service is
 * meaningful — it means the service exists but the user has not enabled it — so
 * we still score the manifest-only case via
 * [com.wireguard.android.jail.model.AuditSignal.NOTIFICATION_LISTENER_DECLARED].
 */
open class NotificationAccessInspector(private val context: Context) {
    private val notificationManager: NotificationManager? =
        context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    open fun isListenerEnabled(packageName: String): Boolean? {
        val nm = notificationManager ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                nm.getEnabledListenerPackages().contains(packageName)
            } else {
                enabledListenerPackagesLegacy().contains(packageName)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Colon-separated component strings; package is the segment before `/`. */
    private fun enabledListenerPackagesLegacy(): Set<String> {
        val raw = Settings.Secure.getString(
            context.applicationContext.contentResolver,
            Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
        ) ?: return emptySet()
        return raw.split(':').mapNotNull { comp ->
            val pkg = comp.substringBefore('/').trim()
            pkg.takeIf { it.isNotEmpty() }
        }.toSet()
    }
}
