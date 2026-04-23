package com.wireguard.android.workprofile

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

class PackageSourceResolver(private val context: Context) {
    private val packageManager = context.packageManager

    fun resolve(packageName: String): List<File> {
        val appInfo = runCatching {
            packageManager.getApplicationInfo(packageName, 0)
        }.getOrElse {
            Log.w(TAG, "resolve failed: package not found=$packageName", it)
            throw PackageManager.NameNotFoundException(packageName)
        }

        val sources = buildList {
            appInfo.sourceDir?.let { add(File(it)) }
            appInfo.splitSourceDirs?.forEach { add(File(it)) }
        }.filter { it.exists() }

        if (sources.isEmpty()) throw IllegalStateException("No APK sources for $packageName")
        return sources
    }

    fun isSystemPackage(packageName: String): Boolean = runCatching {
        val appInfo = packageManager.getApplicationInfo(packageName, 0)
        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(false)

    private companion object {
        const val TAG = "WG-WorkProfile"
    }
}
