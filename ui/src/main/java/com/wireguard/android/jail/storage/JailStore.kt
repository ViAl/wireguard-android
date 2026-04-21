/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.storage

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.wireguard.android.Application
import com.wireguard.android.jail.model.AuditSnapshot
import com.wireguard.android.jail.model.JailTunnelMode
import com.wireguard.android.jail.model.SterileLaunchPreset
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

    /** Cap stored audit JSON size (plan: ~200 most recent snapshots). */
    private const val MAX_AUDIT_SNAPSHOT_ENTRIES = 200
    private val KEY_SELECTED_APPS = stringSetPreferencesKey("jail_selected_apps")
    private val KEY_AUDIT_SNAPSHOTS = stringPreferencesKey("jail_audit_snapshots")
    private val KEY_JAIL_TUNNEL_NAME = stringPreferencesKey("jail_tunnel_name")
    private val KEY_ROUTING_POLICIES = stringPreferencesKey("jail_routing_policies")
    private val KEY_JAIL_MANAGED_PACKAGES = stringSetPreferencesKey("jail_managed_packages")
    private val KEY_LAUNCH_PRESETS = stringPreferencesKey("jail_launch_presets")
    private val KEY_SETUP_STEPS = stringSetPreferencesKey("jail_setup_completed_steps")
    private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("jail_onboarding_completed")

    val selectedApps: Flow<Set<String>>
        get() = Application.getPreferencesDataStore().data.map { it[KEY_SELECTED_APPS] ?: emptySet() }

    val jailTunnelName: Flow<String?>
        get() = Application.getPreferencesDataStore().data.map { it[KEY_JAIL_TUNNEL_NAME] }

    val routingPolicies: Flow<Map<String, JailTunnelMode>>
        get() = Application.getPreferencesDataStore().data.map { prefs ->
            RoutingPolicyCodec.decode(prefs[KEY_ROUTING_POLICIES])
        }

    val jailManagedPackages: Flow<Set<String>>
        get() = Application.getPreferencesDataStore().data.map { it[KEY_JAIL_MANAGED_PACKAGES] ?: emptySet() }

    val launchPresets: Flow<Map<String, SterileLaunchPreset>>
        get() = Application.getPreferencesDataStore().data.map { prefs ->
            LaunchPresetCodec.decode(prefs[KEY_LAUNCH_PRESETS])
        }

    val setupCompletedSteps: Flow<Set<String>>
        get() = Application.getPreferencesDataStore().data.map { it[KEY_SETUP_STEPS] ?: emptySet() }

    val onboardingCompleted: Flow<Boolean>
        get() = Application.getPreferencesDataStore().data.map { it[KEY_ONBOARDING_COMPLETED] == true }

    suspend fun setSelectedApps(packages: Set<String>) {
        Application.getPreferencesDataStore().edit { prefs ->
            if (packages.isEmpty()) prefs.remove(KEY_SELECTED_APPS)
            else prefs[KEY_SELECTED_APPS] = packages
        }
    }

    suspend fun setJailTunnelName(name: String?) {
        Application.getPreferencesDataStore().edit { prefs ->
            if (name.isNullOrBlank()) prefs.remove(KEY_JAIL_TUNNEL_NAME)
            else prefs[KEY_JAIL_TUNNEL_NAME] = name
        }
    }

    suspend fun setRoutingPolicies(policies: Map<String, JailTunnelMode>) {
        Application.getPreferencesDataStore().edit { prefs ->
            if (policies.isEmpty()) prefs.remove(KEY_ROUTING_POLICIES)
            else prefs[KEY_ROUTING_POLICIES] = RoutingPolicyCodec.encode(policies)
        }
    }

    suspend fun setJailManagedPackages(packages: Set<String>) {
        Application.getPreferencesDataStore().edit { prefs ->
            if (packages.isEmpty()) prefs.remove(KEY_JAIL_MANAGED_PACKAGES)
            else prefs[KEY_JAIL_MANAGED_PACKAGES] = packages
        }
    }

    suspend fun setLaunchPresets(presets: Map<String, SterileLaunchPreset>) {
        Application.getPreferencesDataStore().edit { prefs ->
            if (presets.isEmpty()) prefs.remove(KEY_LAUNCH_PRESETS)
            else prefs[KEY_LAUNCH_PRESETS] = LaunchPresetCodec.encode(presets)
        }
    }

    suspend fun updateLaunchPreset(preset: SterileLaunchPreset) {
        Application.getPreferencesDataStore().edit { prefs ->
            val current = LaunchPresetCodec.decode(prefs[KEY_LAUNCH_PRESETS]).toMutableMap()
            current[preset.packageName] = preset
            prefs[KEY_LAUNCH_PRESETS] = LaunchPresetCodec.encode(current)
        }
    }

    suspend fun setSetupCompletedSteps(steps: Set<String>) {
        Application.getPreferencesDataStore().edit { prefs ->
            if (steps.isEmpty()) prefs.remove(KEY_SETUP_STEPS)
            else prefs[KEY_SETUP_STEPS] = steps
        }
    }

    suspend fun addSetupCompletedStep(stepId: String) {
        Application.getPreferencesDataStore().edit { prefs ->
            val cur = (prefs[KEY_SETUP_STEPS] ?: emptySet()).toMutableSet()
            cur.add(stepId)
            prefs[KEY_SETUP_STEPS] = cur
        }
    }

    suspend fun setOnboardingCompleted(done: Boolean) {
        Application.getPreferencesDataStore().edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETED] = done
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
            val capped = trimSnapshotsToMax(next)
            prefs[KEY_AUDIT_SNAPSHOTS] = encodeSnapshots(capped)
        }
    }

    private fun trimSnapshotsToMax(map: Map<String, AuditSnapshot>): Map<String, AuditSnapshot> {
        if (map.size <= MAX_AUDIT_SNAPSHOT_ENTRIES) return map
        return map.entries
            .sortedByDescending { it.value.generatedAtMillis }
            .take(MAX_AUDIT_SNAPSHOT_ENTRIES)
            .associate { it.key to it.value }
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
