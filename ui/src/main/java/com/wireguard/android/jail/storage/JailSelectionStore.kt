/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Process-lifetime facade over [JailStore] that exposes the user's selected Jail packages as a
 * hot [StateFlow] and funnels mutations back through the DataStore.
 *
 * Keeping this object alive for the whole application scope means sub-fragments can observe
 * selection without each re-subscribing to the cold DataStore flow (which would re-read the
 * file on every fragment creation).
 */
class JailSelectionStore(private val scope: CoroutineScope) {
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    init {
        JailStore.selectedApps
            .onEach { _selected.value = it }
            .launchIn(scope)
    }

    /** Toggle [packageName] in the selection set and persist the result. */
    fun toggle(packageName: String) {
        val current = _selected.value
        val next = if (packageName in current) current - packageName else current + packageName
        persist(next)
    }

    /** Replace the selection set wholesale (used by "select all" / "clear" affordances). */
    fun setAll(packages: Set<String>) {
        persist(packages)
    }

    private fun persist(next: Set<String>) {
        // Update the in-memory cache eagerly so the UI reflects the change on the next frame,
        // then persist asynchronously. If persistence fails the DataStore flow will re-emit
        // the previous value and the in-memory cache will converge back.
        _selected.value = next
        scope.launch { JailStore.setSelectedApps(next) }
    }
}
