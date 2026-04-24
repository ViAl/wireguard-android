package com.wireguard.android.workprofile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito.verify

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class WorkProfileInstallCoordinatorTest {

    @Mock lateinit var context: Context
    @Mock lateinit var capabilityChecker: WorkProfileCapabilityChecker
    @Mock lateinit var sourceResolver: PackageSourceResolver
    @Mock lateinit var installer: WorkProfileInstaller
    @Mock lateinit var playStoreLauncher: PlayStoreLauncher
    @Mock lateinit var pendingIntent: PendingIntent

    private lateinit var coordinator: WorkProfileInstallCoordinator

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        `when`(context.packageName).thenReturn("com.wireguard.android")
        coordinator = WorkProfileInstallCoordinator(context, capabilityChecker, sourceResolver, installer, playStoreLauncher)
    }

    @Test
    fun executeInWorkProfile_emptyPackage_returnsErrorPackageNotFound() = runBlocking {
        val intent = Intent().apply {
            putExtra(WorkProfileInstallCoordinator.EXTRA_COMMAND, WorkProfileInstallCoordinator.COMMAND_INSTALL)
            putExtra(WorkProfileInstallCoordinator.EXTRA_PACKAGE_NAME, "")
        }
        val result = coordinator.executeInWorkProfile(intent, pendingIntent)
        assertEquals(PackageCloneResult.ErrorPackageNotFound, result)
    }

    @Test
    fun executeInWorkProfile_notProfileOwner_redirectsToPlayStore() = runBlocking {
        val packageName = "com.test.app"
        val intent = Intent().apply {
            putExtra(WorkProfileInstallCoordinator.EXTRA_COMMAND, WorkProfileInstallCoordinator.COMMAND_INSTALL)
            putExtra(WorkProfileInstallCoordinator.EXTRA_PACKAGE_NAME, packageName)
        }
        `when`(capabilityChecker.isProfileOwner()).thenReturn(false)
        `when`(playStoreLauncher.launch(packageName)).thenReturn(PackageCloneResult.RedirectedToPlayStore)

        val result = coordinator.executeInWorkProfile(intent, pendingIntent)
        assertEquals(PackageCloneResult.RedirectedToPlayStore, result)
        verify(playStoreLauncher).launch(packageName)
    }

    @Test
    fun executeInWorkProfile_installExistingSuccess_returnsSuccessInstalledExisting() = runBlocking {
        val packageName = "com.test.app"
        val intent = Intent().apply {
            putExtra(WorkProfileInstallCoordinator.EXTRA_COMMAND, WorkProfileInstallCoordinator.COMMAND_INSTALL)
            putExtra(WorkProfileInstallCoordinator.EXTRA_PACKAGE_NAME, packageName)
        }
        `when`(capabilityChecker.isProfileOwner()).thenReturn(true)
        `when`(installer.installExistingPackage(packageName)).thenReturn(true)

        val result = coordinator.executeInWorkProfile(intent, pendingIntent)
        assertEquals(PackageCloneResult.SuccessInstalledExisting, result)
    }

    @Test
    fun executeInWorkProfile_installExistingFalseSystemAppSuccess_returnsSuccessEnabledSystemApp() = runBlocking {
        val packageName = "com.test.app"
        val intent = Intent().apply {
            putExtra(WorkProfileInstallCoordinator.EXTRA_COMMAND, WorkProfileInstallCoordinator.COMMAND_INSTALL)
            putExtra(WorkProfileInstallCoordinator.EXTRA_PACKAGE_NAME, packageName)
        }
        `when`(capabilityChecker.isProfileOwner()).thenReturn(true)
        `when`(installer.installExistingPackage(packageName)).thenReturn(false)
        `when`(installer.enableSystemApp(packageName)).thenReturn(true)

        val result = coordinator.executeInWorkProfile(intent, pendingIntent)
        assertEquals(PackageCloneResult.SuccessEnabledSystemApp, result)
    }

    @Test
    fun executeInWorkProfile_notSystemAppNoApks_redirectsToPlayStore() = runBlocking {
        val packageName = "com.test.app"
        val intent = Intent().apply {
            putExtra(WorkProfileInstallCoordinator.EXTRA_COMMAND, WorkProfileInstallCoordinator.COMMAND_INSTALL)
            putExtra(WorkProfileInstallCoordinator.EXTRA_PACKAGE_NAME, packageName)
        }
        `when`(capabilityChecker.isProfileOwner()).thenReturn(true)
        `when`(installer.installExistingPackage(packageName)).thenReturn(false)
        `when`(installer.enableSystemApp(packageName)).thenReturn(false)
        `when`(playStoreLauncher.launch(packageName)).thenReturn(PackageCloneResult.RedirectedToPlayStore)

        val result = coordinator.executeInWorkProfile(intent, pendingIntent)
        assertEquals(PackageCloneResult.RedirectedToPlayStore, result)
    }
}
