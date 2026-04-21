/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State holder shared across the Jail sub-fragments. Intentionally not an
 * [androidx.lifecycle.ViewModel] so that this module does not pull in the lifecycle-viewmodel
 * dependency (the project's existing fragments follow the same "plain state class" pattern).
 *
 * Instances are created per [com.wireguard.android.jail.ui.JailFragment] and shared with the
 * child fragments via [com.wireguard.android.jail.ui.JailFragmentHost].
 */
class JailViewModel {
    private val _overviewState = MutableStateFlow(JailOverviewState())
    val overviewState: StateFlow<JailOverviewState> = _overviewState.asStateFlow()
}
