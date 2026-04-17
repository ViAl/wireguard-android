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

    @Test
    fun decodeParcelTailSupportsOldLayoutWithoutMode() {
        val decoded = InterfaceProxy.decodeParcelTail(
            listOf("51820", "1280", "private-key-base64")
        )
        assertEquals("51820", decoded.listenPort)
        assertEquals("1280", decoded.mtu)
        assertEquals("private-key-base64", decoded.privateKey)
        assertEquals(null, decoded.mode)
    }

    @Test
    fun decodeParcelTailSupportsIntermediateLayoutWithModeBeforeListenPort() {
        val decoded = InterfaceProxy.decodeParcelTail(
            listOf(
                SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS.name,
                "51820",
                "1280",
                "private-key-base64"
            )
        )
        assertEquals("51820", decoded.listenPort)
        assertEquals("1280", decoded.mtu)
        assertEquals("private-key-base64", decoded.privateKey)
        assertEquals(SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS, decoded.mode)
    }

    @Test
    fun decodeParcelTailSupportsNewLayoutWithModeAppended() {
        val decoded = InterfaceProxy.decodeParcelTail(
            listOf(
                "51820",
                "1280",
                "private-key-base64",
                SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS.name
            )
        )
        assertEquals("51820", decoded.listenPort)
        assertEquals("1280", decoded.mtu)
        assertEquals("private-key-base64", decoded.privateKey)
        assertEquals(SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS, decoded.mode)
    }

    @Test
    fun decodeParcelTailFallsBackToLegacyMappingWhenModeIsAbsent() {
        val decoded = InterfaceProxy.decodeParcelTail(
            listOf("51820", "1280", "private-key-base64", "unexpected-trailing")
        )
        assertEquals("51820", decoded.listenPort)
        assertEquals("1280", decoded.mtu)
        assertEquals("private-key-base64", decoded.privateKey)
        assertEquals(null, decoded.mode)
    }

    @Test
    fun absentParcelModeFallsBackToInferredModeFromAppLists() {
        val proxy = InterfaceProxy()
        proxy.excludedApplications.add("com.example.excluded")
        val decoded = InterfaceProxy.decodeParcelTail(listOf("51820", "1280", "private-key-base64"))

        val resolvedMode = decoded.mode ?: proxy.inferSplitTunnelingMode()

        assertEquals(SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS, resolvedMode)
    }

    private fun newResolvableProxy(): InterfaceProxy {
        return InterfaceProxy().apply {
            generateKeyPair()
        }
    }
}
