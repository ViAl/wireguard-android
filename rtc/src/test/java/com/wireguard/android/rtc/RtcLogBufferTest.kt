package com.wireguard.android.rtc

import org.junit.Assert.assertEquals
import org.junit.Test

class RtcLogBufferTest {
    @Test
    fun keepsLastEntriesOnly() {
        val buffer = RtcLogBuffer(maxEntries = 2)
        buffer.add("a")
        buffer.add("b")
        buffer.add("c")
        assertEquals(listOf("b", "c"), buffer.snapshot())
    }
}
