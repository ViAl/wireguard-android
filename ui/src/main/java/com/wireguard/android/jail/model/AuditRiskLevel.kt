/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

import androidx.annotation.StringRes
import com.wireguard.android.R

/**
 * Coarse risk bucket displayed in the UI. Buckets are chosen so that a single
 * high-severity signal (accessibility service enabled, for example) can lift the
 * result straight to [CRITICAL] via [AuditSignal.criticalFloor].
 */
enum class AuditRiskLevel(@StringRes val labelRes: Int) {
    LOW(R.string.jail_risk_level_low),
    MEDIUM(R.string.jail_risk_level_medium),
    HIGH(R.string.jail_risk_level_high),
    CRITICAL(R.string.jail_risk_level_critical);

    companion object {
        /** Threshold table shared with [com.wireguard.android.jail.domain.AppAuditManager]. */
        fun fromScore(score: Int): AuditRiskLevel = when {
            score >= 80 -> CRITICAL
            score >= 45 -> HIGH
            score >= 20 -> MEDIUM
            else -> LOW
        }
    }
}
