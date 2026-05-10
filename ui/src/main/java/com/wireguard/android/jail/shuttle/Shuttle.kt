/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.shuttle

import android.content.Context
import android.os.UserHandle
import android.util.Log

/**
 * High-level Shuttle API: execute lambdas in another user profile.
 *
 * Usage (from parent profile, to run code in work profile):
 * ```kotlin
 * val result = Shuttle(context, workProfileHandle).invoke {
 *     // This runs inside the work profile
 *     DevicePolicies(this).enableSystemApp(pkg)
 * }
 * ```
 *
 * The shuttle uses ContentProvider.call() with cross-profile URIs
 * (`content://{profileId}@authority`). URI permission must be established
 * before the first call — this is handled automatically via
 * [ShuttleCarrierActivity.establishPermission] on the first `invoke()`
 * if the permission is not yet granted.
 */
class Shuttle(val context: Context, val to: UserHandle) {

    companion object {
        private const val TAG = "WG.Shuttle"
    }

    /**
     * Execute [function] in the target profile and return its result.
     * If target is the current profile, runs locally.
     */
    fun <R> invoke(function: Context.() -> R): R {
        val result = ShuttleProvider.call(context, to, function)
        if (result.isNotReady()) {
            Log.d(TAG, "Shuttle not ready — establishing permission")
            ShuttleCarrierActivity.establishPermission(context)
            // Retry after establishment
            val retry = ShuttleProvider.call(context, to, function)
            if (retry.isNotReady()) {
                throw IllegalStateException("Shuttle not ready after establish permission")
            }
            return retry.get()
        }
        return result.get()
    }

    /**
     * Execute a void lambda in the target profile.
     */
    fun launch(function: Context.() -> Unit) {
        invoke(function)
    }

    /**
     * Shuttle with an argument (avoids capturing in lambda).
     */
    inline fun <A, R> invoke(with: A, crossinline function: Context.(A) -> R): R {
        return invoke { this.function(with) }
    }

    inline fun <A> launch(with: A, crossinline function: Context.(A) -> Unit) {
        launch { function(with) }
    }
}
