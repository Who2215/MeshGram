package com.meshchat.app.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshNotificationPolicyTest {
    private val key = MeshNotificationMessageKey("m-1", "chat-1", "node-1")

    @Test
    fun sameMessageIsNotNotifiedTwice() {
        val policy = MeshNotificationPolicy()
        assertTrue(policy.shouldNotify(key))
        assertFalse(policy.shouldNotify(key))
    }

    @Test
    fun localVisibleAndMutedMessagesAreConsumedWithoutAlerts() {
        assertFalse(MeshNotificationPolicy().shouldNotify(key, isLocal = true))
        assertFalse(MeshNotificationPolicy().shouldNotify(key, isVisible = true))
        assertFalse(MeshNotificationPolicy().shouldNotify(key, isMuted = true))
        assertFalse(MeshNotificationPolicy().shouldNotify(key, notificationsEnabled = false))
    }
}
