/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PackageInfoFlags
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.appcompat.app.AlertDialog
import androidx.databinding.Observable
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.wireguard.android.BR
import com.wireguard.android.R
import com.wireguard.android.databinding.AppListDialogFragmentBinding
import com.wireguard.android.databinding.ObservableKeyedArrayList
import com.wireguard.android.model.ApplicationData
import com.wireguard.android.util.ErrorMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListDialogFragment : DialogFragment() {
    private val allAppData = ObservableKeyedArrayList<String, ApplicationData>()
    private val appData = ObservableKeyedArrayList<String, ApplicationData>()
    private var currentlySelectedApps = emptySet<String>()
    private var initialTabPosition = 0
    private var searchQuery = ""
    private var button: Button? = null
    private var tabs: TabLayout? = null
    private var binding: AppListDialogFragmentBinding? = null

    private fun loadData() {
        val activity = activity ?: return
        val pm = activity.packageManager
        setLoading(true)
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val applicationData: MutableList<ApplicationData> = ArrayList()
                withContext(Dispatchers.IO) {
                    val packageInfos = getPackagesHoldingPermissions(pm, arrayOf(Manifest.permission.INTERNET))
                    packageInfos.forEach {
                        val packageName = it.packageName
                        val appInfo = it.applicationInfo ?: return@forEach
                        val appData =
                            ApplicationData(appInfo.loadIcon(pm), appInfo.loadLabel(pm).toString(), packageName, currentlySelectedApps.contains(packageName))
                        applicationData.add(appData)
                        appData.addOnPropertyChangedCallback(object : Observable.OnPropertyChangedCallback() {
                            override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                                if (propertyId == BR.selected)
                                    setButtonText()
                            }
                        })
                    }
                }
                applicationData.sortWith(compareBy<ApplicationData>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.packageName })
                withContext(Dispatchers.Main.immediate) {
                    allAppData.clear()
                    allAppData.addAll(applicationData)
                    applyFilter()
                    setButtonText()
                    setLoading(false)
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    val error = ErrorMessages[e]
                    val message = activity.getString(R.string.error_fetching_apps, error)
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                    setLoading(false)
                    dismissAllowingStateLoss()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentlySelectedApps = (savedInstanceState?.getStringArrayList(KEY_SELECTED_APPS)
            ?: arguments?.getStringArrayList(KEY_SELECTED_APPS)
            ?: emptyList()).toSet()
        initialTabPosition = savedInstanceState?.getInt(KEY_TAB_POSITION)
            ?: if (arguments?.getBoolean(KEY_IS_EXCLUDED) ?: true) 0 else 1
        searchQuery = savedInstanceState?.getString(KEY_SEARCH_QUERY) ?: ""
    }

    private fun getPackagesHoldingPermissions(pm: PackageManager, permissions: Array<String>): List<PackageInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackagesHoldingPermissions(permissions, PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackagesHoldingPermissions(permissions, 0)
        }
    }

    private fun setButtonText() {
        val numSelected = allAppData.count { it.isSelected }
        button?.text = if (numSelected == 0)
            getString(R.string.use_all_applications)
        else when (tabs?.selectedTabPosition) {
            0 -> resources.getQuantityString(R.plurals.exclude_n_applications, numSelected, numSelected)
            1 -> resources.getQuantityString(R.plurals.include_n_applications, numSelected, numSelected)
            else -> null
        }
    }

    private fun applyFilter() {
        val filtered = filterByQuery(searchQuery, allAppData, { it.name }, { it.packageName })
        appData.clear()
        appData.addAll(filtered)
        setEmptyStateVisible(!isLoading() && allAppData.isNotEmpty() && appData.isEmpty())
    }

    private fun setEmptyStateVisible(visible: Boolean) {
        binding?.emptyState?.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun isLoading(): Boolean = binding?.progressBar?.visibility == android.view.View.VISIBLE

    private fun setLoading(loading: Boolean) {
        binding?.progressBar?.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
        if (loading)
            setEmptyStateVisible(false)
        else
            setEmptyStateVisible(allAppData.isNotEmpty() && appData.isEmpty())
    }

    fun onToggleAllSelection(view: android.view.View?) {
        val selectAll = shouldSelectAllVisible(appData.map { it.isSelected })
        updateSelectionState(appData, selectAll, { it.isSelected }) { item, isSelected -> item.isSelected = isSelected }
    }

    fun onClearSelection(view: android.view.View?) {
        updateSelectionState(allAppData, false, { it.isSelected }) { item, isSelected -> item.isSelected = isSelected }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val alertDialogBuilder = MaterialAlertDialogBuilder(requireActivity())
        val binding = AppListDialogFragmentBinding.inflate(requireActivity().layoutInflater, null, false)
        this.binding = binding
        binding.executePendingBindings()
        alertDialogBuilder.setView(binding.root)
        tabs = binding.tabs
        tabs?.apply {
            selectTab(binding.tabs.getTabAt(initialTabPosition))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabReselected(tab: TabLayout.Tab?) = Unit
                override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    initialTabPosition = tab?.position ?: 0
                    setButtonText()
                }
            })
        }
        alertDialogBuilder.setPositiveButton(" ") { _, _ -> setSelectionAndDismiss() }
        alertDialogBuilder.setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
        binding.fragment = this
        binding.appData = appData
        binding.searchText.setText(searchQuery)
        binding.searchText.doAfterTextChanged {
            searchQuery = it?.toString() ?: ""
            applyFilter()
        }
        loadData()
        val dialog = alertDialogBuilder.create()
        dialog.setOnShowListener {
            button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            setButtonText()
        }
        return dialog
    }

    private fun setSelectionAndDismiss() {
        val selectedApps: MutableList<String> = ArrayList()
        for (data in allAppData) {
            if (data.isSelected) {
                selectedApps.add(data.packageName)
            }
        }
        setFragmentResult(
            REQUEST_SELECTION,
            Bundle().apply {
                putStringArray(KEY_SELECTED_APPS, selectedApps.toTypedArray())
                putBoolean(KEY_IS_EXCLUDED, tabs?.selectedTabPosition == 0)
            }
        )
        dismiss()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val selectedPackages = if (allAppData.isEmpty()) currentlySelectedApps else allAppData.filter { it.isSelected }.map { it.packageName }.toSet()
        outState.putStringArrayList(KEY_SELECTED_APPS, ArrayList(selectedPackages))
        outState.putInt(KEY_TAB_POSITION, tabs?.selectedTabPosition ?: initialTabPosition)
        outState.putString(KEY_SEARCH_QUERY, searchQuery)
    }

    override fun onDestroyView() {
        binding = null
        button = null
        tabs = null
        super.onDestroyView()
    }

    companion object {
        const val KEY_SELECTED_APPS = "selected_apps"
        const val KEY_IS_EXCLUDED = "is_excluded"
        const val REQUEST_SELECTION = "request_selection"
        private const val KEY_TAB_POSITION = "tab_position"
        private const val KEY_SEARCH_QUERY = "search_query"

        internal fun matchesSearchQuery(query: String, appName: String, packageName: String): Boolean {
            if (query.isBlank())
                return true
            val normalizedQuery = query.trim().lowercase()
            return appName.lowercase().contains(normalizedQuery) || packageName.lowercase().contains(normalizedQuery)
        }

        internal fun <T> filterByQuery(
            query: String,
            applications: Collection<T>,
            appNameSelector: (T) -> String,
            packageNameSelector: (T) -> String
        ): List<T> = applications.filter {
            matchesSearchQuery(query, appNameSelector(it), packageNameSelector(it))
        }

        internal fun shouldSelectAllVisible(selectedFlags: Collection<Boolean>): Boolean = selectedFlags.none { it }

        internal fun <T> updateSelectionState(
            applications: Collection<T>,
            selected: Boolean,
            selectedGetter: (T) -> Boolean,
            selectedSetter: (T, Boolean) -> Unit
        ) {
            applications.forEach {
                if (selectedGetter(it) != selected)
                    selectedSetter(it, selected)
            }
        }

        fun newInstance(selectedApps: ArrayList<String?>?, isExcluded: Boolean): AppListDialogFragment {
            val extras = Bundle()
            extras.putStringArrayList(KEY_SELECTED_APPS, selectedApps)
            extras.putBoolean(KEY_IS_EXCLUDED, isExcluded)
            val fragment = AppListDialogFragment()
            fragment.arguments = extras
            return fragment
        }
    }
}
