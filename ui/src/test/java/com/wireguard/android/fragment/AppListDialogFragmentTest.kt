/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListDialogFragmentTest {
    private data class TestApp(val name: String, val packageName: String, var selected: Boolean)

    @Test
    fun matchesSearchQueryMatchesLabelCaseInsensitively() {
        assertTrue(AppListDialogFragment.matchesSearchQuery("wire", "WireGuard", "com.example.vpn"))
    }

    @Test
    fun matchesSearchQueryMatchesPackageCaseInsensitively() {
        assertTrue(AppListDialogFragment.matchesSearchQuery("EXAMPLE.VPN", "WireGuard", "com.example.vpn"))
    }

    @Test
    fun matchesSearchQueryReturnsFalseWhenNeitherLabelNorPackageMatch() {
        assertFalse(AppListDialogFragment.matchesSearchQuery("browser", "WireGuard", "com.example.vpn"))
    }

    @Test
    fun filterByQueryReturnsMatchingItemsWithoutCopyingObjects() {
        val first = TestApp("WireGuard", "com.example.vpn", selected = true)
        val second = TestApp("Browser", "com.example.browser", selected = false)
        val filtered = AppListDialogFragment.filterByQuery("example.vpn", listOf(first, second), { it.name }, { it.packageName })

        assertEquals(1, filtered.size)
        assertSame(first, filtered.first())
        assertTrue(filtered.first().selected)
    }

    @Test
    fun updateSelectionStateSupportsToggleAndClearSelectionFlows() {
        val first = TestApp("WireGuard", "com.example.vpn", selected = false)
        val second = TestApp("Browser", "com.example.browser", selected = true)
        val apps = listOf(first, second)

        val selectAll = AppListDialogFragment.shouldSelectAllVisible(apps.map { it.selected })
        AppListDialogFragment.updateSelectionState(apps, selectAll, { it.selected }) { app, selected -> app.selected = selected }
        assertTrue(first.selected)
        assertTrue(second.selected)

        AppListDialogFragment.updateSelectionState(apps, false, { it.selected }) { app, selected -> app.selected = selected }
        assertFalse(first.selected)
        assertFalse(second.selected)
    }

    @Test
    fun toggleAllDecisionUsesVisibleFilteredItemsOnly() {
        val visibleItems = listOf(
            TestApp("Visible one", "com.example.visible.one", selected = true),
            TestApp("Visible two", "com.example.visible.two", selected = false)
        )
        val hiddenItem = TestApp("Hidden", "com.example.hidden", selected = false)

        val selectAllVisible = AppListDialogFragment.shouldSelectAllVisible(visibleItems.map { it.selected })
        AppListDialogFragment.updateSelectionState(visibleItems, selectAllVisible, { it.selected }) { app, selected -> app.selected = selected }

        assertTrue(visibleItems.all { it.selected })
        assertFalse(hiddenItem.selected)
    }
}
