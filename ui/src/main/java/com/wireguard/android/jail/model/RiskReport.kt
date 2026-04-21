/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

/**
 * Aggregate, human-oriented view of a single app's risk profile. Produced by
 * [com.wireguard.android.jail.domain.RiskReportBuilder] from an [AuditSnapshot] and basic
 * [JailAppInfo] presence data, then rendered by
 * [com.wireguard.android.jail.ui.HumanReadableRiskFormatter].
 *
 * The report is intentionally a flat bag of [VisibilityEstimate]s grouped by area. The UI is
 * free to reorder bullets within an area for density, but builders must not put the same text
 * in more than one area — the renderer does not de-duplicate.
 */
data class RiskReport(
    val packageName: String,
    val appLabel: String,
    val overallLevel: AuditRiskLevel,
    val estimates: List<VisibilityEstimate>,
    val auditGenerated: Boolean,
) {
    fun estimatesFor(area: VisibilityEstimate.Area): List<VisibilityEstimate> =
        estimates.filter { it.area == area }
}
