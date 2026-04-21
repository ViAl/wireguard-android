/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.wireguard.android.databinding.JailAppDetailFragmentBinding
import com.wireguard.android.databinding.JailReasonListItemBinding
import com.wireguard.android.jail.domain.AppAuditManager
import com.wireguard.android.jail.domain.JailAppRepository
import com.wireguard.android.jail.domain.JailAuditRepository
import com.wireguard.android.jail.model.AuditSnapshot
import com.wireguard.android.jail.model.JailAppInfo
import com.wireguard.android.jail.model.RiskReason
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Audit detail screen for a single Jail-selected app.
 *
 * Shows the risk level, score breakdown (one card per [RiskReason]), raw declared permissions
 * for transparency, and a button to re-run the auditor. Observes
 * [JailAuditRepository.snapshotFor] so concurrent refreshes — or an audit finishing on a sibling
 * detail screen — are picked up without manual reloads.
 *
 * This fragment is pushed onto the Jail `childFragmentManager` back stack so that system back
 * returns to the Apps list naturally; the parent [JailFragment] clears this stack when the user
 * changes tabs.
 */
class JailAppDetailFragment : Fragment() {
    private var binding: JailAppDetailFragmentBinding? = null
    private lateinit var reasonsAdapter: ReasonsAdapter

    private val repository: JailAppRepository
        get() = Application.getJailComponent().appRepository
    private val auditRepository: JailAuditRepository
        get() = Application.getJailComponent().auditRepository

    private val packageName: String
        get() = requireArguments().getString(ARG_PACKAGE).orEmpty()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = JailAppDetailFragmentBinding.inflate(inflater, container, false)
        return requireNotNull(binding).root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(binding)
        reasonsAdapter = ReasonsAdapter()
        binding.jailDetailReasons.layoutManager = LinearLayoutManager(requireContext())
        binding.jailDetailReasons.adapter = reasonsAdapter
        binding.jailDetailReasons.isNestedScrollingEnabled = false

        binding.jailDetailPackage.text = packageName

        binding.jailDetailRefresh.setOnClickListener { triggerRefresh() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Combine app metadata (icon, label) with the latest audit snapshot so the UI
                // renders in one pass regardless of which flow emits first.
                repository.apps
                    .combine(auditRepository.snapshotFor(packageName)) { apps, snapshot -> apps to snapshot }
                    .combine(auditRepository.isRefreshing) { (apps, snapshot), refreshing ->
                        Triple(apps, snapshot, refreshing)
                    }
                    .collect { (apps, snapshot, refreshing) ->
                        render(apps.firstOrNull { it.packageName == packageName }, snapshot, refreshing)
                    }
            }
        }
    }

    override fun onDestroyView() {
        binding?.jailDetailReasons?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun render(app: JailAppInfo?, snapshot: AuditSnapshot?, refreshing: Boolean) {
        val binding = binding ?: return
        binding.jailDetailIcon.setImageDrawable(app?.icon)
        binding.jailDetailLabel.text = app?.label ?: packageName

        val level = snapshot?.score?.level
        binding.jailDetailLevel.text = level?.let { getString(it.labelRes) }.orEmpty()
        if (snapshot != null) {
            binding.jailDetailScore.text = getString(
                R.string.jail_detail_score_format,
                snapshot.score.score,
                AppAuditManager.SCORE_CEILING,
            )
            binding.jailDetailLastChecked.text = getString(
                R.string.jail_detail_last_checked,
                DateUtils.getRelativeDateTimeString(
                    requireContext(),
                    snapshot.generatedAtMillis,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.WEEK_IN_MILLIS,
                    0,
                ),
            )
        } else {
            binding.jailDetailScore.text = getString(R.string.jail_detail_audit_pending)
            binding.jailDetailLastChecked.text = getString(R.string.jail_detail_last_checked_never)
        }

        val reasons = snapshot?.score?.reasons.orEmpty()
        reasonsAdapter.submitList(reasons)
        val showEmpty = snapshot != null && reasons.isEmpty()
        binding.jailDetailReasonsEmpty.visibility = if (showEmpty) View.VISIBLE else View.GONE

        val declared = snapshot?.permissionAudit?.declaredPermissions.orEmpty()
        if (declared.isEmpty()) {
            binding.jailDetailDeclaredHeader.visibility = View.GONE
            binding.jailDetailDeclared.visibility = View.GONE
        } else {
            binding.jailDetailDeclaredHeader.visibility = View.VISIBLE
            binding.jailDetailDeclared.visibility = View.VISIBLE
            binding.jailDetailDeclared.text = declared.joinToString(separator = "\n")
        }

        binding.jailDetailProgress.visibility = if (refreshing) View.VISIBLE else View.GONE
        binding.jailDetailRefresh.isEnabled = !refreshing
    }

    private fun triggerRefresh() {
        auditRepository.refreshOne(
            context = requireContext().applicationContext,
            packageName = packageName,
            scope = Application.getCoroutineScope(),
        )
    }

    private class ReasonsAdapter : ListAdapter<RiskReason, ReasonViewHolder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReasonViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return ReasonViewHolder(JailReasonListItemBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: ReasonViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<RiskReason>() {
                override fun areItemsTheSame(oldItem: RiskReason, newItem: RiskReason) =
                    oldItem.signalId == newItem.signalId
                override fun areContentsTheSame(oldItem: RiskReason, newItem: RiskReason) =
                    oldItem == newItem
            }
        }
    }

    private class ReasonViewHolder(val binding: JailReasonListItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(reason: RiskReason) {
            val ctx = binding.root.context
            val signal = reason.signal
            binding.jailReasonTitle.text = signal?.let { ctx.getString(it.shortRes) } ?: reason.signalId
            binding.jailReasonDetail.text = signal?.let { ctx.getString(it.detailRes) }.orEmpty()
            binding.jailReasonConfidence.text = ctx.getString(reason.confidence.labelRes)
            binding.jailReasonWeight.text = ctx.getString(R.string.jail_detail_weight_format, reason.weight)
        }
    }

    companion object {
        private const val ARG_PACKAGE = "package_name"

        fun newInstance(packageName: String): JailAppDetailFragment = JailAppDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_PACKAGE, packageName) }
        }
    }
}
