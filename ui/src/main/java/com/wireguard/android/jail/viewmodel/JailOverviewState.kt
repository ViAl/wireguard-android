/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.viewmodel

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.wireguard.android.R
import com.wireguard.android.jail.model.JailDestination

/**
 * Immutable state describing the Jail overview screen. In Phase 1 the card definitions are
 * entirely static; later phases will enrich each card with live status (e.g. audit counts,
 * setup completion progress) without changing the consuming UI code.
 */
data class JailOverviewState(
    val intro: Int = R.string.jail_overview_intro,
    val cards: List<JailOverviewCard> = defaultCards
) {
    companion object {
        val defaultCards: List<JailOverviewCard> = listOf(
            JailOverviewCard(
                id = "work_profile",
                titleRes = R.string.jail_card_work_profile_title,
                subtitleRes = R.string.jail_card_work_profile_subtitle,
                iconRes = R.drawable.ic_jail_card_work_profile,
                destination = JailDestination.SETUP
            ),
            JailOverviewCard(
                id = "app_audit",
                titleRes = R.string.jail_card_app_audit_title,
                subtitleRes = R.string.jail_card_app_audit_subtitle,
                iconRes = R.drawable.ic_jail_card_audit,
                destination = JailDestination.APPS
            ),
            JailOverviewCard(
                id = "sterile_launch",
                titleRes = R.string.jail_card_sterile_launch_title,
                subtitleRes = R.string.jail_card_sterile_launch_subtitle,
                iconRes = R.drawable.ic_jail_card_launch,
                destination = JailDestination.LAUNCH
            ),
            JailOverviewCard(
                id = "risk_report",
                titleRes = R.string.jail_card_risk_report_title,
                subtitleRes = R.string.jail_card_risk_report_subtitle,
                iconRes = R.drawable.ic_jail_card_report,
                destination = JailDestination.REPORT
            ),
            JailOverviewCard(
                id = "network_isolation",
                titleRes = R.string.jail_card_network_isolation_title,
                subtitleRes = R.string.jail_card_network_isolation_subtitle,
                iconRes = R.drawable.ic_jail_card_network,
                destination = JailDestination.APPS
            )
        )
    }
}

data class JailOverviewCard(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    @DrawableRes val iconRes: Int,
    val destination: JailDestination
)
