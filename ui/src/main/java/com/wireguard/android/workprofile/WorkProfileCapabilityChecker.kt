package com.wireguard.android.workprofile

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process

class WorkProfileCapabilityChecker(private val context: Context) {
    fun isProfileOwner(): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        return dpm.isProfileOwnerApp(context.packageName)
    }

    fun hasWorkProfileHelper(): Boolean {
        // If we are already the profile owner, then obviously the helper is here.
        if (isProfileOwner()) return true
        
        // Check if there is another user profile (work profile) where this app is installed.
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps ?: return false
        val myUserHandle = Process.myUserHandle()
        for (userHandle in launcherApps.profiles) {
            if (userHandle != myUserHandle) {
                // Check if our package is installed in that profile
                try {
                    val activityList = launcherApps.getActivityList(context.packageName, userHandle)
                    if (activityList.isNotEmpty()) {
                        return true
                    }
                } catch (e: Exception) {
                    // Ignore SecurityExceptions etc
                }
            }
        }
        return false
    }
}
