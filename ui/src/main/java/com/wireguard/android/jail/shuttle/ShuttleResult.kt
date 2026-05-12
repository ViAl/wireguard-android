/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.shuttle

import android.os.Bundle

/**
 * Wraps a nullable Bundle result from ContentProvider.call().
 * Distinguishes "not ready" from "null result".
 */
@Suppress("EXPERIMENTAL_FEATURE_WARNING")
inline class ShuttleResult<R>(private val bundle: Bundle?) {

    companion object {
        internal val NOT_READY_BUNDLE = Bundle()
        internal val NOT_READY = ShuttleResult<Any>(NOT_READY_BUNDLE)
    }

    fun isNotReady(): Boolean = bundle === NOT_READY.bundle

    @Suppress("UNCHECKED_CAST")
    fun get(): R = bundle?.get(null) as R

    override fun toString() = when (this) {
        NOT_READY -> "ShuttleResult{NOT_READY}"
        else -> "ShuttleResult{" + bundle.toString() + "}"
    }
}
