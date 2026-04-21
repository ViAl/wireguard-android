/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.system

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

/**
 * Thin seam over cross-profile discovery. Used by app inventory, sterile launch, and work-profile
 * guidance. Safe on devices without a managed profile.
 */
open class CrossProfileAppsWrapper(private val context: Context) {

    private val userManager: UserManager?
        get() = context.getSystemService(Context.USER_SERVICE) as? UserManager

    /** @return `true` when a secondary user/profile handle exists besides the current user. */
    open fun hasManagedProfile(): Boolean {
        val um = userManager ?: return false
        if (!um.supportsMultipleUsers()) return false
        val profiles = um.userProfiles ?: return false
        val mine = Process.myUserHandle()
        return profiles.any { it != mine }
    }

    /**
     * @return `true` if installed in another profile assumed to be work/managed,
     *  `false` if we know there is another profile but the package is absent there,
     *  `null` if no secondary profile exists or introspection failed.
     */
    open fun isInstalledInWorkProfile(packageName: String): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        val um = userManager ?: return null
        val profiles = um.userProfiles ?: return null
        val mine = Process.myUserHandle()
        val others = profiles.filter { it != mine }
        if (others.isEmpty()) return null

        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return null

        var found = false
        for (h in others) {
            try {
                if (launcherApps.getActivityList(packageName, h).isNotEmpty()) {
                    found = true
                    break
                }
            } catch (_: Throwable) {
                return null
            }
        }
        return found
    }

    /** UserHandle for the first non-current profile, if any. */
    fun otherProfileHandle(): UserHandle? {
        val um = userManager ?: return null
        val profiles = um.userProfiles ?: return null
        val mine = Process.myUserHandle()
        return profiles.firstOrNull { it != mine }
    }
}
