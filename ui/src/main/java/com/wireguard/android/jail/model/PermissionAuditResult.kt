/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

/**
 * Snapshot of the permission-related signals for a single package.
 *
 * Fields fall into three buckets:
 *  * `Boolean` — confirmed via a direct OS API (e.g. a runtime grant flag). Values are KNOWN.
 *  * `Boolean?` — confirmation requires an API that Android does not make reliably
 *    available to third-party apps for other packages; `null` means "unknown".
 *  * `List<String>` — raw manifest declarations for transparency in the detail UI.
 *
 * The scoring stage consumes this plus [BackgroundAuditResult]; the UI uses
 * [declaredPermissions] to show "what this app asked for" if the user drills in.
 */
data class PermissionAuditResult(
    val packageName: String,
    val declaredPermissions: List<String>,
    val grantedMicrophone: Boolean,
    val grantedCamera: Boolean,
    val grantedFineLocation: Boolean,
    val grantedCoarseLocation: Boolean,
    val grantedBackgroundLocation: Boolean,
    val grantedContacts: Boolean,
    val grantedSms: Boolean,
    val grantedCallLog: Boolean,
    val grantedPhoneState: Boolean,
    val grantedBodySensors: Boolean,
    val declaresOverlay: Boolean,
    val declaresManageExternalStorage: Boolean,
    val declaresNotificationListener: Boolean,
    val notificationListenerEnabled: Boolean?,
    val declaresAccessibilityService: Boolean,
    val accessibilityServiceEnabled: Boolean?,
    val declaresUsageStatsAccess: Boolean,
)
