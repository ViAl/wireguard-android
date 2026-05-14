/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.domain

import android.content.Context
import com.wireguard.android.jail.model.AuditSnapshot
import com.wireguard.android.jail.storage.JailSelectionStore
import com.wireguard.android.jail.storage.JailStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reactive source of truth for audit snapshots. Bridges
 * [com.wireguard.android.jail.storage.JailStore] (persistence) and
 * [AppAuditManager] (inspection) so the UI only sees a single flow.
 *
 * Thread model:
 *  * All inspection happens on `Dispatchers.Default`.
 *  * Persistence writes hop to `Dispatchers.IO` via DataStore's own dispatcher.
 *  * Flows are hot (`stateIn(scope)`) so multiple fragments observing the same
 *    data don't multiply re-scan work.
 */
class JailAuditRepository(
    private val auditManager: AppAuditManager,
    private val selectionStore: JailSelectionStore,
    scope: CoroutineScope,
) {
    /**
     * `true` while a refresh pass is in flight. The Apps / detail UIs use this to
     * show a progress indicator without relying on timing heuristics.
     */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val snapshots: StateFlow<Map<String, AuditSnapshot>> = JailStore.auditSnapshots
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Emits the snapshot for a single package, or `null` while none exists yet. */
    fun snapshotFor(packageName: String): Flow<AuditSnapshot?> =
        snapshots.map { it[packageName] }

    /**
     * Runs the auditor for every currently-selected package in parallel and writes
     * the results to the cache. Safe to call from the UI; it internally hops off the
     * main thread.
     *
     * Also trims stale snapshots for packages that are no longer selected, so the
     * preferences blob does not grow unbounded.
     */
    suspend fun refreshAllSelected(context: Context) {
        val selected = selectionStore.selected.value
        _isRefreshing.value = true
        try {
            JailStore.retainAuditSnapshots(selected)
            if (selected.isEmpty()) return
            withContext(Dispatchers.Default) {
                selected.map { pkg ->
                    async { auditManager.audit(context, pkg) }
                }.awaitAll().filterNotNull().forEach { snapshot ->
                    JailStore.updateAuditSnapshot(snapshot)
                }
            }
        } finally {
            _isRefreshing.value = false
        }
    }

    /**
     * Refreshes a single package. Used by the detail screen's "re-audit" action.
     * Does not touch other snapshots.
     */
    fun refreshOne(context: Context, packageName: String, scope: CoroutineScope) {
        scope.launch {
            val snapshot = auditManager.audit(context, packageName) ?: return@launch
            JailStore.updateAuditSnapshot(snapshot)
        }
    }
}
