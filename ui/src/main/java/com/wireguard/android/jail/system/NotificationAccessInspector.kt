/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.system

import android.content.Context
import android.provider.Settings

/**
 * Reads whether an app package is enabled as a notification listener via
 * `Settings.Secure` (`enabled_notification_listeners`). Does not require notification
 * listener permission to read this string.
 *
 * Returning `false` here for a package that *declares* a listener service is
 * meaningful — it means the service exists but the user has not enabled it — so
 * we still score the manifest-only case via
 * [com.wireguard.android.jail.model.AuditSignal.NOTIFICATION_LISTENER_DECLARED].
 */
open class NotificationAccessInspector(private val context: Context) {

    open fun isListenerEnabled(packageName: String): Boolean? =
        try {
            enabledListenerPackagesFromSettings().contains(packageName)
        } catch (_: Exception) {
            null
        }

    /** Same colon-separated component format on all supported API levels. */
    private fun enabledListenerPackagesFromSettings(): Set<String> {
        val raw = Settings.Secure.getString(
            context.applicationContext.contentResolver,
            ENABLED_NOTIFICATION_LISTENERS_KEY,
        ) ?: return emptySet()
        return raw.split(':').mapNotNull { comp ->
            val pkg = comp.substringBefore('/').trim()
            pkg.takeIf { it.isNotEmpty() }
        }.toSet()
    }

    companion object {
        /** [Settings.Secure] key — use literal so builds compile against any SDK revision. */
        private const val ENABLED_NOTIFICATION_LISTENERS_KEY = "enabled_notification_listeners"
    }
}
