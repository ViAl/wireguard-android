/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.databinding.ObservableList
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.databinding.ObservableKeyedArrayList
import com.wireguard.android.databinding.TunnelAppsFragmentBinding
import com.wireguard.android.model.ApplicationData
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.viewmodel.ConfigProxy
import com.wireguard.android.viewmodel.SplitTunnelingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TunnelAppsFragment : BaseFragment() {
    private var binding: TunnelAppsFragmentBinding? = null
    private val allAppData = ObservableKeyedArrayList<String, ApplicationData>()
    private val appData = ObservableKeyedArrayList<String, ApplicationData>()
    private var selectedTunnelName: String? = null
    private var selectedMode = SplitTunnelingMode.ALL_APPLICATIONS
    private var suppressSelectionPersistence = false
    private var searchQuery = ""
    private var tunnels: ObservableKeyedArrayList<String, ObservableTunnel>? = null

    private val tunnelListObserver = object : ObservableList.OnListChangedCallback<ObservableList<ObservableTunnel>>() {
        override fun onChanged(sender: ObservableList<ObservableTunnel>) = refreshTunnelSelector(sender)
        override fun onItemRangeChanged(sender: ObservableList<ObservableTunnel>, positionStart: Int, itemCount: Int) = refreshTunnelSelector(sender)
        override fun onItemRangeInserted(sender: ObservableList<ObservableTunnel>, positionStart: Int, itemCount: Int) = refreshTunnelSelector(sender)
        override fun onItemRangeMoved(sender: ObservableList<ObservableTunnel>, fromPosition: Int, toPosition: Int, itemCount: Int) = refreshTunnelSelector(sender)
        override fun onItemRangeRemoved(sender: ObservableList<ObservableTunnel>, positionStart: Int, itemCount: Int) = refreshTunnelSelector(sender)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = TunnelAppsFragmentBinding.inflate(inflater, container, false)
        return requireNotNull(binding).root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(this.binding)
        binding.appData = appData
        binding.searchText.doAfterTextChanged {
            searchQuery = it?.toString() ?: ""
            applyFilter()
        }
        binding.toggleAll.setOnClickListener {
            val selectAll = AppListDialogFragment.shouldSelectAllVisible(appData.map { it.isSelected })
            AppListDialogFragment.updateSelectionState(appData, selectAll, { it.isSelected }) { item, selected -> item.isSelected = selected }
        }
        binding.clearSelection.setOnClickListener {
            AppListDialogFragment.updateSelectionState(allAppData, false, { it.isSelected }) { item, selected -> item.isSelected = selected }
        }
        binding.splitTunnelingModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.split_tunneling_mode_exclude -> SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS
                R.id.split_tunneling_mode_include -> SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS
                else -> SplitTunnelingMode.ALL_APPLICATIONS
            }
            if (selectedMode != mode) {
                selectedMode = mode
                updateModeUi()
                persistSplitTunnelingChanges()
            }
        }
        binding.tunnelSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val tunnel = tunnels?.getOrNull(position) ?: return
                if (selectedTunnelName != tunnel.name) {
                    selectedTunnelName = tunnel.name
                    loadSelectedTunnelData(tunnel)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        lifecycleScope.launch {
            tunnels = Application.getTunnelManager().getTunnels().also { it.addOnListChangedCallback(tunnelListObserver) }
            refreshTunnelSelector(requireNotNull(tunnels))
        }
    }

    override fun onDestroyView() {
        tunnels?.removeOnListChangedCallback(tunnelListObserver)
        tunnels = null
        binding = null
        super.onDestroyView()
    }

    private fun refreshTunnelSelector(tunnels: List<ObservableTunnel>) {
        val binding = binding ?: return
        val tunnelNames = tunnels.map { it.name }
        binding.tunnelSelector.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, tunnelNames)

        val selectedTunnelIndex = when {
            tunnelNames.isEmpty() -> -1
            selectedTunnelName == null -> 0
            else -> tunnelNames.indexOf(selectedTunnelName).takeIf { it >= 0 } ?: 0
        }

        if (selectedTunnelIndex >= 0) {
            selectedTunnelName = tunnelNames[selectedTunnelIndex]
            if (binding.tunnelSelector.selectedItemPosition != selectedTunnelIndex) {
                binding.tunnelSelector.setSelection(selectedTunnelIndex)
            } else {
                loadSelectedTunnelData(requireNotNull(tunnels.getOrNull(selectedTunnelIndex)))
            }
        } else {
            selectedTunnelName = null
            allAppData.clear()
            appData.clear()
            updateModeUi()
            binding.summary.text = getString(R.string.no_tunnels_configured)
            binding.noTunnelState.visibility = View.VISIBLE
            binding.content.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
        }
    }

    private fun loadSelectedTunnelData(tunnel: ObservableTunnel) {
        val binding = binding ?: return
        binding.noTunnelState.visibility = View.GONE
        binding.content.visibility = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val config = tunnel.getConfigAsync()
                val proxy = ConfigProxy(config)
                selectedMode = proxy.`interface`.splitTunnelingMode
                val selectedApps = when (selectedMode) {
                    SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS -> proxy.`interface`.excludedApplications.toSet()
                    SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS -> proxy.`interface`.includedApplications.toSet()
                    SplitTunnelingMode.ALL_APPLICATIONS -> emptySet()
                }
                val loadedApps = withContext(Dispatchers.Default) {
                    AppDataLoader.load(requireContext().packageManager, selectedApps) {
                        if (!suppressSelectionPersistence)
                            persistSplitTunnelingChanges()
                    }
                }
                suppressSelectionPersistence = true
                allAppData.clear()
                allAppData.addAll(loadedApps)
                applyFilter()
                updateModeUi()
            } catch (e: Throwable) {
                val error = ErrorMessages[e]
                val message = getString(R.string.error_fetching_apps, error)
                Log.e(TAG, message, e)
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            } finally {
                suppressSelectionPersistence = false
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun updateModeUi() {
        val binding = binding ?: return
        val modeButtonId = when (selectedMode) {
            SplitTunnelingMode.ALL_APPLICATIONS -> R.id.split_tunneling_mode_all
            SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS -> R.id.split_tunneling_mode_exclude
            SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS -> R.id.split_tunneling_mode_include
        }
        if (binding.splitTunnelingModeGroup.checkedButtonId != modeButtonId)
            binding.splitTunnelingModeGroup.check(modeButtonId)

        val appSelectionEnabled = selectedMode != SplitTunnelingMode.ALL_APPLICATIONS
        binding.searchLayout.isEnabled = appSelectionEnabled
        binding.searchText.isEnabled = appSelectionEnabled
        binding.toggleAll.isEnabled = appSelectionEnabled
        binding.clearSelection.isEnabled = appSelectionEnabled
        binding.appList.alpha = if (appSelectionEnabled) 1f else 0.5f
        binding.summary.text = createSummaryText()
    }

    private fun createSummaryText(): String {
        val selectedCount = allAppData.count { it.isSelected }
        return when (selectedMode) {
            SplitTunnelingMode.ALL_APPLICATIONS -> getString(R.string.all_applications)
            SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS -> {
                if (selectedCount == 0) getString(R.string.no_excluded_applications_selected)
                else resources.getQuantityString(R.plurals.n_excluded_applications, selectedCount, selectedCount)
            }

            SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS -> {
                if (selectedCount == 0) getString(R.string.no_included_applications_selected)
                else resources.getQuantityString(R.plurals.n_included_applications, selectedCount, selectedCount)
            }
        }
    }

    private fun applyFilter() {
        val filtered = AppListDialogFragment.filterByQuery(searchQuery, allAppData, { it.name }, { it.packageName })
        appData.clear()
        appData.addAll(filtered)
        val binding = binding ?: return
        binding.emptyState.visibility = if (binding.progressBar.visibility == View.GONE && allAppData.isNotEmpty() && appData.isEmpty()) View.VISIBLE else View.GONE
        binding.summary.text = createSummaryText()
    }

    private fun persistSplitTunnelingChanges() {
        val tunnelName = selectedTunnelName ?: return
        val tunnel = tunnels?.firstOrNull { it.name == tunnelName } ?: return
        if (suppressSelectionPersistence)
            return
        val selectedApps = allAppData.filter { it.isSelected }.map { it.packageName }
        binding?.summary?.text = createSummaryText()
        lifecycleScope.launch {
            try {
                val configProxy = ConfigProxy(tunnel.getConfigAsync())
                val configInterface = configProxy.`interface`
                configInterface.splitTunnelingMode = selectedMode
                when (selectedMode) {
                    SplitTunnelingMode.ALL_APPLICATIONS -> {
                        configInterface.excludedApplications.clear()
                        configInterface.includedApplications.clear()
                    }

                    SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS -> {
                        configInterface.includedApplications.clear()
                        configInterface.excludedApplications.apply {
                            clear()
                            addAll(selectedApps)
                        }
                    }

                    SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS -> {
                        configInterface.excludedApplications.clear()
                        configInterface.includedApplications.apply {
                            clear()
                            addAll(selectedApps)
                        }
                    }
                }
                tunnel.setConfigAsync(configProxy.resolve())
            } catch (e: Throwable) {
                val message = getString(R.string.config_save_error, tunnel.name, ErrorMessages[e])
                Log.e(TAG, message, e)
                view?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
            }
        }
    }

    companion object {
        private const val TAG = "WireGuard/TunnelAppsFragment"
    }
}
