/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Installs a package from the primary profile into the managed (work) profile
 * using the Device Policy Controller (DPC) API.
 *
 * This is the most direct way to clone an app into the work profile — it
 * requires that this app is set as the profile owner of the target profile.
 *
 * Two strategies, tried in order:
 *
 * 1. [DevicePolicyManager.installExistingPackage] (API 21+) — installs a
 *    package already present in the primary profile into the managed profile.
 *    This is the preferred method and works silently (no user interaction).
 *
 * 2. [DevicePolicyManager.setCrossProfileCalendarPackages] — not applicable,
 *    we need general package install, not calendar-specific.
 *
 * Fallback: if DPC install is unavailable, callers should fall back to the
 * existing launch-Play-Store strategies in [WorkProfileAppInstallCapabilityChecker].
 */
class DpcPackageInstaller(
    private val context: Context,
) {
    private val dpm: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)

    private val adminComponent = ComponentName(context, JailDeviceAdminReceiver::class.java)

    /**
     * Attempts to install [packageName] into the managed work profile.
     *
     * @return [DpcInstallResult] with success/failure details and a
     *         human-readable message suitable for logging.
     */
    fun install(packageName: String): DpcInstallResult {
        WorkProfileLogger.d("DpcPackageInstaller: install($packageName) started")
        return try {
            installInternal(packageName)
        } catch (e: Exception) {
            WorkProfileLogger.e("DpcPackageInstaller: install($packageName) exception", e)
            DpcInstallResult.Failure("DPC install threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Quick check whether DPC-based installation is *likely* to work,
     * without actually performing the install.
     *
     * ADB provisioning path: when set-profile-owner is run via ADB, the app
     * becomes profile owner immediately but [DevicePolicyManager.isProfileOwnerApp]
     * may return false on some OEMs (Samsung, Xiaomi) immediately after
     * provisioning. We use a fallback check via shared preferences to detect
     * this case.
     */
    fun isAvailable(): DpcAvailability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            WorkProfileLogger.d("DpcPackageInstaller: SDK ${Build.VERSION.SDK_INT} < 21 — unsupported")
            return DpcAvailability.Unavailable("API level ${Build.VERSION.SDK_INT} < 21")
        }

        val dpm = this.dpm
        if (dpm == null) {
            WorkProfileLogger.e("DpcPackageInstaller: DPM is null")
            return DpcAvailability.Unavailable("DevicePolicyManager is null")
        }

        val isProfileOwner = runCatching {
            dpm.isProfileOwnerApp(context.packageName)
        }.getOrDefault(false)

        val isDeviceOwner = runCatching {
            dpm.isDeviceOwnerApp(context.packageName)
        }.getOrDefault(false)

        val isAdminActive = runCatching {
            dpm.isAdminActive(adminComponent)
        }.getOrDefault(false)

        // Also check via ManagedProfileOwnershipService
        val ownershipService = ManagedProfileOwnershipService(context)
        val ownershipState = ownershipService.state()

        // Check via JailDeviceAdminReceiver (provisioning prefs)
        val provisioned = JailDeviceAdminReceiver().isProvisioned(context)

        // Check via PostProvisioningHandler prefs (ADB provisioning fallback)
        val adbProvisioned = PostProvisioningHandler.isProvisioned(context)

        WorkProfileLogger.d(
            "DpcPackageInstaller: isProfileOwner=$isProfileOwner, isDeviceOwner=$isDeviceOwner, " +
            "isAdminActive=$isAdminActive, ownershipState=$ownershipState, provisioned=$provisioned, " +
            "adbProvisioned=$adbProvisioned"
        )

        // ADB provisioning path: admin active + prefs flag is sufficient
        // even if isProfileOwnerApp returns false on some OEMs.
        if (isAdminActive && adbProvisioned) {
            WorkProfileLogger.d("DpcPackageInstaller: ADB-provisioned via admin+prefs")
            return DpcAvailability.Available
        }

        if (!isProfileOwner) {
            WorkProfileLogger.d("DpcPackageInstaller: not profile owner (package=${context.packageName})")
            return DpcAvailability.Unavailable("Not profile owner")
        }

        if (!isAdminActive) {
            WorkProfileLogger.d("DpcPackageInstaller: admin component not active ($adminComponent)")
            return DpcAvailability.Unavailable("Admin component not active")
        }

        WorkProfileLogger.d("DpcPackageInstaller: DPC is available")
        return DpcAvailability.Available
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun installInternal(packageName: String): DpcInstallResult {
        val availability = isAvailable()
        if (availability !is DpcAvailability.Available) {
            val msg = (availability as DpcAvailability.Unavailable).reason
            WorkProfileLogger.e("DpcPackageInstaller: not available — $msg")
            return DpcInstallResult.Failure("DPC not available: $msg")
        }

        // Verify the package exists in the primary profile.
        val pkgInfo = runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
        }.getOrNull()

        if (pkgInfo == null) {
            WorkProfileLogger.e("DpcPackageInstaller: package $packageName not found in any profile")
            return DpcInstallResult.Failure("Package $packageName not found")
        }

        WorkProfileLogger.d(
            "DpcPackageInstaller: package $packageName v${pkgInfo.versionName} " +
                "(${pkgInfo.versionCode}) found, calling installExistingPackage"
        )

        // Install the existing package into the managed profile.
        val success = runCatching {
            dpm?.installExistingPackage(adminComponent, packageName)
            true
        }.getOrDefault(false)

        if (success) {
            WorkProfileLogger.d("DpcPackageInstaller: installExistingPackage($packageName) returned true")
            return DpcInstallResult.Success
        } else {
            WorkProfileLogger.e("DpcPackageInstaller: installExistingPackage($packageName) returned false")
            return DpcInstallResult.Failure(
                "installExistingPackage returned false " +
                    "(package may already be installed in work profile, " +
                    "or the operation is not permitted on this device)"
            )
        }
    }
}

/** Result of a DPC package installation attempt. */
sealed class DpcInstallResult {
    data object Success : DpcInstallResult()
    data class Failure(val message: String) : DpcInstallResult()

    val isSuccess: Boolean get() = this is Success
}

/** Whether DPC-based package installation is available. */
sealed class DpcAvailability {
    data object Available : DpcAvailability()
    data class Unavailable(val reason: String) : DpcAvailability()

    val isAvailable: Boolean get() = this is Available
}
