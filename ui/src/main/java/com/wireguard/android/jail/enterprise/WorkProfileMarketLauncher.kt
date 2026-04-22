/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.content.Context
import android.content.Intent
import android.content.IntentSender
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
                canLaunchViaLegacyDetailsIntent(packageName, profile)
        } ?: false

    override fun launchInWorkProfile(packageName: String): Boolean {
        val profile = targetProfile() ?: return false
        if (launchViaAppMarketIntentSender(packageName, profile)) return true

        return launchViaLegacyDetailsIntent(packageName, profile)
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

    private fun canLaunchViaLegacyDetailsIntent(packageName: String, profile: UserHandle): Boolean {
        if (isAppMarketIntentApiSupported()) return false
        if (!bridge.hasPlayStoreInProfile(profile)) return false
        return legacyDetailsIntents(packageName, profile).any { it.resolveActivity(packageManager) != null }
    }

    private fun launchViaLegacyDetailsIntent(packageName: String, profile: UserHandle): Boolean {
        if (isAppMarketIntentApiSupported()) return false
        if (!bridge.hasPlayStoreInProfile(profile)) return false

        val candidate = legacyDetailsIntents(packageName, profile).firstOrNull { it.resolveActivity(packageManager) != null }
            ?: return false

        return runCatching {
            bridge.startActivity(candidate)
            true
        }.getOrDefault(false)
    }

    private fun legacyDetailsIntents(packageName: String, profile: UserHandle): List<Intent> = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")),
    ).map { base ->
        Intent(base).apply {
            setPackage(PLAY_STORE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            putExtra(EXTRA_USER, profile)
        }
    }

    private fun isAppMarketIntentApiSupported(): Boolean = Build.VERSION.SDK_INT >= 35

    private companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
        const val EXTRA_USER = "android.intent.extra.USER"
    }
}

interface LauncherAppsBridge {
    fun otherProfiles(): List<UserHandle>
    fun getAppMarketActivityIntentSender(packageName: String, targetProfile: UserHandle): IntentSender?
    fun hasPlayStoreInProfile(targetProfile: UserHandle): Boolean
    fun startActivity(intent: Intent)
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

    override fun hasPlayStoreInProfile(targetProfile: UserHandle): Boolean =
        runCatching {
            launcherApps?.getActivityList(PLAY_STORE_PACKAGE, targetProfile).orEmpty().isNotEmpty()
        }.getOrDefault(false)

    override fun startActivity(intent: Intent) {
        appContext.startActivity(intent)
    }

    private companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
    }
}
