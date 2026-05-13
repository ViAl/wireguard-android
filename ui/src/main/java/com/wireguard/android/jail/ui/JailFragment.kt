/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.databinding.JailFragmentBinding
import com.wireguard.android.jail.enterprise.ManagedProfileProvisioningManager
import com.wireguard.android.jail.enterprise.PostProvisioningHandler
import com.wireguard.android.jail.enterprise.WorkProfileLogger
import com.wireguard.android.jail.storage.JailSelectionStore
import com.wireguard.android.jail.viewmodel.JailViewModel
import kotlinx.coroutines.launch

/**
 * Root of the Jail tab.
 *
 * Shows either a provisioning button (no work profile) or the app list fragment (profile present).
 * This replaces the previous TabLayout + NavigationController structure.
 */
class JailFragment : Fragment(), JailFragmentHost {
    private var binding: JailFragmentBinding? = null
    private lateinit var viewModel: JailViewModel
    private var provisioningManager: ManagedProfileProvisioningManager? = null
    private var backPressedCallback: OnBackPressedCallback? = null

    override val jailViewModel: JailViewModel
        get() = viewModel

    override fun openHelp() {
        dismissAppDetailIfPresent()
        val binding = binding ?: return
        if (childFragmentManager.findFragmentByTag(HELP_FRAGMENT_TAG) != null) return
        childFragmentManager.commit {
            setReorderingAllowed(true)
            add(binding.jailNavHost.id, JailHelpFragment(), HELP_FRAGMENT_TAG)
            addToBackStack(HELP_BACK_STACK_NAME)
        }
        updateBackCallbackEnabled()
    }

    override fun openAppDetail(packageName: String) {
        val binding = binding ?: return
        if (childFragmentManager.findFragmentByTag(DETAIL_FRAGMENT_TAG) != null) return
        childFragmentManager.commit {
            setReorderingAllowed(true)
            add(binding.jailNavHost.id, JailAppDetailFragment.newInstance(packageName), DETAIL_FRAGMENT_TAG)
            addToBackStack(DETAIL_BACK_STACK_NAME)
        }
        updateBackCallbackEnabled()
    }

    private fun dismissAppDetailIfPresent(): Boolean {
        if (childFragmentManager.findFragmentByTag(DETAIL_FRAGMENT_TAG) == null) return false
        return childFragmentManager.popBackStackImmediate(
            DETAIL_BACK_STACK_NAME,
            FragmentManager.POP_BACK_STACK_INCLUSIVE,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = JailViewModel()
        provisioningManager = ManagedProfileProvisioningManager(requireContext().applicationContext)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = JailFragmentBinding.inflate(inflater, container, false)
        return requireNotNull(binding).root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(binding)

        binding.jailProvisioningButton.setOnClickListener {
            val manager = provisioningManager ?: return@setOnClickListener
            val intent = manager.createProvisioningIntent()
            if (intent != null) {
                try {
                    startActivityForResult(intent, REQUEST_PROVISION_MANAGED_PROFILE)
                } catch (e: Exception) {
                    binding.jailProvisioningStatus.setText(R.string.jail_provisioning_launch_failed)
                }
            }
        }

        // Observe provisioning state and selection to switch modes
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val manager = provisioningManager ?: return@repeatOnLifecycle
                val snapshot = manager.snapshot()

                val profilePresent = snapshot.managedProfileLikelyPresent
                binding.jailProvisioningMode.visibility = if (profilePresent) View.GONE else View.VISIBLE
                binding.jailAppsMode.visibility = if (profilePresent) View.VISIBLE else View.GONE

                if (!profilePresent) {
                    binding.jailProvisioningButton.isEnabled = snapshot.canLaunchProvisioning
                    binding.jailProvisioningStatus.text = when {
                        !snapshot.isProvisioningSupported -> getString(R.string.jail_provisioning_status_unsupported)
                        snapshot.canLaunchProvisioning -> getString(R.string.jail_provisioning_status_allowed)
                        snapshot.isProvisioningAllowed && !snapshot.isProvisioningLaunchable ->
                            getString(R.string.jail_provisioning_status_not_launchable)
                        else -> getString(R.string.jail_provisioning_status_not_allowed)
                    }
                } else {
                    // Show the proxy Apps fragment
                    if (childFragmentManager.findFragmentByTag(APPS_FRAGMENT_TAG) == null) {
                        childFragmentManager.commit {
                            setReorderingAllowed(true)
                            replace(binding.jailNavHost.id, JailAppsFragment(), APPS_FRAGMENT_TAG)
                        }
                    }
                }
            }
        }

