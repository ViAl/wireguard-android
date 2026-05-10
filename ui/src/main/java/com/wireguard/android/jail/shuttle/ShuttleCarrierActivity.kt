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
 * The Shuttle is used from the PARENT profile to execute code in the
 * WORK profile. The ContentProvider resides in both profiles (since the
 * app is installed in both). To call the work profile's ContentProvider
 * via `content://{workProfileId}@{packageName}.shuttle`, the parent needs
 * URI permission.
 *
 * Permission establishment flow (round-trip):
 *
 * 1. [Parent] establishPermission() creates an intent, sends it to work
 *    profile via LauncherApps (carrying the bare shuttle URI)
 * 2. [Work profile] Activity receives intent → constructs cross-profile
 *    URI `content://{myUserId}@{packageName}.shuttle` → sends it back
 *    to parent via LauncherApps with FLAG_GRANT_WRITE_URI_PERMISSION
 * 3. [Parent] Activity receives intent → takes persistable URI permission
 *    on the cross-profile URI → now parent can call the work profile's
 *    ContentProvider
 */
class ShuttleCarrierActivity : Activity() {

    companion object {
        private const val TAG = "WG.ShuttleCarrier"
        private const val AUTHORITY_SUFFIX = ".shuttle"

        /**
         * Bootstrap the work profile process by launching ShuttleCarrierActivity
         * in the target profile via LauncherApps. This forces Android to create
         * the application process in the work profile, which triggers:
         *   - Application.onCreate()
         *   - ContentProvider.onCreate() for all providers (including ShuttleProvider)
         *   - ShuttleProvider.initialize() — sends URI grant back to parent
         *
         * Must be called from the parent profile context.
         */
        fun bootstrap(context: Context, workProfile: UserHandle) {
            try {
                val intent = Intent(context, ShuttleCarrierActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or
                             Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                             Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                             Intent.FLAG_ACTIVITY_NO_HISTORY)
                    putExtra("bootstrap", true)
                }

                val launcherAppsService = context.getSystemService(Context.LAUNCHER_APPS_SERVICE)
                if (launcherAppsService != null) {
                    val launcherClass = launcherAppsService.javaClass
                    val startMethod = launcherClass.getMethod(
                        "startActivity",
                        Intent::class.java,
                        UserHandle::class.java,
                        android.graphics.Rect::class.java,
                        Bundle::class.java
                    )
                    startMethod.invoke(launcherAppsService, intent, workProfile, null, null)
                    Log.d(TAG, "bootstrap: launched ShuttleCarrier in work=$workProfile")
                }
            } catch (e: Exception) {
                Log.e(TAG, "bootstrap: failed to start ShuttleCarrier in work profile", e)
            }
        }

