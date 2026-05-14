/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

/**
 * Persistable bundle of everything known about a single audited app at a given moment.
 *
 * Written to DataStore as JSON by
 * [com.wireguard.android.jail.storage.JailStore.setAuditSnapshots]. The serialiser
 * and deserialiser live in
 * [com.wireguard.android.jail.storage.AuditSnapshotCodec] and are intentionally
 * schema-lenient: an unknown signal id is dropped on read, and a missing field
 * falls back to a safe default so we never crash a tab because the cache format
 * shifted between releases.
 */
data class AuditSnapshot(
    val packageName: String,
    val generatedAtMillis: Long,
    val permissionAudit: PermissionAuditResult,
    val backgroundAudit: BackgroundAuditResult,
    val score: AuditRiskScore,
)
