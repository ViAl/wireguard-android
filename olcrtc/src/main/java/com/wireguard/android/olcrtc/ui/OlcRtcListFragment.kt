package com.wireguard.android.olcrtc.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
                isActive && state == OlcRtcConnectionState.CONNECTING -> "◌ Connecting..."
                isActive && state == OlcRtcConnectionState.ERROR -> "● Error"
                else -> "○ Disconnected"
            }
            setTextColor(when {
                isActive && state == OlcRtcConnectionState.CONNECTED -> 0xFF4CAF50.toInt()
                isActive && state == OlcRtcConnectionState.CONNECTING -> 0xFFFF9800.toInt()
                isActive && state == OlcRtcConnectionState.ERROR -> 0xFFF44336.toInt()
                else -> 0xFF9E9E9E.toInt()
            })
        })
        row.addView(topRow)

        // Subtitle
        row.addView(TextView(ctx).apply {
            text = "${config.carrier} / ${config.transport}"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
        })

        // Buttons
        val btnRow = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        btnRow.addView(Button(ctx).apply {
            text = if (isActive && state == OlcRtcConnectionState.CONNECTED) "Disconnect" else "Connect"
            setOnClickListener {
                if (isActive && state == OlcRtcConnectionState.CONNECTED) OlcRtcManager.disconnect()
                else OlcRtcManager.connect(requireContext(), config)
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
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Add OlcRTC Tunnel")
            .setView(dialog)
            .setPositiveButton("Add") { _, _ ->
                val name = dialog.findViewById<EditText>(com.wireguard.android.olcrtc.R.id.dialog_name)?.text.toString()
                val uri = dialog.findViewById<EditText>(com.wireguard.android.olcrtc.R.id.dialog_uri)?.text.toString()
                if (uri.isBlank()) {
                    Toast.makeText(requireContext(), "Enter olcrtc:// URI", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val parsed = OlcRtcUriParser.parse(uri)
                if (parsed == null) {
                    Toast.makeText(requireContext(), "Invalid olcrtc:// URI", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val config = OlcRtcConfig(
                    name = name.ifBlank { parsed.clientId },
                    carrier = parsed.carrier,
                    roomId = parsed.roomId,
                    clientId = parsed.clientId,
                    keyHex = parsed.keyHex,
                    transport = parsed.transport
                )
                val error = OlcRtcConfig.validate(config)
                if (error != null) {
                    Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                } else {
                    configStore?.save(config)
                    refreshList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
