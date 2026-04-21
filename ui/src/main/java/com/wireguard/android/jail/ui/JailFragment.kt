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
import com.google.android.material.tabs.TabLayout
import com.wireguard.android.databinding.JailFragmentBinding
import com.wireguard.android.jail.model.JailDestination
import com.wireguard.android.jail.viewmodel.JailViewModel

/**
 * Root of the Jail tab. Hosts a top [TabLayout] that acts as a coarse navigation bar for the
 * Jail sub-sections plus a [androidx.fragment.app.FragmentContainerView] where the selected
 * destination's fragment is shown.
 *
 * Sub-fragments access the shared [JailViewModel] via [Host] so they do not need to pull in
 * the lifecycle-viewmodel library (the rest of the project keeps this dependency surface
 * intentionally small).
 */
class JailFragment : Fragment(), JailFragment.Host {
    private var binding: JailFragmentBinding? = null
    private lateinit var viewModel: JailViewModel
    private lateinit var navigationController: JailNavigationController
    private var suppressTabSelection = false
    private var backPressedCallback: OnBackPressedCallback? = null

    /** Bridge that sub-fragments use to reach the host without a ViewModelProvider. */
    interface Host {
        val jailViewModel: JailViewModel
        fun navigateTo(destination: JailDestination)

        /**
         * Show the app-detail screen layered on top of the current tab. Implemented as a
         * `childFragmentManager` back-stack entry so system back dismisses it and the
         * underlying Apps list is restored intact.
         */
        fun openAppDetail(packageName: String)
    }

    override val jailViewModel: JailViewModel
        get() = viewModel

    override fun navigateTo(destination: JailDestination) {
        // Tabs are a top-level destination; any transient detail overlay must yield first.
        dismissAppDetailIfPresent()
        navigationController.navigate(destination)
    }

    override fun openAppDetail(packageName: String) {
        val binding = binding ?: return
        if (childFragmentManager.findFragmentByTag(DETAIL_FRAGMENT_TAG) != null) return
        childFragmentManager.commit {
            setReorderingAllowed(true)
            add(binding.jailNavHost.id, JailAppDetailFragment.newInstance(packageName), DETAIL_FRAGMENT_TAG)
            addToBackStack(DETAIL_BACK_STACK_NAME)
        }
        backPressedCallback?.isEnabled = true
    }

    private fun dismissAppDetailIfPresent(): Boolean {
        if (childFragmentManager.findFragmentByTag(DETAIL_FRAGMENT_TAG) == null) return false
        // Use the Immediate variant so callers switching tabs right after dismiss don't end up
        // momentarily showing detail + the new tab stacked on top of each other.
        return childFragmentManager.popBackStackImmediate(
            DETAIL_BACK_STACK_NAME,
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
            onDestinationChanged = ::syncTabToDestination
        )

        binding.jailTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (suppressTabSelection) return
                val destination = JailDestination.fromPosition(tab?.position ?: 0)
                // Route through navigateTo so any transient detail overlay is dismissed first.
                navigateTo(destination)
            }
        })

        // If the child FragmentManager already has a fragment for the destination (configuration
        // change) reuse it; otherwise create it fresh. The controller handles both paths.
        navigationController.navigate(initialDestination)
        syncTabToDestination(initialDestination)

        val detailRestored = childFragmentManager.findFragmentByTag(DETAIL_FRAGMENT_TAG) != null
        backPressedCallback = object : OnBackPressedCallback(detailRestored || initialDestination != JailDestination.OVERVIEW) {
            override fun handleOnBackPressed() {
                if (dismissAppDetailIfPresent()) {
                    // Detail closed; tab state is unchanged so the callback may remain enabled.
                    isEnabled = navigationController.currentDestination != JailDestination.OVERVIEW
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
        backPressedCallback?.isEnabled = destination != JailDestination.OVERVIEW
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

    private fun createFragmentFor(destination: JailDestination): Fragment = when (destination) {
        JailDestination.OVERVIEW -> JailOverviewFragment()
        JailDestination.APPS -> JailAppsFragment()
        JailDestination.REPORT -> JailReportFragment()
        JailDestination.SETUP,
        JailDestination.LAUNCH -> JailPlaceholderFragment.newInstance(destination)
    }

    companion object {
        private const val KEY_DESTINATION = "jail_destination"
        private const val DETAIL_FRAGMENT_TAG = "jail_app_detail"
        private const val DETAIL_BACK_STACK_NAME = "jail_app_detail_stack"
    }
}
