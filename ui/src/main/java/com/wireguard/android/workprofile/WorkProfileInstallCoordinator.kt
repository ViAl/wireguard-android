package com.wireguard.android.workprofile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred

class WorkProfileInstallCoordinator(
    private val context: Context,
    private val capabilityChecker: WorkProfileCapabilityChecker,
    private val sourceResolver: PackageSourceResolver,
    private val installer: WorkProfileInstaller,
    private val playStoreLauncher: PlayStoreLauncher
) {
    // Called from Primary Profile to initiate the whole process
    fun getBridgeIntentForInstall(packageName: String): Intent? {
        if (!capabilityChecker.hasWorkProfileHelper()) {
            return null
        }
        val intent = Intent("com.wireguard.android.workprofile.action.BRIDGE_EXECUTE")
        intent.component = android.content.ComponentName(context.packageName, "com.wireguard.android.workprofile.WorkProfileBridgeActivity")
        intent.putExtra(EXTRA_COMMAND, COMMAND_INSTALL)
        intent.putExtra(EXTRA_PACKAGE_NAME, packageName)
        
        val apkPaths = sourceResolver.getApkPaths(packageName)
        if (apkPaths.isNotEmpty()) {
            val fds = ArrayList<ParcelFileDescriptor>()
            for (path in apkPaths) {
                try {
                    val fd = ParcelFileDescriptor.open(java.io.File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                    fds.add(fd)
                } catch (e: Exception) {
                    // Ignore
                }
            }
            if (fds.isNotEmpty()) {
                val binder = object : android.os.Binder() {
                    override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                        if (code == 1) {
                            reply?.writeNoException()
                            reply?.writeTypedList(fds)
                            return true
                        }
                        return super.onTransact(code, data, reply, flags)
                    }
                }
                val bundle = android.os.Bundle()
                bundle.putBinder("apk_binder", binder)
                intent.putExtra(EXTRA_APK_FDS_BUNDLE, bundle)
            }
        }
        return intent
    }

    fun getBridgeIntentForPlayStore(packageName: String): Intent {
        val intent = Intent("com.wireguard.android.workprofile.action.BRIDGE_EXECUTE")
        intent.component = android.content.ComponentName(context.packageName, "com.wireguard.android.workprofile.WorkProfileBridgeActivity")
        intent.putExtra(EXTRA_COMMAND, COMMAND_OPEN_PLAY_STORE)
        intent.putExtra(EXTRA_PACKAGE_NAME, packageName)
        return intent
    }

    // Called from WorkProfileBridgeActivity in the Work Profile
    suspend fun executeInWorkProfile(intent: Intent, pendingIntentForSession: PendingIntent): PackageCloneResult {
        val command = intent.getStringExtra(EXTRA_COMMAND)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        
        if (packageName.isNullOrBlank()) {
            return PackageCloneResult.ErrorPackageNotFound
        }

        if (command == COMMAND_OPEN_PLAY_STORE) {
            return playStoreLauncher.launch(packageName)
        }

        if (command == COMMAND_INSTALL) {
            if (!capabilityChecker.isProfileOwner()) {
                return playStoreLauncher.launch(packageName)
            }

            if (Build.VERSION.SDK_INT >= 28) {
                if (installer.installExistingPackage(packageName)) {
                    return PackageCloneResult.SuccessInstalledExisting
                }
            }

            if (installer.enableSystemApp(packageName)) {
                return PackageCloneResult.SuccessEnabledSystemApp
            }

            val fds = ArrayList<ParcelFileDescriptor>()
            val bundle = intent.getBundleExtra(EXTRA_APK_FDS_BUNDLE)
            val binder = bundle?.getBinder("apk_binder")
            if (binder != null) {
                val data = android.os.Parcel.obtain()
                val reply = android.os.Parcel.obtain()
                try {
                    binder.transact(1, data, reply, 0)
                    reply.readException()
                    val receivedFds = reply.createTypedArrayList(ParcelFileDescriptor.CREATOR)
                    if (receivedFds != null) {
                        fds.addAll(receivedFds)
                    }
                } catch (e: Exception) {
                    // Ignore
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }

            if (!fds.isNullOrEmpty()) {
                val success = installer.installFromApkSession(packageName, fds, pendingIntentForSession)
                if (success) {
                    // We must wait for the broadcast receiver to get the result of commit()
                    // WorkProfileBridgeActivity handles this waiting part.
                    // This is a bit tricky, we can return a special result indicating "WaitingForSession"
                    // and BridgeActivity doesn't finish yet.
                    return PackageCloneResult.SuccessInstalledFromApkSession // Actually handled asynchronously
                } else {
                    return PackageCloneResult.ErrorInstallSessionFailed
                }
            } else {
                return playStoreLauncher.launch(packageName)
            }
        }
        return PackageCloneResult.ErrorUnknown("Invalid command")
    }

    companion object {
        const val EXTRA_COMMAND = "command"
        const val COMMAND_INSTALL = "install"
        const val COMMAND_OPEN_PLAY_STORE = "open_play_store"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_APK_FDS_BUNDLE = "apk_fds_bundle"
        const val EXTRA_RESULT_RECEIVER = "result_receiver"
        const val RESULT_EXTRA_CLONE_RESULT = "clone_result"
    }
}
