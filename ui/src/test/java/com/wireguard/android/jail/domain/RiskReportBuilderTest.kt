/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.domain

import com.wireguard.android.R
import com.wireguard.android.jail.model.JailAppInfo
import com.wireguard.android.jail.model.VisibilityEstimate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskReportBuilderTest {

    private val sampleApp = JailAppInfo(
        label = "Test",
        packageName = "com.example.app",
        icon = null,
        versionName = "1",
        versionCode = 1L,
        isSystemApp = false,
        hasInternetPermission = true,
        installedInMainProfile = true,
        installedInOtherProfile = null,
        isSelectedForJail = true,
    )

    @Test
    fun build_withoutAudit_hasBaselineCanSeeOwnUi() {
        val report = RiskReportBuilder().build(sampleApp, snapshot = null)
        assertFalse(report.auditGenerated)
        assertTrue(
            report.estimates.any {
                it.area == VisibilityEstimate.Area.CAN_SEE &&
                    it.messageRes == R.string.jail_report_can_see_own_ui
            },
        )
    }
}
