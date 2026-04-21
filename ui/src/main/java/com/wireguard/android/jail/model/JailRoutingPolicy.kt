/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

/** Persisted routing choice for a single package. */
data class JailRoutingPolicy(val packageName: String, val mode: JailTunnelMode)
