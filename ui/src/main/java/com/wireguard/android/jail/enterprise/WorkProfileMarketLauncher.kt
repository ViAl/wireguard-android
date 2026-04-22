/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.ComponentName
import android.net.Uri
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

interface WorkProfileMarketLauncher {
    fun canLaunchInWorkProfile(packageName: String): Boolean
    fun launchInWorkProfile(packageName: String): Boolean
}

class AndroidWorkProfileMarketLauncher(
    context: Context,
    private val packageManager: android.content.pm.PackageManager = context.packageManager,
    private val bridge: LauncherAppsBridge = AndroidLauncherAppsBridge(
        context.applicationContext,
        context.getSystemService(UserManager::class.java),
        context.getSystemService(LauncherApps::class.java),
    ),
    private val launchIntentSender: (IntentSender) -> Boolean = { sender ->
        runCatching {
            context.applicationContext.startIntentSender(
                sender,
                null,
                Intent.FLAG_ACTIVITY_NEW_TASK,
                0,
                0,
            )
            true
        }.getOrDefault(false)
    },
) : WorkProfileMarketLauncher {
    override fun canLaunchInWorkProfile(packageName: String): Boolean =
        targetProfile()?.let { profile ->
            canLaunchViaAppMarketIntentSender(packageName, profile) ||
                canLaunchViaWorkProfileMainActivity(profile)
        } ?: false

    override fun launchInWorkProfile(packageName: String): Boolean {
        val profile = targetProfile() ?: return false
        if (launchViaAppMarketIntentSender(packageName, profile)) return true

        return launchViaLegacyPath(packageName, profile)
    }

    private fun targetProfile(): UserHandle? = bridge.otherProfiles().firstOrNull()

    private fun canLaunchViaAppMarketIntentSender(packageName: String, profile: UserHandle): Boolean {
        if (!isAppMarketIntentApiSupported()) return false
        return bridge.getAppMarketActivityIntentSender(packageName, profile) != null
    }

    private fun launchViaAppMarketIntentSender(packageName: String, profile: UserHandle): Boolean {
        if (!isAppMarketIntentApiSupported()) return false
        val intentSender = bridge.getAppMarketActivityIntentSender(packageName, profile) ?: return false
        return launchIntentSender(intentSender)
    }

    private fun canLaunchViaWorkProfileMainActivity(profile: UserHandle): Boolean {
        if (isAppMarketIntentApiSupported()) return false
        return bridge.findPlayStoreMainActivity(profile) != null
    }

    private fun launchViaLegacyPath(packageName: String, profile: UserHandle): Boolean {
        if (isAppMarketIntentApiSupported()) return false

        // Best-effort details deep link in current profile (cannot enforce cross-profile here).
        val deepLinkLaunched = deepLinkIntents(packageName)
            .firstOrNull { it.resolveActivity(packageManager) != null }
            ?.let { bridge.startActivity(it) }
            ?: false
        if (deepLinkLaunched) return true

        // Guaranteed cross-profile fallback: open Play Store home in target profile.
        val playStoreMain = bridge.findPlayStoreMainActivity(profile) ?: return false
        return bridge.startMainActivity(playStoreMain, profile)
    }

    private fun deepLinkIntents(packageName: String): List<Intent> = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")),
    ).map { base ->
        Intent(base).apply {
            setPackage(PLAY_STORE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
    }

    private fun isAppMarketIntentApiSupported(): Boolean = Build.VERSION.SDK_INT >= 35

    private companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
    }
}

interface LauncherAppsBridge {
    fun otherProfiles(): List<UserHandle>
    fun getAppMarketActivityIntentSender(packageName: String, targetProfile: UserHandle): IntentSender?
    fun findPlayStoreMainActivity(targetProfile: UserHandle): ComponentName?
    fun startMainActivity(componentName: ComponentName, targetProfile: UserHandle): Boolean
    fun startActivity(intent: Intent): Boolean
}

class AndroidLauncherAppsBridge(
    private val appContext: Context,
    private val userManager: UserManager?,
    private val launcherApps: LauncherApps?,
) : LauncherAppsBridge {
    override fun otherProfiles(): List<UserHandle> =
        userManager?.userProfiles.orEmpty().filterNot { it == Process.myUserHandle() }

    override fun getAppMarketActivityIntentSender(packageName: String, targetProfile: UserHandle): IntentSender? =
        runCatching {
            launcherApps?.getAppMarketActivityIntent(packageName, targetProfile)
        }.getOrNull()

    override fun findPlayStoreMainActivity(targetProfile: UserHandle): ComponentName? =
        runCatching {
            launcherApps?.getActivityList(PLAY_STORE_PACKAGE, targetProfile).orEmpty().firstOrNull()?.componentName
        }.getOrNull()

    override fun startMainActivity(componentName: ComponentName, targetProfile: UserHandle): Boolean =
        runCatching {
            launcherApps?.startMainActivity(componentName, targetProfile, null, null)
            true
        }.getOrDefault(false)

    override fun startActivity(intent: Intent): Boolean =
        runCatching {
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)

    private companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
    }
}
