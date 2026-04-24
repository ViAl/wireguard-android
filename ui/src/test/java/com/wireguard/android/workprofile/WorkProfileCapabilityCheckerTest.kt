package com.wireguard.android.workprofile

import android.app.admin.DevicePolicyManager
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

class WorkProfileCapabilityCheckerTest {

    @Mock lateinit var context: Context
    @Mock lateinit var dpm: DevicePolicyManager

    private lateinit var checker: WorkProfileCapabilityChecker

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        `when`(context.packageName).thenReturn("com.wireguard.android")
        `when`(context.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(dpm)
        checker = WorkProfileCapabilityChecker(context)
    }

    @Test
    fun isProfileOwner_returnsTrue_whenDpmSaysSo() {
        `when`(dpm.isProfileOwnerApp("com.wireguard.android")).thenReturn(true)
        assertTrue(checker.isProfileOwner())
    }

    @Test
    fun isProfileOwner_returnsFalse_whenDpmSaysNo() {
        `when`(dpm.isProfileOwnerApp("com.wireguard.android")).thenReturn(false)
        assertFalse(checker.isProfileOwner())
    }
}
