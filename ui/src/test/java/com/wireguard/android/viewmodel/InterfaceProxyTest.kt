/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterfaceProxyTest {
    @Test
    fun splitTunnelingModeExcludeClearsIncludedApplications() {
        val proxy = InterfaceProxy()
        proxy.includedApplications.add("com.example.included")

        proxy.splitTunnelingMode = SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS

        assertTrue(proxy.includedApplications.isEmpty())
        assertEquals(SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS, proxy.splitTunnelingMode)
    }

    @Test
    fun splitTunnelingModeIncludeClearsExcludedApplications() {
        val proxy = InterfaceProxy()
        proxy.excludedApplications.add("com.example.excluded")

        proxy.splitTunnelingMode = SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS

        assertTrue(proxy.excludedApplications.isEmpty())
        assertEquals(SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS, proxy.splitTunnelingMode)
    }

    @Test
    fun splitTunnelingModeAllClearsBothLists() {
        val proxy = InterfaceProxy()
        proxy.excludedApplications.add("com.example.excluded")
        proxy.includedApplications.add("com.example.included")

        proxy.splitTunnelingMode = SplitTunnelingMode.ALL_APPLICATIONS

        assertTrue(proxy.excludedApplications.isEmpty())
        assertTrue(proxy.includedApplications.isEmpty())
    }

    @Test
    fun inferSplitTunnelingModePrefersExcludeWhenListsConflict() {
        val proxy = InterfaceProxy()
        proxy.excludedApplications.add("com.example.excluded")
        proxy.includedApplications.add("com.example.included")

        assertEquals(SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS, proxy.inferSplitTunnelingMode())
    }

    @Test
    fun resolveAllModeEmitsNoApplicationRulesEvenIfListsContainData() {
        val proxy = newResolvableProxy()
        proxy.splitTunnelingMode = SplitTunnelingMode.ALL_APPLICATIONS
        proxy.excludedApplications.add("com.example.excluded")
        proxy.includedApplications.add("com.example.included")

        val resolved = proxy.resolve()

        assertTrue(resolved.excludedApplications.isEmpty())
        assertTrue(resolved.includedApplications.isEmpty())
    }

    @Test
    fun resolveExcludeModeEmitsOnlyExcludedRules() {
        val proxy = newResolvableProxy()
        proxy.splitTunnelingMode = SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS
        proxy.excludedApplications.add("com.example.excluded")
        proxy.includedApplications.add("com.example.included")

        val resolved = proxy.resolve()

        assertEquals(setOf("com.example.excluded"), resolved.excludedApplications)
        assertTrue(resolved.includedApplications.isEmpty())
    }

    @Test
    fun resolveIncludeModeEmitsOnlyIncludedRules() {
        val proxy = newResolvableProxy()
        proxy.splitTunnelingMode = SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS
        proxy.includedApplications.add("com.example.included")
        proxy.excludedApplications.add("com.example.excluded")

        val resolved = proxy.resolve()

        assertEquals(setOf("com.example.included"), resolved.includedApplications)
        assertTrue(resolved.excludedApplications.isEmpty())
    }

    @Test
    fun resolveExcludeModeWithEmptySelectionIsDeterministic() {
        val proxy = newResolvableProxy()
        proxy.splitTunnelingMode = SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS

        val resolved = proxy.resolve()

        assertTrue(resolved.excludedApplications.isEmpty())
        assertTrue(resolved.includedApplications.isEmpty())
    }

    @Test
    fun resolveIncludeModeWithEmptySelectionIsDeterministic() {
        val proxy = newResolvableProxy()
        proxy.splitTunnelingMode = SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS

        val resolved = proxy.resolve()

        assertTrue(resolved.excludedApplications.isEmpty())
        assertTrue(resolved.includedApplications.isEmpty())
    }

    private fun newResolvableProxy(): InterfaceProxy {
        return InterfaceProxy().apply {
            generateKeyPair()
        }
    }
}
