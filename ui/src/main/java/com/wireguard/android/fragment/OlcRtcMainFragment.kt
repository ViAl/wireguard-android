/*
 * Copyright © 2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.olcrtc.OlcRtcManager
import com.wireguard.android.olcrtc.OlcRtcManagerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fragment displaying OlcRTC tunnel connection status.
 *
 * Shows real-time connection state, including reconnect attempts
 * and network instability notifications.
 */
class OlcRtcMainFragment : Fragment() {

    private var statusText: TextView? = null
    private var reconnectInfoText: TextView? = null
    private var statusCollectorJob: Job? = null
    private var olcRtcManager: OlcRtcManager? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_olcrtc_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusText = view.findViewById(R.id.olcrtc_status_text)
        reconnectInfoText = view.findViewById(R.id.olcrtc_reconnect_info)

        // Start observing manager state
        observeManagerState()
    }

    override fun onDestroyView() {
        statusCollectorJob?.cancel()
        statusCollectorJob = null
        statusText = null
        reconnectInfoText = null
        super.onDestroyView()
    }

    /**
     * Attach an [OlcRtcManager] instance to observe.
     */
    fun setManager(manager: OlcRtcManager) {
        olcRtcManager = manager
        if (view != null) {
            observeManagerState()
        }
    }

    private fun observeManagerState() {
        val manager = olcRtcManager ?: return
        statusCollectorJob?.cancel()

        statusCollectorJob = viewLifecycleOwner.lifecycleScope.launch {
            manager.state.collectLatest { state ->
                updateStatusDisplay(state, manager)
            }
        }
    }

    private fun updateStatusDisplay(state: OlcRtcManagerState, manager: OlcRtcManager) {
        val statusView = statusText ?: return
        val reconnectView = reconnectInfoText

        when (state) {
            OlcRtcManagerState.DISCONNECTED -> {
                statusView.text = getString(R.string.olcrtc_status_disconnected)
                statusView.setTextColor(
                    resources.getColor(android.R.color.darker_gray, null)
                )
                reconnectView?.visibility = View.GONE
            }
            OlcRtcManagerState.CONNECTING -> {
                statusView.text = getString(R.string.olcrtc_status_connecting)
                statusView.setTextColor(
                    resources.getColor(android.R.color.holo_orange_dark, null)
                )
                reconnectView?.visibility = View.GONE
            }
            OlcRtcManagerState.CONNECTED -> {
                statusView.text = getString(R.string.olcrtc_status_connected)
                statusView.setTextColor(
                    resources.getColor(android.R.color.holo_green_dark, null)
                )
                reconnectView?.visibility = View.GONE
            }
            OlcRtcManagerState.RECONNECTING -> {
                statusView.text = getString(R.string.olcrtc_status_reconnecting)
                statusView.setTextColor(
                    resources.getColor(android.R.color.holo_orange_light, null)
                )
                reconnectView?.visibility = View.VISIBLE
                reconnectView?.text = getString(
                    R.string.olcrtc_reconnect_attempt,
                    manager.getReconnectAttempts()
                )
            }
            OlcRtcManagerState.NETWORK_UNSTABLE -> {
                statusView.text = getString(R.string.olcrtc_status_network_unstable)
                statusView.setTextColor(
                    resources.getColor(android.R.color.holo_red_light, null)
                )
                reconnectView?.visibility = View.VISIBLE
                reconnectView?.text = getString(
                    R.string.olcrtc_reconnect_attempt,
                    manager.getReconnectAttempts()
                )
            }
            OlcRtcManagerState.ERROR -> {
                statusView.text = getString(R.string.olcrtc_status_error)
                statusView.setTextColor(
                    resources.getColor(android.R.color.holo_red_dark, null)
                )
                reconnectView?.visibility = View.GONE
            }
        }
    }
}
