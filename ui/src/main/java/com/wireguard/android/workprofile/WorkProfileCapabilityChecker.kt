package com.wireguard.android.workprofile

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager

open class WorkProfileCapabilityChecker(
    private val context: Context,
    private val adminComponentName: ComponentName,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    fun isSupported(): Boolean = sdkInt >= Build.VERSION_CODES.P

    fun isProfileOwnerApp(): Boolean = runCatching {
        dpm?.isProfileOwnerApp(context.packageName) == true
    }.getOrDefault(false)

    open fun hasManagedProfile(): Boolean = runCatching {
        userManager?.userProfiles?.any { it != android.os.Process.myUserHandle() } == true
    }.getOrDefault(false)

    open fun canUseDpcOperations(): Boolean = isSupported() && isProfileOwnerApp()

    fun adminComponent(): ComponentName = adminComponentName
}
