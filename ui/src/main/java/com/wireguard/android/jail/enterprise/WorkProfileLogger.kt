/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.util.Log

/**
 * Logger for Work-Profile (WireGuard/WP) operations.
 *
 * Writes directly to logcat under the single tag "WireGuard/WP" so that
 * LogViewerActivity's streaming logcat reader picks it up in real time
 * alongside all other system logs.
 *
 * This object does NOT maintain an in-memory ring buffer. For the canonical
 * log file (e.g. when exporting via the Share button), LogViewerActivity
 * already runs `logcat -d WireGuard/WP:*` at startup to capture historical
 * entries that were produced before the viewer opened.
 */
object WorkProfileLogger {

    /** Single well-known tag consumed by LogViewerActivity. */
    private const val TAG = "WireGuard/WP"

    // ── Public API ──────────────────────────────────────────────────────

    /** Write a debug entry to logcat. */
    fun d(message: String) {
        Log.d(TAG, message)
    }

    /** Write an error entry to logcat. */
    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
    }

    /** Write a warning entry to logcat. */
    fun w(message: String) {
        Log.w(TAG, message)
    }

    /**
     * Return all buffered lines in order (oldest first).
     *
     * Note: this implementation returns an empty list because we no longer
     * keep an in-memory buffer. Historical entries are retrieved from
     * logcat by LogViewerActivity via `logcat -d WireGuard/WP:*`.
     */
    fun snapshot(): List<String> = emptyList()
}
