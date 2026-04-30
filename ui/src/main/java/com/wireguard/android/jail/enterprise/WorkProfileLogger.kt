/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.util.Log
import androidx.collection.CircularArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory circular log buffer for Work-Profile (WireGuard/WP) operations.
 *
 * Unlike logcat, entries here are never evicted by system buffer limits — they survive
 * until LogViewer dumps them into wireguard-log.txt. The buffer is capped at 512 lines
 * to avoid memory pressure.
 */
object WorkProfileLogger {

    private const val MAX_LINES = 512
    private const val LOG_TAG = "WireGuard/WP"
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val buffer = CircularArray<String>()

    /**
     * Write a debug-level entry to both the in-memory buffer and logcat.
     */
    fun d(message: String) {
        val line = format("D", message)
        append(line)
        Log.d(LOG_TAG, message)
    }

    /**
     * Write an error-level entry to both the in-memory buffer and logcat.
     */
    fun e(message: String, throwable: Throwable? = null) {
        val line = format("E", if (throwable != null) "$message — ${throwable.message}" else message)
        append(line)
        Log.e(LOG_TAG, message, throwable)
    }

    /**
     * Write a warning-level entry to both the in-memory buffer and logcat.
     */
    fun w(message: String) {
        val line = format("W", message)
        append(line)
        Log.w(LOG_TAG, message)
    }

    /**
     * Return all buffered lines in order (oldest first).
     */
    fun snapshot(): List<String> {
        val count = buffer.size()
        return (0 until count).map { buffer[it] }
    }

    // ── internal ──

    private fun format(level: String, message: String): String {
        val ts = dateFormat.format(Date())
        return "$ts $level $LOG_TAG: $message"
    }

    private fun append(line: String) {
        if (buffer.size() >= MAX_LINES) {
            buffer.popFirst()
        }
        buffer.addLast(line)
    }
}
