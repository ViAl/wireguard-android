/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.databinding.JailSetupWizardFragmentBinding
import com.wireguard.android.jail.domain.WorkProfileSetupWizard
import com.wireguard.android.jail.enterprise.ManagedProfileProvisioningManager
import com.wireguard.android.jail.model.WorkProfileState
import com.wireguard.android.jail.storage.JailStore
import kotlinx.coroutines.launch

/**
 * Guided work-profile setup flow with conservative provisioning state reporting.
 */
class JailSetupWizardFragment : Fragment() {

    private var binding: JailSetupWizardFragmentBinding? = null
    private var stepIndex = 0
    private var provisioningManager: ManagedProfileProvisioningManager? = null

    private val provisioningLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val messageRes = when (result.resultCode) {
            Activity.RESULT_OK -> R.string.jail_provisioning_launch_ok
            Activity.RESULT_CANCELED -> R.string.jail_provisioning_launch_cancelled
            else -> R.string.jail_provisioning_launch_failed
        }
        binding?.jailWizardProvisioningMessage?.setText(messageRes)
        refreshProvisioningState()
    }

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
            runCatching { provisioningLauncher.launch(manager.createProvisioningIntent()) }
                .onFailure { binding?.jailWizardProvisioningMessage?.setText(R.string.jail_provisioning_launch_failed) }
        }
        bindStep()
        refreshProvisioningState()
    }

    override fun onResume() {
        super.onResume()
        refreshProvisioningState()
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

    private fun refreshProvisioningState() {
        val binding = binding ?: return
        val manager = provisioningManager ?: return
        val snapshot = manager.snapshot()
        binding.jailWizardProvisioningStatus.text = when {
            snapshot.profileReady -> getString(R.string.jail_provisioning_status_ready)
            !snapshot.isProvisioningSupported -> getString(R.string.jail_provisioning_status_unsupported)
            snapshot.isProvisioningAllowed -> getString(R.string.jail_provisioning_status_allowed)
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
            snapshot.isProvisioningSupported && snapshot.isProvisioningAllowed && !snapshot.profileReady
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val KEY_STEP = "jail_wizard_step"
    }
}
