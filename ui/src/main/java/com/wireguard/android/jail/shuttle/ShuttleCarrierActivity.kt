/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.shuttle

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log

/**
 * Trampoline activity that establishes cross-profile URI permission grants
 * for ShuttleProvider.
 *
 * This is a direct copy of Island's `ShuttleCarrierActivity` pattern.
 * **No LauncherApps reflection is used.** Cross-profile routing is done
 * via the system's cross-profile Activity forwarder using the
 * `CATEGORY_PARENT_PROFILE` intent category.
 *
 * ## Flow
 *
 * 1. A ShuttleProvider in the **work profile** calls
 *    `sendToParentProfileQuietlyIfPossible()` which starts this activity
 *    locally (within the work profile).
 *
 * 2. In `onCreate()`, the activity checks `isOwnerUser()`. Since we're in
 *    the work profile (not owner), it calls `startActivityForResult()` with
 *    the intent decorated by `CrossProfileShuttle.decorateIntentForActivityInParentProfile()`.
 *    This adds `CATEGORY_PARENT_PROFILE`, causing the system's cross-profile
 *    Activity forwarder to route the intent to the parent profile.
 *
 * 3. In the parent profile, `onCreate()` runs again. This time `isOwnerUser()`
 *    is true, so it calls `ShuttleProvider.collect()` to take persistable URI
 *    permission, then `setResult()` to send a grant back to the work profile.
 *
 * 4. Back in the work profile, `onActivityResult()` calls
 *    `ShuttleProvider.collect()` again to take the return grant.
 */
class ShuttleCarrierActivity : Activity() {

    companion object {
        private const val TAG = "WG.ShuttleCarrier"
        private const val ACTION_SHUTTLE = "com.wireguard.android.action.SHUTTLE"
        private const val SILENT_LAUNCH_FLAGS = Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                Intent.FLAG_ACTIVITY_NO_HISTORY

        /**
         * Send a URI grant intent to the parent profile to establish
         * cross-profile URI permission.
         *
         * Called from ShuttleProvider.initialize() when running in the
         * work profile and no URI permission is yet granted.
         *
         * Mirrors Island's `ShuttleCarrierActivity.sendToParentProfileQuietlyIfPossible()`.
         *
         * @param context    context (must be work profile)
         * @param decoration lambda to configure the intent (add clipData with URI, flags)
         */
        fun sendToParentProfileQuietlyIfPossible(context: Context,
                                                  decoration: Intent.() -> Unit) {
            val intent = Intent(context, ShuttleCarrierActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or SILENT_LAUNCH_FLAGS)
                .apply(decoration)

            // Wrap in try-catch to guard against ActivityNotFound when device is
            // locked (credential-gated) — the cross-profile forwarder may not
            // be available until the device is unlocked.
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot launch shuttle activity (device may be locked)", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isOwnerUser()) {
            // We are in the PARENT profile — collect URI permission and
            // send a reverse grant back to the work profile.
            Log.d(TAG, "Parent profile: collecting URI permission")
            ShuttleProvider.collect(this, intent)

            // Send reverse shuttle back to work profile
            setResult(RESULT_OK, Intent(null, ShuttleProvider.bareContentUri(this))
                .setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                          Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
            finish()
        } else {
            // We are in the WORK profile — forward to parent profile
            // via the system's cross-profile Activity forwarder.
            Log.d(TAG, "Work profile: forwarding to parent profile")
            val launchIntent = intent
                .setAction(ACTION_SHUTTLE)
                .setComponent(null)
                .apply { flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv() }
            try {
                CrossProfileShuttle.decorateIntentForActivityInParentProfile(
                    this, launchIntent
                )
                startActivityForResult(launchIntent, 1)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot forward to parent profile", e)
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.v(TAG, "onActivityResult: $data")
        // Receive the reverse shuttle sent back as activity result
        ShuttleProvider.collect(this, data ?: return)
        finish()
    }

    /** Check if this process is running in the owner (primary) user profile. */
    private fun isOwnerUser(): Boolean {
        return try {
            Process.myUserHandle().hashCode() == 0
        } catch (_: Exception) {
            true // conservative: assume owner if reflection fails
        }
    }
}
