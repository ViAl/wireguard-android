/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.wireguard.android.jail.shuttle.CrossProfileShuttle
import com.wireguard.android.jail.shuttle.ShuttleProvider

/**
 * Handles post-provisioning setup after this app becomes profile owner.
 *
 * Called after ADB provisioning (`dpm set-profile-owner`) or NFC provisioning
 * completes. Sets profile name, enables the profile, and registers the
 * cross-profile intent filter needed for Shuttle to work.
 *
 * ## When this runs
 *
 * - From [JailDeviceAdminReceiver.onProfileProvisioningComplete] when
 *   provisioning completes through the standard Android provisioning flow.
 * - From [Application.onCreate] auto-detect when the app is already a
 *   profile owner but provisioning prefs are absent (e.g. ADB provisioning).
 */
object PostProvisioningHandler {
    private const val TAG = "WG.PostProvisioning"
    private const val PREFS_NAME = "provisioning"
    private const val KEY_PROVISIONED = "profile_provisioned"

    fun isProvisioned(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROVISIONED, false)
    }

    /**
     * Run all post-provisioning steps. Safe to call multiple times —
     * already-provisioned state is idempotent.
     *
     * @return true if provisioning completed successfully (or was already done)
     */
    fun run(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        val admin = ComponentName(context, JailDeviceAdminReceiver::class.java)

        if (!dpm.isProfileOwnerApp(context.packageName)) {
            Log.w(TAG, "Not profile owner, skipping post-provisioning")
            return false
        }

        if (isProvisioned(context)) {
            Log.d(TAG, "Already provisioned")
            return true
        }

        try {
            // 1. Set profile name
            dpm.setProfileName(admin, "WireGuard")

            // 2. Enable the profile
            dpm.setProfileEnabled(admin)

            // 3. Register cross-profile intent filter for Shuttle
            val shuttleFilter = android.content.IntentFilter().apply {
                addAction(CrossProfileShuttle.SHUTTLE_ACTION)
                addCategory(CrossProfileShuttle.CATEGORY_PARENT_PROFILE)
            }
            dpm.addCrossProfileIntentFilter(
                admin,
                shuttleFilter,
                DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
            )

            // 4. Grant persistable URI permission to shuttle provider for
            //    cross-profile communication (API 34+).
            //    Use reflection to avoid compile-time dependency on API 34.
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    val grantMethod = dpm.javaClass.getMethod(
                        "grantCrossProfileUriPermission",
                        ComponentName::class.java,
                        String::class.java,
                        android.net.Uri::class.java,
                        Int::class.java
                    )
                    val shuttleUri = ShuttleProvider.bareContentUri(context)
                    grantMethod.invoke(
                        dpm,
                        admin,
                        context.packageName,
                        shuttleUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "grantCrossProfileUriPermission failed (non-fatal)", e)
                }
            }

            // 5. Mark as provisioned
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PROVISIONED, true)
                .apply()

            Log.d(TAG, "Post-provisioning completed successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Post-provisioning failed", e)
            return false
        }
    }
}
