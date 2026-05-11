/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.databinding.JailSetupWizardFragmentBinding
import com.wireguard.android.jail.domain.WorkProfileSetupWizard
import com.wireguard.android.jail.enterprise.ManagedProfileProvisioningManager
import com.wireguard.android.jail.enterprise.PostProvisioningHandler
import com.wireguard.android.jail.model.WorkProfileState
import com.wireguard.android.jail.enterprise.WorkProfileLogger
import com.wireguard.android.jail.storage.JailStore
import kotlinx.coroutines.launch

/**
 * Guided work-profile setup flow with conservative provisioning state reporting.
 */
class JailSetupWizardFragment : Fragment() {

    private var binding: JailSetupWizardFragmentBinding? = null
    private var stepIndex = 0
    private var provisioningManager: ManagedProfileProvisioningManager? = null
    private var provisioningMessageRes: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stepIndex = savedInstanceState?.getInt(KEY_STEP, 0) ?: 0
        provisioningManager = ManagedProfileProvisioningManager(requireContext().applicationContext)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = JailSetupWizardFragmentBinding.inflate(inflater, container, false)
        return requireNotNull(binding).root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.jailWizardBack?.setOnClickListener {
            if (stepIndex > 0) {
                stepIndex--
                bindStep()
            }
        }
        binding?.jailWizardNext?.setOnClickListener {
            lifecycleScope.launch {
                val steps = WorkProfileSetupWizard.steps
                if (stepIndex < steps.lastIndex) {
                    JailStore.addSetupCompletedStep(steps[stepIndex].id)
                    stepIndex++
                    bindStep()
                } else {
                    JailStore.setOnboardingCompleted(true)
                    bindStep()
                }
            }
        }
        binding?.jailWizardProvisioningAction?.setOnClickListener {
            val manager = provisioningManager ?: return@setOnClickListener
            val intent = manager.createProvisioningIntent()
            if (intent != null) {
                try {
                    startActivityForResult(intent, REQUEST_PROVISION_MANAGED_PROFILE)
                } catch (e: IllegalStateException) {
                    // Fragment not attached — try from activity (Island fallback)
                    try {
                        requireActivity().startActivity(intent)
                        requireActivity().finish()
                    } catch (e2: Exception) {
                        provisioningMessageRes = R.string.jail_provisioning_launch_failed
                        refreshProvisioningState()
                    }
                } catch (e: Exception) {
                    provisioningMessageRes = R.string.jail_provisioning_launch_failed
                    refreshProvisioningState()
                }
            }
        }
        binding?.jailWizardAdbAction?.setOnClickListener {
            showAdbProvisioningInstructions()
        }
        bindStep()
        refreshProvisioningState(clearTransientMessage = true)
    }

    override fun onResume() {
        super.onResume()
        refreshProvisioningState(clearTransientMessage = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, stepIndex)
    }

    private fun bindStep() {
        val binding = binding ?: return
        val steps = WorkProfileSetupWizard.steps
        stepIndex = stepIndex.coerceIn(0, steps.lastIndex)
        val step = steps[stepIndex]
        binding.jailWizardProgress.text = getString(R.string.jail_wizard_progress_format, stepIndex + 1, steps.size)
        binding.jailWizardBody.text = buildString {
            appendLine(getString(step.titleRes))
            appendLine()
            append(getString(step.bodyRes))
        }
        binding.jailWizardBack.visibility = if (stepIndex > 0) View.VISIBLE else View.GONE
        binding.jailWizardNext.text = if (stepIndex >= steps.lastIndex)
            getString(R.string.jail_wizard_done)
        else
            getString(R.string.jail_wizard_next)
    }

    private fun refreshProvisioningState(clearTransientMessage: Boolean = false) {
        val binding = binding ?: return
        val manager = provisioningManager ?: return
        if (clearTransientMessage) provisioningMessageRes = null

        val snapshot = manager.snapshot()
        if (snapshot.managedProfileLikelyPresent) {
            provisioningMessageRes = null
        }

        binding.jailWizardProvisioningStatus.text = when {
            snapshot.managedProfileLikelyPresent -> getString(R.string.jail_provisioning_status_ready)
            !snapshot.isProvisioningSupported -> getString(R.string.jail_provisioning_status_unsupported)
            snapshot.canLaunchProvisioning -> getString(R.string.jail_provisioning_status_allowed)
            snapshot.isProvisioningAllowed && !snapshot.isProvisioningLaunchable ->
                getString(R.string.jail_provisioning_status_not_launchable)
            else -> getString(R.string.jail_provisioning_status_not_allowed)
        }

        binding.jailWizardProvisioningDetail.text = when (snapshot.profileState) {
            WorkProfileState.MANAGED_PROFILE_CONFIRMED -> getString(R.string.jail_provisioning_detail_confirmed)
            WorkProfileState.MANAGED_PROFILE_UNCERTAIN -> getString(R.string.jail_provisioning_detail_uncertain)
            WorkProfileState.SECONDARY_PROFILE_PRESENT -> getString(R.string.jail_provisioning_detail_secondary_profile)
            WorkProfileState.NO_SECONDARY_PROFILE -> getString(R.string.jail_provisioning_detail_none)
            WorkProfileState.UNSUPPORTED -> getString(R.string.jail_provisioning_detail_unsupported)
        }

        binding.jailWizardProvisioningAction.isEnabled =
            snapshot.canLaunchProvisioning && !snapshot.managedProfileLikelyPresent

        // Show ADB alternative when standard provisioning is not available
        // (e.g. isProvisioningAllowed returns false or intent not launchable on certain OEMs).
        val showAdbOption = !snapshot.canLaunchProvisioning && 
            !snapshot.managedProfileLikelyPresent &&
            snapshot.isProvisioningSupported
        binding.jailWizardAdbAction.visibility = if (showAdbOption) View.VISIBLE else View.GONE
        binding.jailWizardAdbLabel.visibility = if (showAdbOption) View.VISIBLE else View.GONE

        val messageRes = provisioningMessageRes
        binding.jailWizardProvisioningMessage.text = if (messageRes != null) getString(messageRes) else ""
        binding.jailWizardProvisioningMessage.visibility = if (messageRes != null) View.VISIBLE else View.GONE
    }

    /**
     * Shows ADB provisioning instructions and copies the ADB command to clipboard.
     */
    private fun showAdbProvisioningInstructions() {
        val context = requireContext()
        val packageName = context.packageName
        val adminComponent = "${packageName}/.jail.enterprise.JailDeviceAdminReceiver"
        val adbCommand = buildString {
            appendLine("# 1. Create managed profile via ADB:")
            appendLine("adb shell dpm create-profile ${packageName}")
            appendLine()
            appendLine("# 2. Set as profile owner:")
            appendLine("adb shell dpm set-profile-owner ${packageName}/$adminComponent")
            appendLine()
            appendLine("# Note: If step 2 fails, the profile may already exist.")
            appendLine("# Check user ID and try adjusting:")
            appendLine("adb shell dpm set-profile-owner --user 10 $packageName/$adminComponent")
        }

        // Copy to clipboard
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("ADB provisioning", adbCommand))

        Toast.makeText(context, R.string.jail_provisioning_adb_copied, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROVISION_MANAGED_PROFILE) return

        provisioningMessageRes = when (resultCode) {
            Activity.RESULT_OK -> {
                PostProvisioningHandler.run(requireContext())
                R.string.jail_provisioning_launch_ok
            }
            Activity.RESULT_CANCELED -> {
                R.string.jail_provisioning_launch_cancelled
            }
            9 -> {
                WorkProfileLogger.e("Provisioning returned code 9 (Xiaomi custom error)")
                data?.extras?.keySet()?.forEach { key ->
                    WorkProfileLogger.e("  extra: $key = ${data.extras?.get(key)}")
                }
                R.string.jail_provisioning_launch_failed
            }
            else -> R.string.jail_provisioning_launch_failed
        }
        refreshProvisioningState()
    }

    companion object {
        private const val KEY_STEP = "jail_wizard_step"
        private const val REQUEST_PROVISION_MANAGED_PROFILE = 1001
    }
}
