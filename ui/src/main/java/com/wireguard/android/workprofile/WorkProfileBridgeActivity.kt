package com.wireguard.android.workprofile

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WorkProfileBridgeActivity : Activity() {
    companion object {
        const val ACTION_BRIDGE_EXECUTE = "com.wireguard.android.workprofile.action.BRIDGE_EXECUTE"
        const val ACTION_INSTALL_COMMIT = "com.wireguard.android.workprofile.action.INSTALL_COMMIT"
        private const val TAG = "WG-WorkProfile"
    }

    private lateinit var coordinator: WorkProfileInstallCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = com.wireguard.android.Application.getWorkProfileInstallCoordinator()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            handleIntent(intent)
        }
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        if (action == ACTION_INSTALL_COMMIT) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val result = if (status == PackageInstaller.STATUS_SUCCESS) {
                PackageCloneResult.SuccessInstalledFromApkSession
            } else {
                Log.e(TAG, "Install session failed with status: $status")
                PackageCloneResult.ErrorInstallSessionFailed
            }
            finishWithResult(result)
            return
        }

        if (action != ACTION_BRIDGE_EXECUTE) {
            Log.e(TAG, "Invalid action: $action")
            finishWithResult(PackageCloneResult.ErrorUnknown("Invalid action"))
            return
        }

        val resultIntent = Intent(this, WorkProfileBridgeActivity::class.java).apply {
            this.action = ACTION_INSTALL_COMMIT
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, resultIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = coordinator.executeInWorkProfile(intent, pendingIntent)
                // If it's SuccessInstalledFromApkSession, we wait for the commit callback via onNewIntent
                if (result != PackageCloneResult.SuccessInstalledFromApkSession) {
                    finishWithResult(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing in work profile", e)
                finishWithResult(PackageCloneResult.ErrorUnknown(e.message ?: "Exception"))
            }
        }
    }

    private fun finishWithResult(result: PackageCloneResult) {
        val data = Intent().apply {
            putExtra(WorkProfileInstallCoordinator.RESULT_EXTRA_CLONE_RESULT, result)
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }
}
