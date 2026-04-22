/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

/**
 * Per-app routing intent for the Jail tunnel. Values map onto [ConfigProxy] split-tunnel fields
 * in [com.wireguard.android.jail.domain.PerAppVpnManager].
 */
enum class JailTunnelMode {
    /** Does not alter tunnel lists for this package via Jail (Routing tab values untouched by Jail for this pkg). */
    DEFAULT,

    /** Force this package into the tunnel when [JailTunnelBinding.jailTunnelName] is active. */
    JAIL_ROUTE_THROUGH_TUNNEL,

    /** Force this package out of the tunnel (excluded list) on the Jail tunnel. */
    JAIL_EXCLUDE_FROM_TUNNEL,

    /** Explicitly no Jail routing side-effect for this package. */
    DISABLED,
}
