package com.wireguard.android.olcrtc.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class OlcRtcMainFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = View(requireContext())
        view.id = android.R.id.content
        childFragmentManager.beginTransaction()
            .replace(android.R.id.content, OlcRtcListFragment())
            .commit()
        return view
    }
}
