/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.wireguard.android.Application
import com.wireguard.android.databinding.JailFragmentBinding
import com.wireguard.android.jail.model.JailDestination
import com.wireguard.android.jail.viewmodel.JailViewModel
import kotlinx.coroutines.launch

/**
 * Root of the Jail tab. Hosts a top [TabLayout] that acts as a coarse navigation bar for the
 * Jail sub-sections plus a [androidx.fragment.app.FragmentContainerView] where the selected
 * destination's fragment is shown.
 */
class JailFragment : Fragment(), JailFragmentHost {
    private var binding: JailFragmentBinding? = null
    private lateinit var viewModel: JailViewModel
    private lateinit var navigationController: JailNavigationController
    private var suppressTabSelection = false
    private var backPressedCallback: OnBackPressedCallback? = null

    override val jailViewModel: JailViewModel
        get() = viewModel

    override fun navigateTo(destination: JailDestination) {
        dismissAppDetailIfPresent()
        dismissHelpIfPresent()
        navigationController.navigate(destination)
    }

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

    private fun dismissHelpIfPresent(): Boolean {
        if (childFragmentManager.findFragmentByTag(HELP_FRAGMENT_TAG) == null) return false
        return childFragmentManager.popBackStackImmediate(
            HELP_BACK_STACK_NAME,
            FragmentManager.POP_BACK_STACK_INCLUSIVE,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = JailViewModel()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = JailFragmentBinding.inflate(inflater, container, false)
        return requireNotNull(binding).root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(binding)

        val initialDestination = savedInstanceState?.getString(KEY_DESTINATION)
            ?.let { JailDestination.fromTag(it) }
            ?: JailDestination.OVERVIEW

        navigationController = JailNavigationController(
            fragmentManager = childFragmentManager,
            containerId = binding.jailNavHost.id,
            factory = ::createFragmentFor,
            onDestinationChanged = ::syncTabToDestination,
        )

        binding.jailTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (suppressTabSelection) return
                val destination = JailDestination.fromPosition(tab?.position ?: 0)
                navigateTo(destination)
            }
        })

        navigationController.navigate(initialDestination)
        syncTabToDestination(initialDestination)

        viewLifecycleOwner.lifecycleScope.launch {
            Application.getJailComponent().appRepository.refreshInstalledApps(requireContext().applicationContext)
        }

        val detailRestored = childFragmentManager.findFragmentByTag(DETAIL_FRAGMENT_TAG) != null
        val helpRestored = childFragmentManager.findFragmentByTag(HELP_FRAGMENT_TAG) != null
        backPressedCallback = object : OnBackPressedCallback(
            detailRestored || helpRestored || initialDestination != JailDestination.OVERVIEW,
        ) {
            override fun handleOnBackPressed() {
                if (dismissAppDetailIfPresent()) {
                    updateBackCallbackEnabled()
                    return
                }
                if (dismissHelpIfPresent()) {
                    updateBackCallbackEnabled()
                    return
                }
                if (!navigationController.popToOverview()) {
                    isEnabled = false
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::navigationController.isInitialized)
            outState.putString(KEY_DESTINATION, navigationController.currentDestination.tag)
    }

    override fun onDestroyView() {
        backPressedCallback?.remove()
        backPressedCallback = null
        binding = null
        super.onDestroyView()
    }

    private fun syncTabToDestination(destination: JailDestination) {
        updateBackCallbackEnabled()
        val binding = binding ?: return
        val tab = binding.jailTabs.getTabAt(destination.ordinal) ?: return
        if (binding.jailTabs.selectedTabPosition == destination.ordinal)
            return
        suppressTabSelection = true
        try {
            binding.jailTabs.selectTab(tab)
        } finally {
            suppressTabSelection = false
        }
    }

    /**
     * Only consume system Back while this tab is visible ([isHidden] is false). Parent tabs use
     * hide/show, so otherwise we would steal Back from VPN/Routing flows.
     */
    private fun updateBackCallbackEnabled() {
        val cb = backPressedCallback ?: return
        if (!isAdded || isHidden) {
            cb.isEnabled = false
            return
        }
        if (!::navigationController.isInitialized) return
        cb.isEnabled = navigationController.currentDestination != JailDestination.OVERVIEW ||
            childFragmentManager.findFragmentByTag(DETAIL_FRAGMENT_TAG) != null ||
            childFragmentManager.findFragmentByTag(HELP_FRAGMENT_TAG) != null
    }

    private fun createFragmentFor(destination: JailDestination): Fragment = when (destination) {
        JailDestination.OVERVIEW -> JailOverviewFragment()
        JailDestination.APPS -> JailAppsFragment()
        JailDestination.REPORT -> JailReportFragment()
        JailDestination.SETUP -> JailSetupWizardFragment()
    }

    companion object {
        private const val KEY_DESTINATION = "jail_destination"
        private const val DETAIL_FRAGMENT_TAG = "jail_app_detail"
        private const val DETAIL_BACK_STACK_NAME = "jail_app_detail_stack"
        private const val HELP_FRAGMENT_TAG = "jail_help_overlay"
        private const val HELP_BACK_STACK_NAME = "jail_help_stack"
    }
}
