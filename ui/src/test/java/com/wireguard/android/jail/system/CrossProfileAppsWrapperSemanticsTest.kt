/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.system

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrossProfileAppsWrapperSemanticsTest {

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    @Suppress("DEPRECATION")
    fun hasManagedProfile_isTransitionalAliasOfHasSecondaryProfile() {
        val wrapperTrue = object : CrossProfileAppsWrapper(app) {
            override fun hasSecondaryProfile(): Boolean = true
        }
        val wrapperFalse = object : CrossProfileAppsWrapper(app) {
            override fun hasSecondaryProfile(): Boolean = false
        }

        assertTrue(wrapperTrue.hasSecondaryProfile())
        assertTrue(wrapperTrue.hasManagedProfile())

        assertFalse(wrapperFalse.hasSecondaryProfile())
        assertFalse(wrapperFalse.hasManagedProfile())
    }

    @Test
    @Suppress("DEPRECATION")
    fun isInstalledInWorkProfile_isTransitionalAliasOfIsInstalledInOtherProfile() {
        val trueWrapper = object : CrossProfileAppsWrapper(app) {
            override fun isInstalledInOtherProfile(packageName: String): Boolean? = true
        }
        val falseWrapper = object : CrossProfileAppsWrapper(app) {
            override fun isInstalledInOtherProfile(packageName: String): Boolean? = false
        }
        val nullWrapper = object : CrossProfileAppsWrapper(app) {
            override fun isInstalledInOtherProfile(packageName: String): Boolean? = null
        }

        assertEquals(true, trueWrapper.isInstalledInWorkProfile("pkg"))
        assertEquals(false, falseWrapper.isInstalledInWorkProfile("pkg"))
        assertEquals(null, nullWrapper.isInstalledInWorkProfile("pkg"))
    }
}
