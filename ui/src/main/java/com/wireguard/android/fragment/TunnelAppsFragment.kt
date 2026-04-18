/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.os.Bundle
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.databinding.ObservableList
import androidx.lifecycle.Lifecycle
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TunnelAppsFragment : BaseFragment() {
    private enum class SaveStatus {
        IDLE,
        SAVING,
        SAVED,
        ERROR
    }

    private data class SavedRoutingState(
        val mode: SplitTunnelingMode,
        val selectedApps: Set<String>
    )

    private var binding: TunnelAppsFragmentBinding? = null
    private val allAppData = ObservableKeyedArrayList<String, ApplicationData>()
    private val appData = ObservableKeyedArrayList<String, ApplicationData>()
    private var selectedTunnelName: String? = null
    private var selectedMode = SplitTunnelingMode.ALL_APPLICATIONS
    private var suppressSelectionUpdates = false
    private var searchQuery = ""
    private var tunnels: ObservableKeyedArrayList<String, ObservableTunnel>? = null
    private var loadJob: Job? = null
    private val inFlightSaveTunnels = mutableSetOf<String>()
    private var latestLoadRequestId = 0L
    private var savedRoutingState: SavedRoutingState? = null
    private var hasUnsavedChanges = false
    private var saveStatus = SaveStatus.IDLE
    private var searchTextWatcher: TextWatcher? = null
    private var isViewTearingDown = false
    private var lastRenderedAppSelectionEnabled: Boolean? = null

    private val appRowConfigurationHandler = object : RowConfigurationHandler<AppListItemBinding, ApplicationData> {
        override fun onConfigureRow(binding: AppListItemBinding, item: ApplicationData, position: Int) {
            val selectionEnabled = selectedMode != SplitTunnelingMode.ALL_APPLICATIONS
            binding.root.isEnabled = selectionEnabled
            binding.root.isClickable = selectionEnabled
            binding.root.isFocusable = selectionEnabled
            binding.root.setOnClickListener(
                if (selectionEnabled) View.OnClickListener { item.isSelected = !item.isSelected } else null
            )
            binding.selectedCheckbox.isEnabled = selectionEnabled
            binding.selectedCheckbox.isClickable = selectionEnabled
            binding.selectedCheckbox.isFocusable = selectionEnabled
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
        isViewTearingDown = false
        lastRenderedAppSelectionEnabled = null
        val binding = requireNotNull(this.binding)
        binding.appData = appData
        binding.rowConfigurationHandler = appRowConfigurationHandler
        binding.searchText.setText(searchQuery)
        searchTextWatcher = binding.searchText.doAfterTextChanged {
            if (!isViewUsableForUiUpdates())
                return@doAfterTextChanged
            searchQuery = it?.toString() ?: ""
            applyFilter()
        }
        binding.searchText.setOnEditorActionListener { textView, actionId, event ->
            val isDoneAction = actionId == EditorInfo.IME_ACTION_DONE
            val isEnterKeyUp = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (!isDoneAction && !isEnterKeyUp)
                return@setOnEditorActionListener false
            if (isViewUsableForUiUpdates()) {
                textView.clearFocus()
                applyFilter()
                val imm = textView.context.getSystemService(InputMethodManager::class.java)
                imm?.hideSoftInputFromWindow(textView.windowToken, 0)
            }
            true
        }
        binding.toggleAll.setOnClickListener {
            if (selectedMode == SplitTunnelingMode.ALL_APPLICATIONS)
                return@setOnClickListener
            AppListDialogFragment.updateSelectionState(appData, true, { it.isSelected }) { item, selected -> item.isSelected = selected }
        }
        binding.clearSelection.setOnClickListener {
            if (selectedMode == SplitTunnelingMode.ALL_APPLICATIONS)
                return@setOnClickListener
            AppListDialogFragment.updateSelectionState(allAppData, false, { it.isSelected }) { item, selected -> item.isSelected = selected }
        }
        binding.cancelChanges.setOnClickListener { restoreSavedState() }
        binding.saveChanges.setOnClickListener { persistCurrentState() }
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
            selectedTunnelName = selectedTunnel?.name ?: selectedTunnelName
            refreshTunnelSelector(requireNotNull(tunnels))
        }
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        // Routing selection is local to this tab. Ignore global selected-tunnel navigation updates.
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SELECTED_TUNNEL_NAME, selectedTunnelName)
        outState.putString(KEY_SEARCH_QUERY, searchQuery)
        outState.putString(KEY_SELECTED_MODE, selectedMode.name)
    }

    override fun onDestroyView() {
        isViewTearingDown = true
        binding?.searchText?.let { searchText ->
            searchTextWatcher?.let(searchText::removeTextChangedListener)
            searchText.setOnEditorActionListener(null)
        }
        searchTextWatcher = null
        tunnels?.removeOnListChangedCallback(tunnelListObserver)
        tunnels = null
        loadJob?.cancel()
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
            savedRoutingState = null
            hasUnsavedChanges = false
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
                suppressSelectionUpdates = true
                selectedMode = mode
                allAppData.clear()
                allAppData.addAll(loadedApps)
                savedRoutingState = SavedRoutingState(mode, selectedApps)
                hasUnsavedChanges = false
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
                suppressSelectionUpdates = false
            }
        }
    }

    private fun onAppSelectionChanged() {
        if (suppressSelectionUpdates || selectedMode == SplitTunnelingMode.ALL_APPLICATIONS)
            return
        onUserStateChanged()
    }

    private fun onUserStateChanged() {
        if (suppressSelectionUpdates)
            return
        hasUnsavedChanges = calculateHasUnsavedChanges()
        if (saveStatus == SaveStatus.SAVED || saveStatus == SaveStatus.ERROR)
            saveStatus = SaveStatus.IDLE
        updateModeUi()
    }

    private fun persistCurrentState() {
        val tunnelName = selectedTunnelName ?: return
        if (!hasUnsavedChanges || tunnelName in inFlightSaveTunnels)
            return
        val tunnel = tunnels?.firstOrNull { it.name == tunnelName } ?: return
        val mode = selectedMode
        val selectedApps = allAppData.filter { it.isSelected }.map { it.packageName }
        inFlightSaveTunnels.add(tunnelName)
        saveStatus = SaveStatus.SAVING
        updateModeUi()
        lifecycleScope.launch {
            try {
                val configProxy = ConfigProxy(tunnel.getConfigAsync())
                val configInterface = configProxy.`interface`
                configInterface.splitTunnelingMode = mode
                when (mode) {
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
                if (selectedTunnelName == tunnelName) {
                    savedRoutingState = SavedRoutingState(mode, selectedApps.toSet())
                    hasUnsavedChanges = calculateHasUnsavedChanges()
                    saveStatus = if (hasUnsavedChanges) SaveStatus.IDLE else SaveStatus.SAVED
                }
            } catch (e: Throwable) {
                val message = getString(R.string.config_save_error, tunnel.name, ErrorMessages[e])
                Log.e(TAG, message, e)
                if (selectedTunnelName == tunnelName) {
                    saveStatus = SaveStatus.ERROR
                    view?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
                }
            } finally {
                inFlightSaveTunnels.remove(tunnelName)
                if (selectedTunnelName == tunnelName)
                    updateModeUi()
            }
        }
    }

    private fun restoreSavedState() {
        val state = savedRoutingState ?: return
        suppressSelectionUpdates = true
        selectedMode = state.mode
        allAppData.forEach { it.isSelected = it.packageName in state.selectedApps }
        applyFilter()
        suppressSelectionUpdates = false
        hasUnsavedChanges = false
        saveStatus = SaveStatus.IDLE
        updateModeUi()
    }

    private fun calculateHasUnsavedChanges(): Boolean {
        val state = savedRoutingState ?: return false
        if (selectedMode != state.mode)
            return true
        return allAppData.asSequence().filter { it.isSelected }.map { it.packageName }.toSet() != state.selectedApps
    }

    private fun updateModeUi() {
        val binding = binding ?: return
        val modeButtonId = when (selectedMode) {
            SplitTunnelingMode.ALL_APPLICATIONS -> R.id.split_tunneling_mode_all
            SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS -> R.id.split_tunneling_mode_exclude
            SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS -> R.id.split_tunneling_mode_include
        }
        if (binding.splitTunnelingModeGroup.getCheckedRadioButtonId() != modeButtonId)
            binding.splitTunnelingModeGroup.check(modeButtonId)

        val appSelectionEnabled = selectedMode != SplitTunnelingMode.ALL_APPLICATIONS
        val isCurrentTunnelSaving = selectedTunnelName?.let { it in inFlightSaveTunnels } == true
        binding.searchLayout.isEnabled = appSelectionEnabled
        binding.searchText.isEnabled = appSelectionEnabled
        binding.toggleAll.isEnabled = appSelectionEnabled
        binding.clearSelection.isEnabled = appSelectionEnabled
        binding.saveChanges.isEnabled = hasUnsavedChanges && !isCurrentTunnelSaving
        binding.cancelChanges.isEnabled = hasUnsavedChanges && !isCurrentTunnelSaving
        binding.appList.alpha = if (appSelectionEnabled) 1f else 0.7f
        if (lastRenderedAppSelectionEnabled != appSelectionEnabled) {
            lastRenderedAppSelectionEnabled = appSelectionEnabled
            requestSafeAppListRefresh()
        }
        binding.summary.text = createSummaryText()
        binding.saveStatus.text = when (saveStatus) {
            SaveStatus.IDLE -> ""
            SaveStatus.SAVING -> getString(R.string.saving)
            SaveStatus.SAVED -> getString(R.string.saved)
            SaveStatus.ERROR -> getString(R.string.save_failed)
        }
    }

    private fun requestSafeAppListRefresh() {
        val currentBinding = binding ?: return
        val appList = currentBinding.appList
        appList.post {
            val liveBinding = binding ?: return@post
            if (liveBinding !== currentBinding)
                return@post
            if (appList.isComputingLayout) {
                appList.post {
                    if (binding === liveBinding)
                        liveBinding.appList.adapter?.notifyDataSetChanged()
                }
            } else {
                liveBinding.appList.adapter?.notifyDataSetChanged()
            }
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
        if (!isViewUsableForUiUpdates())
            return
        val filtered = AppListDialogFragment.filterByQuery(searchQuery, allAppData, { it.name }, { it.packageName })
        appData.clear()
        appData.addAll(filtered)
        val binding = binding ?: return
        binding.emptyState.visibility = if (binding.progressBar.visibility == View.GONE && allAppData.isNotEmpty() && appData.isEmpty()) View.VISIBLE else View.GONE
        binding.summary.text = createSummaryText()
    }

    private fun isViewUsableForUiUpdates(): Boolean {
        if (isViewTearingDown || binding == null)
            return false
        val owner = viewLifecycleOwnerLiveData.value ?: return false
        return owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    companion object {
        private const val TAG = "WireGuard/TunnelAppsFragment"
        private const val KEY_SELECTED_TUNNEL_NAME = "selected_tunnel_name"
        private const val KEY_SEARCH_QUERY = "search_query"
        private const val KEY_SELECTED_MODE = "selected_mode"
    }
}
