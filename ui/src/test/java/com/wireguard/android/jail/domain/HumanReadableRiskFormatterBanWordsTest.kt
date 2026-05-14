/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.domain

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.wireguard.android.R
import com.wireguard.android.jail.model.AuditConfidence
import com.wireguard.android.jail.model.AuditRiskLevel
import com.wireguard.android.jail.model.RiskReport
import com.wireguard.android.jail.model.VisibilityEstimate
import com.wireguard.android.jail.ui.HumanReadableRiskFormatter
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan Phase 10: exported report text must not contain absolute blocked-claim wording. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HumanReadableRiskFormatterBanWordsTest {

    @Test
    fun formattedBody_avoidsBannedClaimPatterns() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val formatter = HumanReadableRiskFormatter(app.resources)
        val report = RiskReport(
            packageName = "com.example.x",
            appLabel = "Test",
            overallLevel = AuditRiskLevel.MEDIUM,
            estimates = listOf(
                VisibilityEstimate(
                    area = VisibilityEstimate.Area.CAN_SEE,
                    messageRes = R.string.jail_report_can_see_own_ui,
                    confidence = AuditConfidence.KNOWN,
                ),
            ),
            auditGenerated = true,
        )
        val body = formatter.fullReportBody(report).lowercase()
        val banned = listOf(
            "cannot be tracked",
            "fully safe",
            "guaranteed",
            "impossible to",
        )
        for (phrase in banned) {
            assertFalse("unexpected phrase in report: $phrase", body.contains(phrase))
        }
    }
}
