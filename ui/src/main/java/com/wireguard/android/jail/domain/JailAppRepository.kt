/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.domain

import android.content.Context
import com.wireguard.android.jail.model.AuditSnapshot
import com.wireguard.android.jail.model.JailAppBadge
import com.wireguard.android.jail.model.JailAppInfo
import com.wireguard.android.jail.storage.JailSelectionStore
import com.wireguard.android.jail.system.InstalledAppsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/**
 * Domain-level repository that surfaces the installed-apps list with up-to-date selection
 * state for the Jail UI.
 *
 * The installed-apps list is expensive to compute (PackageManager hits + icon/label loads),
 * so it is cached in a [MutableStateFlow] that the caller refreshes explicitly via
 * [refreshInstalledApps]. Selection state is layered on top reactively; flipping a checkbox
 * does not require re-scanning PackageManager. Emitted lists are sorted: selected user apps,
 * selected system apps, unselected user apps, then unselected system apps (label within each).
 */
class JailAppRepository(
    private val selectionStore: JailSelectionStore,
    private val classifier: JailAppClassifier
) {
    private val installedAppsBase = MutableStateFlow<List<JailAppInfoBase>>(emptyList())

    /**
     * Reactive view of the installed apps with current selection applied.
     *
     * Emits a new list whenever either the installed apps or the selection set changes.
     */
    val apps: Flow<List<JailAppInfo>> = combine(
        installedAppsBase.asStateFlow(),
        selectionStore.selected
    ) { base, selected ->
        base.map { it.withSelection(selected = it.packageName in selected) }
            .sortedWith(JAIL_APP_LIST_ORDER)
    }

    /** @return the badge list for [app] as computed by the classifier. */
    fun badgesFor(app: JailAppInfo, audit: AuditSnapshot? = null): List<JailAppBadge> =
        classifier.badgesFor(app, audit)

    /**
     * Re-scan installed apps. Safe to call off the main thread; safe to call while other
     * collectors are active — the update is atomic.
     */
    suspend fun refreshInstalledApps(context: Context) {
        val selected = selectionStore.selected.value
        val loaded = withContext(Dispatchers.Default) {
            InstalledAppsSource.load(context.packageManager, selected)
        }
        installedAppsBase.value = loaded.map(JailAppInfoBase::from)
    }

    /**
     * Immutable per-package info without selection state. Selection is applied on read so
     * that a checkbox toggle does not force a reload of icons and labels.
     */
    internal data class JailAppInfoBase(
        val label: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable?,
        val versionName: String?,
        val versionCode: Long,
        val isSystemApp: Boolean,
        val hasInternetPermission: Boolean,
        val installedInMainProfile: Boolean,
        val installedInOtherProfile: Boolean?
    ) {
        fun withSelection(selected: Boolean): JailAppInfo = JailAppInfo(
            label = label,
            packageName = packageName,
            icon = icon,
            versionName = versionName,
            versionCode = versionCode,
            isSystemApp = isSystemApp,
            hasInternetPermission = hasInternetPermission,
            installedInMainProfile = installedInMainProfile,
            installedInOtherProfile = installedInOtherProfile,
            isSelectedForJail = selected
        )

        companion object {
            fun from(info: JailAppInfo) = JailAppInfoBase(
                label = info.label,
                packageName = info.packageName,
                icon = info.icon,
                versionName = info.versionName,
                versionCode = info.versionCode,
                isSystemApp = info.isSystemApp,
                hasInternetPermission = info.hasInternetPermission,
                installedInMainProfile = info.installedInMainProfile,
                installedInOtherProfile = info.installedInOtherProfile
            )
        }
    }

    private companion object {
        /** Selected user → selected system → unselected user → unselected system. */
        private val JAIL_APP_LIST_ORDER =
            compareBy<JailAppInfo>({ jailAppsSortTier(it) })
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.packageName }

        private fun jailAppsSortTier(app: JailAppInfo): Int {
            val selected = app.isSelectedForJail
            val system = app.isSystemApp
            return when {
                selected && !system -> 0
                selected && system -> 1
                !selected && !system -> 2
                else -> 3
            }
        }
    }
}
