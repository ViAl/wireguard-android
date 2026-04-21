/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wireguard.android.databinding.JailOverviewCardBinding
import com.wireguard.android.databinding.JailOverviewFragmentBinding
import com.wireguard.android.jail.model.JailDestination
import com.wireguard.android.jail.viewmodel.JailOverviewCard
import kotlinx.coroutines.launch

/** Static landing screen of the Jail tab showing the five feature cards. */
class JailOverviewFragment : Fragment() {
    private var binding: JailOverviewFragmentBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = JailOverviewFragmentBinding.inflate(inflater, container, false)
        return requireNotNull(binding).root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = requireNotNull(binding)
        val host = host()
        val adapter = CardAdapter { card ->
            val h = host()
            if (card.opensHelp) h?.openHelp()
            else h?.navigateTo(card.destination)
        }
        binding.jailOverviewCards.layoutManager = LinearLayoutManager(requireContext())
        binding.jailOverviewCards.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                host?.jailViewModel?.overviewState?.collect { state ->
                    binding.jailOverviewIntro.setText(state.intro)
                    adapter.submit(state.cards)
                }
            }
        }
    }

    override fun onDestroyView() {
        binding?.jailOverviewCards?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun host(): JailFragmentHost? = parentFragment as? JailFragmentHost

    private class CardAdapter(
        private val onCardClick: (JailOverviewCard) -> Unit
    ) : RecyclerView.Adapter<CardAdapter.CardViewHolder>() {
        private val cards = mutableListOf<JailOverviewCard>()

        fun submit(newCards: List<JailOverviewCard>) {
            cards.clear()
            cards.addAll(newCards)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = cards.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return CardViewHolder(JailOverviewCardBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
            holder.bind(cards[position], onCardClick)
        }

        class CardViewHolder(val binding: JailOverviewCardBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(card: JailOverviewCard, onClick: (JailOverviewCard) -> Unit) {
                binding.jailOverviewCardIcon.setImageResource(card.iconRes)
                binding.jailOverviewCardTitle.setText(card.titleRes)
                binding.jailOverviewCardSubtitle.setText(card.subtitleRes)
                binding.jailOverviewCardRoot.setOnClickListener { onClick(card) }
            }
        }
    }
}