        /**
         * Establish shuttle URI permission from parent → work profile.
         * Called from parent-profile code.
         *
         * Starts ShuttleCarrierActivity in the work profile via LauncherApps.
         * The work-profile instance will send a cross-profile URI grant back.
         */
        fun establishPermission(context: Context) {
            val bareUri = Uri.parse("content://${context.packageName}$AUTHORITY_SUFFIX")
            val intent = Intent(context, ShuttleCarrierActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newUri(context.contentResolver, "shuttle", bareUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                         Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or
                         Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                         Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                         Intent.FLAG_ACTIVITY_NO_HISTORY)
            }

            // Extra to signal this is a permission-establishment intent,
            // not a return hop. The work-profile instance checks this.
            intent.putExtra("establish", true)

            try {
                val launcherAppsService = context.getSystemService(Context.LAUNCHER_APPS_SERVICE)
                if (launcherAppsService != null) {
                    val launcherClass = launcherAppsService.javaClass
                    val profilesMethod = launcherClass.getMethod("getProfiles")
                    @Suppress("UNCHECKED_CAST")
                    val profiles = profilesMethod.invoke(launcherAppsService) as? List<*> ?: emptyList<Any>()
                    val myHandle = Process.myUserHandle()
                    // Find the work profile (any profile that isn't current parent)
                    val workProfileHandle = profiles.firstOrNull { it != myHandle } as? UserHandle
                    if (workProfileHandle != null) {
                        val startMethod = launcherClass.getMethod(
                            "startActivity",
                            Intent::class.java,
                            UserHandle::class.java,
                            android.graphics.Rect::class.java,
                            Bundle::class.java
                        )
                        startMethod.invoke(launcherAppsService, intent,
                            workProfileHandle, null, null)
                        Log.d(TAG, "establishPermission: LauncherApps sent to work=$workProfileHandle")
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "establishPermission: LauncherApps reflection failed", e)
            }

            // Fallback: start locally (current profile — parent)
            context.startActivity(intent)
        }

        /**
         * Returns the numeric user ID of the current process.
         * Equivalent to UserHandle.myUserId() but uses reflection for
         * maximum compatibility.
         */
        private fun getMyUserId(): Int {
            return try {
                val handle = Process.myUserHandle()
                val idMethod = UserHandle::class.java.getMethod("hashCode")
                idMethod.invoke(handle) as? Int ?: 0
            } catch (e: Exception) {
                0 // conservative default
            }
        }

        /**
         * Build a cross-profile URI: `content://{userId}@{packageName}.shuttle`
         */
        private fun buildCrossProfileUri(packageName: String, userId: Int): Uri {
            return Uri.Builder()
                .scheme("content")
                .encodedAuthority("$userId@${packageName}$AUTHORITY_SUFFIX")
                .build()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bootstrap: launched purely to create the process in work profile.
        // No round-trip needed — ShuttleProvider.initialize() already handles it.
        if (intent.getBooleanExtra("bootstrap", false)) {
            Log.d(TAG, "Bootstrap activity — finishing immediately")
            finish()
            return
        }

        val isEstablish = intent.getBooleanExtra("establish", false)
        val myUserId = getMyUserId()

        if (isEstablish) {
            // First hop: this activity was sent by parent into work profile.
            // Now build the cross-profile URI for the work profile's provider
            // and send it back to the parent with permission grant flags.
            Log.d(TAG, "Establish hop in work profile (userId=$myUserId)")

            val crossProfileUri = buildCrossProfileUri(packageName, myUserId)
            val returnIntent = Intent(this, ShuttleCarrierActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                data = crossProfileUri
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                         Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or
                         Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                         Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                         Intent.FLAG_ACTIVITY_NO_HISTORY)
                // No "establish" extra — this is the return hop
            }

            // Send to parent profile
            try {
                val launcherAppsService = getSystemService(Context.LAUNCHER_APPS_SERVICE)
                if (launcherAppsService != null) {
                    val launcherClass = launcherAppsService.javaClass
                    val profilesMethod = launcherClass.getMethod("getProfiles")
                    @Suppress("UNCHECKED_CAST")
                    val profiles = profilesMethod.invoke(launcherAppsService) as? List<*> ?: emptyList<Any>()
                    val myHandle = Process.myUserHandle()
                    // Find parent profile (any profile that isn't current — work profile)
                    val parentHandle = profiles.firstOrNull { it != myHandle } as? UserHandle
                    if (parentHandle != null) {
                        val startMethod = launcherClass.getMethod(
                            "startActivity",
                            Intent::class.java,
                            UserHandle::class.java,
                            android.graphics.Rect::class.java,
                            Bundle::class.java
                        )
                        startMethod.invoke(launcherAppsService, returnIntent,
                            parentHandle, null, null)
                        Log.d(TAG, "Return hop sent to parent=$parentHandle URI=$crossProfileUri")
                        finish()
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Return hop LauncherApps failed", e)
            }
        } else {
            // Second hop (or fallback): this activity is in the parent profile
            // (or received directly). Collect URI permission.
            Log.d(TAG, "Collect hop: intent data=${intent.data} clipData=${intent.clipData} flags=${intent.flags}")

            // Check if this is a grant from initialize() — data has bare URI with GRANT flags
            val isInitGrant = intent.data != null &&
                    (intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0

            if (isInitGrant) {
                // Grant from initialize(): work profile sent bare URI directly via data
                Log.d(TAG, "Collect hop from initialize: data=${intent.data}")
                ShuttleProvider.collect(this, intent)
            } else {
                // Collect from intent.data (cross-profile URI) first,
                // fall back to clipData (bare URI)
                val uriToPersist = intent.data ?: intent.clipData
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.uri

                if (uriToPersist != null) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uriToPersist, Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        Log.d(TAG, "Collected persistable URI permission: $uriToPersist")
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Failed to take persistable URI permission", e)
                    }
                }
            }
        }

        finish()
    }
}
