/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Transparent proxy activity that accepts a cross-profile intent (or a direct deep-link intent)
 * and re-forwards it to Play Store as a same-profile intent.
 *
 * This solves the problem of Play Store ignoring deep-link URIs when launched
 * from a different user profile. By proxying through our own app copy that already
 * resides IN the work profile, the re-forwarded intent originates from within the
 * same profile and Play Store processes the deep link correctly.
 *
 * Accepts two invocation modes:
 *
 * Mode 1 — direct ACTION_VIEW (e.g. https:// or market:// URI):
 *   Simply re-dispatches the same intent without setPackage so the system
 *   resolver inside the target profile picks Play Store.
 *
 * Mode 2 — explicit proxy (ACTION_PROXY_PLAY_STORE with EXTRA_PACKAGE_NAME):
 *   Launches Play Store with market://details?id=<packageName>.
 */
class PlayStoreProxyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        val data = intent?.data

        WorkProfileLogger.d("ProxyActivity onCreate: action=$action, data=$data, profile=${android.os.Process.myUserHandle()}")

        when {
            // Mode 1: direct deep-link URIs (ACTION_VIEW with market:// or https://)
            Intent.ACTION_VIEW == action && data != null -> {
                relayToPlayStore(data)
            }
            // Mode 2: explicit proxy intent
            ACTION_PROXY_PLAY_STORE == action -> {
                val packageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME)
                if (packageName != null) {
                    relayToPlayStore(packageName)
                } else {
                    WorkProfileLogger.w("Proxy intent received but $EXTRA_PACKAGE_NAME is missing")
                    finish()
                }
            }
            else -> {
                WorkProfileLogger.w("Unknown intent action=$action, data=$data")
                finish()
            }
        }
    }

    private fun relayToPlayStore(packageName: String) {
        relayToPlayStore(Uri.parse("market://details?id=$packageName"))
    }

    private fun relayToPlayStore(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            // Do NOT setPackage here — this proxy runs inside the work profile,
            // and the system resolver inside that profile will pick up Play Store
            // naturally. Setting the package would tie the intent to a specific
            // user's Play Store instance, breaking cross-profile routing.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            WorkProfileLogger.d("ProxyActivity: Play Store intent dispatched successfully")
        } catch (e: Throwable) {
            WorkProfileLogger.e("ProxyActivity: Failed to launch Play Store with uri=$uri", e)
            // Fallback to https (without setPackage — same logic as above)
            try {
                val httpsUri = if (uri.scheme == "market") {
                    val packageName = uri.getQueryParameter("id") ?: return
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                } else {
                    uri
                }
                val fallbackIntent = Intent(Intent.ACTION_VIEW, httpsUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(fallbackIntent)
            } catch (e2: Throwable) {
                WorkProfileLogger.e("ProxyActivity: Fallback https also failed", e2)
            }
        } finally {
            finish()
        }
    }

    /**
     * Creates an intent that targets this proxy activity in the same app but
     * in another user profile. The receiver (our copy running inside the work
     * profile) will relay the deep link to Play Store.
     */
    companion object {
        @Suppress("unused")
        private const val TAG = "PlayStoreProxy"

        /** Play Store package name constant. */
        private const val PLAY_STORE_PACKAGE = "com.android.vending"

        /** Custom action for explicit cross-profile proxy invocation. */
        const val ACTION_PROXY_PLAY_STORE = "com.wireguard.android.action.PROXY_PLAY_STORE"

        /** Extra: the package name to open in Play Store. */
        const val EXTRA_PACKAGE_NAME = "com.wireguard.android.extra.PLAY_STORE_PACKAGE_NAME"

        /**
         * Builds an explicit Intent to our [PlayStoreProxyActivity] that can
         * be launched in the target profile using LauncherApps.startActivity
         * or makeOpenInUser / CrossProfileApps.
         *
         * @param context Application context used to resolve the package name.
         * @param packageName The package to open in Play Store.
         */
        fun buildProxyIntent(context: android.content.Context, packageName: String): Intent =
            Intent(ACTION_PROXY_PLAY_STORE).apply {
                `package` = context.packageName
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        /**
         * Builds an ACTION_VIEW Intent to our [PlayStoreProxyActivity] for
         * direct deep-link proxying.
         */
        fun buildViewProxyIntent(uri: Uri): Intent =
            Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
