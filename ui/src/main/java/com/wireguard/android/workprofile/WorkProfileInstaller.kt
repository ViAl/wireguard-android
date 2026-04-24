package com.wireguard.android.workprofile

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.ParcelFileDescriptor

class WorkProfileInstaller(
    private val context: Context,
    private val sessionWriter: ApkSessionWriter
) {
    fun installExistingPackage(packageName: String): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
            val adminComponent = ComponentName(context, ProfileAdminReceiver::class.java)
            try {
                return dpm.installExistingPackage(adminComponent, packageName)
            } catch (e: Exception) {
                // Ignore SecurityExceptions or others
            }
        }
        return false
    }

    fun enableSystemApp(packageName: String): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        val adminComponent = ComponentName(context, ProfileAdminReceiver::class.java)
        try {
            dpm.enableSystemApp(adminComponent, packageName)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun installFromApkSession(packageName: String, fds: List<ParcelFileDescriptor>, pendingIntent: PendingIntent): Boolean {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        try {
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            sessionWriter.writeApks(session, fds)
            session.commit(pendingIntent.intentSender)
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
