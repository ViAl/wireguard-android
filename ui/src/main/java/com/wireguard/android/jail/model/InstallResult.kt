/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

sealed interface InstallResult {
    data object Installed : InstallResult
    data object AlreadyInstalled : InstallResult
    data class UserActionRequired(val reason: UserActionReason) : InstallResult
    data class Unsupported(val reason: UnsupportedReason) : InstallResult
    data class Failed(val message: String? = null) : InstallResult
}

enum class UserActionReason {
    MANUAL_INSTALL_REQUIRED,
    OPEN_IN_WORK_PROFILE,
}

enum class UnsupportedReason {
    OWNERSHIP_NOT_AVAILABLE,
    PACKAGE_NOT_AVAILABLE_IN_PARENT,
    INSTALL_API_NOT_AVAILABLE,
    SECURITY_RESTRICTED,
    UNKNOWN,
}
