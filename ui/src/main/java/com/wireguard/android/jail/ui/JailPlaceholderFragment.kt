/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.wireguard.android.databinding.JailPlaceholderFragmentBinding
import com.wireguard.android.jail.model.JailDestination

/**
 * Temporary placeholder shown for Jail destinations whose real implementation lands in a later
 * phase. It reads the intended destination from arguments and logs a clear "not yet implemented"
 * breadcrumb so regressions are easy to spot.
 */
class JailPlaceholderFragment : Fragment() {
    private var binding: JailPlaceholderFragmentBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = JailPlaceholderFragmentBinding.inflate(inflater, container, false)
        return requireNotNull(binding).root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(binding)
        val destination = destination()
        binding.jailPlaceholderTitle.setText(destination.titleRes)
        Log.i(TAG, "Jail destination '${destination.name}' is not yet implemented")
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun destination(): JailDestination {
        val tag = arguments?.getString(ARG_DESTINATION_TAG)
        return JailDestination.fromTag(tag) ?: JailDestination.OVERVIEW
    }

    companion object {
        private const val TAG = "WireGuard/JailPlaceholderFragment"
        private const val ARG_DESTINATION_TAG = "destination_tag"

        fun newInstance(destination: JailDestination): JailPlaceholderFragment =
            JailPlaceholderFragment().apply {
                arguments = Bundle().apply { putString(ARG_DESTINATION_TAG, destination.tag) }
            }
    }
}
