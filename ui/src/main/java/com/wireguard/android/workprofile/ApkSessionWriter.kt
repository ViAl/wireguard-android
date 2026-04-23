package com.wireguard.android.workprofile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import java.io.File

class ApkSessionWriter(private val context: Context) {
    private val packageInstaller: PackageInstaller = context.packageManager.packageInstaller

    fun install(packageName: String, apkFiles: List<File>): Boolean {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
        }
        val sessionId = packageInstaller.createSession(params)
        return runCatching {
            packageInstaller.openSession(sessionId).use { session ->
                apkFiles.forEach { apk ->
                    session.openWrite(apk.name, 0, apk.length()).use { out ->
                        apk.inputStream().use { input -> input.copyTo(out) }
                        session.fsync(out)
                    }
                }
                val callbackIntent = Intent(context, WorkProfileBridgeActivity::class.java).apply {
                    action = WorkProfileBridgeActivity.ACTION_INSTALL_COMMIT_CALLBACK
                    putExtra(WorkProfileBridgeActivity.EXTRA_PACKAGE_NAME, packageName)
                }
                val intentSender = android.app.PendingIntent.getActivity(
                    context,
                    sessionId,
                    callbackIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
                ).intentSender
                session.commit(intentSender)
            }
            true
        }.onFailure {
            Log.e(TAG, "PackageInstaller session failed for $packageName", it)
            runCatching { packageInstaller.abandonSession(sessionId) }
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "WG-WorkProfile"
    }
}
