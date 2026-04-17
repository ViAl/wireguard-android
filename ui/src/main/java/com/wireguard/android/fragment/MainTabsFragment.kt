/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.tabs.TabLayout
import com.wireguard.android.R
import com.wireguard.android.databinding.MainTabsFragmentBinding

class MainTabsFragment : Fragment() {
    enum class MainTab {
        VPN,
        APPS
    }

    interface Listener {
        fun onMainTabChanged(tab: MainTab)
    }

    private var binding: MainTabsFragmentBinding? = null
    private var selectedTab: MainTab = MainTab.VPN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTab = savedInstanceState?.getString(KEY_SELECTED_TAB)
            ?.let { runCatching { MainTab.valueOf(it) }.getOrNull() }
            ?: MainTab.VPN
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = MainTabsFragmentBinding.inflate(inflater, container, false)
        return requireNotNull(binding).root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(binding)
        val targetTabIndex = if (selectedTab == MainTab.VPN) 0 else 1
        binding.tabs.selectTab(binding.tabs.getTabAt(targetTabIndex))
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val newTab = if ((tab?.position ?: 0) == 0) MainTab.VPN else MainTab.APPS
                if (selectedTab == newTab)
                    return
                selectedTab = newTab
                showCurrentTab()
                (activity as? Listener)?.onMainTabChanged(newTab)
            }
        })
        showCurrentTab()
        (activity as? Listener)?.onMainTabChanged(selectedTab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SELECTED_TAB, selectedTab.name)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun showCurrentTab() {
        val tag = if (selectedTab == MainTab.VPN) VPN_TAG else APPS_TAG
        val fragmentManager = childFragmentManager
        val targetFragment = fragmentManager.findFragmentByTag(tag) ?: when (selectedTab) {
            MainTab.VPN -> TunnelListFragment()
            MainTab.APPS -> TunnelAppsFragment()
        }
        fragmentManager.commit {
            setReorderingAllowed(true)
            fragmentManager.findFragmentByTag(VPN_TAG)?.let {
                if (it == targetFragment) show(it) else hide(it)
            }
            fragmentManager.findFragmentByTag(APPS_TAG)?.let {
                if (it == targetFragment) show(it) else hide(it)
            }
            if (targetFragment.isAdded)
                show(targetFragment)
            else
                add(R.id.main_tab_content, targetFragment, tag)
        }
    }

    companion object {
        private const val KEY_SELECTED_TAB = "selected_tab"
        private const val VPN_TAG = "vpn_tab"
        private const val APPS_TAG = "apps_tab"
    }
}
