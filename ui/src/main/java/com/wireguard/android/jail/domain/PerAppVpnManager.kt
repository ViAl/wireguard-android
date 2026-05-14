/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.domain

import com.wireguard.android.jail.model.JailTunnelMode
import com.wireguard.android.jail.storage.JailStore
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.model.TunnelManager
import com.wireguard.android.viewmodel.ConfigProxy
import com.wireguard.android.viewmodel.SplitTunnelingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Applies [JailTunnelMode] choices onto an **existing** tunnel using the same [ConfigProxy] surface
 * the Routing tab uses. Conflicts between include- and exclude-based Jail policies, or between
 * Jail include mode and an active exclude-only routing configuration, surface as failures so the UI
 * can explain the situation honestly.
 */
class PerAppVpnManager(private val tunnelManager: TunnelManager) {

    sealed class ApplyResult {
        object Success : ApplyResult()
        data class Conflict(val message: String) : ApplyResult()
        object TunnelNotFound : ApplyResult()
    }

    suspend fun applyJailRouting(
        jailTunnelName: String,
        policies: Map<String, JailTunnelMode>,
        previouslyManaged: Set<String>,
    ): ApplyResult = withContext(Dispatchers.Main.immediate) {
        val tunnel = tunnelManager.getTunnels().find { it.name == jailTunnelName }
            ?: return@withContext ApplyResult.TunnelNotFound

        val routePkgs = policies.filter { isRouteMode(it.value) }.keys
        val exPkgs = policies.filter { it.value == JailTunnelMode.JAIL_EXCLUDE_FROM_TUNNEL }.keys

        if (routePkgs.isNotEmpty() && exPkgs.isNotEmpty()) {
            return@withContext ApplyResult.Conflict(
                "Jail cannot combine include-tunnel and exclude-from-tunnel policies on the same tunnel at once.",
            )
        }

        val newManaged = buildSet {
            addAll(routePkgs)
            addAll(exPkgs)
        }

        val config = tunnel.getConfigAsync()
        val proxy = ConfigProxy(config)
        val iface = proxy.`interface`

        val toStrip = previouslyManaged - newManaged
        for (pkg in toStrip) {
            iface.includedApplications.removeAll { it == pkg }
            iface.excludedApplications.removeAll { it == pkg }
        }

        if (routePkgs.isEmpty() && exPkgs.isEmpty()) {
            if (iface.includedApplications.isEmpty() && iface.excludedApplications.isEmpty())
                iface.splitTunnelingMode = SplitTunnelingMode.ALL_APPLICATIONS
            tunnel.setConfigAsync(proxy.resolve())
            JailStore.setJailManagedPackages(emptySet())
            return@withContext ApplyResult.Success
        }

        if (routePkgs.isNotEmpty()) {
            when (iface.splitTunnelingMode) {
                SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS ->
                    return@withContext ApplyResult.Conflict(
                        "This tunnel is in “exclude apps” routing mode. Open the Routing tab, switch to “include only” or “all apps”, then retry.",
                    )

                SplitTunnelingMode.ALL_APPLICATIONS -> {
                    iface.splitTunnelingMode = SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS
                    iface.normalizeForSplitTunnelingMode()
                    iface.includedApplications.addAll(routePkgs)
                }

                SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS -> {
                    val merged = iface.includedApplications.map { it }.toMutableSet()
                    merged.addAll(routePkgs)
                    iface.includedApplications.clear()
                    iface.includedApplications.addAll(merged)
                }
            }
        } else if (exPkgs.isNotEmpty()) {
            when (iface.splitTunnelingMode) {
                SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS ->
                    return@withContext ApplyResult.Conflict(
                        "This tunnel is in “include only” routing mode. Jail cannot add exclude rules without changing that mode first — adjust the Routing tab or pick a different tunnel.",
                    )

                SplitTunnelingMode.ALL_APPLICATIONS -> {
                    iface.splitTunnelingMode = SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS
                    iface.normalizeForSplitTunnelingMode()
                    iface.excludedApplications.addAll(exPkgs)
                }

                SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS -> {
                    val merged = iface.excludedApplications.map { it }.toMutableSet()
                    merged.addAll(exPkgs)
                    iface.excludedApplications.clear()
                    iface.excludedApplications.addAll(merged)
                }
            }
        }

        tunnel.setConfigAsync(proxy.resolve())
        JailStore.setJailManagedPackages(newManaged)
        ApplyResult.Success
    }

    private fun isRouteMode(mode: JailTunnelMode) =
        mode == JailTunnelMode.JAIL_ROUTE_THROUGH_TUNNEL || mode == JailTunnelMode.JAIL_STRICT_PROFILE

    suspend fun findTunnel(name: String): ObservableTunnel? = withContext(Dispatchers.Main.immediate) {
        tunnelManager.getTunnels().find { it.name == name }
    }
}
