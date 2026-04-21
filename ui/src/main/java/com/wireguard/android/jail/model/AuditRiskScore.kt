/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

/**
 * Result of running the scoring pass over [PermissionAuditResult] and
 * [BackgroundAuditResult] for a single package.
 *
 * @property score Sum of [RiskReason.weight] across [reasons]. Capped at 100 for display.
 * @property level Coarse bucket derived from [score] and any `criticalFloor` reason.
 * @property reasons Ordered (descending by weight) list of things that contributed.
 */
data class AuditRiskScore(
    val packageName: String,
    val score: Int,
    val level: AuditRiskLevel,
    val reasons: List<RiskReason>,
)

/**
 * Why a given [AuditSignal] fired for a particular app. Stored verbatim so the UI
 * (and Phase 4's human-readable report builder) can explain the score without
 * re-running the inspectors.
 */
data class RiskReason(
    val signalId: String,
    val weight: Int,
    val confidence: AuditConfidence,
    val signal: AuditSignal?,
)
