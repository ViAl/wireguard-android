/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.IntentSender
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidWorkProfileMarketLauncherTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun launchInWorkProfile_successWhenTargetExistsAndIntentSenderAvailable() {
        val sender = pendingIntentSender()
        val launcher = AndroidWorkProfileMarketLauncher(
            context = app,
            bridge = FakeBridge(
                profiles = listOf(appUserHandle()),
                sender = sender,
            ),
            launchIntentSender = { true },
        )

        assertTrue(launcher.canLaunchInWorkProfile(PKG))
        assertTrue(launcher.launchInWorkProfile(PKG))
    }

    @Test
    fun launchInWorkProfile_unavailableWithoutSecondaryProfileTarget() {
        val launcher = AndroidWorkProfileMarketLauncher(
            context = app,
            bridge = FakeBridge(
                profiles = emptyList(),
                sender = pendingIntentSender(),
            ),
            launchIntentSender = { true },
        )

        assertFalse(launcher.canLaunchInWorkProfile(PKG))
        assertFalse(launcher.launchInWorkProfile(PKG))
    }

    @Test
    fun launchInWorkProfile_unavailableWhenNoMarketIntentSenderForTarget() {
        val launcher = AndroidWorkProfileMarketLauncher(
            context = app,
            bridge = FakeBridge(
                profiles = listOf(appUserHandle()),
                sender = null,
            ),
            launchIntentSender = { true },
        )

        assertFalse(launcher.canLaunchInWorkProfile(PKG))
        assertFalse(launcher.launchInWorkProfile(PKG))
    }

    @Test
    fun launchInWorkProfile_reportsFailureWhenStartThrowsOrFails() {
        val launcher = AndroidWorkProfileMarketLauncher(
            context = app,
            bridge = FakeBridge(
                profiles = listOf(appUserHandle()),
                sender = pendingIntentSender(),
            ),
            launchIntentSender = { false },
        )

        assertTrue(launcher.canLaunchInWorkProfile(PKG))
        assertFalse(launcher.launchInWorkProfile(PKG))
    }

    @Test
    fun launchInWorkProfile_fallsBackToStartMainActivityWhenIntentSenderUnavailable() {
        val launcher = AndroidWorkProfileMarketLauncher(
            context = app,
            bridge = FakeBridge(
                profiles = listOf(appUserHandle()),
                sender = null,
                hasAppMarketActivity = true,
                startMainActivityResult = true,
            ),
            launchIntentSender = { false },
        )

        assertTrue(launcher.canLaunchInWorkProfile(PKG))
        assertTrue(launcher.launchInWorkProfile(PKG))
    }

    private fun pendingIntentSender(): IntentSender = PendingIntent.getActivity(
        app,
        0,
        Intent(Intent.ACTION_VIEW),
        PendingIntent.FLAG_IMMUTABLE,
    ).intentSender

    private fun appUserHandle(): UserHandle = android.os.Process.myUserHandle()

    private class FakeBridge(
        private val profiles: List<UserHandle>,
        private val sender: IntentSender?,
        private val hasAppMarketActivity: Boolean = false,
        private val startMainActivityResult: Boolean = false,
    ) : LauncherAppsBridge {
        override fun otherProfiles(): List<UserHandle> = profiles

        override fun getAppMarketActivityIntentSender(
            packageName: String,
            targetProfile: UserHandle,
        ): IntentSender? = sender

        override fun findAppMarketActivity(targetProfile: UserHandle): ComponentName? =
            if (hasAppMarketActivity) ComponentName("com.android.vending", "FakePlayStoreActivity") else null

        override fun startMainActivity(activity: ComponentName, targetProfile: UserHandle): Boolean =
            startMainActivityResult
    }

    companion object {
        private const val PKG = "com.example.app"
    }
}
