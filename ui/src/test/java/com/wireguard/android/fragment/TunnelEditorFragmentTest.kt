/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import com.wireguard.android.viewmodel.SplitTunnelingMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelEditorFragmentTest {
    @Test
    fun appSelectionDisabledForAllApplicationsMode() {
        assertFalse(TunnelEditorFragment.isAppSelectionEnabledForMode(SplitTunnelingMode.ALL_APPLICATIONS))
    }

    @Test
    fun appSelectionEnabledForExcludeAndIncludeModes() {
        assertTrue(TunnelEditorFragment.isAppSelectionEnabledForMode(SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS))
        assertTrue(TunnelEditorFragment.isAppSelectionEnabledForMode(SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS))
    }
}
