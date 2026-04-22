/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.ComponentName
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

interface WorkProfileMarketLauncher {
    fun canLaunchInWorkProfile(packageName: String): Boolean
    fun launchInWorkProfile(packageName: String): Boolean
}

class AndroidWorkProfileMarketLauncher(
    context: Context,
    private val bridge: LauncherAppsBridge = AndroidLauncherAppsBridge(
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
            bridge.getAppMarketActivityIntentSender(packageName, profile) != null ||
                bridge.findAppMarketActivity(profile) != null
        } ?: false

    override fun launchInWorkProfile(packageName: String): Boolean {
        val profile = targetProfile() ?: return false
        val intentSender = bridge.getAppMarketActivityIntentSender(packageName, profile)
        if (intentSender != null && launchIntentSender(intentSender)) return true

        val appMarketActivity = bridge.findAppMarketActivity(profile) ?: return false
        return bridge.startMainActivity(appMarketActivity, profile)
    }

    private fun targetProfile(): UserHandle? = bridge.otherProfiles().firstOrNull()
}

interface LauncherAppsBridge {
    fun otherProfiles(): List<UserHandle>
    fun getAppMarketActivityIntentSender(packageName: String, targetProfile: UserHandle): IntentSender?
    fun findAppMarketActivity(targetProfile: UserHandle): ComponentName?
    fun startMainActivity(activity: ComponentName, targetProfile: UserHandle): Boolean
}

class AndroidLauncherAppsBridge(
    private val userManager: UserManager?,
    private val launcherApps: LauncherApps?,
) : LauncherAppsBridge {
    override fun otherProfiles(): List<UserHandle> =
        userManager?.userProfiles.orEmpty().filterNot { it == Process.myUserHandle() }

    override fun getAppMarketActivityIntentSender(packageName: String, targetProfile: UserHandle): IntentSender? =
        runCatching {
            launcherApps?.getAppMarketActivityIntent(packageName, targetProfile)
        }.getOrNull()

    override fun findAppMarketActivity(targetProfile: UserHandle): ComponentName? =
        runCatching {
            launcherApps?.getActivityList(PLAY_STORE_PACKAGE, targetProfile).orEmpty().firstOrNull()?.componentName
        }.getOrNull()

    override fun startMainActivity(activity: ComponentName, targetProfile: UserHandle): Boolean =
        runCatching {
            launcherApps?.startMainActivity(activity, targetProfile, null, null)
            true
        }.getOrDefault(false)

    private companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
    }
}
