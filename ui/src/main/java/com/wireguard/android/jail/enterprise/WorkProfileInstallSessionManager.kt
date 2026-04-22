/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import com.wireguard.android.jail.model.InstallResult
import com.wireguard.android.jail.model.WorkProfileInstallMode
import com.wireguard.android.jail.model.WorkProfileInstallSessionState
import com.wireguard.android.jail.storage.JailStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

class WorkProfileInstallSessionManager(
    installService: WorkProfileAppInstallService,
    private val capabilityChecker: WorkProfileAppInstallCapabilityChecker,
    private val store: SessionStore = JailStoreSessionStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val installExecutorOverride: InstallExecutor? = null,
) {
    private val installExecutor: InstallExecutor = installExecutorOverride ?: ServiceInstallExecutor(installService)
    fun sessionState(packageName: String): Flow<WorkProfileInstallSessionState> =
        store.sessions.map { it[packageName] ?: WorkProfileInstallSessionState.Idle }

    suspend fun installAutomatically(packageName: String): WorkProfileInstallSessionState {
        val startedAt = now()
        val mode = WorkProfileInstallMode.AUTOMATIC
        store.set(packageName, WorkProfileInstallSessionState.InstallAttempted(packageName, startedAt, mode))

        return when (val result = installExecutor.install(packageName)) {
            InstallResult.AlreadyInstalled,
            InstallResult.Installed,
            -> reconcile(packageName)
            is InstallResult.UserActionRequired -> {
                val waiting = WorkProfileInstallSessionState.WaitingForUserAction(packageName, startedAt, mode)
                store.set(packageName, waiting)
                waiting
            }
            is InstallResult.Unsupported -> {
                val failed = WorkProfileInstallSessionState.Failed(
                    packageName = packageName,
                    completedAtMillis = now(),
                    mode = mode,
                    message = "Automatic install is unsupported in this environment",
                )
                store.set(packageName, failed)
                failed
            }
            is InstallResult.Failed -> {
                val failed = WorkProfileInstallSessionState.Failed(
                    packageName = packageName,
                    completedAtMillis = now(),
                    mode = mode,
                    message = result.message,
                )
                store.set(packageName, failed)
                failed
            }
        }
    }

    suspend fun launchManualStore(packageName: String): WorkProfileInstallSessionState {
        val startedAt = now()
        val mode = WorkProfileInstallMode.MANUAL_STORE
        store.set(packageName, WorkProfileInstallSessionState.InstallAttempted(packageName, startedAt, mode))

        return when (val result = installExecutor.launchManualInstall(packageName)) {
            InstallResult.AlreadyInstalled,
            InstallResult.Installed,
            -> reconcile(packageName)
            is InstallResult.UserActionRequired -> {
                val waiting = WorkProfileInstallSessionState.WaitingForUserAction(packageName, startedAt, mode)
                store.set(packageName, waiting)
                waiting
            }
            is InstallResult.Unsupported -> {
                val failed = WorkProfileInstallSessionState.Failed(
                    packageName = packageName,
                    completedAtMillis = now(),
                    mode = mode,
                    message = "Manual store launch is unsupported for this package",
                )
                store.set(packageName, failed)
                failed
            }
            is InstallResult.Failed -> {
                val failed = WorkProfileInstallSessionState.Failed(
                    packageName = packageName,
                    completedAtMillis = now(),
                    mode = mode,
                    message = result.message,
                )
                store.set(packageName, failed)
                failed
            }
        }
    }

    suspend fun reconcileIfPending(packageName: String): WorkProfileInstallSessionState {
        val current = store.sessions.first()[packageName] ?: return WorkProfileInstallSessionState.Idle
        return if (current.isPending()) reconcile(packageName) else current
    }

    suspend fun clear(packageName: String) {
        store.clear(packageName)
    }

    private suspend fun reconcile(packageName: String): WorkProfileInstallSessionState {
        val current = store.sessions.first()[packageName] ?: return WorkProfileInstallSessionState.Idle
        val mode = current.mode() ?: WorkProfileInstallMode.MANUAL_STORE
        val startedAt = current.startedAtMillis() ?: now()

        val verifying = WorkProfileInstallSessionState.Verifying(packageName, startedAt, mode)
        store.set(packageName, verifying)

        val capability = capabilityChecker.capabilityFor(packageName)
        val next = if (capability.installedInWorkProfile) {
            WorkProfileInstallSessionState.Installed(packageName, now(), mode)
        } else if (capability.canLaunchManualFallback) {
            WorkProfileInstallSessionState.WaitingForUserAction(packageName, startedAt, mode)
        } else {
            WorkProfileInstallSessionState.Failed(
                packageName = packageName,
                completedAtMillis = now(),
                mode = mode,
                message = "Still not visible in the work profile",
            )
        }
        store.set(packageName, next)
        return next
    }

    interface SessionStore {
        val sessions: Flow<Map<String, WorkProfileInstallSessionState>>
        suspend fun set(packageName: String, state: WorkProfileInstallSessionState)
        suspend fun clear(packageName: String)
    }

    internal interface InstallExecutor {
        fun install(packageName: String): InstallResult
        fun launchManualInstall(packageName: String): InstallResult
    }

    private class ServiceInstallExecutor(
        private val service: WorkProfileAppInstallService,
    ) : InstallExecutor {
        override fun install(packageName: String): InstallResult = service.install(packageName)
        override fun launchManualInstall(packageName: String): InstallResult = service.launchManualInstall(packageName)
    }

    private object JailStoreSessionStore : SessionStore {
        override val sessions: Flow<Map<String, WorkProfileInstallSessionState>>
            get() = JailStore.workProfileInstallSessions

        override suspend fun set(packageName: String, state: WorkProfileInstallSessionState) {
            JailStore.setWorkProfileInstallSession(packageName, state)
        }

        override suspend fun clear(packageName: String) {
            JailStore.clearWorkProfileInstallSession(packageName)
        }
    }
}

private fun WorkProfileInstallSessionState.isPending(): Boolean = when (this) {
    is WorkProfileInstallSessionState.InstallAttempted,
    is WorkProfileInstallSessionState.WaitingForUserAction,
    is WorkProfileInstallSessionState.Verifying,
    -> true
    is WorkProfileInstallSessionState.Installed,
    is WorkProfileInstallSessionState.Failed,
    WorkProfileInstallSessionState.Idle,
    -> false
}

private fun WorkProfileInstallSessionState.mode(): WorkProfileInstallMode? = when (this) {
    is WorkProfileInstallSessionState.InstallAttempted -> mode
    is WorkProfileInstallSessionState.WaitingForUserAction -> mode
    is WorkProfileInstallSessionState.Verifying -> mode
    is WorkProfileInstallSessionState.Installed -> mode
    is WorkProfileInstallSessionState.Failed -> mode
    WorkProfileInstallSessionState.Idle -> null
}

private fun WorkProfileInstallSessionState.startedAtMillis(): Long? = when (this) {
    is WorkProfileInstallSessionState.InstallAttempted -> startedAtMillis
    is WorkProfileInstallSessionState.WaitingForUserAction -> startedAtMillis
    is WorkProfileInstallSessionState.Verifying -> startedAtMillis
    is WorkProfileInstallSessionState.Installed,
    is WorkProfileInstallSessionState.Failed,
    WorkProfileInstallSessionState.Idle,
    -> null
}
