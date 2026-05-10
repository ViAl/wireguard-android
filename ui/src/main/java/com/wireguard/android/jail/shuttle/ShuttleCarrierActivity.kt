/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.shuttle

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.util.Log

/**
 * Trampoline activity that establishes cross-profile URI permission grants
 * for ShuttleProvider.
 *
 * Invocation flows:
 *
 * [Work profile] → startActivity(ShuttleCarrierActivity)
 *   → cross-profile intent to parent profile (via reflection on LauncherApps)
 *   → parent profile receives URI permission grant → persists it
 *   → sends result back → work profile confirms
 */
class ShuttleCarrierActivity : Activity() {

    companion object {
        private const val TAG = "WG.ShuttleCarrier"

        /**
         * Establish the shuttle URI permission from work profile to parent profile.
         * Called from work-profile code.
         *
         * Uses reflection on LauncherApps to avoid AGP 9.1.0 / kapt resolution issues
         * with `launcherApps.startActivity(component, user, rect, bundle)`.
         */
        fun establishPermission(context: Context) {
            // Build the content URI for our ShuttleProvider
            val uri = Uri.parse("content://${context.packageName}.shuttle")
            val intent = Intent(context, ShuttleCarrierActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newUri(context.contentResolver, "shuttle", uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                         Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or
                         Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                         Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                         Intent.FLAG_ACTIVITY_NO_HISTORY)
            }

            try {
                // Use reflection to start the activity in parent profile
                val launcherAppsService = context.getSystemService(Context.LAUNCHER_APPS_SERVICE)
                if (launcherAppsService != null) {
                    val launcherClass = launcherAppsService.javaClass
                    val profilesMethod = launcherClass.getMethod("getProfiles")
                    @Suppress("UNCHECKED_CAST")
                    val profiles = profilesMethod.invoke(launcherAppsService) as? List<*> ?: emptyList<Any>()
                    // Find parent profile (any profile that isn't current)
                    val myHandle = Process.myUserHandle()
                    val parentHandle = profiles.firstOrNull { it != myHandle } as? UserHandle
                    if (parentHandle != null) {
                        // Use the Intent overload so clipData & URI permission grants are
                        // carried across profiles. ComponentName overload would lose them.
                        val startMethod = launcherClass.getMethod(
                            "startActivity",
                            Intent::class.java,
                            UserHandle::class.java,
                            android.graphics.Rect::class.java,
                            Bundle::class.java
                        )
                        startMethod.invoke(launcherAppsService, intent,
                            parentHandle, null, null)
                        Log.d(TAG, "establishPermission: LauncherApps sent to parent=$parentHandle")
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "establishPermission: LauncherApps reflection failed", e)
            }

            // Fallback: start locally (current profile)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isRunningInParentProfile()) {
            // We're in the parent profile — collect URI permission grants
            Log.d(TAG, "In parent profile, collecting URI permissions")
            ShuttleProvider.collect(this, intent)
            // Send reverse result back
            val resultIntent = Intent(null, Uri.parse("content://${packageName}.shuttle"))
                .setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                          Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            setResult(RESULT_OK, resultIntent)
            finish()
        } else {
            // We're in the work profile — this shouldn't happen directly,
            // establishPermission() is the entry point
            Log.w(TAG, "ShuttleCarrierActivity started in work profile without trampoline")
            finish()
        }
    }

    private fun isRunningInParentProfile(): Boolean {
        // Simple heuristic: in a managed profile, userId != 0
        // This works for the common case where parent is user 0
        return try {
            val userMethod = Process::class.java.getMethod("myUserHandle")
            val myHandle = userMethod.invoke(null) as? UserHandle
            // If the process is running as user 0, it's the parent
            val idMethod = UserHandle::class.java.getMethod("hashCode")
            val myId = idMethod.invoke(myHandle) as? Int ?: 0
            myId == 0
        } catch (e: Exception) {
            true // conservative default
        }
    }
}
