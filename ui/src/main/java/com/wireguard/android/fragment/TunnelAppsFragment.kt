/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.graphics.drawable.Drawable
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
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
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.doAfterTextChanged
import androidx.databinding.ObservableList
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
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
        val excludedSelectedApps: Set<String>,
        val includedSelectedApps: Set<String>
    )

    private var binding: TunnelAppsFragmentBinding? = null
    private val allAppData = ObservableKeyedArrayList<String, ApplicationData>()
    private val appData = ObservableKeyedArrayList<String, ApplicationData>()
    private var selectedTunnelName: String? = null
    private var selectedMode = SplitTunnelingMode.ALL_APPLICATIONS
    private var suppressSelectionUpdates = false
    private var searchQuery = ""
    private val searchQueriesByMode = mutableMapOf<SplitTunnelingMode, String>()
    private val scrollPositionsByMode = mutableMapOf<SplitTunnelingMode, Int>()
    private var tunnels: ObservableKeyedArrayList<String, ObservableTunnel>? = null
    private var loadJob: Job? = null
    private val inFlightSaveTunnels = mutableSetOf<String>()
    private var latestLoadRequestId = 0L
    private var latestFilterRequestId = 0L
    private var savedRoutingState: SavedRoutingState? = null
    private val excludedSelectedApps = mutableSetOf<String>()
    private val includedSelectedApps = mutableSetOf<String>()
    private var hasUnsavedChanges = false
    private var saveStatus = SaveStatus.IDLE
    private var searchTextWatcher: TextWatcher? = null
    private var isViewTearingDown = false
    private var lastRenderedAppSelectionEnabled: Boolean? = null
    private var suppressModeDropdownSelection = false
    private var isAnimatingModeTransition = false
    private var filterJob: Job? = null
    private val iconLoadJobs = mutableMapOf<String, Job>()
    private val iconCache = mutableMapOf<String, Drawable>()
    private lateinit var modeSelectorAdapter: ArrayAdapter<String>
    private val modeSelectorModes = listOf(
        SplitTunnelingMode.ALL_APPLICATIONS,
        SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS,
        SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS
    )

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
            ensureRowIconLoaded(item)
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
        SplitTunnelingMode.entries.forEach { mode ->
            searchQueriesByMode[mode] = ""
            scrollPositionsByMode[mode] = 0
        }
        searchQueriesByMode[selectedMode] = searchQuery
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = TunnelAppsFragmentBinding.inflate(inflater, container, false)
        return requireNotNull(binding).root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val viewCreatedAt = SystemClock.elapsedRealtime()
        isViewTearingDown = false
        lastRenderedAppSelectionEnabled = null
        val binding = requireNotNull(this.binding)
        binding.appData = appData
        binding.rowConfigurationHandler = appRowConfigurationHandler
        if (binding.appList.layoutManager == null)
            binding.appList.layoutManager = LinearLayoutManager(requireContext())
        binding.searchText.setText(searchQuery)
        searchTextWatcher = binding.searchText.doAfterTextChanged {
            if (!isViewUsableForUiUpdates())
                return@doAfterTextChanged
            searchQuery = it?.toString() ?: ""
            searchQueriesByMode[selectedMode] = searchQuery
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
        binding.switchToExcludeMode.setOnClickListener { switchMode(SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS) }
        binding.switchToIncludeMode.setOnClickListener { switchMode(SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS) }
        binding.cancelChanges.setOnClickListener { restoreSavedState() }
        binding.saveChanges.setOnClickListener { persistCurrentState() }
        modeSelectorAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            modeSelectorModes.map(::getModeDisplayLabel)
        )
        binding.routingModeDropdown.inputType = InputType.TYPE_NULL
        binding.routingModeDropdown.keyListener = null
        binding.routingModeDropdown.isCursorVisible = false
        binding.routingModeDropdown.showSoftInputOnFocus = false
        binding.routingModeDropdown.setTextIsSelectable(false)
        binding.routingModeDropdown.setAdapter(modeSelectorAdapter)
        binding.routingModeDropdown.setOnItemClickListener { _, _, position, _ ->
            if (suppressModeDropdownSelection)
                return@setOnItemClickListener
            val mode = modeSelectorModes.getOrNull(position) ?: return@setOnItemClickListener
            switchMode(mode)
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
            Log.d(TAG, "Routing tab created and tunnel selector initialized in ${SystemClock.elapsedRealtime() - viewCreatedAt} ms")
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
        filterJob?.cancel()
        iconLoadJobs.values.forEach { it.cancel() }
        iconLoadJobs.clear()
        iconCache.clear()
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
            excludedSelectedApps.clear()
            includedSelectedApps.clear()
            SplitTunnelingMode.entries.forEach { mode ->
                searchQueriesByMode[mode] = ""
                scrollPositionsByMode[mode] = 0
            }
            searchQuery = ""
            binding.searchText.setText("")
            hasUnsavedChanges = false
            saveStatus = SaveStatus.IDLE
            updateModeUi()
            binding.summary.text = getString(R.string.no_tunnels_configured)
            binding.summary.setCompoundDrawablesRelative(null, null, null, null)
            binding.noTunnelState.visibility = View.VISIBLE
            binding.content.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
        }
    }

    private fun loadSelectedTunnelData(tunnel: ObservableTunnel) {
        val binding = binding ?: return
        val requestId = ++latestLoadRequestId
        val loadStartedAt = SystemClock.elapsedRealtime()
        loadJob?.cancel()
        filterJob?.cancel()
        iconLoadJobs.values.forEach { it.cancel() }
        iconLoadJobs.clear()
        binding.noTunnelState.visibility = View.GONE
        binding.content.visibility = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE
        loadJob = lifecycleScope.launch {
            try {
                val config = withContext(Dispatchers.IO) { tunnel.getConfigAsync() }
                val proxy = ConfigProxy(config)
                val mode = proxy.`interface`.splitTunnelingMode
                val loadedExcludedApps = proxy.`interface`.excludedApplications.toSet()
                val loadedIncludedApps = proxy.`interface`.includedApplications.toSet()
                if (!isAdded || requestId != latestLoadRequestId || selectedTunnelName != tunnel.name)
                    return@launch
                suppressSelectionUpdates = true
                selectedMode = mode
                allAppData.clear()
                appData.clear()
                excludedSelectedApps.clear()
                excludedSelectedApps.addAll(loadedExcludedApps)
                includedSelectedApps.clear()
                includedSelectedApps.addAll(loadedIncludedApps)
                SplitTunnelingMode.entries.forEach { tabMode ->
                    searchQueriesByMode[tabMode] = if (tabMode == mode) searchQuery else ""
                    scrollPositionsByMode[tabMode] = 0
                }
                applySelectionForMode(mode)
                savedRoutingState = SavedRoutingState(mode, loadedExcludedApps, loadedIncludedApps)
                hasUnsavedChanges = false
                applyFilter()
                saveStatus = SaveStatus.IDLE
                updateModeUi()
                if (mode == SplitTunnelingMode.ALL_APPLICATIONS) {
                    Log.d(TAG, "Routing tunnel config loaded in ${SystemClock.elapsedRealtime() - loadStartedAt} ms (apps skipped for ALL mode)")
                    return@launch
                }
                val selectedPackages = when (mode) {
                    SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS -> loadedExcludedApps
                    SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS -> loadedIncludedApps
                    SplitTunnelingMode.ALL_APPLICATIONS -> emptySet()
                }
                val loadedApps = withContext(Dispatchers.Default) {
                    AppDataLoader.load(requireContext().packageManager, selectedPackages) { onAppSelectionChanged() }
                }
                if (!isAdded || requestId != latestLoadRequestId || selectedTunnelName != tunnel.name)
                    return@launch
                allAppData.clear()
                allAppData.addAll(loadedApps)
                applyFilter()
                Log.d(TAG, "Routing tab load for ${tunnel.name} completed in ${SystemClock.elapsedRealtime() - loadStartedAt} ms")
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
        syncActiveModeSelectionFromUi()
        if (isViewUsableForUiUpdates())
            applyFilter()
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
        syncActiveModeSelectionFromUi()
        val mode = selectedMode
        val excludedAppsToPersist = excludedSelectedApps.toSet()
        val includedAppsToPersist = includedSelectedApps.toSet()
        inFlightSaveTunnels.add(tunnelName)
        saveStatus = SaveStatus.SAVING
        updateModeUi()
        lifecycleScope.launch {
            try {
                val configProxy = ConfigProxy(tunnel.getConfigAsync())
                val configInterface = configProxy.`interface`
                configInterface.splitTunnelingMode = mode
                configInterface.excludedApplications.apply {
                    clear()
                    addAll(excludedAppsToPersist)
                }
                configInterface.includedApplications.apply {
                    clear()
                    addAll(includedAppsToPersist)
                }
                tunnel.setConfigAsync(configProxy.resolve())
                if (selectedTunnelName == tunnelName) {
                    savedRoutingState = SavedRoutingState(mode, excludedAppsToPersist, includedAppsToPersist)
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
        val previousMode = selectedMode
        saveModeUiState(previousMode)
        suppressSelectionUpdates = true
        selectedMode = state.mode
        excludedSelectedApps.clear()
        excludedSelectedApps.addAll(state.excludedSelectedApps)
        includedSelectedApps.clear()
        includedSelectedApps.addAll(state.includedSelectedApps)
        applySelectionForMode(state.mode)
        suppressSelectionUpdates = false
        restoreModeUiState(state.mode)
        hasUnsavedChanges = false
        saveStatus = SaveStatus.IDLE
        updateModeUi()
        if (previousMode != state.mode)
            animateModeContentTransition()
    }

    private fun calculateHasUnsavedChanges(): Boolean {
        val state = savedRoutingState ?: return false
        if (selectedMode != state.mode)
            return true
        if (excludedSelectedApps != state.excludedSelectedApps)
            return true
        return includedSelectedApps != state.includedSelectedApps
    }

    private fun syncActiveModeSelectionFromUi() {
        val selection = allAppData.asSequence().filter { it.isSelected }.map { it.packageName }.toSet()
        when (selectedMode) {
            SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS -> {
                excludedSelectedApps.clear()
                excludedSelectedApps.addAll(selection)
            }

            SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS -> {
                includedSelectedApps.clear()
                includedSelectedApps.addAll(selection)
            }

            SplitTunnelingMode.ALL_APPLICATIONS -> Unit
        }
    }

    private fun applySelectionForMode(mode: SplitTunnelingMode) {
        val selectedPackages = when (mode) {
            SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS -> excludedSelectedApps
            SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS -> includedSelectedApps
            SplitTunnelingMode.ALL_APPLICATIONS -> emptySet()
        }
        allAppData.forEach { it.isSelected = it.packageName in selectedPackages }
    }

    private fun switchMode(mode: SplitTunnelingMode) {
        if (selectedMode == mode)
            return
        saveModeUiState(selectedMode)
        syncActiveModeSelectionFromUi()
        selectedMode = mode
        restoreModeUiState(mode)
        suppressSelectionUpdates = true
        applySelectionForMode(mode)
        suppressSelectionUpdates = false
        onUserStateChanged()
        animateModeContentTransition()
    }

    private fun saveModeUiState(mode: SplitTunnelingMode) {
        searchQueriesByMode[mode] = searchQuery
        val layoutManager = binding?.appList?.layoutManager as? LinearLayoutManager ?: return
        scrollPositionsByMode[mode] = layoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
    }

    private fun restoreModeUiState(mode: SplitTunnelingMode) {
        searchQuery = searchQueriesByMode[mode].orEmpty()
        val liveBinding = binding ?: return
        if (liveBinding.searchText.text?.toString().orEmpty() != searchQuery)
            liveBinding.searchText.setText(searchQuery)
        applyFilter()
        val scrollPosition = scrollPositionsByMode[mode] ?: 0
        liveBinding.appList.post {
            val layoutManager = binding?.appList?.layoutManager as? LinearLayoutManager ?: return@post
            layoutManager.scrollToPositionWithOffset(scrollPosition, 0)
        }
    }

    private fun animateModeContentTransition() {
        val liveBinding = binding ?: return
        if (isAnimatingModeTransition)
            return
        val modeContainer = liveBinding.modeContentContainer
        if (!modeContainer.isAttachedToWindow)
            return
        val distancePx = resources.displayMetrics.density * 10f
        isAnimatingModeTransition = true
        modeContainer.animate()
            .alpha(0f)
            .translationY(distancePx)
            .setDuration(90L)
            .withEndAction {
                modeContainer.translationY = -distancePx
                modeContainer.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(140L)
                    .withEndAction { isAnimatingModeTransition = false }
                    .start()
            }
            .start()
    }

    private fun updateModeUi() {
        val binding = binding ?: return
        val selectedModeLabel = getModeDisplayLabel(selectedMode)
        if (binding.routingModeDropdown.text?.toString() != selectedModeLabel) {
            suppressModeDropdownSelection = true
            binding.routingModeDropdown.setText(selectedModeLabel, false)
            suppressModeDropdownSelection = false
        }

        val appSelectionEnabled = selectedMode != SplitTunnelingMode.ALL_APPLICATIONS
        binding.allModeContainer.visibility = if (appSelectionEnabled) View.GONE else View.VISIBLE
        binding.selectionModeContainer.visibility = if (appSelectionEnabled) View.VISIBLE else View.GONE
        binding.modeHelper.visibility = if (appSelectionEnabled) View.VISIBLE else View.GONE
        binding.searchFeedback.visibility = if (appSelectionEnabled && searchQuery.isNotBlank()) View.VISIBLE else View.GONE
        binding.modeHelper.text = when (selectedMode) {
            SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS -> getString(R.string.routing_mode_helper_exclude)
            SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS -> getString(R.string.routing_mode_helper_include)
            SplitTunnelingMode.ALL_APPLICATIONS -> ""
        }
        val isCurrentTunnelSaving = selectedTunnelName?.let { it in inFlightSaveTunnels } == true
        binding.searchLayout.isEnabled = appSelectionEnabled
        binding.searchText.isEnabled = appSelectionEnabled
        binding.toggleAll.isEnabled = appSelectionEnabled
        binding.clearSelection.isEnabled = appSelectionEnabled
        binding.saveChanges.isEnabled = hasUnsavedChanges && !isCurrentTunnelSaving
        binding.cancelChanges.isEnabled = hasUnsavedChanges && !isCurrentTunnelSaving
        binding.saveChanges.alpha = if (binding.saveChanges.isEnabled) 1f else 0.5f
        binding.cancelChanges.alpha = if (binding.cancelChanges.isEnabled) 1f else 0.5f
        binding.appList.alpha = if (appSelectionEnabled) 1f else 0.7f
        if (lastRenderedAppSelectionEnabled != appSelectionEnabled) {
            lastRenderedAppSelectionEnabled = appSelectionEnabled
            requestSafeAppListRefresh()
        }
        updateSummaryUi()
        binding.searchFeedback.text = resources.getQuantityString(R.plurals.found_n_apps, appData.size, appData.size)
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

    private fun createSummaryText(): CharSequence {
        val summaryDetails = when (selectedMode) {
            SplitTunnelingMode.ALL_APPLICATIONS -> getString(R.string.vpn_applies_to_all_apps)
            SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS -> {
                val selectedCount = excludedSelectedApps.size
                if (selectedCount == 0) getString(R.string.no_apps_excluded_from_vpn)
                else resources.getQuantityString(R.plurals.n_apps_excluded_from_vpn, selectedCount, selectedCount)
            }

            SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS -> {
                val selectedCount = includedSelectedApps.size
                if (selectedCount == 0) getString(R.string.no_apps_use_vpn)
                else resources.getQuantityString(R.plurals.n_apps_use_vpn, selectedCount, selectedCount)
            }
        }
        val prefix = getString(R.string.routing_current_mode_prefix)
        return SpannableStringBuilder().apply {
            append(prefix)
            setSpan(StyleSpan(Typeface.BOLD), 0, prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(' ')
            append(summaryDetails)
        }
    }

    private fun updateSummaryUi() {
        val liveBinding = binding ?: return
        liveBinding.summary.text = createSummaryText()
        liveBinding.summary.setCompoundDrawablesRelative(
            createSummaryIconDrawable(getSummaryIconResIdForMode(selectedMode)),
            null,
            null,
            null
        )
        liveBinding.summary.compoundDrawablePadding = (resources.displayMetrics.density * 6).toInt()
    }

    private fun createSummaryIconDrawable(iconResId: Int): Drawable? {
        val drawable = AppCompatResources.getDrawable(requireContext(), iconResId)?.mutate() ?: return null
        val iconSizePx = (resources.displayMetrics.density * 18).toInt()
        drawable.setBounds(0, 0, iconSizePx, iconSizePx)
        DrawableCompat.setTint(drawable, MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnSurfaceVariant))
        return drawable
    }

    private fun getSummaryIconResIdForMode(mode: SplitTunnelingMode): Int {
        return when (mode) {
            SplitTunnelingMode.ALL_APPLICATIONS -> R.drawable.ic_routing_all
            SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS -> R.drawable.ic_routing_bypass
            SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS -> R.drawable.ic_routing_vpn_only
        }
    }

    private fun getModeDisplayLabel(mode: SplitTunnelingMode): String {
        return when (mode) {
            SplitTunnelingMode.ALL_APPLICATIONS -> getString(R.string.routing_mode_option_all_traffic)
            SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS -> getString(R.string.routing_mode_option_exclude)
            SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS -> getString(R.string.routing_mode_option_include_only)
        }
    }

    private fun applyFilter() {
        if (!isViewUsableForUiUpdates())
            return
        if (selectedMode == SplitTunnelingMode.ALL_APPLICATIONS) {
            updateAppListSafely(emptyList())
            return
        }
        val filterRequestId = ++latestFilterRequestId
        val filterStartedAt = SystemClock.elapsedRealtime()
        val query = searchQuery
        val source = allAppData.toList()
        filterJob?.cancel()
        filterJob = lifecycleScope.launch(Dispatchers.Default) {
            val filtered = AppListDialogFragment.filterByQuery(query, source, { it.name }, { it.packageName })
                .sortedWith(
                    compareBy<ApplicationData> { !it.isSelected }
                        .thenBy { it.isSystemApp }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.packageName }
                )
            withContext(Dispatchers.Main.immediate) {
                if (filterRequestId != latestFilterRequestId)
                    return@withContext
                updateAppListSafely(filtered)
                Log.d(TAG, "Routing list filter+sort completed in ${SystemClock.elapsedRealtime() - filterStartedAt} ms (size=${filtered.size}, query='${query}')")
            }
        }
    }

    private fun updateAppListSafely(newList: List<ApplicationData>) {
        val currentBinding = binding ?: return
        val recyclerView = currentBinding.appList
        recyclerView.post {
            val liveBinding = binding ?: return@post
            if (liveBinding !== currentBinding || !isViewUsableForUiUpdates())
                return@post
            if (recyclerView.isComputingLayout) {
                recyclerView.post { updateAppListSafely(newList) }
                return@post
            }
            appData.clear()
            appData.addAll(newList)
            val shouldShowEmptyState = selectedMode != SplitTunnelingMode.ALL_APPLICATIONS &&
                liveBinding.progressBar.visibility == View.GONE &&
                allAppData.isNotEmpty() &&
                newList.isEmpty()
            liveBinding.emptyState.visibility = if (shouldShowEmptyState) View.VISIBLE else View.GONE
            liveBinding.searchFeedback.text = resources.getQuantityString(R.plurals.found_n_apps, newList.size, newList.size)
            liveBinding.searchFeedback.visibility =
                if (selectedMode != SplitTunnelingMode.ALL_APPLICATIONS && searchQuery.isNotBlank()) View.VISIBLE else View.GONE
            updateSummaryUi()
            Log.d(TAG, "Routing first list render/refresh applied (visible=${newList.size})")
        }
    }

    private fun ensureRowIconLoaded(item: ApplicationData) {
        if (item.hasLoadedIcon)
            return
        val packageName = item.packageName
        iconCache[packageName]?.let { cachedIcon ->
            item.icon = cachedIcon
            item.hasLoadedIcon = true
            return
        }
        if (iconLoadJobs.containsKey(packageName))
            return
        iconLoadJobs[packageName] = lifecycleScope.launch(Dispatchers.IO) {
            val icon = runCatching { requireContext().packageManager.getApplicationIcon(packageName) }.getOrNull()
            withContext(Dispatchers.Main.immediate) {
                iconLoadJobs.remove(packageName)
                if (icon == null)
                    return@withContext
                if (!isViewUsableForUiUpdates())
                    return@withContext
                iconCache[packageName] = icon
                allAppData.asSequence().filter { it.packageName == packageName }.forEach {
                    it.icon = icon
                    it.hasLoadedIcon = true
                }
            }
        }
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
