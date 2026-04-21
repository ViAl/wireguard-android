/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.domain

import com.wireguard.android.R
import com.wireguard.android.jail.model.AuditConfidence
import com.wireguard.android.jail.model.AuditRiskLevel
import com.wireguard.android.jail.model.AuditSnapshot
import com.wireguard.android.jail.model.CapabilityMatrix
import com.wireguard.android.jail.model.CapabilityState
import com.wireguard.android.jail.model.JailAppInfo
import com.wireguard.android.jail.model.RiskReport
import com.wireguard.android.jail.model.VisibilityEstimate

/**
 * Pure transformation from [JailAppInfo] + optional [AuditSnapshot] to a [RiskReport]. Kept
 * context-free so unit tests and the UI share exactly the same ordering/phrasing decisions.
 *
 * Design notes:
 *  * We emit **string resource IDs** rather than formatted English. The UI (or tests)
 *    provides a [android.content.res.Resources] instance to finish the rendering. This keeps
 *    localisation deterministic and prevents accidental hard-coded copy drift.
 *  * We preserve honesty: capabilities observed from a runtime grant are [AuditConfidence.KNOWN],
 *    declarations that we cannot verify at runtime are [AuditConfidence.LIKELY], and
 *    reassuring bullets ("probably cannot see X") default to LIKELY — Android does not expose
 *    enough to promise an app truly cannot do something.
 *  * Section content ordering is deterministic per input. Tests can snapshot the emitted list.
 *
 * When the snapshot is null we only emit the baseline bullets that do not depend on audit
 * results (e.g. self-UI visibility, work-profile framing), and flag [RiskReport.auditGenerated]
 * as false so the UI can prompt the user to run the audit first.
 */
class RiskReportBuilder {
    fun build(app: JailAppInfo, snapshot: AuditSnapshot?): RiskReport {
        val estimates = mutableListOf<VisibilityEstimate>()
        val level = snapshot?.score?.level ?: AuditRiskLevel.LOW

        addCanSeeSection(app, snapshot, estimates)
        addCannotSeeSection(snapshot, estimates)
        addWorkProfileSection(app, estimates)
        addNetworkSection(app, estimates)
        addResidualRisksSection(app, snapshot, estimates)

        return RiskReport(
            packageName = app.packageName,
            appLabel = app.label,
            overallLevel = level,
            estimates = estimates.toList(),
            auditGenerated = snapshot != null,
        )
    }

