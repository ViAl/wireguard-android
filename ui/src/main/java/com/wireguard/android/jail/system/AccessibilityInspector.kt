/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.system

import android.content.ContentResolver
import android.provider.Settings

/**
 * Determines whether a given package currently has an enabled accessibility service.
 *
 * Enabled services are stored in `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` as a
 * colon-delimited list of `ComponentName.flattenToString()` entries. Reading this
 * setting does not require any runtime permission, and the value is authoritative for
 * the current user — making this the most honest "KNOWN" signal we can produce for
 * accessibility exposure.
 *
 * Accessibility detection is intentionally *not* part of the Jail-granted permission
 * surface of this app: we only inspect the OS setting, we never request
 * `BIND_ACCESSIBILITY_SERVICE` for ourselves.
 */
open class AccessibilityInspector(private val contentResolver: ContentResolver) {
    /**
     * Returns `true` iff at least one `ComponentName` in the enabled-services setting
     * belongs to [packageName]. Returns `false` if the setting is empty, missing, or
     * the resolver throws — an enabled accessibility service is strictly a positive
     * condition, and when the OS refuses to answer we conservatively report "no".
     */
    open fun hasEnabledServiceFor(packageName: String): Boolean {
        val raw = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (_: Exception) {
            null
        } ?: return false
        if (raw.isEmpty()) return false
        return raw.splitToSequence(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { it.substringBefore('/', missingDelimiterValue = "") == packageName }
    }
}
