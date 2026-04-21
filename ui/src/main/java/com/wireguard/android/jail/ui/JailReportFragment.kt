/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.wireguard.android.Application
import com.wireguard.android.R
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
 * Plain-language risk summary for selected Jail apps: pick an app from the spinner, read the
 * five section groups, copy the full report to the clipboard.
 */
class JailReportFragment : Fragment() {

    private var binding: JailReportFragmentBinding? = null
    private val reportBuilder = RiskReportBuilder()
    private lateinit var formatter: HumanReadableRiskFormatter

    private val repository: JailAppRepository
        get() = Application.getJailComponent().appRepository

    private val auditRepository: JailAuditRepository
        get() = Application.getJailComponent().auditRepository

    private var rows: List<ReportRow> = emptyList()
    private var suppressSpinnerCallback = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        formatter = HumanReadableRiskFormatter(resources)
        val b = JailReportFragmentBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(binding)

        binding.jailReportCopy.setOnClickListener {
            val idx = binding.jailReportAppSpinner.selectedItemPosition
            if (idx < 0 || idx >= rows.size) return@setOnClickListener
            val report = rows[idx].report
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.jail_report_title), formatter.clipboardText(report)))
            Snackbar.make(binding.root, R.string.jail_report_copied, Snackbar.LENGTH_SHORT).show()
        }

        binding.jailReportAppSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinnerCallback) return
                renderSelected(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(repository.apps, auditRepository.snapshots) { apps, snapshots ->
                    apps.filter { it.isSelectedForJail }.map { app ->
                        val snap = snapshots[app.packageName]
                        ReportRow(app, snap, reportBuilder.build(app, snap))
                    }
                }.collect { newRows ->
                    val b = binding ?: return@collect
                    rows = newRows
                    val empty = newRows.isEmpty()
                    b.jailReportContent.visibility = if (empty) View.GONE else View.VISIBLE
                    b.jailReportEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                    if (empty) return@collect

                    val labels = newRows.map { it.app.label }
                    val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
                    suppressSpinnerCallback = true
                    try {
                        b.jailReportAppSpinner.adapter = adapter
                        val sel = newRows.lastIndex.coerceAtLeast(0)
                        b.jailReportAppSpinner.setSelection(sel)
                        renderSelected(sel)
                    } finally {
                        suppressSpinnerCallback = false
                    }
                }
            }
        }
    }

    private fun renderSelected(position: Int) {
        val binding = binding ?: return
        if (position !in rows.indices) return
        val row = rows[position]
        binding.jailReportHeadline.text = formatter.headline(row.report)
        binding.jailReportBody.text = formatter.fullReportBody(row.report)
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
}
