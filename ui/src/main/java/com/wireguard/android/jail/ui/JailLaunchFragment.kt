/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.ui

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
import com.wireguard.android.databinding.JailLaunchFragmentBinding
import com.wireguard.android.jail.domain.JailAppRepository
import com.wireguard.android.jail.domain.SterileLaunchManager
import com.wireguard.android.jail.model.CheckStatus
import com.wireguard.android.jail.model.SterileLaunchPreset
import com.wireguard.android.jail.model.SterileLaunchResult
import com.wireguard.android.jail.model.JailAppInfo
import com.wireguard.android.jail.storage.JailStore
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Sterile launch: pick Jail tunnel name, pick a jailed app, review checklist, launch with explicit
 * confirmation (not a sandbox).
 */
class JailLaunchFragment : Fragment() {

    private var binding: JailLaunchFragmentBinding? = null

    private val repository: JailAppRepository
        get() = Application.getJailComponent().appRepository

    private val sterileLaunch: SterileLaunchManager
        get() = Application.getJailComponent().sterileLaunchManager

    private var tunnelNames: List<String> = emptyList()
    private var jailedApps: List<JailAppInfo> = emptyList()
    private var launchPresetsSnapshot: Map<String, SterileLaunchPreset> = emptyMap()
    private var selectedPkg: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = JailLaunchFragmentBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(binding)

        binding.jailLaunchTunnelSave.setOnClickListener {
            val idx = binding.jailLaunchTunnelSpinner.selectedItemPosition
            lifecycleScope.launch {
                if (idx <= 0) {
                    JailStore.setJailTunnelName(null)
                } else {
                    val name = tunnelNames.getOrNull(idx - 1) ?: return@launch
                    JailStore.setJailTunnelName(name)
                }
                Snackbar.make(binding.root, R.string.saved, Snackbar.LENGTH_SHORT).show()
            }
        }

        binding.jailLaunchSavePreset.setOnClickListener {
            val pkg = selectedPkg ?: return@setOnClickListener
            val preset = SterileLaunchPreset.defaultFor(pkg)
            lifecycleScope.launch {
                JailStore.updateLaunchPreset(preset)
                Snackbar.make(binding.root, R.string.jail_launch_preset_restored, Snackbar.LENGTH_SHORT).show()
            }
        }

        binding.jailLaunchFixFirst.setOnClickListener {
            (parentFragment as? JailFragmentHost)?.navigateTo(com.wireguard.android.jail.model.JailDestination.APPS)
        }

        binding.jailLaunchGoApps.setOnClickListener {
            (parentFragment as? JailFragmentHost)?.navigateTo(com.wireguard.android.jail.model.JailDestination.APPS)
        }

        binding.jailLaunchLaunch.setOnClickListener {
            val pkg = selectedPkg ?: return@setOnClickListener
            val preset = launchPresetsSnapshot[pkg] ?: SterileLaunchPreset.defaultFor(pkg)
            when (val r = sterileLaunch.launch(pkg, preset)) {
                is SterileLaunchResult.Launched -> Unit
                is SterileLaunchResult.Failed ->
                    Snackbar.make(binding.root, r.message, Snackbar.LENGTH_LONG).show()
                is SterileLaunchResult.NeedsUserAction ->
                    Snackbar.make(binding.root, r.message, Snackbar.LENGTH_LONG).show()
            }
        }

        binding.jailLaunchAppSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedPkg = jailedApps.getOrNull(position)?.packageName
                refreshChecklist()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedPkg = null
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    repository.apps,
                    JailStore.jailTunnelName,
                    JailStore.launchPresets,
                ) { apps, tunnelName, presets -> Triple(apps, tunnelName, presets) }
                    .collect { (apps, tunnelName, presets) ->
                        lifecycleScope.launch {
                            val binding = binding ?: return@launch
                            val previouslySelectedPkg = selectedPkg
                            launchPresetsSnapshot = presets

                            val tunnels = Application.getTunnelManager().getTunnels()
                            tunnelNames = tunnels.map { it.name }

                            val tunnelAdapter = ArrayAdapter(
                                requireContext(),
                                android.R.layout.simple_spinner_dropdown_item,
                                listOf(getString(R.string.jail_launch_tunnel_none)) + tunnelNames,
                            )
                            binding.jailLaunchTunnelSpinner.adapter = tunnelAdapter
                            val sel = when {
                                tunnelName.isNullOrBlank() -> 0
                                else -> {
                                    val idx = tunnelNames.indexOf(tunnelName)
                                    if (idx >= 0) idx + 1 else 0
                                }
                            }
                            binding.jailLaunchTunnelSpinner.setSelection(sel)

                            jailedApps = apps.filter { it.isSelectedForJail }
                            val labels = jailedApps.map { it.label }
                            binding.jailLaunchAppSpinner.adapter = ArrayAdapter(
                                requireContext(),
                                android.R.layout.simple_spinner_dropdown_item,
                                labels,
                            )
                            if (jailedApps.isEmpty()) {
                                selectedPkg = null
                            } else {
                                val position = previouslySelectedPkg?.let { pkg ->
                                    jailedApps.indexOfFirst { it.packageName == pkg }.takeIf { it >= 0 }
                                } ?: 0
                                binding.jailLaunchAppSpinner.setSelection(position)
                                selectedPkg = jailedApps[position].packageName
                            }
                            refreshChecklist()
                        }
                    }
            }
        }
    }

    private fun refreshChecklist() {
        val binding = binding ?: return
        val pkg = selectedPkg ?: run {
            binding.jailLaunchChecklistText.text = ""
            return
        }
        val preset = launchPresetsSnapshot[pkg] ?: SterileLaunchPreset.defaultFor(pkg)
        val selected = jailedApps.any { it.packageName == pkg && it.isSelectedForJail }
        lifecycleScope.launch {
            val checklist = sterileLaunch.buildChecklist(pkg, preset, selected)
            val text = checklist.items.joinToString("\n\n") { item ->
                val mark = when (item.status) {
                    CheckStatus.OK -> "✓"
                    CheckStatus.WARNING -> "!"
                    CheckStatus.BLOCKED -> "✕"
                }
                "$mark ${item.title}"
            }
            binding.jailLaunchChecklistText.text = text.ifBlank { getString(R.string.jail_detail_no_signals) }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
