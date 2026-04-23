package com.wireguard.android.workprofile

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.RuntimeEnvironment

class PlayStoreLauncherTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `play store unavailable returns error`() {
        val launcher = object : PlayStoreLauncher(context) {
            override fun launchInCurrentProfile(packageName: String, fromActivity: android.app.Activity?): PackageCloneResult {
                return PackageCloneResult.ErrorPlayStoreUnavailable
            }
        }

        val result = launcher.launchInCurrentProfile("com.wireguard.android", null)
        assertEquals(PackageCloneResult.ErrorPlayStoreUnavailable, result)
    }
}
