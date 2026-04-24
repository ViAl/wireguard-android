package com.wireguard.android.workprofile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.content.ActivityNotFoundException

class PlayStoreLauncher(private val context: Context) {
    companion object {
        private const val TAG = "WG-WorkProfile"
    }

    fun launch(packageName: String): PackageCloneResult {
        if (packageName.isBlank()) {
            return PackageCloneResult.ErrorPackageNotFound
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                setPackage("com.android.vending")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return PackageCloneResult.RedirectedToPlayStore
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Play Store not found with package, trying without package", e)
            return try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                PackageCloneResult.RedirectedToPlayStore
            } catch (e2: ActivityNotFoundException) {
                Log.e(TAG, "Failed to open Play Store entirely", e2)
                PackageCloneResult.ErrorPlayStoreUnavailable
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Play Store", e)
            return PackageCloneResult.ErrorUnknown(e.message ?: "Unknown error")
        }
    }
}