    private fun addCanSeeSection(
        app: JailAppInfo,
        snapshot: AuditSnapshot?,
        out: MutableList<VisibilityEstimate>,
    ) {
        // The "can observe its own UI" bullet is true for every app that can be launched, so it
        // is emitted unconditionally. It anchors the section and prevents a misleadingly empty
        // "can see" list for apps that hold no sensitive permissions.
        out += estimate(R.string.jail_report_can_see_own_ui, AuditConfidence.KNOWN, VisibilityEstimate.Area.CAN_SEE)

        val matrix = snapshot?.let { CapabilityMatrix.from(it) } ?: return

        if (matrix.location == CapabilityState.GRANTED) {
            out += estimate(R.string.jail_report_can_see_location, AuditConfidence.KNOWN, VisibilityEstimate.Area.CAN_SEE)
        }
        if (matrix.backgroundLocation == CapabilityState.GRANTED) {
            out += estimate(R.string.jail_report_can_see_background_location, AuditConfidence.KNOWN, VisibilityEstimate.Area.CAN_SEE)
        }
        if (matrix.microphone == CapabilityState.GRANTED) {
            out += estimate(R.string.jail_report_can_see_microphone, AuditConfidence.KNOWN, VisibilityEstimate.Area.CAN_SEE)
        }
        if (matrix.camera == CapabilityState.GRANTED) {
            out += estimate(R.string.jail_report_can_see_camera, AuditConfidence.KNOWN, VisibilityEstimate.Area.CAN_SEE)
        }
        if (matrix.contacts == CapabilityState.GRANTED) {
            out += estimate(R.string.jail_report_can_see_contacts, AuditConfidence.KNOWN, VisibilityEstimate.Area.CAN_SEE)
        }
        if (matrix.sms == CapabilityState.GRANTED) {
            out += estimate(R.string.jail_report_can_see_sms, AuditConfidence.KNOWN, VisibilityEstimate.Area.CAN_SEE)
        }
        if (matrix.callLog == CapabilityState.GRANTED) {
            out += estimate(R.string.jail_report_can_see_call_log, AuditConfidence.KNOWN, VisibilityEstimate.Area.CAN_SEE)
        }
        if (matrix.phoneState == CapabilityState.GRANTED) {
            out += estimate(R.string.jail_report_can_see_phone_state, AuditConfidence.KNOWN, VisibilityEstimate.Area.CAN_SEE)
        }
        if (matrix.bodySensors == CapabilityState.GRANTED) {
            out += estimate(R.string.jail_report_can_see_sensors, AuditConfidence.KNOWN, VisibilityEstimate.Area.CAN_SEE)
        }
        if (matrix.overlay.isActive) {
            out += estimate(R.string.jail_report_can_see_overlay, AuditConfidence.LIKELY, VisibilityEstimate.Area.CAN_SEE)
        }
        if (matrix.externalStorage.isActive) {
            out += estimate(R.string.jail_report_can_see_storage, AuditConfidence.LIKELY, VisibilityEstimate.Area.CAN_SEE)
        }
        if (matrix.notificationListener.isActive) {
            val confidence = if (matrix.notificationListener == CapabilityState.GRANTED)
                AuditConfidence.KNOWN else AuditConfidence.LIKELY
            out += estimate(R.string.jail_report_can_see_notifications, confidence, VisibilityEstimate.Area.CAN_SEE)
        }
        if (matrix.accessibilityService.isActive) {
            val confidence = if (matrix.accessibilityService == CapabilityState.GRANTED)
                AuditConfidence.KNOWN else AuditConfidence.LIKELY
            out += estimate(R.string.jail_report_can_see_accessibility, confidence, VisibilityEstimate.Area.CAN_SEE)
        }
        if (matrix.usageStats.isActive) {
            out += estimate(R.string.jail_report_can_see_usage_stats, AuditConfidence.LIKELY, VisibilityEstimate.Area.CAN_SEE)
        }
    }

    private fun addCannotSeeSection(
        snapshot: AuditSnapshot?,
        out: MutableList<VisibilityEstimate>,
    ) {
        // Without an audit we cannot make confident "probably cannot" statements; skip the
        // section entirely in that case so the user is not misled.
        val matrix = snapshot?.let { CapabilityMatrix.from(it) } ?: return

        if (matrix.location == CapabilityState.ABSENT && matrix.backgroundLocation == CapabilityState.ABSENT) {
            out += estimate(R.string.jail_report_cannot_see_location, AuditConfidence.LIKELY, VisibilityEstimate.Area.CANNOT_SEE)
        }
        if (matrix.microphone == CapabilityState.ABSENT) {
            out += estimate(R.string.jail_report_cannot_see_microphone, AuditConfidence.LIKELY, VisibilityEstimate.Area.CANNOT_SEE)
        }
        if (matrix.camera == CapabilityState.ABSENT) {
            out += estimate(R.string.jail_report_cannot_see_camera, AuditConfidence.LIKELY, VisibilityEstimate.Area.CANNOT_SEE)
        }
        if (matrix.contacts == CapabilityState.ABSENT) {
            out += estimate(R.string.jail_report_cannot_see_contacts, AuditConfidence.LIKELY, VisibilityEstimate.Area.CANNOT_SEE)
        }
        if (matrix.sms == CapabilityState.ABSENT && matrix.callLog == CapabilityState.ABSENT) {
            out += estimate(R.string.jail_report_cannot_see_messaging, AuditConfidence.LIKELY, VisibilityEstimate.Area.CANNOT_SEE)
        }
        if (matrix.notificationListener == CapabilityState.ABSENT) {
            out += estimate(R.string.jail_report_cannot_see_notifications, AuditConfidence.LIKELY, VisibilityEstimate.Area.CANNOT_SEE)
        }
        if (matrix.accessibilityService == CapabilityState.ABSENT) {
            out += estimate(R.string.jail_report_cannot_see_accessibility, AuditConfidence.LIKELY, VisibilityEstimate.Area.CANNOT_SEE)
        }
        if (matrix.overlay == CapabilityState.ABSENT) {
            out += estimate(R.string.jail_report_cannot_see_overlay, AuditConfidence.LIKELY, VisibilityEstimate.Area.CANNOT_SEE)
        }
    }

