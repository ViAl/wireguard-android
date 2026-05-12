/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.content.Context
import android.util.Log
import com.wireguard.android.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger for Work-Profile (WireGuard/WP) operations.
 *
 * Writes to BOTH logcat and a dedicated file inside the app's private
 * files directory. The file-based log is necessary because runtime
 * logcat reading is restricted on Android 11+; LogViewerActivity reads
 * our log file directly instead of relying on logcat.
 *
 * The log file is rotated when it exceeds 256 KiB, keeping at most
 * one backup copy (wireguard_wp.log.1).
 */
object WorkProfileLogger {

    private const val TAG = "WireGuard/WP"
    private const val MAX_FILE_SIZE = 256 * 1024 // 256 KiB
    private const val FILE_NAME = "wireguard_wp.log"

    /** Must be set once, typically from Application.onCreate(). */
    private var filesDir: File? = null

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // ── Init ───────────────────────────────────────────────────────────

    /** Initialise the logger with the app's files directory. */
    fun init(context: Context) {
        filesDir = context.filesDir
        // Create or truncate the file to a fresh start.
        val logFile = logFile() ?: return
        if (logFile.exists()) {
            logFile.delete()
        }
        d("WorkProfileLogger initialised, file=${logFile.absolutePath}")
        d("App version: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
        d("Build ID: WG-PROVISION-003")
    }

    // ── Public API ─────────────────────────────────────────────────────

    fun d(message: String) {
        Log.d(TAG, message)
        appendToFile("D", message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
            appendToFile("E", "$message — ${throwable.message}")
        } else {
            Log.e(TAG, message)
            appendToFile("E", message)
        }
    }

    fun w(message: String) {
        Log.w(TAG, message)
        appendToFile("W", message)
    }

    /**
     * Returns the full content of the log file as a list of lines
     * (oldest first), or an empty list if the file doesn't exist.
     *
     * LogViewerActivity calls this to embed WP logs into
     * the exported log file (see rawLogBytes).
     */
    fun snapshot(): List<String> {
        val file = logFile() ?: return emptyList()
        return try {
            file.readLines()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Returns the absolute path of the current log file, so that
     * LogViewerActivity can read it directly.
     */
    fun logFilePath(): String? = logFile()?.absolutePath

    // ── Internal ───────────────────────────────────────────────────────

    private fun logFile(): File? {
        val dir = filesDir ?: return null
        return File(dir, FILE_NAME)
    }

    @Synchronized
    private fun appendToFile(level: String, message: String) {
        val file = logFile() ?: return
        try {
            // Rotate if too large.
            if (file.exists() && file.length() > MAX_FILE_SIZE) {
                val backup = File(file.parentFile, "$FILE_NAME.1")
                file.renameTo(backup)
                file.createNewFile()
            } else if (!file.exists()) {
                file.createNewFile()
            }

            val timestamp = dateFormat.format(Date())
            val line = "$timestamp $level $TAG: $message\n"
            file.appendText(line)
        } catch (_: Exception) {
            // Best-effort; logcat entries are still written above.
        }
    }
}
