package com.wireguard.android.workprofile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

class PlayStoreLauncherTest {

    @Mock lateinit var context: Context
    private lateinit var launcher: PlayStoreLauncher

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        launcher = PlayStoreLauncher(context)
    }

    @Test
    fun launch_emptyPackage_returnsErrorPackageNotFound() {
        val result = launcher.launch("")
        assertEquals(PackageCloneResult.ErrorPackageNotFound, result)
    }

    @Test
    fun launch_playStoreAvailable_returnsRedirectedToPlayStore() {
        val result = launcher.launch("com.test.app")
        assertEquals(PackageCloneResult.RedirectedToPlayStore, result)
        verify(context).startActivity(any(Intent::class.java))
    }

    @Test
    fun launch_playStoreUnavailable_returnsErrorPlayStoreUnavailable() {
        doThrow(ActivityNotFoundException()).`when`(context).startActivity(any(Intent::class.java))
        
        val result = launcher.launch("com.test.app")
        assertEquals(PackageCloneResult.ErrorPlayStoreUnavailable, result)
    }
}
