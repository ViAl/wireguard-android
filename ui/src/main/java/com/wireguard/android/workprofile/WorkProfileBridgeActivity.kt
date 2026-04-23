package com.wireguard.android.workprofile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

class WorkProfileBridgeActivity : AppCompatActivity() {
    private val launcher by lazy { PlayStoreLauncher(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incomingAction = intent?.action.orEmpty()
        val packageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()

        if (incomingAction == ACTION_INSTALL_COMMIT_CALLBACK) {
            setResult(Activity.RESULT_OK)
            finish()
            return
        }

        if (incomingAction != ACTION_OPEN_PLAY_STORE_IN_WORK) {
            Log.w(TAG, "Rejected bridge action: $incomingAction")
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        if (packageName.isBlank()) {
            Log.w(TAG, "Rejected bridge request: packageName blank")
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val result = launcher.launchInCurrentProfile(packageName, this)
        setResult(if (result is PackageCloneResult.RedirectedToPlayStore) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        const val ACTION_OPEN_PLAY_STORE_IN_WORK = "com.wireguard.android.action.OPEN_PLAY_STORE_IN_WORK"
        const val ACTION_INSTALL_COMMIT_CALLBACK = "com.wireguard.android.action.WORK_INSTALL_COMMIT_CALLBACK"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
    }

    private companion object {
        const val TAG = "WG-WorkProfile"
    }
}
