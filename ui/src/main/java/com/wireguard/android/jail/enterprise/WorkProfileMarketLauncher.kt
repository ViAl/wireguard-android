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
    fun resolveLaunchPath(packageName: String): WorkProfileMarketLaunchPath
    fun launch(packageName: String): WorkProfileMarketLaunchResult
}

enum class WorkProfileMarketLaunchPath {
    APP_MARKET_INTENT_SENDER,
    CURRENT_PROFILE_PLAY_DETAILS,
    WORK_PROFILE_PLAY_STORE_HOME,
    UNAVAILABLE,
}

data class WorkProfileMarketLaunchResult(
    val launched: Boolean,
    val path: WorkProfileMarketLaunchPath,
)

private fun WorkProfileMarketLaunchPath.isAvailable(): Boolean = this != WorkProfileMarketLaunchPath.UNAVAILABLE

private fun WorkProfileMarketLaunchResult.isLaunched(): Boolean = launched

fun WorkProfileMarketLauncher.canLaunchInWorkProfile(packageName: String): Boolean =
    resolveLaunchPath(packageName).isAvailable()

fun WorkProfileMarketLauncher.launchInWorkProfile(packageName: String): Boolean =
    launch(packageName).isLaunched()

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
    override fun resolveLaunchPath(packageName: String): WorkProfileMarketLaunchPath {
        if (isAppMarketIntentApiSupported()) {
            val profile = targetProfile() ?: return WorkProfileMarketLaunchPath.UNAVAILABLE
            return if (canLaunchViaAppMarketIntentSender(packageName, profile)) {
                WorkProfileMarketLaunchPath.APP_MARKET_INTENT_SENDER
            } else {
                WorkProfileMarketLaunchPath.UNAVAILABLE
            }
        }

        if (canLaunchViaDeepLink(packageName)) return WorkProfileMarketLaunchPath.CURRENT_PROFILE_PLAY_DETAILS
        return if (targetProfile()?.let { canLaunchViaWorkProfileMainActivity(it) } == true) {
            WorkProfileMarketLaunchPath.WORK_PROFILE_PLAY_STORE_HOME
        } else {
            WorkProfileMarketLaunchPath.UNAVAILABLE
        }
    }

    override fun launch(packageName: String): WorkProfileMarketLaunchResult {
        if (isAppMarketIntentApiSupported()) {
            val profile = targetProfile()
                ?: return WorkProfileMarketLaunchResult(false, WorkProfileMarketLaunchPath.UNAVAILABLE)
            val launched = launchViaAppMarketIntentSender(packageName, profile)
            return WorkProfileMarketLaunchResult(
                launched = launched,
                path = if (launched) {
                    WorkProfileMarketLaunchPath.APP_MARKET_INTENT_SENDER
                } else {
                    WorkProfileMarketLaunchPath.UNAVAILABLE
                },
            )
        }

        // Best-effort details deep link in current profile (cannot enforce cross-profile here).
        if (launchViaDeepLink(packageName)) {
            return WorkProfileMarketLaunchResult(true, WorkProfileMarketLaunchPath.CURRENT_PROFILE_PLAY_DETAILS)
        }

        // Guaranteed cross-profile fallback when another profile is available.
        val profile = targetProfile()
        if (profile != null && launchViaWorkProfileMainActivity(profile)) {
            return WorkProfileMarketLaunchResult(true, WorkProfileMarketLaunchPath.WORK_PROFILE_PLAY_STORE_HOME)
        }

        return WorkProfileMarketLaunchResult(false, WorkProfileMarketLaunchPath.UNAVAILABLE)
    }

    /**
     * Selects the first non-current profile exposed to this app.
     * This is a practical fallback target; it does not prove managed-profile ownership.
     */
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

    private fun launchViaDeepLink(packageName: String): Boolean {
        if (isAppMarketIntentApiSupported()) return false

        return deepLinkIntents(packageName)
            .firstOrNull { it.resolveActivity(packageManager) != null }
            ?.let { bridge.startActivity(it) }
            ?: false
    }

    private fun canLaunchViaDeepLink(packageName: String): Boolean {
        if (isAppMarketIntentApiSupported()) return false
        return deepLinkIntents(packageName).any { it.resolveActivity(packageManager) != null }
    }

    private fun launchViaWorkProfileMainActivity(profile: UserHandle): Boolean {
        if (isAppMarketIntentApiSupported()) return false
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
