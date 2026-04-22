/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.wireguard.android.jail.model.InstallResult
import com.wireguard.android.jail.model.ManagedProfileOwnershipState
import com.wireguard.android.jail.model.WorkProfileAppAction
import com.wireguard.android.jail.model.WorkProfileAppAvailability
import com.wireguard.android.jail.model.WorkProfileAppInstallCapability
import com.wireguard.android.jail.model.WorkProfileInstallEnvironment
import com.wireguard.android.jail.model.WorkProfileInstallEnvironmentReason
import com.wireguard.android.jail.model.WorkProfileInstallMode
import com.wireguard.android.jail.model.WorkProfileInstallSessionState
import com.wireguard.android.jail.model.UserActionReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkProfileInstallSessionManagerTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun automaticSession_installedAfterVerification() = runTest {
        val store = InMemorySessionStore()
        val manager = manager(
            installResult = InstallResult.Installed,
            capabilityStates = listOf(capability(installedInWork = true)),
            store = store,
        )

        val state = manager.installAutomatically(PKG)

        assertTrue(state is WorkProfileInstallSessionState.Installed)
        assertTrue(store.state.value[PKG] is WorkProfileInstallSessionState.Installed)
    }

    @Test
    fun manualSession_waitingWhenStoreLaunched() = runTest {
        val store = InMemorySessionStore()
        val manager = manager(
            installResult = InstallResult.UserActionRequired(UserActionReason.MANUAL_INSTALL_REQUIRED),
            capabilityStates = listOf(capability(installedInWork = false, canFallback = true)),
            store = store,
        )

        val state = manager.launchManualStore(PKG)

        assertTrue(state is WorkProfileInstallSessionState.WaitingForUserAction)
        assertEquals(WorkProfileInstallMode.MANUAL_STORE, (state as WorkProfileInstallSessionState.WaitingForUserAction).mode)
    }

    @Test
    fun reconcile_manualReturnInstalled_marksInstalled() = runTest {
        val store = InMemorySessionStore(
            mapOf(PKG to WorkProfileInstallSessionState.WaitingForUserAction(PKG, 100L, WorkProfileInstallMode.MANUAL_STORE)),
        )
        val manager = manager(
            installResult = InstallResult.UserActionRequired(UserActionReason.MANUAL_INSTALL_REQUIRED),
            capabilityStates = listOf(capability(installedInWork = true)),
            store = store,
        )

        val state = manager.reconcileIfPending(PKG)

        assertTrue(state is WorkProfileInstallSessionState.Installed)
    }

    @Test
    fun reconcile_manualReturnStillMissing_keepsWaiting() = runTest {
        val store = InMemorySessionStore(
            mapOf(PKG to WorkProfileInstallSessionState.WaitingForUserAction(PKG, 100L, WorkProfileInstallMode.MANUAL_STORE)),
        )
        val manager = manager(
            installResult = InstallResult.UserActionRequired(UserActionReason.MANUAL_INSTALL_REQUIRED),
            capabilityStates = listOf(capability(installedInWork = false, canFallback = true)),
            store = store,
        )

        val state = manager.reconcileIfPending(PKG)

        assertTrue(state is WorkProfileInstallSessionState.WaitingForUserAction)
    }

    @Test
    fun automaticReportedInstalled_butPostCheckMissing_fails() = runTest {
        val store = InMemorySessionStore()
        val manager = manager(
            installResult = InstallResult.Installed,
            capabilityStates = listOf(capability(installedInWork = false, canFallback = false)),
            store = store,
        )

        val state = manager.installAutomatically(PKG)

        assertTrue(state is WorkProfileInstallSessionState.Failed)
    }

    @Test
    fun automaticFailurePath_marksFailed() = runTest {
        val store = InMemorySessionStore()
        val manager = manager(
            installResult = InstallResult.Failed("boom"),
            capabilityStates = listOf(capability(installedInWork = false, canFallback = true)),
            store = store,
        )

        val state = manager.installAutomatically(PKG)

        assertTrue(state is WorkProfileInstallSessionState.Failed)
        assertEquals("boom", (state as WorkProfileInstallSessionState.Failed).message)
    }

    @Test
    fun pendingSession_restoredAndReconciled() = runTest {
        val store = InMemorySessionStore(
            mapOf(PKG to WorkProfileInstallSessionState.InstallAttempted(PKG, 77L, WorkProfileInstallMode.AUTOMATIC)),
        )
        val manager = manager(
            installResult = InstallResult.Failed("unused"),
            capabilityStates = listOf(capability(installedInWork = true)),
            store = store,
        )

        val state = manager.reconcileIfPending(PKG)

        assertTrue(state is WorkProfileInstallSessionState.Installed)
    }

    private fun manager(
        installResult: InstallResult,
        capabilityStates: List<WorkProfileAppInstallCapability>,
        store: WorkProfileInstallSessionManager.SessionStore,
    ): WorkProfileInstallSessionManager {
        val service = WorkProfileAppInstallService(
            context = app,
            capabilityChecker = capabilityChecker(capability(installedInWork = false, canFallback = true)),
            fallbackLauncher = fakeFallback,
            installer = object : WorkProfileAppInstallService.EnterpriseInstaller {
                override fun installExistingPackage(packageName: String): Boolean = false
            },
        )
        return WorkProfileInstallSessionManager(
            installService = service,
            capabilityChecker = capabilityCheckerSequence(capabilityStates),
            store = store,
            now = { 1234L },
            installExecutorOverride = object : WorkProfileInstallSessionManager.InstallExecutor {
                override fun install(packageName: String): InstallResult = installResult
                override fun launchManualInstall(packageName: String): InstallResult = installResult
            },
        )
    }

    private fun capabilityCheckerSequence(sequence: List<WorkProfileAppInstallCapability>): WorkProfileAppInstallCapabilityChecker {
        var index = 0
        return object : WorkProfileAppInstallCapabilityChecker(
            app,
            ownershipService = object : ManagedProfileOwnershipStateProvider {
                override fun state() = ManagedProfileOwnershipState.MANAGED_PROFILE_OURS
            },
            packageInspector = fakeInspector,
            fallbackLauncher = fakeFallback,
        ) {
            override fun capabilityFor(packageName: String): WorkProfileAppInstallCapability {
                val value = sequence.getOrElse(index) { sequence.last() }
                index += 1
                return value
            }
        }
    }

    private fun capabilityChecker(value: WorkProfileAppInstallCapability): WorkProfileAppInstallCapabilityChecker =
        capabilityCheckerSequence(listOf(value))

    private class InMemorySessionStore(
        initial: Map<String, WorkProfileInstallSessionState> = emptyMap(),
    ) : WorkProfileInstallSessionManager.SessionStore {
        val state = MutableStateFlow(initial)
        override val sessions = state

        override suspend fun set(packageName: String, state: WorkProfileInstallSessionState) {
            this.state.value = this.state.value.toMutableMap().apply {
                if (state is WorkProfileInstallSessionState.Idle) remove(packageName) else put(packageName, state)
            }
        }

        override suspend fun clear(packageName: String) {
            state.value = state.value.toMutableMap().apply { remove(packageName) }
        }
    }

    private fun capability(
        installedInWork: Boolean = false,
        canAuto: Boolean = false,
        canFallback: Boolean = false,
    ) = WorkProfileAppInstallCapability(
        label = "Label",
        ownershipState = ManagedProfileOwnershipState.MANAGED_PROFILE_OURS,
        installedInParentProfile = true,
        installedInWorkProfile = installedInWork,
        canInstallAutomatically = canAuto,
        canLaunchManualFallback = canFallback,
        environment = WorkProfileInstallEnvironment(
            ownershipState = ManagedProfileOwnershipState.MANAGED_PROFILE_OURS,
            installedInParentProfile = true,
            installedInWorkProfile = installedInWork,
            installExistingPackageApiAvailable = true,
            manualStoreFallbackResolvable = canFallback,
            hasTargetUserProfiles = true,
            autoInstallAllowedByEnvironment = canAuto,
            environmentReason = WorkProfileInstallEnvironmentReason.UNKNOWN,
        ),
        availability = when {
            installedInWork -> WorkProfileAppAvailability.INSTALLED_IN_WORK
            canAuto -> WorkProfileAppAvailability.INSTALLABLE_AUTOMATICALLY
            canFallback -> WorkProfileAppAvailability.REQUIRES_MANUAL_INSTALL
            else -> WorkProfileAppAvailability.UNAVAILABLE
        },
        action = when {
            installedInWork -> WorkProfileAppAction.OPEN_IN_WORK
            canAuto -> WorkProfileAppAction.INSTALL_AUTOMATICALLY
            canFallback -> WorkProfileAppAction.OPEN_STORE_MANUALLY
            else -> WorkProfileAppAction.NONE
        },
    )

    private val fakeInspector = object : WorkProfileAppInstallCapabilityChecker.PackageInspector {
        override fun isInstalledInParent(packageName: String): Boolean = true
        override fun isInstalledInWorkProfile(packageName: String): Boolean = false
        override fun hasTargetProfiles(): Boolean = true
        override fun appLabel(packageName: String): String = "Label"
    }

    private val fakeFallback = object : WorkProfileAppInstallCapabilityChecker.FallbackLauncher {
        override fun canLaunchStoreIntent(packageName: String): Boolean = true
        override fun launchStoreIntent(packageName: String): Boolean = true
    }

    companion object {
        private const val PKG = "com.example.app"
    }
}
