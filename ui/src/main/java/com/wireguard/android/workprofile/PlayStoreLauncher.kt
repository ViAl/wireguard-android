package com.wireguard.android.workprofile

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log

open class PlayStoreLauncher(private val context: Context) {
    open fun launchInCurrentProfile(packageName: String, fromActivity: Activity? = null): PackageCloneResult {
        val baseIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(playStoreUrl(packageName))).apply {
            setPackage(PLAY_STORE_PACKAGE)
            if (fromActivity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val started = runCatching {
            (fromActivity ?: context).startActivity(baseIntent)
            true
        }.recoverCatching {
            if (it !is ActivityNotFoundException) throw it
            Log.w(TAG, "Play Store package missing, trying generic ACTION_VIEW")
            val fallback = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(playStoreUrl(packageName))).apply {
                if (fromActivity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            (fromActivity ?: context).startActivity(fallback)
            true
        }.getOrElse {
            Log.e(TAG, "Play Store unavailable for $packageName", it)
            return PackageCloneResult.ErrorPlayStoreUnavailable
        }

        return if (started) PackageCloneResult.RedirectedToPlayStore else PackageCloneResult.ErrorPlayStoreUnavailable
    }

    fun launchViaBridge(packageName: String, fromActivity: Activity): PackageCloneResult {
        val bridgeIntent = Intent(WorkProfileBridgeActivity.ACTION_OPEN_PLAY_STORE_IN_WORK).apply {
            setClass(fromActivity, WorkProfileBridgeActivity::class.java)
            putExtra(WorkProfileBridgeActivity.EXTRA_PACKAGE_NAME, packageName)
        }
        return runCatching {
            fromActivity.startActivity(bridgeIntent)
            PackageCloneResult.RedirectedToPlayStore
        }.getOrElse {
            Log.e(TAG, "Work profile helper not found for bridge launch", it)
            PackageCloneResult.ErrorNoWorkProfileHelper
        }
    }

    companion object {
        private const val TAG = "WG-WorkProfile"
        const val PLAY_STORE_PACKAGE = "com.android.vending"

        fun playStoreUrl(packageName: String): String =
            "https://play.google.com/store/apps/details?id=$packageName"
    }
}
