/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.enterprise

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Catches [android.app.admin.DevicePolicyManager.ACTION_PROVISIONING_SUCCESSFUL]
 * broadcast on Android O+.
 *
 * The system fires this intent when provisioning has completed successfully.
 * We delegate to [PostProvisioningHandler] to finish the setup.
 */
class ProvisioningCompletionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("WG.Provisioning", "ACTION_PROVISIONING_SUCCESSFUL received")
        PostProvisioningHandler.run(this)
        setResult(RESULT_OK)
        finish()
    }
}
