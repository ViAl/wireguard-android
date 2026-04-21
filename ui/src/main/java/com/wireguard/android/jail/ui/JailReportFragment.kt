/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.ui

import android.os.Bundle
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
import com.wireguard.android.databinding.JailReportAppItemBinding
import com.wireguard.android.databinding.JailReportFragmentBinding
import com.wireguard.android.jail.domain.JailAppRepository
import com.wireguard.android.jail.domain.JailAuditRepository
import com.wireguard.android.jail.domain.RiskReportBuilder
import com.wireguard.android.jail.model.AuditSnapshot
import com.wireguard.android.jail.model.JailAppInfo
import com.wireguard.android.jail.model.RiskReport
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Plain-language risk summary for each selected Jail app. Built from [RiskReportBuilder] and
 * rendered through [HumanReadableRiskFormatter].
 */
class JailReportFragment : Fragment() {

    private var binding: JailReportFragmentBinding? = null
    private val reportBuilder = RiskReportBuilder()
    private lateinit var formatter: HumanReadableRiskFormatter
    private lateinit var adapter: ReportAdapter

    private val repository: JailAppRepository
        get() = Application.getJailComponent().appRepository

    private val auditRepository: JailAuditRepository
        get() = Application.getJailComponent().auditRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        formatter = HumanReadableRiskFormatter(resources)
        adapter = ReportAdapter(formatter) { pkg ->
            (parentFragment as? JailFragment.Host)?.openAppDetail(pkg)
        }
        val b = JailReportFragmentBinding.inflate(inflater, container, false)
        binding = b
        b.jailReportList.layoutManager = LinearLayoutManager(requireContext())
        b.jailReportList.adapter = adapter
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(repository.apps, auditRepository.snapshots) { apps, snapshots ->
                    apps.filter { it.isSelectedForJail }.map { app ->
                        val snap = snapshots[app.packageName]
                        val report = reportBuilder.build(app, snap)
                        ReportRow(app, snap, report)
                    }
                }.collect { rows ->
                    val binding = binding ?: return@collect
                    val empty = rows.isEmpty()
                    binding.jailReportContent.visibility = if (empty) View.GONE else View.VISIBLE
                    binding.jailReportEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                    adapter.submitList(rows)
                }
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private data class ReportRow(
        val app: JailAppInfo,
        val snapshot: AuditSnapshot?,
        val report: RiskReport,
    )

    private class ReportAdapter(
        private val formatter: HumanReadableRiskFormatter,
        private val onOpenDetail: (String) -> Unit,
    ) : ListAdapter<ReportRow, ReportViewHolder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReportViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return ReportViewHolder(JailReportAppItemBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: ReportViewHolder, position: Int) {
            holder.bind(getItem(position), formatter, onOpenDetail)
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<ReportRow>() {
                override fun areItemsTheSame(oldItem: ReportRow, newItem: ReportRow) =
                    oldItem.app.packageName == newItem.app.packageName

                override fun areContentsTheSame(oldItem: ReportRow, newItem: ReportRow) =
                    oldItem.app == newItem.app &&
                        oldItem.snapshot?.generatedAtMillis == newItem.snapshot?.generatedAtMillis &&
                        oldItem.report.overallLevel == newItem.report.overallLevel &&
                        oldItem.report.estimates == newItem.report.estimates
            }
        }
    }

    private class ReportViewHolder(private val binding: JailReportAppItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: ReportRow, formatter: HumanReadableRiskFormatter, onOpenDetail: (String) -> Unit) {
            val app = row.app
            binding.jailReportIcon.setImageDrawable(app.icon)
            binding.jailReportLabel.text = app.label
            binding.jailReportPackage.text = app.packageName
            binding.jailReportHeadline.text = formatter.headline(row.report)
            binding.jailReportBody.text = formatter.fullReportBody(row.report)
            binding.jailReportOpenDetail.setOnClickListener { onOpenDetail(app.packageName) }
        }
    }
}
