package com.wireguard.android.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.databinding.RtcFragmentBinding
import com.wireguard.android.rtc.RtcController
import com.wireguard.android.rtc.RtcState
import com.wireguard.android.rtc.config.OlcRtcTunnelConfig
import com.wireguard.android.rtc.config.OlcRtcUriParser
import kotlinx.coroutines.launch

class RtcFragment : Fragment() {
    private var binding: RtcFragmentBinding? = null
    private lateinit var rtcController: RtcController
    private var parsedConfig: OlcRtcTunnelConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rtcController = RtcController(requireContext().applicationContext)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = RtcFragmentBinding.inflate(inflater, container, false)
        return requireNotNull(binding).root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(binding)

        binding.parseButton.setOnClickListener {
            val raw = binding.rtcUriInput.text?.toString().orEmpty()
            runCatching { OlcRtcUriParser.parse(raw) }
                .onSuccess { config ->
                    parsedConfig = config
                    renderConfig(config)
                    binding.errorText.text = ""
                }
                .onFailure { error ->
                    parsedConfig = null
                    clearPreview()
                    binding.errorText.text = error.message ?: getString(R.string.rtc_invalid_uri)
                }
        }

        binding.startButton.setOnClickListener {
            val config = parsedConfig
            if (config == null) {
                Toast.makeText(requireContext(), R.string.rtc_invalid_uri, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            rtcController.start(config)
        }
        binding.stopButton.setOnClickListener { rtcController.stop() }

        viewLifecycleOwner.lifecycleScope.launch {
            rtcController.state.collect { state -> renderState(state) }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun renderConfig(config: OlcRtcTunnelConfig) {
        val binding = requireNotNull(binding)
        binding.previewDisplayName.text = config.effectiveDisplayName
        binding.previewCarrier.text = config.carrier.uriValue
        binding.previewTransport.text = config.transport.uriValue
        binding.previewRoomId.text = config.roomId
        binding.previewClientId.text = config.clientId
        binding.previewKey.text = config.maskedKey()
    }

    private fun clearPreview() {
        val binding = requireNotNull(binding)
        binding.previewDisplayName.text = "—"
        binding.previewCarrier.text = "—"
        binding.previewTransport.text = "—"
        binding.previewRoomId.text = "—"
        binding.previewClientId.text = "—"
        binding.previewKey.text = "—"
    }

    private fun renderState(state: RtcState) {
        val binding = requireNotNull(binding)
        binding.statusText.text = when (state) {
            RtcState.Stopped -> getString(R.string.rtc_status_stopped)
            RtcState.Starting -> getString(R.string.rtc_status_starting)
            RtcState.Running -> getString(R.string.rtc_status_running)
            is RtcState.Error -> state.message
        }
    }
}