    private fun addWorkProfileSection(app: JailAppInfo, out: MutableList<VisibilityEstimate>) {
        when (app.installedInWorkProfile) {
            null -> out += estimate(R.string.jail_report_work_profile_none, AuditConfidence.LIKELY, VisibilityEstimate.Area.WORK_PROFILE_DELTA)
            true -> out += estimate(R.string.jail_report_work_profile_present, AuditConfidence.LIKELY, VisibilityEstimate.Area.WORK_PROFILE_DELTA)
            false -> out += estimate(R.string.jail_report_work_profile_missing, AuditConfidence.LIKELY, VisibilityEstimate.Area.WORK_PROFILE_DELTA)
        }
        out += estimate(R.string.jail_report_work_profile_shared_channels, AuditConfidence.LIKELY, VisibilityEstimate.Area.WORK_PROFILE_DELTA)
    }

    private fun addNetworkSection(app: JailAppInfo, out: MutableList<VisibilityEstimate>) {
        if (app.hasInternetPermission) {
            out += estimate(R.string.jail_report_network_internet, AuditConfidence.LIKELY, VisibilityEstimate.Area.NETWORK_METADATA)
        }
        out += estimate(R.string.jail_report_network_vpn_detection, AuditConfidence.LIKELY, VisibilityEstimate.Area.NETWORK_METADATA)
        out += estimate(R.string.jail_report_network_fingerprint, AuditConfidence.LIKELY, VisibilityEstimate.Area.NETWORK_METADATA)
    }

    private fun addResidualRisksSection(
        app: JailAppInfo,
        snapshot: AuditSnapshot?,
        out: MutableList<VisibilityEstimate>,
    ) {
        // Always-true bullet that anchors the section and reinforces honest messaging.
        out += estimate(R.string.jail_report_residual_self_input, AuditConfidence.KNOWN, VisibilityEstimate.Area.RESIDUAL_RISK)

        val matrix = snapshot?.let { CapabilityMatrix.from(it) }
        if (matrix != null) {
            if (matrix.unrestrictedBackground == CapabilityState.GRANTED) {
                out += estimate(R.string.jail_report_residual_background, AuditConfidence.KNOWN, VisibilityEstimate.Area.RESIDUAL_RISK)
            }
            if (matrix.persistentForegroundService.isActive) {
                out += estimate(R.string.jail_report_residual_foreground_service, AuditConfidence.LIKELY, VisibilityEstimate.Area.RESIDUAL_RISK)
            }
            if (matrix.accessibilityService.isActive) {
                out += estimate(R.string.jail_report_residual_accessibility, AuditConfidence.LIKELY, VisibilityEstimate.Area.RESIDUAL_RISK)
            }
            if (matrix.overlay.isActive) {
                out += estimate(R.string.jail_report_residual_overlay, AuditConfidence.LIKELY, VisibilityEstimate.Area.RESIDUAL_RISK)
            }
        }
        if (app.isSystemApp) {
            out += estimate(R.string.jail_report_residual_system_app, AuditConfidence.LIKELY, VisibilityEstimate.Area.RESIDUAL_RISK)
        }
    }

    private fun estimate(
        messageRes: Int,
        confidence: AuditConfidence,
        area: VisibilityEstimate.Area,
    ) = VisibilityEstimate(area = area, messageRes = messageRes, confidence = confidence)
}
