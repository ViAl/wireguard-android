/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

/** Single row in the pre-launch checklist UI. */
data class SterileLaunchCheckItem(
    val id: String,
    val title: String,
    val detail: String?,
    val status: CheckStatus,
)

enum class CheckStatus {
    OK,
    WARNING,
    BLOCKED,
}

data class SterileLaunchChecklist(val items: List<SterileLaunchCheckItem>) {
    val hasBlocking: Boolean get() = items.any { it.status == CheckStatus.BLOCKED }
    val hasWarnings: Boolean get() = items.any { it.status == CheckStatus.WARNING }
}
