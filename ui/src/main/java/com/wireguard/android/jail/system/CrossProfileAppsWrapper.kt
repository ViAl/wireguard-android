/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.system

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

/**
 * Thin seam over cross-profile discovery. Used by app inventory, sterile launch, and profile
 * guidance. Safe on devices without secondary profiles.
 */
open class CrossProfileAppsWrapper(private val context: Context) {
    private val profileDetector = ManagedProfileDetector(context)

    private val userManager: UserManager?
        get() = context.getSystemService(Context.USER_SERVICE) as? UserManager

    /** @return `true` when a secondary user/profile handle exists besides the current user. */
    open fun hasSecondaryProfile(): Boolean = profileDetector.hasSecondaryProfile()

    /**
     * Legacy shim retained for compatibility with existing call sites.
     * Prefer [hasSecondaryProfile] for uncertainty-aware wording in new code.
     */
    open fun hasManagedProfile(): Boolean = hasSecondaryProfile()

    /**
     * @return `true` if installed in another profile,
     *  `false` if we know another profile exists but the package is absent there,
     *  `null` if no secondary profile exists or introspection failed.
     */
    open fun isInstalledInOtherProfile(packageName: String): Boolean? {
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

    /** Legacy shim retained for compatibility. Prefer [isInstalledInOtherProfile]. */
    open fun isInstalledInWorkProfile(packageName: String): Boolean? = isInstalledInOtherProfile(packageName)

    /** UserHandle for the first non-current profile, if any. */
    fun otherProfileHandle(): UserHandle? {
        val um = userManager ?: return null
        val profiles = um.userProfiles ?: return null
        val mine = Process.myUserHandle()
        return profiles.firstOrNull { it != mine }
    }

    /**
     * Starts the app's main launcher activity in the first non-current user profile (typically
     * work), if one exists and the app is installed there. Returns `false` if nothing was started.
     */
    open fun tryStartMainActivityInOtherProfile(packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        val handle = otherProfileHandle() ?: return false
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return false
        val activities = try {
            launcherApps.getActivityList(packageName, handle)
        } catch (_: Throwable) {
            return false
        }
        val first = activities.firstOrNull() ?: return false
        val component: ComponentName = first.componentName
        return try {
            launcherApps.startMainActivity(component, handle, null, null)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
