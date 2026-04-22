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
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPackageManager

@RunWith(RobolectricTestRunner::class)
class AndroidWorkProfileMarketLauncherTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Config(sdk = [35])
    @Test
    fun launchInWorkProfile_successWhenTargetExistsAndIntentSenderAvailable() {
        val sender = pendingIntentSender()
        val launcher = AndroidWorkProfileMarketLauncher(
            context = app,
            packageManager = app.packageManager,
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
    @Config(sdk = [35])
    fun launchInWorkProfile_unavailableWithoutSecondaryProfileTarget() {
        val launcher = AndroidWorkProfileMarketLauncher(
            context = app,
            packageManager = app.packageManager,
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
    @Config(sdk = [35])
    fun launchInWorkProfile_unavailableWhenNoMarketIntentSenderForTarget() {
        val launcher = AndroidWorkProfileMarketLauncher(
            context = app,
            packageManager = app.packageManager,
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
    @Config(sdk = [35])
    fun launchInWorkProfile_reportsFailureWhenStartThrowsOrFails() {
        val launcher = AndroidWorkProfileMarketLauncher(
            context = app,
            packageManager = app.packageManager,
            bridge = FakeBridge(
                profiles = listOf(appUserHandle()),
                sender = pendingIntentSender(),
            ),
            launchIntentSender = { false },
        )

        assertTrue(launcher.canLaunchInWorkProfile(PKG))
        assertFalse(launcher.launchInWorkProfile(PKG))
    }

    @Config(sdk = [34])
    @Test
    fun launchInWorkProfile_oldApi_usesDeepLinkWhenItStartsSuccessfully() {
        makePlayStoreIntentResolvable()
        val bridge = FakeBridge(
            profiles = listOf(appUserHandle()),
            sender = null,
            playStoreMainComponent = ComponentName("com.android.vending", "Main"),
            deepLinkStartResult = true,
            startMainActivityResult = true,
        )
        val launcher = AndroidWorkProfileMarketLauncher(
            context = app,
            packageManager = app.packageManager,
            bridge = bridge,
            launchIntentSender = { false },
        )

        assertTrue(launcher.canLaunchInWorkProfile(PKG))
        assertTrue(launcher.launchInWorkProfile(PKG))
        assertEquals(1, bridge.startActivityCalls)
        assertEquals(0, bridge.startMainActivityCalls)
    }

    @Config(sdk = [34])
    @Test
    fun launchInWorkProfile_oldApi_fallsBackToStartMainActivityWhenDeepLinkFails() {
        makePlayStoreIntentResolvable()
        val bridge = FakeBridge(
            profiles = listOf(appUserHandle()),
            sender = null,
            playStoreMainComponent = ComponentName("com.android.vending", "Main"),
            deepLinkStartResult = false,
            startMainActivityResult = true,
        )
        val launcher = AndroidWorkProfileMarketLauncher(
            context = app,
            packageManager = app.packageManager,
            bridge = bridge,
            launchIntentSender = { false },
        )

        assertTrue(launcher.canLaunchInWorkProfile(PKG))
        assertTrue(launcher.launchInWorkProfile(PKG))
        assertEquals(1, bridge.startActivityCalls)
        assertEquals(1, bridge.startMainActivityCalls)
    }

    @Config(sdk = [34])
    @Test
    fun launchInWorkProfile_oldApiUnavailableWhenNoWorkProfileMainActivity() {
        makePlayStoreIntentResolvable()
        val bridge = FakeBridge(
            profiles = listOf(appUserHandle()),
            sender = null,
            playStoreMainComponent = null,
            deepLinkStartResult = false,
            startMainActivityResult = false,
        )
        val launcher = AndroidWorkProfileMarketLauncher(
            context = app,
            packageManager = app.packageManager,
            bridge = bridge,
            launchIntentSender = { false },
        )

        assertFalse(launcher.canLaunchInWorkProfile(PKG))
        assertFalse(launcher.launchInWorkProfile(PKG))
    }

    @Config(sdk = [34])
    @Test
    fun launchInWorkProfile_oldApi_availableAndLaunchesWhenOnlyMainActivityPathExists() {
        val bridge = FakeBridge(
            profiles = listOf(appUserHandle()),
            sender = null,
            playStoreMainComponent = ComponentName("com.android.vending", "Main"),
            deepLinkStartResult = false,
            startMainActivityResult = true,
        )
        val launcher = AndroidWorkProfileMarketLauncher(
            context = app,
            packageManager = app.packageManager,
            bridge = bridge,
            launchIntentSender = { false },
        )

        assertTrue(launcher.canLaunchInWorkProfile(PKG))
        assertTrue(launcher.launchInWorkProfile(PKG))
        assertEquals(0, bridge.startActivityCalls)
        assertEquals(1, bridge.startMainActivityCalls)
    }

    private fun pendingIntentSender(): IntentSender = PendingIntent.getActivity(
        app,
        0,
        Intent(Intent.ACTION_VIEW),
        PendingIntent.FLAG_IMMUTABLE,
    ).intentSender

    private fun appUserHandle(): UserHandle = android.os.Process.myUserHandle()

    private fun makePlayStoreIntentResolvable() {
        val shadowPackageManager = org.robolectric.Shadows.shadowOf(app.packageManager) as ShadowPackageManager
        val resolveInfo = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "com.android.vending"
                name = "FakeActivity"
            }
        }
        shadowPackageManager.addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW).setPackage("com.android.vending"),
            resolveInfo,
        )
    }

    private class FakeBridge(
        private val profiles: List<UserHandle>,
        private val sender: IntentSender?,
        private val playStoreMainComponent: ComponentName? = null,
        private val deepLinkStartResult: Boolean = true,
        private val startMainActivityResult: Boolean = false,
    ) : LauncherAppsBridge {
        var startActivityCalls: Int = 0
        var startMainActivityCalls: Int = 0

        override fun otherProfiles(): List<UserHandle> = profiles

        override fun getAppMarketActivityIntentSender(
            packageName: String,
            targetProfile: UserHandle,
        ): IntentSender? = sender

        override fun findPlayStoreMainActivity(targetProfile: UserHandle): ComponentName? = playStoreMainComponent

        override fun startMainActivity(componentName: ComponentName, targetProfile: UserHandle): Boolean {
            startMainActivityCalls += 1
            return startMainActivityResult
        }

        override fun startActivity(intent: Intent): Boolean {
            startActivityCalls += 1
            return deepLinkStartResult
        }
    }

    companion object {
        private const val PKG = "com.example.app"
    }
}
