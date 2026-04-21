/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.system

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.wireguard.android.jail.model.WorkProfileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManagedProfileDetectorTest {

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun detectState_unsupportedWhenMultipleUsersUnsupported() {
        val detector = fakeDetector(
            supportsMultipleUsers = false,
            hasSecondaryProfile = false,
            currentProfileManaged = false,
            hasCrossProfileTargets = false,
        )
        assertEquals(WorkProfileState.UNSUPPORTED, detector.detectState())
        assertFalse(detector.hasSecondaryProfile())
    }

    @Test
    fun detectState_noSecondaryProfile() {
        val detector = fakeDetector(
            supportsMultipleUsers = true,
            hasSecondaryProfile = false,
            currentProfileManaged = false,
            hasCrossProfileTargets = false,
        )
        assertEquals(WorkProfileState.NO_SECONDARY_PROFILE, detector.detectState())
        assertFalse(detector.hasSecondaryProfile())
    }

    @Test
    fun detectState_secondaryProfilePresentWithoutManagedHints() {
        val detector = fakeDetector(
            supportsMultipleUsers = true,
            hasSecondaryProfile = true,
            currentProfileManaged = false,
            hasCrossProfileTargets = false,
        )
        assertEquals(WorkProfileState.SECONDARY_PROFILE_PRESENT, detector.detectState())
        assertTrue(detector.hasSecondaryProfile())
    }

    @Test
    fun detectState_managedProfileConfirmedForCurrentProfile() {
        val detector = fakeDetector(
            supportsMultipleUsers = true,
            hasSecondaryProfile = true,
            currentProfileManaged = true,
            hasCrossProfileTargets = false,
        )
        assertEquals(WorkProfileState.MANAGED_PROFILE_CONFIRMED, detector.detectState())
    }

    @Test
    fun detectState_managedProfileUncertainFromCrossProfileTargets() {
        val detector = fakeDetector(
            supportsMultipleUsers = true,
            hasSecondaryProfile = true,
            currentProfileManaged = false,
            hasCrossProfileTargets = true,
        )
        assertEquals(WorkProfileState.MANAGED_PROFILE_UNCERTAIN, detector.detectState())
    }

    private fun fakeDetector(
        supportsMultipleUsers: Boolean,
        hasSecondaryProfile: Boolean,
        currentProfileManaged: Boolean,
        hasCrossProfileTargets: Boolean,
    ): ManagedProfileDetector = object : ManagedProfileDetector(app) {
        override fun readSnapshot(): DetectionSnapshot = DetectionSnapshot(
            supportsMultipleUsers = supportsMultipleUsers,
            hasSecondaryProfile = hasSecondaryProfile,
            currentProfileManaged = currentProfileManaged,
            hasCrossProfileTargets = hasCrossProfileTargets,
        )
    }
}
