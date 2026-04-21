/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.domain

import androidx.annotation.StringRes
import com.wireguard.android.R

/** Static wizard steps (honest guidance only — no automated provisioning). */
object WorkProfileSetupWizard {
    data class Step(val id: String, @StringRes val titleRes: Int, @StringRes val bodyRes: Int)

    val steps: List<Step> = listOf(
        Step("what", R.string.jail_wizard_step_what_title, R.string.jail_wizard_step_what_body),
        Step("detect", R.string.jail_wizard_step_detect_title, R.string.jail_wizard_step_detect_body),
        Step("install", R.string.jail_wizard_step_install_title, R.string.jail_wizard_step_install_body),
        Step("icons", R.string.jail_wizard_step_icons_title, R.string.jail_wizard_step_icons_body),
        Step("limits", R.string.jail_wizard_step_limits_title, R.string.jail_wizard_step_limits_body),
        Step("honest", R.string.jail_wizard_step_honest_title, R.string.jail_wizard_step_honest_body),
    )
}
