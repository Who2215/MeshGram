package com.meshchat.app.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRelayPolicyTest {
    @Test
    fun validEnvelopeAcceptsOnlyHopsInsideConfiguredBounds() {
        assertTrue(MeshRelayPolicy.isValidEnvelope(hops = 0, maxHops = 4, absoluteMaxHops = 8))
        assertTrue(MeshRelayPolicy.isValidEnvelope(hops = 4, maxHops = 4, absoluteMaxHops = 8))
        assertFalse(MeshRelayPolicy.isValidEnvelope(hops = -1, maxHops = 4, absoluteMaxHops = 8))
        assertFalse(MeshRelayPolicy.isValidEnvelope(hops = 5, maxHops = 4, absoluteMaxHops = 8))
        assertFalse(MeshRelayPolicy.isValidEnvelope(hops = 0, maxHops = 9, absoluteMaxHops = 8))
    }

    @Test
    fun forwardingStopsAtHopLimitAndNeverCreatesAnInfiniteLoop() {
        assertTrue(MeshRelayPolicy.canForward(hops = 0, maxHops = 2))
        assertEquals(1, MeshRelayPolicy.nextHop(hops = 0, maxHops = 2))
        assertTrue(MeshRelayPolicy.canForward(hops = 1, maxHops = 2))
        assertEquals(2, MeshRelayPolicy.nextHop(hops = 1, maxHops = 2))
        assertFalse(MeshRelayPolicy.canForward(hops = 2, maxHops = 2))
        assertNull(MeshRelayPolicy.nextHop(hops = 2, maxHops = 2))
    }
}
