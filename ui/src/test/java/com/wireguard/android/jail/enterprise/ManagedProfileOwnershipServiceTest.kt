/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.wireguard.android.jail.model.ManagedProfileOwnershipState
import com.wireguard.android.jail.model.WorkProfileState
import com.wireguard.android.jail.system.ManagedProfileDetector
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManagedProfileOwnershipServiceTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun state_noManagedProfile() {
        val service = ManagedProfileOwnershipService(
            context = app,
            detector = fakeDetector(WorkProfileState.NO_SECONDARY_PROFILE),
            systemApi = fakeSystemApi(false),
        )
        assertEquals(ManagedProfileOwnershipState.NO_MANAGED_PROFILE, service.state())
    }

    @Test
    fun state_secondaryProfilePresentNotOurs() {
        val service = ManagedProfileOwnershipService(
            context = app,
            detector = fakeDetector(WorkProfileState.SECONDARY_PROFILE_PRESENT),
            systemApi = fakeSystemApi(false),
        )
        assertEquals(ManagedProfileOwnershipState.SECONDARY_PROFILE_PRESENT_NOT_OURS, service.state())
    }

    @Test
    fun state_managedConfirmedNotOurs() {
        val service = ManagedProfileOwnershipService(
            context = app,
            detector = fakeDetector(WorkProfileState.MANAGED_PROFILE_CONFIRMED),
            systemApi = fakeSystemApi(false),
        )
        assertEquals(ManagedProfileOwnershipState.MANAGED_PROFILE_PRESENT_NOT_OURS, service.state())
    }

    @Test
    fun state_managedUncertainNotOurs() {
        val service = ManagedProfileOwnershipService(
            context = app,
            detector = fakeDetector(WorkProfileState.MANAGED_PROFILE_UNCERTAIN),
            systemApi = fakeSystemApi(false),
        )
        assertEquals(ManagedProfileOwnershipState.OWNERSHIP_UNCERTAIN, service.state())
    }

    @Test
    fun state_managedOwnedByUs() {
        val service = ManagedProfileOwnershipService(
            context = app,
            detector = fakeDetector(WorkProfileState.SECONDARY_PROFILE_PRESENT),
            systemApi = fakeSystemApi(true),
        )
        assertEquals(ManagedProfileOwnershipState.MANAGED_PROFILE_OURS, service.state())
    }

    private fun fakeDetector(state: WorkProfileState): ManagedProfileDetector = object : ManagedProfileDetector(app) {
        override fun readSnapshot(): DetectionSnapshot = when (state) {
            WorkProfileState.NO_SECONDARY_PROFILE -> DetectionSnapshot(true, false, false, false)
            WorkProfileState.SECONDARY_PROFILE_PRESENT -> DetectionSnapshot(true, true, false, false)
            WorkProfileState.MANAGED_PROFILE_CONFIRMED -> DetectionSnapshot(true, true, true, false)
            WorkProfileState.MANAGED_PROFILE_UNCERTAIN -> DetectionSnapshot(true, true, false, true)
            WorkProfileState.UNSUPPORTED -> DetectionSnapshot(false, false, false, false)
        }
    }

    private fun fakeSystemApi(profileOwner: Boolean): ManagedProfileOwnershipService.OwnershipSystemApi =
        object : ManagedProfileOwnershipService.OwnershipSystemApi {
            override fun isProfileOwnerApp(packageName: String): Boolean = profileOwner
        }
}
