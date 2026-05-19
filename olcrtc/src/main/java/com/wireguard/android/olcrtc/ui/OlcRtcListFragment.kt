package com.wireguard.android.olcrtc.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout.VERTICAL
import android.widget.LinearLayout.HORIZONTAL
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.olcrtc.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class OlcRtcListFragment : Fragment() {

    private var listView: LinearLayout? = null
    private var emptyView: TextView? = null
    private var configStore: OlcRtcConfigStore? = null
    private var pendingConnectConfig: OlcRtcConfig? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            pendingConnectConfig?.let { OlcRtcManager.connect(requireContext(), it) }
        }
        pendingConnectConfig = null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(
            com.wireguard.android.olcrtc.R.layout.olcrtc_list_fragment,
            container, false
        )
        listView = view.findViewById(com.wireguard.android.olcrtc.R.id.olcrtc_list)
        emptyView = view.findViewById(com.wireguard.android.olcrtc.R.id.olcrtc_empty)
        configStore = OlcRtcConfigStore(requireContext())

        lifecycleScope.launch {
            OlcRtcManager.connectionState.collectLatest { refreshList() }
        }

        view.findViewById<Button>(com.wireguard.android.olcrtc.R.id.olcrtc_add_button)?.setOnClickListener {
            showAddDialog()
        }

        refreshList()
        return view
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val configs = configStore?.loadAll() ?: emptyList()
        val container = listView ?: return
        container.removeAllViews()

        if (configs.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            return
        }
        emptyView?.visibility = View.GONE

        configs.forEach { config -> container.addView(createConfigRow(config)) }
    }

    private fun createConfigRow(config: OlcRtcConfig): View {
        val ctx = requireContext()
        val isActive = OlcRtcManager.getConfig()?.name == config.name
        val state = OlcRtcManager.connectionState.value

        val row = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(16, 12, 16, 12)
        }

        // Name + status
        val topRow = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        topRow.addView(TextView(ctx).apply {
            text = config.name
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        topRow.addView(TextView(ctx).apply {
            textSize = 12f
            text = when {
                isActive && state == OlcRtcConnectionState.CONNECTED -> "● Connected"
                isActive && state == OlcRtcConnectionState.LOCAL_READY -> "◌ Connected locally, verifying remote..."
                isActive && state == OlcRtcConnectionState.VERIFYING_REMOTE -> "◌ Verifying remote connection..."
                isActive && state == OlcRtcConnectionState.CONNECTING -> "◌ Connecting..."
                isActive && state == OlcRtcConnectionState.DISCONNECTING -> "◌ Disconnecting..."
                isActive && state == OlcRtcConnectionState.ERROR -> {
                    val reason = OlcRtcManager.errorReason.value
                    if (reason != null) "● Error: $reason" else "● Error"
                }
                else -> "○ Disconnected"
            }
            setTextColor(when {
                isActive && state == OlcRtcConnectionState.CONNECTED -> 0xFF4CAF50.toInt()
                isActive && (state == OlcRtcConnectionState.LOCAL_READY || state == OlcRtcConnectionState.VERIFYING_REMOTE) -> 0xFF2196F3.toInt()
                isActive && (state == OlcRtcConnectionState.CONNECTING || state == OlcRtcConnectionState.DISCONNECTING) -> 0xFFFF9800.toInt()
                isActive && state == OlcRtcConnectionState.ERROR -> 0xFFF44336.toInt()
                else -> 0xFF9E9E9E.toInt()
            })
        })
        row.addView(topRow)

        // Subtitle: carrier / transport
        row.addView(TextView(ctx).apply {
            text = "${config.carrier} / ${config.transport}"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
        })

        // Room + client summary
        row.addView(TextView(ctx).apply {
            text = "Room: ${config.roomId} · Client: ${config.clientId}"
            textSize = 11f
            setTextColor(0xFF888888.toInt())
        })

        // Buttons
        val btnRow = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        btnRow.addView(Button(ctx).apply {
            when {
                isActive && state == OlcRtcConnectionState.CONNECTING -> {
                    text = "Connecting..."
                    isEnabled = false
                }
                isActive && state == OlcRtcConnectionState.LOCAL_READY -> {
                    text = "Connecting..."
                    isEnabled = false
                }
                isActive && state == OlcRtcConnectionState.VERIFYING_REMOTE -> {
                    text = "Verifying..."
                    isEnabled = false
                }
                isActive && state == OlcRtcConnectionState.DISCONNECTING -> {
                    text = "Disconnecting..."
                    isEnabled = false
                }
                isActive && state == OlcRtcConnectionState.CONNECTED -> {
                    text = "Disconnect"
                    isEnabled = true
                }
                isActive && state == OlcRtcConnectionState.ERROR -> {
                    text = "Connect"
                    isEnabled = true
                }
                else -> {
                    text = "Connect"
                    isEnabled = true
                }
            }
            setOnClickListener {
                if (isActive && state == OlcRtcConnectionState.CONNECTED) {
                    OlcRtcManager.disconnect()
                } else {
                    // Warn about WireGuard
                    Toast.makeText(ctx, "OlcRTC will stop active WireGuard tunnel", Toast.LENGTH_LONG).show()
                    val intent = android.net.VpnService.prepare(requireContext())
                    if (intent != null) {
                        pendingConnectConfig = config
                        vpnPermissionLauncher.launch(intent)
                    } else {
                        OlcRtcManager.connect(requireContext(), config)
                    }
                }
                refreshList()
            }
        })
        btnRow.addView(Button(ctx).apply {
            text = "Delete"
            setOnClickListener {
                OlcRtcManager.disconnect()
                configStore?.delete(config.name)
                refreshList()
            }
        })
        row.addView(btnRow)

        // Divider
        row.addView(View(ctx).apply {
            setBackgroundColor(0xFFE0E0E0.toInt())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { topMargin = 8 }
        })

        return row
    }

    private fun showAddDialog() {
        val dialog = layoutInflater.inflate(
            com.wireguard.android.olcrtc.R.layout.olcrtc_add_dialog, null
        )
        val errorView = TextView(requireContext()).apply {
            textSize = 12f
            setTextColor(0xFFF44336.toInt())
            visibility = View.GONE
        }
        val summaryView = TextView(requireContext()).apply {
            textSize = 11f
            setTextColor(0xFF666666.toInt())
            visibility = View.GONE
        }

        // Add summary and error to the dialog layout
        val dialogContent = dialog.findViewById<ViewGroup>(android.R.id.content) ?: dialog as? ViewGroup
        // Append after the URI field
        val uriField = dialog.findViewById<EditText>(com.wireguard.android.olcrtc.R.id.dialog_uri)
        val nameField = dialog.findViewById<EditText>(com.wireguard.android.olcrtc.R.id.dialog_name)
        val parent = uriField?.parent as? ViewGroup
        if (parent != null) {
            parent.addView(summaryView)
            parent.addView(errorView)
        }

        val alert = android.app.AlertDialog.Builder(requireContext())
            .setTitle("Add OlcRTC Tunnel")
            .setView(dialog)
            .setPositiveButton("Add", null) // Set later to prevent auto-dismiss
            .setNegativeButton("Cancel", null)
            .show()

        // Override positive button to keep dialog open on validation errors
        alert.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameField?.text.toString()
            val uri = uriField?.text.toString()
            if (uri.isBlank()) {
                errorView.text = "Enter olcrtc:// URI"
                errorView.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val parsed = OlcRtcUriParser.parse(uri)
            if (parsed == null) {
                errorView.text = "Invalid olcrtc:// URI"
                errorView.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val config = OlcRtcConfig(
                name = name.ifBlank { parsed.clientId },
                carrier = parsed.carrier,
                roomId = parsed.roomId,
                clientId = parsed.clientId,
                keyHex = parsed.keyHex,
                transport = parsed.transport
            )
            // Apply payload from URI
            val finalConfig = OlcRtcUriParser.applyPayload(config, parsed)

            // Show summary
            val warnings = mutableListOf<String>()
            if (parsed.transport == "seichannel" || parsed.transport == "videochannel") {
                warnings.add("'${parsed.transport}' transport: not configurable yet (payload ignored)")
            }
            val summary = buildString {
                appendLine("Carrier: ${finalConfig.carrier}")
                appendLine("Transport: ${finalConfig.transport}")
                appendLine("Room: ${finalConfig.roomId}")
                appendLine("Client: ${finalConfig.clientId}")
                if (parsed.payload.isNotEmpty()) {
                    append("Payload: ${parsed.payload}")
                }
                if (warnings.isNotEmpty()) {
                    appendLine()
                    append(warnings.joinToString("\n"))
                }
            }
            summaryView.text = summary
            summaryView.visibility = View.VISIBLE

            val error = OlcRtcConfig.validate(finalConfig)
            if (error != null) {
                errorView.text = error
                errorView.visibility = View.VISIBLE
                return@setOnClickListener
            }
            errorView.visibility = View.GONE

            // Warn about WireGuard tunnel conflict
            Toast.makeText(requireContext(), "OlcRTC will stop active WireGuard tunnel", Toast.LENGTH_LONG).show()

            configStore?.save(finalConfig)
            refreshList()
            alert.dismiss()
        }
    }
}
