/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.shuttle

import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log

/**
 * Cross-profile intent decoration for launching activities from the
 * work profile into the parent profile.
 *
 * This is a direct copy of Island's `CrossProfile` object, adapted
 * for WireGuard's package namespace.
 *
 * The key mechanism: when an intent has the `CATEGORY_PARENT_PROFILE`
 * category, Android's system Activity forwarder (package "android")
 * can route it from a managed profile to the parent profile.
 *
 * If the system forwarder is not pre-configured (e.g., on some OEM
 * devices), we add a cross-profile intent filter via DPM
 * `addCrossProfileIntentFilter()` and retry.
 */
object CrossProfileShuttle {

    private const val TAG = "WG.CrossProfileShuttle"

    /**
     * Intent category used by the system to route activities from
     * a managed profile to the parent profile.
     */
    const val CATEGORY_PARENT_PROFILE =
        "com.wireguard.android.category.PARENT_PROFILE"

    /**
     * Custom action for the shuttle trampoline activity.
     */
    const val SHUTTLE_ACTION = "com.wireguard.android.action.SHUTTLE"

    /**
     * Decorate [intent] so that it launches in the parent profile
     * via the system's cross-profile Activity forwarder.
     *
     * Strategy (mirrors Island's `CrossProfile.decorateIntentForActivityInParentProfile()`):
     *
     * 1. Add `CATEGORY_PARENT_PROFILE` and query for matching activities
     * 2. Look for the system forwarder (package "android")
     * 3. If not found, add a cross-profile intent filter via DPM and retry
     * 4. Set the forwarder as the explicit component
     *
     * @throws IllegalStateException if forwarder cannot be found or created
     */
    @JvmStatic
    fun decorateIntentForActivityInParentProfile(context: Context,
                                                  intent: Intent) {
        require(intent.data == null) {
            "Intent with data is not supported"
        }

        intent.addCategory(CATEGORY_PARENT_PROFILE)

        val candidates = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DISABLED_COMPONENTS or
                    PackageManager.MATCH_DEFAULT_ONLY
        )

        val forwarder = candidates.firstOrNull {
            it.activityInfo.packageName == "android"
        }

        if (forwarder != null) {
            Log.d(TAG, "Found system forwarder: ${forwarder.activityInfo}")
            intent.component = ComponentName(
                forwarder.activityInfo.packageName,
                forwarder.activityInfo.name
            )
            return
        }

        // Forwarder not found — add via DPM (same as Island pattern)
        Log.w(TAG, "System forwarder not found, adding via DPM")
        addRequiredForwarding(context, intent)

        // Retry after adding the filter
        val retry = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DISABLED_COMPONENTS or
                    PackageManager.MATCH_DEFAULT_ONLY
        )
        val retryForwarder = retry.firstOrNull {
            it.activityInfo.packageName == "android"
        }

        if (retryForwarder != null) {
            intent.component = ComponentName(
                retryForwarder.activityInfo.packageName,
                retryForwarder.activityInfo.name
            )
            return
        }

        throw IllegalStateException(
            "Cannot forward to parent profile: no system forwarder found " +
                    "and DPM addCrossProfileIntentFilter did not help"
        )
    }

    /**
     * Add a cross-profile intent filter via DevicePolicyManager so the
     * system knows that this Activity can be launched from the managed
     * profile into the parent profile.
     *
     * This is required on some devices (e.g., Samsung) where the system
     * forwarder is not pre-configured for custom categories.
     */
    private fun addRequiredForwarding(context: Context, intent: Intent) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm == null) {
            Log.w(TAG, "DPM not available, cannot add cross-profile filter")
            return
        }

        val adminComponent = resolveAdminComponent(context) ?: run {
            Log.w(TAG, "Admin component not found, cannot add cross-profile filter")
            return
        }

        val filter = IntentFilter(SHUTTLE_ACTION).apply {
            addCategory(CATEGORY_PARENT_PROFILE)
        }

        try {
            dpm.addCrossProfileIntentFilter(
                adminComponent, filter, FLAG_PARENT_CAN_ACCESS_MANAGED
            )
            Log.d(TAG, "Added cross-profile intent filter via DPM")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add cross-profile intent filter", e)
        }
    }

    /**
     * Resolve the DeviceAdminReceiver ComponentName.
     */
    private fun resolveAdminComponent(context: Context): ComponentName? {
        return try {
            ComponentName(
                context,
                Class.forName(
                    "com.wireguard.android.jail.enterprise.JailDeviceAdminReceiver"
                )
            )
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "JailDeviceAdminReceiver class not found", e)
            null
        }
    }
}
