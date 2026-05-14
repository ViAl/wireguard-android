package com.wireguard.android.rtc

class RtcLogBuffer(
    private val maxEntries: Int = 50,
) {
    private val entries = ArrayDeque<String>()

    @Synchronized
    fun add(message: String) {
        if (entries.size >= maxEntries) {
            entries.removeFirst()
        }
        entries.addLast(message)
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
