/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.ui

import com.wireguard.android.jail.model.JailDestination
import com.wireguard.android.jail.viewmodel.JailViewModel

/**
 * Bridge that Jail sub-fragments use to reach the root [JailFragment] without a ViewModelProvider.
 *
 * Declared top-level so [JailFragment] can implement it without a Kotlin cycle in nested types.
 */
interface JailFragmentHost {
    val jailViewModel: JailViewModel
    fun navigateTo(destination: JailDestination)

    /** Full-screen overlay with honest expectations copy. */
    fun openHelp()

    /** App audit drill-in layered on apps (or another tab). */
    fun openAppDetail(packageName: String)
}
