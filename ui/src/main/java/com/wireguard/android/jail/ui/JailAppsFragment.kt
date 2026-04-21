/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.databinding.JailAppListItemBinding
import com.wireguard.android.databinding.JailAppsFragmentBinding
import com.wireguard.android.jail.domain.JailAppRepository
import com.wireguard.android.jail.domain.JailAuditRepository
import com.wireguard.android.jail.model.AuditSnapshot
import com.wireguard.android.jail.model.JailAppBadge
import com.wireguard.android.jail.model.JailAppInfo
import com.wireguard.android.jail.storage.JailSelectionStore
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Searchable list of installed applications. Users pick which ones are considered "jailed";
 * the selection is persisted to [com.wireguard.android.jail.storage.JailStore] via
 * [JailSelectionStore] and survives process death.
 *
 * The heavy PackageManager scan runs once per view creation on a background dispatcher via
 * [JailAppRepository.refreshInstalledApps]; subsequent selection toggles only rebuild the
 * visible list from the cached base, keeping scrolling responsive.
 */
class JailAppsFragment : Fragment() {
    private var binding: JailAppsFragmentBinding? = null
    private lateinit var adapter: AppAdapter
    private var searchQuery: String = ""

    private val repository: JailAppRepository
        get() = Application.getJailComponent().appRepository
    private val selectionStore: JailSelectionStore
        get() = Application.getJailComponent().selectionStore
    private val auditRepository: JailAuditRepository
        get() = Application.getJailComponent().auditRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = JailAppsFragmentBinding.inflate(inflater, container, false)
        return requireNotNull(binding).root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(binding)
        adapter = AppAdapter(
            badgesFor = { row -> repository.badgesFor(row.app, row.snapshot) },
            badgesSeparator = getString(R.string.jail_apps_badges_separator),
            onToggle = { pkg -> selectionStore.toggle(pkg) },
            onOpenDetail = { pkg ->
                (parentFragment as? JailFragmentHost)?.openAppDetail(pkg)
            }
        )
        binding.jailAppsList.layoutManager = LinearLayoutManager(requireContext())
        binding.jailAppsList.adapter = adapter

        val restoredQuery = savedInstanceState?.getString(KEY_SEARCH_QUERY).orEmpty()
        if (restoredQuery.isNotEmpty()) {
            searchQuery = restoredQuery
            binding.jailAppsSearch.setText(restoredQuery)
        }
        binding.jailAppsSearch.doAfterTextChanged {
            searchQuery = it?.toString().orEmpty()
            renderList(cachedRows)
        }

        binding.jailAppsProgress.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            repository.refreshInstalledApps(requireContext())
            binding.jailAppsProgress.visibility = View.GONE
        }

        // Kick an audit refresh in the application scope so it completes even if the user leaves
        // this screen; results flow back through the repository's StateFlow either way.
        Application.getCoroutineScope().launch {
            auditRepository.refreshAllSelected(requireContext().applicationContext)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.apps
                    .combine(auditRepository.snapshots) { apps, snapshots ->
                        apps.map { AppRow(it, snapshots[it.packageName]) }
                    }
                    .collect { rows ->
                        cachedRows = rows
                        renderList(rows)
                    }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SEARCH_QUERY, searchQuery)
    }

    override fun onDestroyView() {
        binding?.jailAppsList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private var cachedRows: List<AppRow> = emptyList()

    private fun renderList(rows: List<AppRow>) {
        val binding = binding ?: return
        val query = searchQuery.trim()
        val filtered = if (query.isEmpty()) rows else rows.filter {
            it.app.label.contains(query, ignoreCase = true) ||
                it.app.packageName.contains(query, ignoreCase = true)
        }
        adapter.submitList(filtered)
        binding.jailAppsEmpty.visibility = if (filtered.isEmpty() && binding.jailAppsProgress.visibility != View.VISIBLE)
            View.VISIBLE else View.GONE
        val total = rows.size
        val chosen = rows.count { it.app.isSelectedForJail }
        binding.jailAppsSummary.text = resources.getQuantityString(
            R.plurals.jail_apps_summary_selected,
            total,
            chosen,
            total
        )
    }

    /** Paired (app, latest-audit-snapshot) row model so ListAdapter/DiffUtil can track both. */
    data class AppRow(val app: JailAppInfo, val snapshot: AuditSnapshot?)

    private class AppAdapter(
        private val badgesFor: (AppRow) -> List<JailAppBadge>,
        private val badgesSeparator: String,
        private val onToggle: (String) -> Unit,
        private val onOpenDetail: (String) -> Unit
    ) : ListAdapter<AppRow, AppViewHolder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return AppViewHolder(JailAppListItemBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            holder.bind(getItem(position), badgesFor, badgesSeparator, onToggle, onOpenDetail)
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<AppRow>() {
                override fun areItemsTheSame(oldItem: AppRow, newItem: AppRow) =
                    oldItem.app.packageName == newItem.app.packageName

                override fun areContentsTheSame(oldItem: AppRow, newItem: AppRow) =
                    oldItem == newItem
            }
        }
    }

    private class AppViewHolder(val binding: JailAppListItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            row: AppRow,
            badgesFor: (AppRow) -> List<JailAppBadge>,
            badgesSeparator: String,
            onToggle: (String) -> Unit,
            onOpenDetail: (String) -> Unit
        ) {
            val app = row.app
            binding.jailAppIcon.setImageDrawable(app.icon)
            binding.jailAppLabel.text = app.label
            binding.jailAppPackage.text = app.packageName
            binding.jailAppCheckbox.setOnCheckedChangeListener(null)
            binding.jailAppCheckbox.isChecked = app.isSelectedForJail
            val badges = badgesFor(row)
            if (badges.isEmpty()) {
                binding.jailAppBadges.visibility = View.GONE
            } else {
                val context = binding.root.context
                binding.jailAppBadges.visibility = View.VISIBLE
                binding.jailAppBadges.text = badges.joinToString(badgesSeparator) { context.getString(it.labelRes) }
            }
            binding.jailAppRow.setOnClickListener { onToggle(app.packageName) }
            binding.jailAppInfo.setOnClickListener { onOpenDetail(app.packageName) }
        }
    }

    companion object {
        private const val KEY_SEARCH_QUERY = "jail_apps_search_query"
    }
}
