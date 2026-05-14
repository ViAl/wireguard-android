/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

import androidx.annotation.StringRes

/**
 * Categorised statement about what a specific app can (or cannot) observe. Each estimate is
 * rendered as a single bullet in the Jail report; the [confidence] badge lets the UI be
 * honest about how certain the claim is.
 *
 * A dedicated type (rather than raw strings) keeps the Phase 4 report builder testable and
 * makes localisation deterministic — the builder only produces string resource IDs plus
 * confidence, never pre-formatted English text.
 */
data class VisibilityEstimate(
    val area: Area,
    @StringRes val messageRes: Int,
    val confidence: AuditConfidence,
) {
    /** Which report section this estimate belongs to. */
    enum class Area {
        /** "What this app can likely see" */
        CAN_SEE,

        /** "What this app probably cannot see" — reassuring bullets. */
        CANNOT_SEE,

        /** "What changes if you run it inside a work profile." */
        WORK_PROFILE_DELTA,

        /** "What network metadata may still remain visible." */
        NETWORK_METADATA,

        /** "What risks remain even after Jail protections." */
        RESIDUAL_RISK,
    }
}
