package com.wireguard.android.workprofile

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.RuntimeEnvironment

class WorkProfileInstallCoordinatorTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `installExistingPackage success returns SuccessInstalledExisting`() {
        val coordinator = WorkProfileInstallCoordinator(
            context = context,
            capabilityChecker = fakeCapability(canUseDpc = true, hasProfile = true),
            installer = fakeInstaller(PackageCloneResult.SuccessInstalledExisting),
            playStoreLauncher = fakePlayStore(PackageCloneResult.RedirectedToPlayStore),
        )

        val result = coordinator.installInWorkProfile("com.wireguard.android", null)
        assertEquals(PackageCloneResult.SuccessInstalledExisting, result)
    }

    @Test
    fun `installExisting false and system app goes enableSystemApp branch`() {
        val coordinator = WorkProfileInstallCoordinator(
            context = context,
            capabilityChecker = fakeCapability(canUseDpc = true, hasProfile = true),
            installer = fakeInstaller(PackageCloneResult.SuccessEnabledSystemApp),
            playStoreLauncher = fakePlayStore(PackageCloneResult.RedirectedToPlayStore),
        )

        val result = coordinator.installInWorkProfile("com.android.vending", null)
        assertEquals(PackageCloneResult.SuccessEnabledSystemApp, result)
    }

    @Test
    fun `installExisting false and non system app goes apk session branch`() {
        val coordinator = WorkProfileInstallCoordinator(
            context = context,
            capabilityChecker = fakeCapability(canUseDpc = true, hasProfile = true),
            installer = fakeInstaller(PackageCloneResult.SuccessInstalledFromApkSession),
            playStoreLauncher = fakePlayStore(PackageCloneResult.RedirectedToPlayStore),
        )

        val result = coordinator.installInWorkProfile("com.whatsapp", null)
        assertEquals(PackageCloneResult.SuccessInstalledFromApkSession, result)
    }

    @Test
    fun `without dpc redirects to play`() {
        val coordinator = WorkProfileInstallCoordinator(
            context = context,
            capabilityChecker = fakeCapability(canUseDpc = false, hasProfile = true),
            installer = fakeInstaller(PackageCloneResult.ErrorNotProfileOwner),
            playStoreLauncher = fakePlayStore(PackageCloneResult.RedirectedToPlayStore),
        )

        val result = coordinator.installInWorkProfile("com.google.android.gm", null)
        assertEquals(PackageCloneResult.RedirectedToPlayStore, result)
    }

    @Test
    fun `empty package returns package not found`() {
        val coordinator = WorkProfileInstallCoordinator(
            context = context,
            capabilityChecker = fakeCapability(canUseDpc = true, hasProfile = true),
            installer = fakeInstaller(PackageCloneResult.SuccessInstalledExisting),
            playStoreLauncher = fakePlayStore(PackageCloneResult.RedirectedToPlayStore),
        )

        val result = coordinator.installInWorkProfile("", null)
        assertEquals(PackageCloneResult.ErrorPackageNotFound, result)
    }

    private fun fakeCapability(canUseDpc: Boolean, hasProfile: Boolean) = object : WorkProfileCapabilityChecker(
        context,
        android.content.ComponentName(context, ProfileAdminReceiver::class.java),
    ) {
        override fun canUseDpcOperations(): Boolean = canUseDpc
        override fun hasManagedProfile(): Boolean = hasProfile
    }

    private fun fakeInstaller(result: PackageCloneResult) = object : WorkProfileInstaller(
        context,
        fakeCapability(canUseDpc = true, hasProfile = true),
    ) {
        override fun install(packageName: String): PackageCloneResult = result
    }

    private fun fakePlayStore(result: PackageCloneResult) = object : PlayStoreLauncher(context) {
        override fun launchInCurrentProfile(packageName: String, fromActivity: android.app.Activity?): PackageCloneResult = result
    }
}
