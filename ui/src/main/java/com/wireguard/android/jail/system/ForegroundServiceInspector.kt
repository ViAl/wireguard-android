/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.system

import android.content.pm.PackageManager
import android.content.pm.PackageManager.PackageInfoFlags
import android.content.pm.ServiceInfo
import android.os.Build
import com.wireguard.android.jail.model.BackgroundAuditResult

/**
 * Enumerates declared `<service>` components in a package and classifies their
 * `foregroundServiceType` into a small vocabulary used by the scorer.
 *
 * We only read the manifest — whether the service is *currently running* in the
 * foreground is not reliably knowable for foreign packages and would vary over
 * time anyway. The declaration is scored as LIKELY for that reason.
 */
object ForegroundServiceInspector {
    /** Lower-case tokens recognised by the scorer. */
    const val TYPE_LOCATION = "location"
    const val TYPE_MICROPHONE = "microphone"
    const val TYPE_CAMERA = "camera"

    fun inspect(
        packageManager: PackageManager,
        packageName: String,
        isIgnoringBatteryOptimizations: Boolean?,
    ): BackgroundAuditResult {
        val services = fetchServices(packageManager, packageName)
        val types = HashSet<String>()
        var hasAny = false
        services.forEach { svc ->
            hasAny = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val bitmask = svc.foregroundServiceType
                if (bitmask and FOREGROUND_SERVICE_TYPE_LOCATION != 0) types.add(TYPE_LOCATION)
                if (bitmask and FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) types.add(TYPE_MICROPHONE)
                if (bitmask and FOREGROUND_SERVICE_TYPE_CAMERA != 0) types.add(TYPE_CAMERA)
            }
        }
        return BackgroundAuditResult(
            packageName = packageName,
            isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
            hasForegroundServices = hasAny,
            foregroundServiceTypes = types,
        )
    }

    private fun fetchServices(packageManager: PackageManager, packageName: String): List<ServiceInfo> = try {
        val flags = PackageManager.GET_SERVICES
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, flags)
        }
        info.services?.toList().orEmpty()
    } catch (_: PackageManager.NameNotFoundException) {
        emptyList()
    }

    // Kept as private constants so the code still compiles on lower min SDKs; these values match
    // `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` from API 29.
    private const val FOREGROUND_SERVICE_TYPE_LOCATION = 0x0000_0008
    private const val FOREGROUND_SERVICE_TYPE_CAMERA = 0x0000_0040
    private const val FOREGROUND_SERVICE_TYPE_MICROPHONE = 0x0000_0080
}
