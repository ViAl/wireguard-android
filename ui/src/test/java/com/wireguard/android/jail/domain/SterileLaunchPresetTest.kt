/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.domain

import com.wireguard.android.jail.model.JailTunnelMode
import com.wireguard.android.jail.model.LaunchProfile
import com.wireguard.android.jail.model.SterileLaunchPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class SterileLaunchPresetTest {
    @Test
    fun defaultFor_usesAnyProfileAndDefaultTunnelMode() {
        val p = SterileLaunchPreset.defaultFor("com.example.x")
        assertEquals("com.example.x", p.packageName)
        assertEquals(LaunchProfile.ANY, p.requiredProfile)
        assertEquals(JailTunnelMode.DEFAULT, p.requiredTunnelMode)
        assertEquals(true, p.warnIfRiskyPermissions)
    }
}
