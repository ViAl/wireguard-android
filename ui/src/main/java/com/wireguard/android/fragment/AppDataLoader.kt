/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PackageInfoFlags
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.databinding.Observable
import com.wireguard.android.BR
import com.wireguard.android.model.ApplicationData

object AppDataLoader {
    private const val TAG = "WireGuard/AppDataLoader"

    private data class CachedApplicationRecord(
        val icon: android.graphics.drawable.Drawable,
        val name: String,
        val packageName: String,
        val isSystemApp: Boolean
    )

    private val cacheLock = Any()
    @Volatile
    private var cachedApplications: List<CachedApplicationRecord>? = null

    fun load(pm: PackageManager, selectedPackages: Set<String>, onSelectionChanged: () -> Unit): List<ApplicationData> {
        val loadStart = SystemClock.elapsedRealtime()
        val baseData = getOrBuildCachedApplicationRecords(pm)
        val hydrationStart = SystemClock.elapsedRealtime()
        val applicationData = ArrayList<ApplicationData>(baseData.size)
        baseData.forEach { cached ->
            val appData = ApplicationData(
                drawableForListItem(cached.icon),
                cached.name,
                cached.packageName,
                cached.isSystemApp,
                selectedPackages.contains(cached.packageName)
            )
            appData.addOnPropertyChangedCallback(object : Observable.OnPropertyChangedCallback() {
                override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                    if (propertyId == BR.selected)
                        onSelectionChanged()
                }
            })
            applicationData.add(appData)
        }
        Log.d(
            TAG,
            "App list hydration finished in ${SystemClock.elapsedRealtime() - hydrationStart} ms " +
                "(total ${SystemClock.elapsedRealtime() - loadStart} ms, size=${applicationData.size})"
        )
        applicationData.sortWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, ApplicationData::name)
                .thenBy(String.CASE_INSENSITIVE_ORDER, ApplicationData::packageName)
        )
        return applicationData
    }

    private fun getOrBuildCachedApplicationRecords(pm: PackageManager): List<CachedApplicationRecord> {
        cachedApplications?.let { return it }
        synchronized(cacheLock) {
            cachedApplications?.let { return it }
            val buildStart = SystemClock.elapsedRealtime()
            val cached = ArrayList<CachedApplicationRecord>()
            getPackagesHoldingPermissions(pm, arrayOf(Manifest.permission.INTERNET)).forEach {
                val packageName = it.packageName
                val appInfo = it.applicationInfo ?: return@forEach
                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                cached.add(
                    CachedApplicationRecord(
                        icon = appInfo.loadIcon(pm),
                        name = appInfo.loadLabel(pm).toString(),
                        packageName = packageName,
                        isSystemApp = isSystemApp
                    )
                )
            }
            cached.sortWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, CachedApplicationRecord::name)
                    .thenBy(String.CASE_INSENSITIVE_ORDER, CachedApplicationRecord::packageName)
            )
            cachedApplications = cached
            Log.d(TAG, "Built app metadata/icon cache in ${SystemClock.elapsedRealtime() - buildStart} ms (size=${cached.size})")
            return cached
        }
    }

    private fun drawableForListItem(cachedDrawable: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable {
        val constantState = cachedDrawable.constantState ?: return cachedDrawable
        return constantState.newDrawable().mutate()
    }

    private fun getPackagesHoldingPermissions(pm: PackageManager, permissions: Array<String>): List<PackageInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackagesHoldingPermissions(permissions, PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackagesHoldingPermissions(permissions, 0)
        }
    }
}
