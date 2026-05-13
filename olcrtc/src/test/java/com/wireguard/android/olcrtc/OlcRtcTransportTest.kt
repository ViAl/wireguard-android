package com.wireguard.android.olcrtc

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OlcRtcTransportTest {

    private lateinit var transport: OlcRtcTransport
    private val testConfig = OlcRtcConfig(
        name = "test",
        carrier = "telemost",
        roomId = "test-room",
        clientId = "test-client",
        keyHex = "a".repeat(64)
    )

    @Before
    fun setUp() {
        transport = OlcRtcTransport()
    }

    @After
    fun tearDown() {
        transport.stop()
    }

    @Test
    fun `initial state is IDLE`() = runTest {
        kotlin.test.assertEquals(OlcRtcTransportState.IDLE, transport.state.value)
    }

    @Test
    fun `start transitions to READY`() = runTest {
        val job = transport.start(testConfig)
        job.join()
        kotlin.test.assertEquals(OlcRtcTransportState.READY, transport.state.value)
    }

    @Test
    fun `state flow emits STARTING then READY`() = runTest {
        val states = mutableListOf<OlcRtcTransportState>()
        val collectJob = launch {
            transport.state.collect { states.add(it) }
        }
        delay(50) // let collector start

        val job = transport.start(testConfig)
        job.join()
        delay(50)

        assertTrue(states.contains(OlcRtcTransportState.STARTING))
        assertTrue(states.contains(OlcRtcTransportState.READY))
        collectJob.cancel()
    }

    @Test
    fun `stop returns to IDLE`() = runTest {
        val job = transport.start(testConfig)
        job.join()
        transport.stop()
        kotlin.test.assertEquals(OlcRtcTransportState.IDLE, transport.state.value)
    }

    @Test
    fun `isRunning returns true when READY`() = runTest {
        val job = transport.start(testConfig)
        job.join()
        assertTrue(transport.isRunning())
    }

    @Test
    fun `isRunning returns false when IDLE`() {
        assertFalse(transport.isRunning())
    }

    @Test
    fun `isRunning returns false after stop`() = runTest {
        val job = transport.start(testConfig)
        job.join()
        transport.stop()
        assertFalse(transport.isRunning())
    }

    @Test
    fun `start while running restarts cleanly`() = runTest {
        val job1 = transport.start(testConfig)
        job1.join()
        kotlin.test.assertEquals(OlcRtcTransportState.READY, transport.state.value)

        val job2 = transport.start(testConfig.copy(name = "test2"))
        job2.join()
        kotlin.test.assertEquals(OlcRtcTransportState.READY, transport.state.value)
    }

    @Test
    fun `getConfig returns the config after start`() = runTest {
        val job = transport.start(testConfig)
        job.join()
        kotlin.test.assertEquals(testConfig, transport.getConfig())
    }

    @Test
    fun `getConfig returns null before start`() {
        assertNull(transport.getConfig())
    }

    @Test
    fun `getStatusSummary contains tunnel name`() = runTest {
        val job = transport.start(testConfig)
        job.join()
        val summary = transport.getStatusSummary()
        assertTrue(summary.contains("test"), "Summary should contain tunnel name: $summary")
    }
}
