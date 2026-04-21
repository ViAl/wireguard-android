/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

/**
 * Snapshot of background-execution-related signals.
 *
 *  * [isIgnoringBatteryOptimizations] is nullable because `PowerManager` may refuse
 *    or simply return misleading answers for foreign packages on some Android builds.
 *  * [foregroundServiceTypes] contains the declared `android:foregroundServiceType`
 *    flags for services in the manifest, as lower-case token strings
 *    (e.g. `location`, `microphone`). A missing/0 value means the service is declared
 *    but without a foreground-service type.
 */
data class BackgroundAuditResult(
    val packageName: String,
    val isIgnoringBatteryOptimizations: Boolean?,
    val hasForegroundServices: Boolean,
    val foregroundServiceTypes: Set<String>,
)
