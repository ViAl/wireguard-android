package com.wireguard.android.workprofile

import android.app.Activity
import android.content.Context
import android.util.Log

class WorkProfileInstallCoordinator(
    private val context: Context,
    private val capabilityChecker: WorkProfileCapabilityChecker,
    private val installer: WorkProfileInstaller,
    private val playStoreLauncher: PlayStoreLauncher,
) {
    fun installInWorkProfile(packageName: String, activity: Activity? = null): PackageCloneResult {
        if (packageName.isBlank()) {
            Log.w(TAG, "packageName empty")
            return PackageCloneResult.ErrorPackageNotFound
        }

        if (!capabilityChecker.hasManagedProfile()) {
            Log.w(TAG, "No work profile helper available")
            return PackageCloneResult.ErrorNoWorkProfileHelper
        }

        if (capabilityChecker.canUseDpcOperations()) {
            val result = installer.install(packageName)
            if (result is PackageCloneResult.SuccessInstalledExisting ||
                result is PackageCloneResult.SuccessEnabledSystemApp ||
                result is PackageCloneResult.SuccessInstalledFromApkSession
            ) {
                return result
            }
            if (result is PackageCloneResult.ErrorPermissionDenied || result is PackageCloneResult.ErrorNotProfileOwner) {
                Log.w(TAG, "DPC path not available, fallback to Play")
            } else if (result is PackageCloneResult.ErrorPackageNotFound) {
                return result
            }
        }

        if (activity != null) {
            return playStoreLauncher.launchViaBridge(packageName, activity)
        }
        return playStoreLauncher.launchInCurrentProfile(packageName, null)
    }

    private companion object {
        const val TAG = "WG-WorkProfile"
    }
}
