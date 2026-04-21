/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.ui

import android.content.res.Resources
import com.wireguard.android.R
import com.wireguard.android.jail.model.AuditRiskLevel
import com.wireguard.android.jail.model.RiskReport
import com.wireguard.android.jail.model.VisibilityEstimate

/**
 * Turns a [RiskReport] plus a [Resources] handle into user-facing strings. Kept separate from
 * the builder so the domain layer stays free of Android dependencies, and so the formatter can
 * be swapped out for alternative renderings (e.g. share-to-clipboard in a later phase).
 */
class HumanReadableRiskFormatter(private val resources: Resources) {

    fun headline(report: RiskReport): String =
        resources.getString(R.string.jail_report_overall_risk_format, resources.getString(levelLabel(report.overallLevel)))

    /**
     * Multi-line body for the report screen: optional pending notice, then each non-empty
     * section in [SECTION_ORDER] with titled bullets.
     */
    fun fullReportBody(report: RiskReport): String = buildString {
        if (!report.auditGenerated) {
            appendLine(resources.getString(R.string.jail_report_pending))
            appendLine()
        }
        for (area in SECTION_ORDER) {
            val items = report.estimatesFor(area)
            if (items.isEmpty()) continue
            appendLine(sectionTitle(area))
            for (estimate in items) {
                appendLine("• ${bulletFor(estimate)}")
            }
            appendLine()
        }
    }.trimEnd()

    fun sectionTitle(area: VisibilityEstimate.Area): String =
        resources.getString(sectionTitleRes(area))

    fun bulletFor(estimate: VisibilityEstimate): String {
        val base = resources.getString(estimate.messageRes)
        val confidence = resources.getString(estimate.confidence.labelRes)
        val suffix = resources.getString(R.string.jail_report_confidence_format, confidence)
        return "$base $suffix"
    }

    private fun sectionTitleRes(area: VisibilityEstimate.Area): Int = when (area) {
        VisibilityEstimate.Area.CAN_SEE -> R.string.jail_report_section_can_see
        VisibilityEstimate.Area.CANNOT_SEE -> R.string.jail_report_section_cannot_see
        VisibilityEstimate.Area.WORK_PROFILE_DELTA -> R.string.jail_report_section_work_profile
        VisibilityEstimate.Area.NETWORK_METADATA -> R.string.jail_report_section_network
        VisibilityEstimate.Area.RESIDUAL_RISK -> R.string.jail_report_section_residual
    }

    private fun levelLabel(level: AuditRiskLevel): Int = when (level) {
        AuditRiskLevel.LOW -> R.string.jail_risk_level_low
        AuditRiskLevel.MEDIUM -> R.string.jail_risk_level_medium
        AuditRiskLevel.HIGH -> R.string.jail_risk_level_high
        AuditRiskLevel.CRITICAL -> R.string.jail_risk_level_critical
    }

    companion object {
        /** The deterministic order sections are rendered in the UI. */
        val SECTION_ORDER: List<VisibilityEstimate.Area> = listOf(
            VisibilityEstimate.Area.CAN_SEE,
            VisibilityEstimate.Area.CANNOT_SEE,
            VisibilityEstimate.Area.NETWORK_METADATA,
            VisibilityEstimate.Area.WORK_PROFILE_DELTA,
            VisibilityEstimate.Area.RESIDUAL_RISK,
        )
    }
}
