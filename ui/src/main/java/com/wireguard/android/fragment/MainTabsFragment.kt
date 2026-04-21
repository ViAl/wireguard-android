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
import com.wireguard.android.jail.ui.JailFragment

class MainTabsFragment : Fragment() {
    enum class MainTab(val tag: String) {
        VPN("vpn_tab"),
        APPS("apps_tab"),
        JAIL("jail_tab");

        companion object {
            fun fromPosition(position: Int): MainTab = entries.getOrElse(position) { VPN }
        }
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
        binding.tabs.selectTab(binding.tabs.getTabAt(selectedTab.ordinal))
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val newTab = MainTab.fromPosition(tab?.position ?: 0)
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
        val fragmentManager = childFragmentManager
        val targetTag = selectedTab.tag
        val targetFragment = fragmentManager.findFragmentByTag(targetTag) ?: createFragmentFor(selectedTab)
        fragmentManager.commit {
            setReorderingAllowed(true)
            MainTab.entries.forEach { tab ->
                fragmentManager.findFragmentByTag(tab.tag)?.let { existing ->
                    if (existing === targetFragment) show(existing) else hide(existing)
                }
            }
            if (targetFragment.isAdded)
                show(targetFragment)
            else
                add(R.id.main_tab_content, targetFragment, targetTag)
        }
    }

    private fun createFragmentFor(tab: MainTab): Fragment = when (tab) {
        MainTab.VPN -> TunnelListFragment()
        MainTab.APPS -> TunnelAppsFragment()
        MainTab.JAIL -> JailFragment()
    }

    companion object {
        private const val KEY_SELECTED_TAB = "selected_tab"
    }
}
