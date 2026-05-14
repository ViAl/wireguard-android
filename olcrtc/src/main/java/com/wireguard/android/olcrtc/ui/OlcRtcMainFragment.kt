package com.wireguard.android.olcrtc.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit

class OlcRtcMainFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = FrameLayout(requireContext())
        root.id = View.generateViewId()
        childFragmentManager.commit {
            replace(root.id, OlcRtcListFragment())
        }
        return root
    }
}
