/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AuditRiskLevelTest {
    @Test
    fun fromScore_thresholds() {
        assertEquals(AuditRiskLevel.LOW, AuditRiskLevel.fromScore(0))
        assertEquals(AuditRiskLevel.LOW, AuditRiskLevel.fromScore(19))
        assertEquals(AuditRiskLevel.MEDIUM, AuditRiskLevel.fromScore(20))
        assertEquals(AuditRiskLevel.MEDIUM, AuditRiskLevel.fromScore(44))
        assertEquals(AuditRiskLevel.HIGH, AuditRiskLevel.fromScore(45))
        assertEquals(AuditRiskLevel.HIGH, AuditRiskLevel.fromScore(79))
        assertEquals(AuditRiskLevel.CRITICAL, AuditRiskLevel.fromScore(80))
    }
}
