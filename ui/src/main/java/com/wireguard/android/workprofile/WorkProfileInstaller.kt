package com.wireguard.android.workprofile

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

open class WorkProfileInstaller(
    private val context: Context,
    private val capabilityChecker: WorkProfileCapabilityChecker,
    private val packageSourceResolver: PackageSourceResolver = PackageSourceResolver(context),
    private val apkSessionWriter: ApkSessionWriter = ApkSessionWriter(context),
) {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)

    open fun install(packageName: String): PackageCloneResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return PackageCloneResult.ErrorUnsupportedAndroidVersion
        if (!capabilityChecker.canUseDpcOperations()) return PackageCloneResult.ErrorNotProfileOwner

        val admin = capabilityChecker.adminComponent()
        val manager = dpm ?: return PackageCloneResult.ErrorNotProfileOwner

        try {
            if (manager.installExistingPackage(admin, packageName)) {
                Log.i(TAG, "installExistingPackage success: $packageName")
                return PackageCloneResult.SuccessInstalledExisting
            }
            Log.i(TAG, "installExistingPackage returned false, try next strategy: $packageName")
        } catch (securityException: SecurityException) {
            Log.w(TAG, "installExistingPackage denied for $packageName", securityException)
            return PackageCloneResult.ErrorPermissionDenied
        }

        if (packageSourceResolver.isSystemPackage(packageName)) {
            try {
                val enabled = manager.enableSystemApp(admin, packageName)
                if (enabled != 0) {
                    Log.i(TAG, "enableSystemApp success: $packageName")
                    return PackageCloneResult.SuccessEnabledSystemApp
                }
            } catch (securityException: SecurityException) {
                Log.w(TAG, "enableSystemApp denied for $packageName", securityException)
                return PackageCloneResult.ErrorPermissionDenied
            }
        }

        val sources = try {
            packageSourceResolver.resolve(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return PackageCloneResult.ErrorPackageNotFound
        } catch (securityException: SecurityException) {
            return PackageCloneResult.ErrorPermissionDenied
        } catch (t: Throwable) {
            return PackageCloneResult.ErrorUnknown(t.message ?: "resolve sources failed")
        }

        val committed = runCatching { apkSessionWriter.install(packageName, sources) }
            .getOrElse {
                Log.e(TAG, "APK session install failed: $packageName", it)
                return PackageCloneResult.ErrorInstallSessionFailed
            }
        return if (committed) {
            PackageCloneResult.SuccessInstalledFromApkSession
        } else {
            PackageCloneResult.ErrorInstallSessionFailed
        }
    }

    private companion object {
        const val TAG = "WG-WorkProfile"
    }
}
