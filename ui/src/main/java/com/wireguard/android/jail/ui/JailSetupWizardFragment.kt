/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.databinding.JailSetupWizardFragmentBinding
import com.wireguard.android.jail.domain.WorkProfileSetupWizard
import com.wireguard.android.jail.storage.JailStore
import kotlinx.coroutines.launch

/**
 * Guided work-profile explanation. Does not provision profiles — Settings / IT / Android flows do.
 */
class JailSetupWizardFragment : Fragment() {

    private var binding: JailSetupWizardFragmentBinding? = null
    private var stepIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stepIndex = savedInstanceState?.getInt(KEY_STEP, 0) ?: 0
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
        bindStep()
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

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val KEY_STEP = "jail_wizard_step"
    }
}
