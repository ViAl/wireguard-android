/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Minimal content provider required for DPC provisioning.
 *
 * Android's managed-profile provisioning flow requires the DPC app to expose
 * a content provider that responds to DPC-specific content URIs. This provider
 * returns the current provisioning state so the system can track setup progress.
 */
class JailDpcContentProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        WorkProfileLogger.d("JailDpcContentProvider: onCreate")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        WorkProfileLogger.d("JailDpcContentProvider: query uri=$uri")
        val cursor = MatrixCursor(arrayOf("_id", "value"))
        cursor.addRow(arrayOf(1, "provisioned"))
        return cursor
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.dir/vnd.wireguard.dpc"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
