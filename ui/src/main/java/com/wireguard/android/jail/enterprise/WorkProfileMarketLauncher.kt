/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle

interface WorkProfileMarketLauncher {
    fun canLaunchInWorkProfile(packageName: String): Boolean
    fun launchInWorkProfile(packageName: String): Boolean
}

class AndroidWorkProfileMarketLauncher(
    context: Context,
    private val bridge: LauncherAppsBridge = AndroidLauncherAppsBridge(
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
            bridge.getAppMarketActivityIntentSender(packageName, profile) != null
        } ?: false

    override fun launchInWorkProfile(packageName: String): Boolean {
        val profile = targetProfile() ?: return false
        val intentSender = bridge.getAppMarketActivityIntentSender(packageName, profile) ?: return false
        return launchIntentSender(intentSender)
    }

    private fun targetProfile(): UserHandle? = bridge.otherProfiles().firstOrNull()
}

interface LauncherAppsBridge {
    fun otherProfiles(): List<UserHandle>
    fun getAppMarketActivityIntentSender(packageName: String, targetProfile: UserHandle): IntentSender?
}

class AndroidLauncherAppsBridge(
    private val launcherApps: LauncherApps?,
) : LauncherAppsBridge {
    override fun otherProfiles(): List<UserHandle> =
        launcherApps?.profiles.orEmpty().filterNot { it == Process.myUserHandle() }

    override fun getAppMarketActivityIntentSender(packageName: String, targetProfile: UserHandle): IntentSender? =
        runCatching {
            launcherApps?.getAppMarketActivityIntent(packageName, targetProfile)
        }.getOrNull()
}
