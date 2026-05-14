/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

import androidx.annotation.StringRes
import com.wireguard.android.R

/**
 * Honest rating of how certain a signal is. The UI surfaces this next to each
 * [RiskReason] so the user can tell facts from inferences.
 *
 *  * [KNOWN]   — directly observable from a stable OS API (e.g. enabled accessibility
 *    services from Settings.Secure).
 *  * [LIKELY]  — derived from manifest declarations or behaviour that strongly implies,
 *    but does not prove, a runtime effect (e.g. overlay permission declared, but we
 *    cannot reliably know if it was granted on every Android release).
 *  * [UNKNOWN] — declared or suggested, but the OS either refuses to answer or will
 *    answer unreliably. Used sparingly; prefer omitting a signal over lying about it.
 */
enum class AuditConfidence(@StringRes val labelRes: Int) {
    KNOWN(R.string.jail_confidence_known),
    LIKELY(R.string.jail_confidence_likely),
    UNKNOWN(R.string.jail_confidence_unknown)
}
