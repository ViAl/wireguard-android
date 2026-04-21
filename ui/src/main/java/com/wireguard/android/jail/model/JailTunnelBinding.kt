/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

/**
 * Identifies which existing WireGuard tunnel receives Jail-driven include/exclude updates.
 * There is no separate “Jail tunnel” config object — only a name pointing at a normal tunnel.
 */
data class JailTunnelBinding(val jailTunnelName: String?)
