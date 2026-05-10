/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.shuttle

import android.app.Activity
import android.content.*
import android.content.ContentResolver.SCHEME_CONTENT
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.database.Cursor
import android.net.Uri
import android.os.*
import android.util.Log
import android.util.Size
import android.util.SizeF
import android.util.SparseArray
import java.io.Serializable

class ShuttleProvider : ContentProvider() {

    companion object {
        private const val TAG = "WG.ShuttleProvider"

        /** Authority derived from applicationId. */
        private const val AUTHORITY_SUFFIX = ".shuttle"
        private fun authority(context: Context) = context.packageName + AUTHORITY_SUFFIX
        private fun bareContentUri(context: Context) =
            Uri.Builder().scheme(SCHEME_CONTENT).authority(authority(context)).build()

        /**
         * Build cross-profile URI: `content://<profileId>@<authority>`.
         * The [profileId] is the USER ID of the TARGET profile where the
         * ContentProvider lives.
         */
        private fun buildCrossProfileUri(context: Context, profileUserId: Int) =
            Uri.Builder()
                .scheme(SCHEME_CONTENT)
                .encodedAuthority("$profileUserId@${authority(context)}")
                .build()

        /**
         * Get the cross-profile URI for a given target profile.
         */
        fun getCrossProfileUri(context: Context, profile: UserHandle): Uri {
            val userId = getUserId(profile)
            return buildCrossProfileUri(context, userId)
        }

        /**
         * Get the numeric user ID from a UserHandle.
         */
        private fun getUserId(handle: UserHandle): Int {
            return try {
                val method = UserHandle::class.java.getMethod("hashCode")
                method.invoke(handle) as? Int ?: 0
            } catch (e: Exception) {
                handle.hashCode()
            }
        }

        /**
         * Send a lambda to a specific user profile via ContentProvider.call().
         *
         * @param context caller context
         * @param profile target UserHandle (use UserHandle of work profile)
         * @param function lambda to execute in target profile
         * @return ShuttleResult wrapping the return value
         */
        fun <R> call(context: Context, profile: UserHandle,
                     function: Context.() -> R): ShuttleResult<R> {
            val bundle = Bundle(1).apply { putParcelable(null, Closure(function)) }
            val uri = buildCrossProfileUri(context, getUserId(profile))
            return try {
                ShuttleResult(
                    context.contentResolver.call(uri, function.javaClass.name, null, bundle)
                )
            } catch (e: SecurityException) {
                @Suppress("UNCHECKED_CAST")
                if (isReady(context, profile)) throw e
                else ShuttleResult.NOT_READY as ShuttleResult<R>
            }
        }

        /**
         * Check if the cross-profile URI permission has been granted for
         * calling into [profile].
         */
        fun isReady(context: Context, profile: UserHandle): Boolean {
            val uri = buildCrossProfileUri(context, getUserId(profile))
            return context.checkUriPermission(
                uri, 0, Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            ) == PERMISSION_GRANTED
        }

        /**
         * Collect URI permission grants from an intent (data or clipData).
         * Called when the activity receives a URI grant via cross-profile intent.
         */
        fun collect(context: Context, intent: Intent) {
            // Prefer intent.data (cross-profile URI from return hop),
            // fall back to clipData (bare URI from establish hop)
            val uris = sequence {
                intent.data?.let { yield(it) }
                intent.clipData?.let { cd ->
                    for (i in 0 until cd.itemCount) {
                        cd.getItemAt(i).uri?.let { yield(it) }
                    }
                }
            }
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    Log.d(TAG, "[collect] Took persistable permission: $uri")
                } catch (e: SecurityException) {
                    // URI may already be granted or not grantable — log but don't crash
                    Log.d(TAG, "[collect] Could not take persistable permission for $uri: ${e.message}")
                }
            }
        }
    }

    // ── ContentProvider call() ────────────────────────────────────────

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val cl = Closure::class.java.classLoader
        extras?.classLoader = cl
        val closure = requireNotNull(extras?.getParcelable<Closure>(null)) { "Missing extra" }
        val result = closure.invoke(context!!).also {
            Log.i(TAG, "Call: $method()=$it")
        }
        return if (result == null || result == Unit) null
        else Bundle().apply { putBundleValue(null, result) }
    }

    // ── Initialize permission grants ──────────────────────────────────

    override fun onCreate(): Boolean {
        initialize()
        return true
    }

    private fun initialize() {
        Log.d(TAG, "ShuttleProvider initializing (user=${Process.myUserHandle()})")

        val ctx = context ?: return
        val myHandle = Process.myUserHandle()

        // Try to find parent profile (any profile != current)
        val parentHandle: UserHandle? = try {
            val launcherAppsService = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE)
            if (launcherAppsService != null) {
                val launcherClass = launcherAppsService.javaClass
                val profilesMethod = launcherClass.getMethod("getProfiles")
                @Suppress("UNCHECKED_CAST")
                val profiles = profilesMethod.invoke(launcherAppsService) as? List<*> ?: emptyList<Any>()
                profiles.firstOrNull { it != myHandle } as? UserHandle
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "initialize: could not query profiles", e)
            null
        }

        if (parentHandle == null) {
            // Single profile (not work profile), nothing to do
            Log.d(TAG, "initialize: no other profile found, nothing to establish")
            return
        }

        // We are in a secondary profile (work) with a parent.
        // Build the bare shuttle URI and check if permission is already granted.
        val bareUri = Uri.parse("content://${ctx.packageName}.shuttle")
        if (ctx.checkUriPermission(bareUri, 0, Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "initialize: already ready")
            return
        }

        // Permission not granted — send a URI grant intent to the parent profile.
        // The parent will receive the bare URI and take persistable permission on it,
        // allowing cross-profile ContentProvider calls.
        Log.d(TAG, "initialize: establishing permission with parent=$parentHandle")
        try {
            val intent = Intent(ctx, ShuttleCarrierActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Send bare URI as data so parent can take persistable permission
                data = bareUri
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_HISTORY)
            }

            val launcherAppsService = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE)!!
            val launcherClass = launcherAppsService.javaClass
            val startMethod = launcherClass.getMethod(
                "startActivity",
                Intent::class.java, UserHandle::class.java,
                android.graphics.Rect::class.java, Bundle::class.java
            )
            startMethod.invoke(launcherAppsService, intent, parentHandle, null, null)
            Log.d(TAG, "initialize: sent permission grant request to parent=$parentHandle")
        } catch (e: Exception) {
            Log.e(TAG, "initialize: failed to send permission grant", e)
        }
    }

    // ── Stub ContentProvider methods ──────────────────────────────────

    override fun query(uri: Uri, projection: Array<out String>?,
                       selection: String?, selectionArgs: Array<out String>?,
                       sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?,
                        selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?,
                        selection: String?, selectionArgs: Array<out String>?) = 0

    // ── Helper: put any supported type into Bundle ────────────────────

    private fun Bundle.putBundleValue(key: String?, value: Any?) {
        when (value) {
            null -> putString(key, null)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is String -> putString(key, value)
            is CharSequence -> putCharSequence(key, value)
            is Parcelable -> putParcelable(key, value)
            is Array<*> -> when {
                value.isArrayOf<Parcelable>() -> @Suppress("UNCHECKED_CAST")
                    putParcelableArray(key, value as Array<Parcelable?>)
                value.isArrayOf<CharSequence>() -> @Suppress("UNCHECKED_CAST")
                    putCharSequenceArray(key, value as Array<CharSequence?>)
                value.isArrayOf<String>() -> @Suppress("UNCHECKED_CAST")
                    putStringArray(key, value as Array<String?>)
                else -> throw IllegalArgumentException("Unsupported array: ${value.javaClass}")
            }
            is List<*> -> @Suppress("UNCHECKED_CAST") putParcelableArrayList(key,
                if (value is ArrayList<*>) value as ArrayList<Parcelable>
                else ArrayList(value as List<Parcelable>))
            is SparseArray<*> -> @Suppress("UNCHECKED_CAST")
                putSparseParcelableArray(key, value as SparseArray<Parcelable>)
            is Bundle -> putBundle(key, value)
            is Serializable -> putSerializable(key, value)
            is Byte -> putByte(key, value)
            is Char -> putChar(key, value)
            is Short -> putShort(key, value)
            is Float -> putFloat(key, value)
            is Double -> putDouble(key, value)
            is Size -> putSize(key, value)
            is SizeF -> putSizeF(key, value)
            is BooleanArray -> putBooleanArray(key, value)
            is IntArray -> putIntArray(key, value)
            is LongArray -> putLongArray(key, value)
            is ByteArray -> putByteArray(key, value)
            is CharArray -> putCharArray(key, value)
            is ShortArray -> putShortArray(key, value)
            is FloatArray -> putFloatArray(key, value)
            is DoubleArray -> putDoubleArray(key, value)
            is IBinder -> putBinder(key, value)
            else -> throw IllegalArgumentException("Unsupported type: ${value.javaClass}")
        }
    }

}
