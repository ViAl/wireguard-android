/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

sealed interface WorkProfileInstallSessionState {
    data object Idle : WorkProfileInstallSessionState

    data class InstallAttempted(
        val packageName: String,
        val startedAtMillis: Long,
        val mode: WorkProfileInstallMode,
    ) : WorkProfileInstallSessionState

    data class WaitingForUserAction(
        val packageName: String,
        val startedAtMillis: Long,
        val mode: WorkProfileInstallMode,
    ) : WorkProfileInstallSessionState

    data class Verifying(
        val packageName: String,
        val startedAtMillis: Long,
        val mode: WorkProfileInstallMode,
    ) : WorkProfileInstallSessionState

    data class Installed(
        val packageName: String,
        val completedAtMillis: Long,
        val mode: WorkProfileInstallMode,
    ) : WorkProfileInstallSessionState

    data class Failed(
        val packageName: String,
        val completedAtMillis: Long,
        val mode: WorkProfileInstallMode,
        val message: String? = null,
    ) : WorkProfileInstallSessionState
}
