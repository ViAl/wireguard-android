/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.Manifest
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PackageInfoFlags
import android.os.Build
import androidx.databinding.Observable
import com.wireguard.android.BR
import com.wireguard.android.model.ApplicationData

object AppDataLoader {
    fun load(pm: PackageManager, selectedPackages: Set<String>, onSelectionChanged: () -> Unit): List<ApplicationData> {
        val applicationData: MutableList<ApplicationData> = ArrayList()
        getPackagesHoldingPermissions(pm, arrayOf(Manifest.permission.INTERNET)).forEach {
            val packageName = it.packageName
            val appInfo = it.applicationInfo ?: return@forEach
            val appData = ApplicationData(
                appInfo.loadIcon(pm),
                appInfo.loadLabel(pm).toString(),
                packageName,
                selectedPackages.contains(packageName)
            )
            appData.addOnPropertyChangedCallback(object : Observable.OnPropertyChangedCallback() {
                override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                    if (propertyId == BR.selected)
                        onSelectionChanged()
                }
            })
            applicationData.add(appData)
        }
        applicationData.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER, ApplicationData::name).thenBy(String.CASE_INSENSITIVE_ORDER, ApplicationData::packageName))
        return applicationData
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
