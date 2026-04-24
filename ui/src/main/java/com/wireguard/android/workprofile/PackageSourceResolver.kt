package com.wireguard.android.workprofile

import android.content.Context
import android.content.pm.PackageManager

class PackageSourceResolver(private val context: Context) {
    fun getApkPaths(packageName: String): List<String> {
        val paths = mutableListOf<String>()
        try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            val sourceDir = appInfo.sourceDir
            if (sourceDir != null) {
                paths.add(sourceDir)
            }
            val splitDirs = appInfo.splitSourceDirs
            if (splitDirs != null) {
                paths.addAll(splitDirs)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // Ignore, handled by returning empty list
        }
        return paths.distinct()
    }
}
