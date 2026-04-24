package com.wireguard.android.workprofile

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class ProfileAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
    }
}
