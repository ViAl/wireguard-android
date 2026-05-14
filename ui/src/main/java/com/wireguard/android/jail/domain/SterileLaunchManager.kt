/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.domain

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.wireguard.android.R
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.jail.model.AuditRiskLevel
import com.wireguard.android.jail.model.CheckStatus
import com.wireguard.android.jail.model.LaunchProfile
import com.wireguard.android.jail.model.SterileLaunchCheckItem
import com.wireguard.android.jail.model.SterileLaunchChecklist
import com.wireguard.android.jail.model.SterileLaunchPreset
import com.wireguard.android.jail.model.SterileLaunchResult
import com.wireguard.android.jail.storage.JailStore
import com.wireguard.android.jail.system.CrossProfileAppsWrapper
import com.wireguard.android.jail.system.PowerManagerWrapper
import com.wireguard.android.model.TunnelManager
import kotlinx.coroutines.flow.first

/**
 * Builds sterile-launch checklists and performs **explicit** launches (never silent tunnel
 * toggles). Profile-aware launches use standard intents; work-profile-specific routing is best-effort.
 */
class SterileLaunchManager(
    private val context: Context,
    private val tunnelManager: TunnelManager,
    private val crossProfile: CrossProfileAppsWrapper,
    private val auditRepository: JailAuditRepository,
    private val powerManager: PowerManagerWrapper,
) {

    suspend fun buildChecklist(
        packageName: String,
        preset: SterileLaunchPreset,
        selectedForJail: Boolean,
    ): SterileLaunchChecklist {
        val items = mutableListOf<SterileLaunchCheckItem>()
        val tunnelName = JailStore.jailTunnelName.first().takeIf { !it.isNullOrBlank() }

        items += if (tunnelName != null) {
            SterileLaunchCheckItem(
                id = "tunnel_named",
                title = context.getString(R.string.jail_launch_check_tunnel_named),
                detail = tunnelName,
                status = CheckStatus.OK,
            )
        } else {
            SterileLaunchCheckItem(
                id = "tunnel_named",
                title = context.getString(R.string.jail_launch_check_tunnel_missing),
                detail = null,
                status = CheckStatus.WARNING,
            )
        }

        val tunnels = tunnelManager.getTunnels()
        val tunnelUp = tunnelName?.let { name ->
            tunnels.find { it.name == name }?.state == Tunnel.State.UP
        } == true

        items += SterileLaunchCheckItem(
            id = "tunnel_up",
            title = context.getString(R.string.jail_launch_check_tunnel_up),
            detail = null,
            status = when {
                tunnelName == null -> CheckStatus.WARNING
                tunnelUp -> CheckStatus.OK
                else -> CheckStatus.WARNING
            },
        )

        items += SterileLaunchCheckItem(
            id = "selected",
            title = context.getString(R.string.jail_launch_check_selected),
            detail = null,
            status = if (selectedForJail) CheckStatus.OK else CheckStatus.WARNING,
        )

        val snapMap = auditRepository.snapshots.value
        val snap = snapMap[packageName]
        val risky = snap?.score?.level?.let {
            it == AuditRiskLevel.HIGH || it == AuditRiskLevel.CRITICAL
        } == true

        val heavyReasons = snap?.score?.reasons.orEmpty().filter { it.weight >= 25 }
        if (heavyReasons.isNotEmpty()) {
            items += SterileLaunchCheckItem(
                id = "heavy_reasons",
                title = context.getString(R.string.jail_launch_check_heavy_reasons),
                detail = heavyReasons.joinToString { it.signalId },
                status = CheckStatus.WARNING,
            )
        }

        if (preset.warnIfRiskyPermissions) {
            items += SterileLaunchCheckItem(
                id = "risk",
                title = context.getString(R.string.jail_launch_check_risk),
                detail = null,
                status = when {
                    snap == null -> CheckStatus.WARNING
                    risky -> CheckStatus.WARNING
                    else -> CheckStatus.OK
                },
            )
        }

        val workCopy = crossProfile.isInstalledInOtherProfile(packageName)
        if (preset.requiredProfile == LaunchProfile.WORK && preset.warnIfNoWorkProfileCopy) {
            items += SterileLaunchCheckItem(
                id = "work_copy",
                title = context.getString(R.string.jail_launch_check_work_copy),
                detail = null,
                status = when (workCopy) {
                    true -> CheckStatus.OK
                    false -> CheckStatus.WARNING
                    null -> CheckStatus.WARNING
                },
            )
        }

        if (preset.warnLocationEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val locOn = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
            items += SterileLaunchCheckItem(
                id = "location",
                title = context.getString(R.string.jail_launch_check_location),
                detail = null,
                status = if (locOn) CheckStatus.WARNING else CheckStatus.OK,
            )
        }

        val unrestricted = powerManager.isIgnoringBatteryOptimizations(packageName) == true
        items += SterileLaunchCheckItem(
            id = "battery",
            title = context.getString(R.string.jail_launch_check_battery),
            detail = null,
            status = if (unrestricted) CheckStatus.WARNING else CheckStatus.OK,
        )

        return SterileLaunchChecklist(items)
    }

    /**
     * Opens [packageName] respecting [preset.requiredProfile]. Never silently toggles tunnels.
     */
    fun launch(packageName: String, preset: SterileLaunchPreset): SterileLaunchResult =
        when (preset.requiredProfile) {
            LaunchProfile.WORK ->
                if (crossProfile.tryStartMainActivityInOtherProfile(packageName)) {
                    SterileLaunchResult.Launched(viaWorkProfile = true)
                } else {
                    SterileLaunchResult.NeedsUserAction(
                        context.getString(R.string.jail_launch_work_manual_hint),
                    )
                }

            LaunchProfile.MAIN -> launchMainProfile(packageName)

            LaunchProfile.ANY ->
                if (crossProfile.tryStartMainActivityInOtherProfile(packageName)) {
                    SterileLaunchResult.Launched(viaWorkProfile = true)
                } else {
                    launchMainProfile(packageName)
                }
        }

    fun launchMainProfile(packageName: String): SterileLaunchResult {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return SterileLaunchResult.Failed(context.getString(R.string.jail_launch_no_intent))
        return try {
            context.startActivity(intent)
            SterileLaunchResult.Launched(viaWorkProfile = false)
        } catch (e: Throwable) {
            SterileLaunchResult.Failed(e.message ?: context.getString(R.string.jail_launch_failed))
        }
    }
}
