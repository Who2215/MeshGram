package com.meshchat.app.mesh

/**
 * Pure hop validation used by every transport. Keeping this policy independent
 * from Android BLE APIs makes relay loop and boundary behavior unit-testable.
 */
internal object MeshRelayPolicy {
    internal fun isValidEnvelope(
        hops: Int,
        maxHops: Int,
        absoluteMaxHops: Int
    ): Boolean {
        return maxHops in 1..absoluteMaxHops && hops in 0..maxHops
    }

    internal fun canForward(hops: Int, maxHops: Int): Boolean {
        return hops >= 0 && maxHops > 0 && hops < maxHops
    }

    internal fun nextHop(hops: Int, maxHops: Int): Int? {
        return if (canForward(hops, maxHops)) hops + 1 else null
    }
}
