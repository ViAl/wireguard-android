/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

enum class WorkProfileAppAvailability {
    INSTALLED_IN_WORK,
    INSTALLABLE_AUTOMATICALLY,
    REQUIRES_MANUAL_INSTALL,
    UNAVAILABLE,
}
