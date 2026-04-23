package com.wireguard.android.workprofile

import android.content.ComponentName
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment

class WorkProfileCapabilityCheckerTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `unsupported sdk returns false`() {
        val checker = WorkProfileCapabilityChecker(
            context,
            ComponentName(context, ProfileAdminReceiver::class.java),
            sdkInt = 27,
        )
        assertFalse(checker.isSupported())
    }

    @Test
    fun `supported sdk returns true`() {
        val checker = WorkProfileCapabilityChecker(
            context,
            ComponentName(context, ProfileAdminReceiver::class.java),
            sdkInt = 34,
        )
        assertTrue(checker.isSupported())
    }
}
