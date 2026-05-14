package com.wireguard.android.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wireguard.android.R
import com.wireguard.android.databinding.RtcFragmentBinding
import com.wireguard.android.rtc.RtcController
import com.wireguard.android.rtc.RtcLogBuffer
import com.wireguard.android.rtc.RtcState
import com.wireguard.android.rtc.config.OlcRtcTunnelConfig
import com.wireguard.android.rtc.config.OlcRtcUriParser
import com.wireguard.android.rtc.native.OlcRtcNativeEngine
import kotlinx.coroutines.launch

class RtcFragment : Fragment() {
    private var binding: RtcFragmentBinding? = null
    private lateinit var rtcController: RtcController
    private var parsedConfig: OlcRtcTunnelConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val logBuffer = RtcLogBuffer()
        rtcController = RtcController(
            engine = OlcRtcNativeEngine(logSink = { line: String -> logBuffer.add(line) }),
            logBuffer = logBuffer,
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = RtcFragmentBinding.inflate(inflater, container, false)
        return requireNotNull(binding).root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = requireNotNull(binding)

        b.parseButton.setOnClickListener {
            val raw = b.rtcUriInput.text?.toString().orEmpty()
            runCatching { OlcRtcUriParser.parse(raw) }
                .onSuccess {
                    parsedConfig = it
                    renderConfig(it)
                    b.errorText.text = ""
                    rtcController.logBuffer.add("Parsed successfully")
                }
                .onFailure { error ->
                    parsedConfig = null
                    clearPreview()
                    b.errorText.text = error.message ?: getString(R.string.rtc_invalid_uri)
                }
        }
        b.startButton.setOnClickListener { parsedConfig?.let { rtcController.start(it) } ?: run { b.errorText.text = getString(R.string.rtc_invalid_uri) } }
        b.stopButton.setOnClickListener { rtcController.stop() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { rtcController.state.collect { renderState(it) } }
                launch { rtcController.logBuffer.entries.collect { lines -> b.logText.text = lines.joinToString("\n") } }
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        rtcController.close()
        super.onDestroy()
    }

    private fun renderConfig(config: OlcRtcTunnelConfig) {
        val b = requireNotNull(binding)
        b.previewDisplayName.text = config.effectiveDisplayName
        b.previewCarrier.text = config.carrier.uriValue
        b.previewTransport.text = config.transport.uriValue
        b.previewRoomId.text = config.roomId
        b.previewClientId.text = config.clientId
        b.previewKey.text = config.maskedKey()
    }

    private fun clearPreview() {
        val b = requireNotNull(binding)
        b.previewDisplayName.text = "—"
        b.previewCarrier.text = "—"
        b.previewTransport.text = "—"
        b.previewRoomId.text = "—"
        b.previewClientId.text = "—"
        b.previewKey.text = "—"
    }

    private fun renderState(state: RtcState) {
        val b = requireNotNull(binding)
        when (state) {
            RtcState.Stopped -> {
                b.statusText.text = getString(R.string.rtc_status_stopped)
                b.startButton.isEnabled = parsedConfig != null
                b.stopButton.isEnabled = false
            }
            RtcState.Starting -> {
                b.statusText.text = getString(R.string.rtc_status_starting)
                b.startButton.isEnabled = false
                b.stopButton.isEnabled = false
            }
            is RtcState.Running -> {
                b.statusText.text = getString(R.string.rtc_status_running_details, state.displayName, state.socksPort)
                b.startButton.isEnabled = false
                b.stopButton.isEnabled = true
            }
            RtcState.Stopping -> {
                b.statusText.text = getString(R.string.rtc_status_stopping)
                b.startButton.isEnabled = false
                b.stopButton.isEnabled = false
            }
            is RtcState.Error -> {
                b.statusText.text = getString(R.string.rtc_status_error, state.message)
                b.startButton.isEnabled = parsedConfig != null
                b.stopButton.isEnabled = false
            }
        }
    }
}
