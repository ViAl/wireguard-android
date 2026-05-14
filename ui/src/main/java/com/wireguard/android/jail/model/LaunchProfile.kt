/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

/** Which Android profile Sterile Launch should prefer when opening an app. */
enum class LaunchProfile {
    MAIN,
    WORK,
    ANY,
}
