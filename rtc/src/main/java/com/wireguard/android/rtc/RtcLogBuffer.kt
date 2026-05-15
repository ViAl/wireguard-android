package com.wireguard.android.rtc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RtcLogBuffer(
    private val maxEntries: Int = 100,
) {
    private val mutableEntries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = mutableEntries.asStateFlow()

    @Synchronized
    fun add(message: String) {
        val current = mutableEntries.value
        if (current.lastOrNull() == message) return
        val next = (current + message).takeLast(maxEntries)
        mutableEntries.value = next
    }

    @Synchronized
    fun snapshot(): List<String> = mutableEntries.value

    @Synchronized
    fun clear() {
        mutableEntries.value = emptyList()
    }
}
