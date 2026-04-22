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
import android.widget.ArrayAdapter
import android.widget.Toast
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wireguard.android.databinding.JailAppDetailFragmentBinding
import com.wireguard.android.databinding.JailReasonListItemBinding
import com.wireguard.android.jail.domain.AppAuditManager
import com.wireguard.android.jail.domain.JailAppRepository
import com.wireguard.android.jail.domain.JailAuditRepository
import com.wireguard.android.jail.domain.PerAppVpnManager
import com.wireguard.android.jail.enterprise.WorkProfileAppCatalogService
import com.wireguard.android.jail.model.AuditSnapshot
import com.wireguard.android.jail.model.JailAppInfo
import com.wireguard.android.jail.model.JailTunnelMode
import com.wireguard.android.jail.model.RiskReason
import com.wireguard.android.jail.model.WorkProfileAppAction
import com.wireguard.android.jail.model.WorkProfileInstallEnvironmentReason
import com.wireguard.android.jail.model.WorkProfileInstallSessionState
import com.wireguard.android.jail.storage.JailStore
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
    private val perAppVpnManager: PerAppVpnManager
        get() = Application.getJailComponent().perAppVpnManager
    private val workProfileCatalogService: WorkProfileAppCatalogService
        get() = Application.getJailComponent().workProfileCatalogService
    private val workProfileInstallSessionManager
        get() = Application.getJailComponent().workProfileInstallSessionManager

    /** Latest routing snapshot for Apply (tunnel name + merge baseline). */
    private var routingPoliciesSnapshot: Map<String, JailTunnelMode> = emptyMap()
    private var jailTunnelNameSnapshot: String? = null
    private var jailManagedPackagesSnapshot: Set<String> = emptySet()
    private var workProfileInstallSessionState: WorkProfileInstallSessionState = WorkProfileInstallSessionState.Idle

    private val packageName: String
        get() = requireArguments().getString(ARG_PACKAGE).orEmpty()

    private val routingModes: List<JailTunnelMode> = listOf(
        JailTunnelMode.DEFAULT,
        JailTunnelMode.JAIL_ROUTE_THROUGH_TUNNEL,
        JailTunnelMode.JAIL_EXCLUDE_FROM_TUNNEL,
        JailTunnelMode.JAIL_STRICT_PROFILE,
        JailTunnelMode.DISABLED,
    )

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

        binding.jailDetailRiskHelp.setOnClickListener {
            (parentFragment as? JailFragmentHost)?.openHelp()
        }

        val routingLabels = routingModes.map { getString(it.labelRes()) }
        binding.jailDetailRoutingSpinner.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, routingLabels)
        binding.jailDetailRoutingHelp.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.jail_detail_routing_title)
                .setMessage(R.string.jail_detail_routing_help_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        binding.jailDetailRoutingApply.setOnClickListener { applyRoutingSelection() }
        binding.jailDetailWorkProfileAction.setOnClickListener { onWorkProfileActionClicked() }

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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    JailStore.routingPolicies,
                    JailStore.jailTunnelName,
                    JailStore.jailManagedPackages,
                ) { policies, tunnelName, managed ->
                    Triple(policies, tunnelName, managed)
                }.collect { (policies, tunnelName, managed) ->
                    routingPoliciesSnapshot = policies
                    jailTunnelNameSnapshot = tunnelName
                    jailManagedPackagesSnapshot = managed
                    bindRoutingSpinner(policies, tunnelName)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                workProfileInstallSessionManager.sessionState(packageName).collect { state ->
                    workProfileInstallSessionState = state
                    renderWorkProfileState()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            workProfileInstallSessionManager.reconcileIfPending(packageName)
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
        renderWorkProfileState()
    }

    private fun renderWorkProfileState() {
        val binding = binding ?: return
        val entry = workProfileCatalogService.buildCatalog(listOf(packageName)).firstOrNull() ?: return
        when (val sessionState = workProfileInstallSessionState) {
            WorkProfileInstallSessionState.Idle -> {
                binding.jailDetailWorkProfileState.text = when (entry.action) {
                    WorkProfileAppAction.OPEN_IN_WORK -> getString(R.string.jail_detail_work_profile_state_installed)
                    WorkProfileAppAction.INSTALL_AUTOMATICALLY -> getString(R.string.jail_detail_work_profile_state_auto)
                    WorkProfileAppAction.OPEN_STORE_MANUALLY -> getString(R.string.jail_detail_work_profile_state_manual)
                    WorkProfileAppAction.NONE -> getString(R.string.jail_detail_work_profile_state_unavailable)
                }
                val reasonText = environmentReasonText(entry.environmentReason)
                binding.jailDetailWorkProfileReason.text = reasonText
                binding.jailDetailWorkProfileReason.visibility = if (reasonText.isBlank()) View.GONE else View.VISIBLE
                binding.jailDetailWorkProfileAction.text = when (entry.action) {
                    WorkProfileAppAction.OPEN_IN_WORK -> getString(R.string.jail_detail_work_profile_action_installed)
                    WorkProfileAppAction.INSTALL_AUTOMATICALLY -> getString(R.string.jail_detail_work_profile_action_install)
                    WorkProfileAppAction.OPEN_STORE_MANUALLY -> getString(R.string.jail_detail_work_profile_action_store)
                    WorkProfileAppAction.NONE -> getString(R.string.jail_detail_work_profile_action_unavailable)
                }
                binding.jailDetailWorkProfileAction.isEnabled =
                    entry.action == WorkProfileAppAction.INSTALL_AUTOMATICALLY ||
                        entry.action == WorkProfileAppAction.OPEN_STORE_MANUALLY
                binding.jailDetailWorkProfileAction.tag = entry.action
            }
            is WorkProfileInstallSessionState.InstallAttempted,
            is WorkProfileInstallSessionState.Verifying,
            -> {
                binding.jailDetailWorkProfileState.text = getString(R.string.jail_detail_work_profile_state_verifying)
                binding.jailDetailWorkProfileReason.text = getString(R.string.jail_detail_work_profile_reason_verifying)
                binding.jailDetailWorkProfileReason.visibility = View.VISIBLE
                binding.jailDetailWorkProfileAction.text = getString(R.string.jail_detail_work_profile_action_checking)
                binding.jailDetailWorkProfileAction.isEnabled = false
                binding.jailDetailWorkProfileAction.tag = WorkProfileAppAction.NONE
            }
            is WorkProfileInstallSessionState.WaitingForUserAction -> {
                binding.jailDetailWorkProfileState.text = getString(R.string.jail_detail_work_profile_state_waiting_user)
                binding.jailDetailWorkProfileReason.text = getString(R.string.jail_detail_work_profile_reason_still_not_visible)
                binding.jailDetailWorkProfileReason.visibility = View.VISIBLE
                binding.jailDetailWorkProfileAction.text = getString(R.string.jail_detail_work_profile_action_check_again)
                binding.jailDetailWorkProfileAction.isEnabled = true
                binding.jailDetailWorkProfileAction.tag = WORK_PROFILE_ACTION_CHECK_AGAIN
            }
            is WorkProfileInstallSessionState.Installed -> {
                binding.jailDetailWorkProfileState.text = getString(R.string.jail_detail_work_profile_state_installed_detected)
                binding.jailDetailWorkProfileReason.text = getString(R.string.jail_detail_work_profile_reason_detected)
                binding.jailDetailWorkProfileReason.visibility = View.VISIBLE
                binding.jailDetailWorkProfileAction.text = getString(R.string.jail_detail_work_profile_action_installed)
                binding.jailDetailWorkProfileAction.isEnabled = false
                binding.jailDetailWorkProfileAction.tag = WorkProfileAppAction.NONE
            }
            is WorkProfileInstallSessionState.Failed -> {
                binding.jailDetailWorkProfileState.text = getString(R.string.jail_detail_work_profile_state_failed)
                binding.jailDetailWorkProfileReason.text =
                    sessionState.message ?: getString(R.string.jail_detail_work_profile_reason_failed)
                binding.jailDetailWorkProfileReason.visibility = View.VISIBLE
                binding.jailDetailWorkProfileAction.text = when (entry.action) {
                    WorkProfileAppAction.INSTALL_AUTOMATICALLY -> getString(R.string.jail_detail_work_profile_action_retry_auto)
                    WorkProfileAppAction.OPEN_STORE_MANUALLY -> getString(R.string.jail_detail_work_profile_action_retry_manual)
                    else -> getString(R.string.jail_detail_work_profile_action_unavailable)
                }
                binding.jailDetailWorkProfileAction.isEnabled =
                    entry.action == WorkProfileAppAction.INSTALL_AUTOMATICALLY ||
                        entry.action == WorkProfileAppAction.OPEN_STORE_MANUALLY
                binding.jailDetailWorkProfileAction.tag = entry.action
            }
        }
    }

    private fun environmentReasonText(reason: WorkProfileInstallEnvironmentReason): String = when (reason) {
        WorkProfileInstallEnvironmentReason.ALREADY_INSTALLED_IN_WORK ->
            getString(R.string.jail_detail_work_profile_reason_installed)
        WorkProfileInstallEnvironmentReason.PROFILE_OWNER_CONFIRMED ->
            getString(R.string.jail_detail_work_profile_reason_owner_confirmed)
        WorkProfileInstallEnvironmentReason.MANAGED_PROFILE_NOT_OURS ->
            getString(R.string.jail_detail_work_profile_reason_not_ours)
        WorkProfileInstallEnvironmentReason.SECONDARY_PROFILE_PRESENT_ONLY ->
            getString(R.string.jail_detail_work_profile_reason_secondary_only)
        WorkProfileInstallEnvironmentReason.OWNERSHIP_UNCERTAIN ->
            getString(R.string.jail_detail_work_profile_reason_ownership_uncertain)
        WorkProfileInstallEnvironmentReason.NO_MANAGED_PROFILE ->
            getString(R.string.jail_detail_work_profile_reason_no_profile)
        WorkProfileInstallEnvironmentReason.API_LEVEL_UNSUPPORTED ->
            getString(R.string.jail_detail_work_profile_reason_api_unsupported)
        WorkProfileInstallEnvironmentReason.PARENT_PACKAGE_MISSING ->
            getString(R.string.jail_detail_work_profile_reason_parent_missing)
        WorkProfileInstallEnvironmentReason.MANUAL_FALLBACK_ONLY ->
            getString(R.string.jail_detail_work_profile_reason_manual_only)
        WorkProfileInstallEnvironmentReason.NO_FALLBACK_AVAILABLE ->
            getString(R.string.jail_detail_work_profile_reason_no_path)
        WorkProfileInstallEnvironmentReason.UNKNOWN ->
            getString(R.string.jail_detail_work_profile_reason_unknown)
    }

    private fun onWorkProfileActionClicked() {
        val tag = binding?.jailDetailWorkProfileAction?.tag ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            when (tag) {
                WORK_PROFILE_ACTION_CHECK_AGAIN -> workProfileInstallSessionManager.reconcileIfPending(packageName)
                WorkProfileAppAction.INSTALL_AUTOMATICALLY -> workProfileInstallSessionManager.installAutomatically(packageName)
                WorkProfileAppAction.OPEN_STORE_MANUALLY -> workProfileInstallSessionManager.launchManualStore(packageName)
                else -> Unit
            }
        }
    }

    private fun triggerRefresh() {
        auditRepository.refreshOne(
            context = requireContext().applicationContext,
            packageName = packageName,
            scope = Application.getCoroutineScope(),
        )
    }

    private fun JailTunnelMode.labelRes(): Int = when (this) {
        JailTunnelMode.DEFAULT -> R.string.jail_mode_default
        JailTunnelMode.JAIL_ROUTE_THROUGH_TUNNEL -> R.string.jail_mode_route
        JailTunnelMode.JAIL_EXCLUDE_FROM_TUNNEL -> R.string.jail_mode_exclude
        JailTunnelMode.JAIL_STRICT_PROFILE -> R.string.jail_mode_strict
        JailTunnelMode.DISABLED -> R.string.jail_mode_disabled
    }

    private fun bindRoutingSpinner(policies: Map<String, JailTunnelMode>, tunnelName: String?) {
        val binding = binding ?: return
        val mode = policies[packageName] ?: JailTunnelMode.DEFAULT
        val idx = routingModes.indexOf(mode).takeIf { it >= 0 } ?: 0
        binding.jailDetailRoutingSpinner.setSelection(idx)
        binding.jailDetailRoutingApply.isEnabled = !tunnelName.isNullOrBlank()
    }

    private fun applyRoutingSelection() {
        val tunnelName = jailTunnelNameSnapshot
        if (tunnelName.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.jail_detail_routing_no_tunnel, Toast.LENGTH_SHORT).show()
            return
        }
        val binding = binding ?: return
        val selectedMode = routingModes[binding.jailDetailRoutingSpinner.selectedItemPosition.coerceIn(0, routingModes.lastIndex)]

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.jail_merge_confirm_title)
            .setMessage(getString(R.string.jail_detail_routing_merge_tunnel_message, tunnelName))
            .setNegativeButton(R.string.jail_merge_cancel, null)
            .setPositiveButton(R.string.jail_merge_continue) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    performRoutingApply(tunnelName, selectedMode)
                }
            }
            .show()
    }

    private suspend fun performRoutingApply(tunnelName: String, selectedMode: JailTunnelMode) {
        val policies = routingPoliciesSnapshot
        val previouslyManaged = jailManagedPackagesSnapshot
        val next = policies.toMutableMap()
        when (selectedMode) {
            JailTunnelMode.DEFAULT -> next.remove(packageName)
            else -> next[packageName] = selectedMode
        }

        when (val result = perAppVpnManager.applyJailRouting(tunnelName, next, previouslyManaged)) {
            PerAppVpnManager.ApplyResult.Success -> {
                JailStore.setRoutingPolicies(next)
                Toast.makeText(requireContext(), R.string.jail_detail_routing_applied, Toast.LENGTH_SHORT).show()
            }
            is PerAppVpnManager.ApplyResult.Conflict ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.jail_detail_routing_title)
                    .setMessage(getString(R.string.jail_detail_routing_conflict, result.message))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            PerAppVpnManager.ApplyResult.TunnelNotFound ->
                Toast.makeText(requireContext(), R.string.jail_detail_routing_no_tunnel, Toast.LENGTH_SHORT).show()
        }
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
        private const val WORK_PROFILE_ACTION_CHECK_AGAIN = "check_again"

        fun newInstance(packageName: String): JailAppDetailFragment = JailAppDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_PACKAGE, packageName) }
        }
    }
}
