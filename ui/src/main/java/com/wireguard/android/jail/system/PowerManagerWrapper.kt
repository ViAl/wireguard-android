/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.system

import android.content.Context
import android.os.PowerManager

/**
 * Thin wrapper around [PowerManager.isIgnoringBatteryOptimizations].
 *
 * The platform contract here is finicky: on a handful of OEM builds, asking about
 * another package throws [SecurityException], and on a handful more it silently
 * returns `false` regardless of the real state. We therefore treat anomalies as
 * "unknown" (`null`) rather than conflating them with "restricted".
 *
 * The resulting signal is scored as [com.wireguard.android.jail.model.AuditConfidence.LIKELY];
 * "unrestricted background" is dangerous enough to mention even when we cannot be
 * 100% certain the OS honours our answer.
 */
open class PowerManagerWrapper(context: Context) {
    private val powerManager: PowerManager? =
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    open fun isIgnoringBatteryOptimizations(packageName: String): Boolean? {
        val pm = powerManager ?: return null
        return try {
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
