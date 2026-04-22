/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.storage

import com.wireguard.android.jail.model.JailTunnelMode
import com.wireguard.android.jail.model.LaunchProfile
import com.wireguard.android.jail.model.SterileLaunchPreset
import com.wireguard.android.jail.model.WorkProfileInstallMode
import com.wireguard.android.jail.model.WorkProfileInstallSessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class JailCodecRoundtripTest {
    @Test
    fun routingPolicy_roundTrip() {
        val original = mapOf(
            "com.example.one" to JailTunnelMode.JAIL_ROUTE_THROUGH_TUNNEL,
            "com.example.two" to JailTunnelMode.DEFAULT,
        )
        val encoded = RoutingPolicyCodec.encode(original)
        assertEquals(original, RoutingPolicyCodec.decode(encoded))
        assertEquals(emptyMap<String, JailTunnelMode>(), RoutingPolicyCodec.decode(null))
        assertEquals(emptyMap<String, JailTunnelMode>(), RoutingPolicyCodec.decode(""))
    }

    @Test
    fun launchPreset_roundTrip() {
        val original = mapOf(
            "com.example.app" to SterileLaunchPreset(
                packageName = "com.example.app",
                requiredProfile = LaunchProfile.WORK,
                requiredTunnelMode = JailTunnelMode.JAIL_STRICT_PROFILE,
                warnLocationEnabled = false,
                warnBluetoothEnabled = true,
                warnClearClipboard = true,
                warnIfNoWorkProfileCopy = false,
                warnIfRiskyPermissions = false,
            ),
        )
        val encoded = LaunchPresetCodec.encode(original)
        assertEquals(original, LaunchPresetCodec.decode(encoded))
        assertEquals(emptyMap<String, SterileLaunchPreset>(), LaunchPresetCodec.decode(null))
    }

    @Test
    fun installSession_roundTrip() {
        val original = mapOf(
            "com.example.app" to WorkProfileInstallSessionState.WaitingForUserAction(
                packageName = "com.example.app",
                startedAtMillis = 42L,
                mode = WorkProfileInstallMode.MANUAL_STORE,
            ),
            "com.example.two" to WorkProfileInstallSessionState.Failed(
                packageName = "com.example.two",
                completedAtMillis = 66L,
                mode = WorkProfileInstallMode.AUTOMATIC,
                message = "Still not visible",
            ),
        )
        val encoded = WorkProfileInstallSessionCodec.encode(original)
        assertEquals(original, WorkProfileInstallSessionCodec.decode(encoded))
        assertEquals(emptyMap<String, WorkProfileInstallSessionState>(), WorkProfileInstallSessionCodec.decode(null))
        assertEquals(emptyMap<String, WorkProfileInstallSessionState>(), WorkProfileInstallSessionCodec.decode(""))
    }
}
