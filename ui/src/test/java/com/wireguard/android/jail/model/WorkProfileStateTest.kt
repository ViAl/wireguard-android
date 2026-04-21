/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkProfileStateTest {
    @Test
    fun enumEntries_stableNamesForPersistence() {
        assertEquals(4, WorkProfileState.entries.size)
        assertEquals("UNSUPPORTED", WorkProfileState.UNSUPPORTED.name)
        assertEquals("PROFILE_DETECTED", WorkProfileState.PROFILE_DETECTED.name)
    }
}
