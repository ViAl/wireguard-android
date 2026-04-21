/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import com.wireguard.android.jail.model.InstallResult
import com.wireguard.android.jail.model.UnsupportedReason
import com.wireguard.android.jail.model.UserActionReason

class WorkProfileAppInstallService(
    context: Context,
    private val capabilityChecker: WorkProfileAppInstallCapabilityChecker,
    private val fallbackLauncher: WorkProfileAppInstallCapabilityChecker.FallbackLauncher,
    private val installer: EnterpriseInstaller = AndroidEnterpriseInstaller(context),
) {

    fun install(packageName: String): InstallResult {
        val before = capabilityChecker.capabilityFor(packageName)
        if (before.installedInWorkProfile) return InstallResult.AlreadyInstalled

        if (before.canInstallAutomatically) {
            val installAttempt = runCatching { installer.installExistingPackage(packageName) }
                .fold(
                    onSuccess = { it },
                    onFailure = { throwable ->
                        return when (throwable) {
                            is SecurityException -> InstallResult.Unsupported(UnsupportedReason.SECURITY_RESTRICTED)
                            else -> InstallResult.Failed(throwable.message)
                        }
                    }
                )

            if (!installAttempt) {
                val afterFailure = capabilityChecker.capabilityFor(packageName)
                return if (afterFailure.installedInWorkProfile) {
                    InstallResult.Installed
                } else if (afterFailure.canLaunchManualFallback) {
                    InstallResult.UserActionRequired(UserActionReason.MANUAL_INSTALL_REQUIRED)
                } else {
                    InstallResult.Failed("installExistingPackage returned false")
                }
            }

            val afterInstall = capabilityChecker.capabilityFor(packageName)
            return if (afterInstall.installedInWorkProfile) {
                InstallResult.Installed
            } else {
                InstallResult.Failed("Package is still not visible in work profile after install")
            }
        }

        return if (before.canLaunchManualFallback) {
            InstallResult.UserActionRequired(UserActionReason.MANUAL_INSTALL_REQUIRED)
        } else {
            InstallResult.Unsupported(UnsupportedReason.OWNERSHIP_NOT_AVAILABLE)
        }
    }

    fun launchManualInstall(packageName: String): InstallResult {
        val capability = capabilityChecker.capabilityFor(packageName)
        if (capability.installedInWorkProfile) return InstallResult.AlreadyInstalled
        if (!capability.canLaunchManualFallback) {
            return InstallResult.Unsupported(UnsupportedReason.UNKNOWN)
        }

        val launched = fallbackLauncher.launchStoreIntent(packageName)
        if (!launched) return InstallResult.Failed("Could not launch store for manual install")

        val afterLaunch = capabilityChecker.capabilityFor(packageName)
        return if (afterLaunch.installedInWorkProfile) {
            InstallResult.Installed
        } else {
            InstallResult.UserActionRequired(UserActionReason.MANUAL_INSTALL_REQUIRED)
        }
    }

    interface EnterpriseInstaller {
        fun installExistingPackage(packageName: String): Boolean
    }

    private class AndroidEnterpriseInstaller(context: Context) : EnterpriseInstaller {
        private val appContext = context.applicationContext
        private val dpm = context.getSystemService(DevicePolicyManager::class.java)

        override fun installExistingPackage(packageName: String): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            val manager = dpm ?: return false
            return manager.installExistingPackage(
                ComponentHolder.adminComponent(appContext),
                packageName,
            )
        }
    }
}

private object ComponentHolder {
    fun adminComponent(context: Context) = android.content.ComponentName(
        context,
        JailDeviceAdminReceiver::class.java,
    )
}
