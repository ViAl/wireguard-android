/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.domain

import android.content.Intent
import android.net.Uri

/**
 * Honest guidance hooks for installing an app inside a work profile. Prefer the work-profile Play
 * Store badge when the user opens this intent manually — Android resolves the best handler.
 */
object WorkProfileInstallGuide {

    /** Google Play deep link that many devices route to the work-profile store when isolated. */
    fun playStoreDetailsIntent(packageName: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }

    /** https fallback when `market://` is unavailable. */
    fun playStoreHttpsIntent(packageName: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
}
