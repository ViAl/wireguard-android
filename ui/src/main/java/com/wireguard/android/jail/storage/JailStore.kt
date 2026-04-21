/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.storage

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.wireguard.android.Application
import com.wireguard.android.jail.model.AuditSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONException
import org.json.JSONObject

/**
 * Centralised access to all Jail-related preferences, layered on top of the single
 * process-wide preferences [androidx.datastore.core.DataStore] that
 * [com.wireguard.android.Application] already creates.
 *
 * Design notes:
 *  * Keeping Jail state inside the existing DataStore avoids introducing a second
 *    storage file and keeps the serialization guarantees consistent with the rest of the app.
 *  * Every piece of Jail state has its own key; no mega-blob. Later phases add keys here
 *    (routing policies as JSON, launch presets, onboarding progress, ...).
 *  * Writes are off-main because `DataStore.edit` is a `suspend` function.
 *  * Audit snapshots are encoded as a single JSON object keyed by package name so one
 *    write can refresh the whole cache without racing per-package entries.
 */
object JailStore {
    private val KEY_SELECTED_APPS = stringSetPreferencesKey("jail_selected_apps")
    private val KEY_AUDIT_SNAPSHOTS = stringPreferencesKey("jail_audit_snapshots")

    val selectedApps: Flow<Set<String>>
        get() = Application.getPreferencesDataStore().data.map { it[KEY_SELECTED_APPS] ?: emptySet() }

    suspend fun setSelectedApps(packages: Set<String>) {
        Application.getPreferencesDataStore().edit { prefs ->
            if (packages.isEmpty()) prefs.remove(KEY_SELECTED_APPS)
            else prefs[KEY_SELECTED_APPS] = packages
        }
    }

    /**
     * Reactive stream of audit snapshots, keyed by package name. Emits an empty map
     * if the cache is missing or cannot be parsed — we never propagate a corrupted
     * blob to the UI.
     */
    val auditSnapshots: Flow<Map<String, AuditSnapshot>>
        get() = Application.getPreferencesDataStore().data.map { prefs ->
            prefs[KEY_AUDIT_SNAPSHOTS]?.let(::decodeSnapshots) ?: emptyMap()
        }

    suspend fun updateAuditSnapshot(snapshot: AuditSnapshot) {
        Application.getPreferencesDataStore().edit { prefs ->
            val current = prefs[KEY_AUDIT_SNAPSHOTS]?.let(::decodeSnapshots) ?: emptyMap()
            val next = current.toMutableMap().apply { put(snapshot.packageName, snapshot) }
            prefs[KEY_AUDIT_SNAPSHOTS] = encodeSnapshots(next)
        }
    }

    /**
     * Trims the cache down to [retainPackages]. Call this after the selection set
     * changes so that un-selected apps stop bloating the preferences blob.
     */
    suspend fun retainAuditSnapshots(retainPackages: Set<String>) {
        Application.getPreferencesDataStore().edit { prefs ->
            val current = prefs[KEY_AUDIT_SNAPSHOTS]?.let(::decodeSnapshots) ?: return@edit
            val trimmed = current.filterKeys { it in retainPackages }
            if (trimmed.isEmpty()) prefs.remove(KEY_AUDIT_SNAPSHOTS)
            else prefs[KEY_AUDIT_SNAPSHOTS] = encodeSnapshots(trimmed)
        }
    }

    private fun encodeSnapshots(map: Map<String, AuditSnapshot>): String {
        val root = JSONObject()
        map.forEach { (pkg, snapshot) ->
            root.put(pkg, JSONObject(AuditSnapshotCodec.encode(snapshot)))
        }
        return root.toString()
    }

    private fun decodeSnapshots(raw: String): Map<String, AuditSnapshot> {
        if (raw.isBlank()) return emptyMap()
        val root = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            return emptyMap()
        }
        val out = HashMap<String, AuditSnapshot>(root.length())
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val obj = root.optJSONObject(key) ?: continue
            val snapshot = AuditSnapshotCodec.decode(obj.toString()) ?: continue
            out[key] = snapshot
        }
        return out
    }
}
