/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.shuttle

import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.util.Log

/**
 * High-level Shuttle API: execute lambdas in another user profile.
 *
 * Mirrors Island's `Shuttle` class. Uses `ShuttleProvider.call()` to
 * dispatch a Closure (serialized lambda) to the target profile's
 * `ContentProvider.call()`, which deserializes and executes it.
 *
 * Usage (from parent profile, to run code in work profile):
 * ```kotlin
 * val result = Shuttle(context, workProfileHandle).invoke {
 *     // This runs inside the work profile
 *     DevicePolicies(this).enableSystemApp(pkg)
 * }
 * ```
 *
 * URI permission is established automatically by `ShuttleProvider.onCreate()`
 * when the provider starts in the work profile. If not yet ready,
 * `invoke()` will throw `IllegalStateException("Shuttle not ready")`.
 *
 * **No LauncherApps reflection is used.**
 */
class Shuttle(val context: Context, val to: UserHandle) {

    companion object {
        private const val TAG = "WG.Shuttle"
    }

    /**
     * Execute [function] in the target profile and return its result.
     * If target is the current profile, runs locally (no IPC).
     */
    fun <R> invoke(function: Context.() -> R): R {
        val current = Process.myUserHandle()
        if (to.hashCode() == current.hashCode()) {
            // Same profile — run locally
            return context.function()
        }

        val result = ShuttleProvider.call(context, to, function)
        if (result.isNotReady()) {
            throw IllegalStateException(
                "Shuttle not ready. Ensure the work profile process has " +
                        "been started (e.g., via LauncherApps.startActivity()) " +
                        "so that ShuttleProvider.onCreate() can establish " +
                        "URI permission grants."
            )
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