        // Observe selection for Jail status indicator
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val selectionStore: JailSelectionStore = Application.getJailComponent().selectionStore
                selectionStore.selected.collect { selected ->
                    binding.jailStatusIndicator.text = if (selected.isEmpty())
                        getString(R.string.jail_inactive)
                    else
                        getString(R.string.jail_active)
                }
            }
        }

        val detailRestored = childFragmentManager.findFragmentByTag(DETAIL_FRAGMENT_TAG) != null
        val helpRestored = childFragmentManager.findFragmentByTag(HELP_FRAGMENT_TAG) != null
        backPressedCallback = object : OnBackPressedCallback(detailRestored || helpRestored) {
            override fun handleOnBackPressed() {
                if (dismissAppDetailIfPresent()) {
                    updateBackCallbackEnabled()
                    return
                }
                if (!isEnabled) {
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }.also { callback ->
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        }
        updateBackCallbackEnabled()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        updateBackCallbackEnabled()
    }

    override fun onResume() {
        super.onResume()
        // Re-check provisioning state when returning from Android setup screens.
        updateProvisioningState()
    }

    override fun onDestroyView() {
        backPressedCallback?.remove()
        backPressedCallback = null
        binding = null
        super.onDestroyView()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROVISION_MANAGED_PROFILE) return

        val binding = binding ?: return
        when (resultCode) {
            Activity.RESULT_OK -> {
                PostProvisioningHandler.run(requireContext())
                binding.jailProvisioningStatus.setText(R.string.jail_provisioning_launch_ok)
                // The state observation will pick up the profile presence change.
            }
            Activity.RESULT_CANCELED -> {
                binding.jailProvisioningStatus.setText(R.string.jail_provisioning_launch_cancelled)
            }
            9 -> {
                WorkProfileLogger.e("Provisioning returned code 9 (Xiaomi custom error)")
                data?.extras?.keySet()?.forEach { key ->
                    WorkProfileLogger.e("  extra: $key = ${data.extras?.get(key)}")
                }
                binding.jailProvisioningStatus.setText(R.string.jail_provisioning_launch_failed)
            }
            else -> {
                binding.jailProvisioningStatus.setText(R.string.jail_provisioning_launch_failed)
            }
        }
    }

    private fun updateBackCallbackEnabled() {
        val cb = backPressedCallback ?: return
        if (!isAdded || isHidden) {
            cb.isEnabled = false
            return
        }
        cb.isEnabled = childFragmentManager.findFragmentByTag(DETAIL_FRAGMENT_TAG) != null ||
            childFragmentManager.findFragmentByTag(HELP_FRAGMENT_TAG) != null
    }

    private fun updateProvisioningState() {
        val binding = binding ?: return
        val manager = provisioningManager ?: return
        val snapshot = manager.snapshot()
        val profilePresent = snapshot.managedProfileLikelyPresent
        binding.jailProvisioningMode.visibility = if (profilePresent) View.GONE else View.VISIBLE
        binding.jailAppsMode.visibility = if (profilePresent) View.VISIBLE else View.GONE

        if (profilePresent && childFragmentManager.findFragmentByTag(APPS_FRAGMENT_TAG) == null) {
            childFragmentManager.commit {
                setReorderingAllowed(true)
                replace(binding.jailNavHost.id, JailAppsFragment(), APPS_FRAGMENT_TAG)
            }
        }
    }

    companion object {
        private const val DETAIL_FRAGMENT_TAG = "jail_app_detail"
        private const val DETAIL_BACK_STACK_NAME = "jail_app_detail_stack"
        private const val HELP_FRAGMENT_TAG = "jail_help_overlay"
        private const val HELP_BACK_STACK_NAME = "jail_help_stack"
        private const val APPS_FRAGMENT_TAG = "jail_apps"
        private const val REQUEST_PROVISION_MANAGED_PROFILE = 1001
    }
}
