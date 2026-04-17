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
import com.wireguard.android.R
import com.wireguard.android.Application
import com.wireguard.android.databinding.AppListItemBinding
import com.wireguard.android.databinding.ObservableKeyedArrayList
import com.wireguard.android.databinding.ObservableKeyedRecyclerViewAdapter.RowConfigurationHandler
import com.wireguard.android.databinding.TunnelAppsFragmentBinding
import com.wireguard.android.model.ApplicationData
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.viewmodel.ConfigProxy
import com.wireguard.android.viewmodel.SplitTunnelingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TunnelAppsFragment : BaseFragment() {
    private enum class SaveStatus {
        IDLE,
        SAVING,
        SAVED,
        ERROR
    }

    private data class PersistSnapshot(
        val tunnelName: String,
        val mode: SplitTunnelingMode,
        val selectedApps: List<String>,
        val uiVersion: Long
    )

    private var binding: TunnelAppsFragmentBinding? = null
    private val allAppData = ObservableKeyedArrayList<String, ApplicationData>()
    private val appData = ObservableKeyedArrayList<String, ApplicationData>()
    private var selectedTunnelName: String? = null
    private var selectedMode = SplitTunnelingMode.ALL_APPLICATIONS
    private var suppressSelectionPersistence = false
    private var searchQuery = ""
    private var tunnels: ObservableKeyedArrayList<String, ObservableTunnel>? = null
    private var loadJob: Job? = null
    private var saveDebounceJob: Job? = null
    private var saveJob: Job? = null
    private var latestLoadRequestId = 0L
    private var latestUiVersion = 0L
    private var pendingPersistSnapshot: PersistSnapshot? = null
    private var saveStatus = SaveStatus.IDLE

    private val appRowConfigurationHandler = object : RowConfigurationHandler<AppListItemBinding, ApplicationData> {
        override fun onConfigureRow(binding: AppListItemBinding, item: ApplicationData, position: Int) {
            binding.selectionEnabled = selectedMode != SplitTunnelingMode.ALL_APPLICATIONS
        }
    }

    private val tunnelListObserver = object : ObservableList.OnListChangedCallback<ObservableList<ObservableTunnel>>() {
        override fun onChanged(sender: ObservableList<ObservableTunnel>) = refreshTunnelSelector(sender)
        override fun onItemRangeChanged(sender: ObservableList<ObservableTunnel>, positionStart: Int, itemCount: Int) = refreshTunnelSelector(sender)
        override fun onItemRangeInserted(sender: ObservableList<ObservableTunnel>, positionStart: Int, itemCount: Int) = refreshTunnelSelector(sender)
        override fun onItemRangeMoved(sender: ObservableList<ObservableTunnel>, fromPosition: Int, toPosition: Int, itemCount: Int) = refreshTunnelSelector(sender)
        override fun onItemRangeRemoved(sender: ObservableList<ObservableTunnel>, positionStart: Int, itemCount: Int) = refreshTunnelSelector(sender)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTunnelName = savedInstanceState?.getString(KEY_SELECTED_TUNNEL_NAME)
        searchQuery = savedInstanceState?.getString(KEY_SEARCH_QUERY) ?: ""
        selectedMode = savedInstanceState?.getString(KEY_SELECTED_MODE)
            ?.let { runCatching { SplitTunnelingMode.valueOf(it) }.getOrNull() }
            ?: SplitTunnelingMode.ALL_APPLICATIONS
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = TunnelAppsFragmentBinding.inflate(inflater, container, false)
        return requireNotNull(binding).root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(this.binding)
        binding.appData = appData
        binding.rowConfigurationHandler = appRowConfigurationHandler
        binding.searchText.setText(searchQuery)
        binding.searchText.doAfterTextChanged {
            searchQuery = it?.toString() ?: ""
            applyFilter()
        }
        binding.toggleAll.setOnClickListener {
            if (selectedMode == SplitTunnelingMode.ALL_APPLICATIONS)
                return@setOnClickListener
            val selectAll = AppListDialogFragment.shouldSelectAllVisible(appData.map { it.isSelected })
            AppListDialogFragment.updateSelectionState(appData, selectAll, { it.isSelected }) { item, selected -> item.isSelected = selected }
        }
        binding.clearSelection.setOnClickListener {
            if (selectedMode == SplitTunnelingMode.ALL_APPLICATIONS)
                return@setOnClickListener
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
                onUserStateChanged()
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SELECTED_TUNNEL_NAME, selectedTunnelName)
        outState.putString(KEY_SEARCH_QUERY, searchQuery)
        outState.putString(KEY_SELECTED_MODE, selectedMode.name)
    }

    override fun onDestroyView() {
        tunnels?.removeOnListChangedCallback(tunnelListObserver)
        tunnels = null
        loadJob?.cancel()
        saveDebounceJob?.cancel()
        saveJob?.cancel()
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
            pendingPersistSnapshot = null
            saveStatus = SaveStatus.IDLE
            updateModeUi()
            binding.summary.text = getString(R.string.no_tunnels_configured)
            binding.noTunnelState.visibility = View.VISIBLE
            binding.content.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
        }
    }

    private fun loadSelectedTunnelData(tunnel: ObservableTunnel) {
        val binding = binding ?: return
        val requestId = ++latestLoadRequestId
        loadJob?.cancel()
        binding.noTunnelState.visibility = View.GONE
        binding.content.visibility = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE
        loadJob = lifecycleScope.launch {
            try {
                val config = tunnel.getConfigAsync()
                val proxy = ConfigProxy(config)
                val mode = proxy.`interface`.splitTunnelingMode
                val selectedApps = when (mode) {
                    SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS -> proxy.`interface`.excludedApplications.toSet()
                    SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS -> proxy.`interface`.includedApplications.toSet()
                    SplitTunnelingMode.ALL_APPLICATIONS -> emptySet()
                }
                val loadedApps = withContext(Dispatchers.Default) {
                    AppDataLoader.load(requireContext().packageManager, selectedApps) {
                        onAppSelectionChanged()
                    }
                }
                if (!isAdded || requestId != latestLoadRequestId || selectedTunnelName != tunnel.name)
                    return@launch
                suppressSelectionPersistence = true
                selectedMode = mode
                allAppData.clear()
                allAppData.addAll(loadedApps)
                applyFilter()
                saveStatus = SaveStatus.IDLE
                updateModeUi()
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Throwable) {
                if (!isAdded || requestId != latestLoadRequestId)
                    return@launch
                val error = ErrorMessages[e]
                val message = getString(R.string.error_fetching_apps, error)
                Log.e(TAG, message, e)
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            } finally {
                if (requestId == latestLoadRequestId)
                    binding.progressBar.visibility = View.GONE
                suppressSelectionPersistence = false
            }
        }
    }

    private fun onAppSelectionChanged() {
        if (suppressSelectionPersistence || selectedMode == SplitTunnelingMode.ALL_APPLICATIONS)
            return
        onUserStateChanged()
    }

    private fun onUserStateChanged() {
        if (suppressSelectionPersistence)
            return
        latestUiVersion += 1
        saveStatus = SaveStatus.SAVING
        updateModeUi()
        schedulePersist()
    }

    private fun schedulePersist() {
        val tunnelName = selectedTunnelName ?: return
        pendingPersistSnapshot = PersistSnapshot(
            tunnelName = tunnelName,
            mode = selectedMode,
            selectedApps = allAppData.filter { it.isSelected }.map { it.packageName },
            uiVersion = latestUiVersion
        )
        saveDebounceJob?.cancel()
        saveDebounceJob = lifecycleScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            drainPersistQueue()
        }
    }

    private fun drainPersistQueue() {
        if (saveJob?.isActive == true)
            return
        saveJob = lifecycleScope.launch {
            while (true) {
                val snapshot = pendingPersistSnapshot ?: break
                pendingPersistSnapshot = null
                val success = persistSnapshot(snapshot)
                if (!success)
                    break
                if (pendingPersistSnapshot != null)
                    delay(SAVE_DEBOUNCE_MS)
            }
            if (saveStatus == SaveStatus.SAVING)
                saveStatus = SaveStatus.SAVED
            updateModeUi()
        }
    }

    private suspend fun persistSnapshot(snapshot: PersistSnapshot): Boolean {
        val tunnel = tunnels?.firstOrNull { it.name == snapshot.tunnelName } ?: return false
        return try {
            val configProxy = ConfigProxy(tunnel.getConfigAsync())
            val configInterface = configProxy.`interface`
            configInterface.splitTunnelingMode = snapshot.mode
            when (snapshot.mode) {
                SplitTunnelingMode.ALL_APPLICATIONS -> {
                    configInterface.excludedApplications.clear()
                    configInterface.includedApplications.clear()
                }

                SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS -> {
                    configInterface.includedApplications.clear()
                    configInterface.excludedApplications.apply {
                        clear()
                        addAll(snapshot.selectedApps)
                    }
                }

                SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS -> {
                    configInterface.excludedApplications.clear()
                    configInterface.includedApplications.apply {
                        clear()
                        addAll(snapshot.selectedApps)
                    }
                }
            }
            tunnel.setConfigAsync(configProxy.resolve())
            if (snapshot.uiVersion == latestUiVersion)
                saveStatus = SaveStatus.SAVED
            true
        } catch (e: Throwable) {
            val message = getString(R.string.config_save_error, tunnel.name, ErrorMessages[e])
            saveStatus = SaveStatus.ERROR
            Log.e(TAG, message, e)
            view?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
            if (selectedTunnelName == snapshot.tunnelName)
                loadSelectedTunnelData(tunnel)
            false
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
        binding.appList.alpha = if (appSelectionEnabled) 1f else 0.7f
        binding.appList.adapter?.notifyDataSetChanged()
        binding.summary.text = createSummaryText()
        binding.saveStatus.text = when (saveStatus) {
            SaveStatus.IDLE -> ""
            SaveStatus.SAVING -> getString(R.string.saving)
            SaveStatus.SAVED -> getString(R.string.saved)
            SaveStatus.ERROR -> getString(R.string.save_failed)
        }
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

    companion object {
        private const val TAG = "WireGuard/TunnelAppsFragment"
        private const val SAVE_DEBOUNCE_MS = 350L
        private const val KEY_SELECTED_TUNNEL_NAME = "selected_tunnel_name"
        private const val KEY_SEARCH_QUERY = "search_query"
        private const val KEY_SELECTED_MODE = "selected_mode"
    }
}
