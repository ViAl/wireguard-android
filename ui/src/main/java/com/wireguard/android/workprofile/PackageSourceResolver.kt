package com.wireguard.android.workprofile

import android.content.Context
import android.content.pm.PackageManager

class PackageSourceResolver(private val context: Context) {
    fun getApkPaths(packageName: String): List<String> {
        val paths = mutableListOf<String>()
        try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            if (appInfo.sourceDir != null) {
                paths.add(appInfo.sourceDir)
            }
            if (appInfo.splitSourceDirs != null) {
                paths.addAll(appInfo.splitSourceDirs)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // Ignore, handled by returning empty list
        }
        return paths.distinct()
    }
}
