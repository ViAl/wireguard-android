package com.wireguard.android.olcrtc

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [OlcRtcManager] state transitions.
 *
 * Note: These tests verify the state enum and initial state only.
 * Full state machine testing (connect/disconnect/error transitions)
 * requires Android runtime with VpnService and coroutine integration.
 * See manual test checklist (Task 12) for device-level verification.
 */
class OlcRtcManagerStateTest {

    @Test
    fun `initial state is DISCONNECTED`() {
        assertEquals(OlcRtcConnectionState.DISCONNECTED, OlcRtcManager.connectionState.value)
    }

    @Test
    fun `initial tunnel name is null`() {
        assertNull(OlcRtcManager.currentTunnelName.value)
    }

    @Test
    fun `initial config is null`() {
        assertNull(OlcRtcManager.getConfig())
    }

    @Test
    fun `state enum values are correct`() {
        val states = OlcRtcConnectionState.values()
        assertTrue(states.contains(OlcRtcConnectionState.DISCONNECTED))
        assertTrue(states.contains(OlcRtcConnectionState.CONNECTING))
        assertTrue(states.contains(OlcRtcConnectionState.CONNECTED))
        assertTrue(states.contains(OlcRtcConnectionState.DISCONNECTING))
        assertTrue(states.contains(OlcRtcConnectionState.ERROR))
        assertEquals(5, states.size)
    }

    @Test
    fun `state enum ordering`() {
        val values = OlcRtcConnectionState.values()
        // No strict ordering requirement, but verify they're all present
        assertEquals(
            setOf(
                OlcRtcConnectionState.DISCONNECTED,
                OlcRtcConnectionState.CONNECTING,
                OlcRtcConnectionState.CONNECTED,
                OlcRtcConnectionState.DISCONNECTING,
                OlcRtcConnectionState.ERROR
            ),
            values.toSet()
        )
    }

    @Test
    fun `transport state enum values`() {
        val states = OlcRtcTransportState.values()
        assertTrue(states.contains(OlcRtcTransportState.IDLE))
        assertTrue(states.contains(OlcRtcTransportState.STARTING))
        assertTrue(states.contains(OlcRtcTransportState.READY))
        assertTrue(states.contains(OlcRtcTransportState.STOPPING))
        assertTrue(states.contains(OlcRtcTransportState.ERROR))
        assertEquals(5, states.size)
    }

    @Test
    fun `vpn status enum values`() {
        val events = VpnStatusEvent.values()
        assertTrue(events.contains(VpnStatusEvent.TUN_ESTABLISHED))
        assertTrue(events.contains(VpnStatusEvent.TUN2SOCKS_STARTED))
        assertTrue(events.contains(VpnStatusEvent.TUN2SOCKS_EXITED))
        assertTrue(events.contains(VpnStatusEvent.VPN_STOPPED))
        assertTrue(events.contains(VpnStatusEvent.ERROR))
        assertEquals(5, events.size)
    }
}
