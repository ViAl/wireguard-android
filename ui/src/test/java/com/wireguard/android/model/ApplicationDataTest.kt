/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.model

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationDataTest {
    private class TestDrawable : Drawable() {
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.UNKNOWN
    }

    @Test
    fun usesPackageNameAsStableKey() {
        val app = ApplicationData(TestDrawable(), "WireGuard", "com.wireguard.android", false)
        assertEquals("com.wireguard.android", app.key)
    }
}
