/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.ui

import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import com.wireguard.android.jail.model.JailDestination

/**
 * Thin wrapper over a child [FragmentManager] that swaps between Jail sub-fragments.
 *
 * The controller does not use Jetpack Navigation; the project deliberately keeps its
 * dependency surface small and the existing codebase uses plain [FragmentManager]
 * transactions throughout. Each destination's fragment is cached and show/hide-d so that
 * expensive state (e.g. an app list) survives navigation without requiring view model
 * scoping.
 */
class JailNavigationController(
    private val fragmentManager: FragmentManager,
    @IdRes private val containerId: Int,
    private val factory: (JailDestination) -> Fragment,
    private val onDestinationChanged: (JailDestination) -> Unit = {}
) {
    var currentDestination: JailDestination = JailDestination.OVERVIEW
        private set

    fun navigate(destination: JailDestination) {
        if (destination == currentDestination && fragmentManager.findFragmentByTag(destination.tag) != null)
            return
        val target = fragmentManager.findFragmentByTag(destination.tag) ?: factory(destination)
        fragmentManager.commit {
            setReorderingAllowed(true)
            JailDestination.entries.forEach { other ->
                fragmentManager.findFragmentByTag(other.tag)?.let { existing ->
                    if (existing === target) show(existing) else hide(existing)
                }
            }
            if (target.isAdded)
                show(target)
            else
                add(containerId, target, destination.tag)
        }
        currentDestination = destination
        onDestinationChanged(destination)
    }

    /**
     * Pop back to [JailDestination.OVERVIEW] if we are not already there.
     * @return `true` if a navigation was performed, `false` otherwise.
     */
    fun popToOverview(): Boolean {
        if (currentDestination == JailDestination.OVERVIEW)
            return false
        navigate(JailDestination.OVERVIEW)
        return true
    }
}
