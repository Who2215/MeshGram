package com.meshchat.app.mesh

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FriendshipPolicyTest {
    private val now = 1_800_000_000_000L
    private val peer = PeerIdentity("friend-node", "Roman", "enc", "sig", "fingerprint", now, now)
    private val request = FriendPacket(action = FriendPacket.REQUEST,
        requestId = "request-1234567890", alias = "Roman", avatarData = "photo")
    private fun receive(state: FriendState, packet: FriendPacket = request, identity: PeerIdentity = peer) =
        FriendshipPolicy.receive(state, identity, packet, now, fromNearby = true)

    @Test fun nearbyOptInDoesNotAdmitUninvitedInternetRequests() {
        val state = FriendState(discoverable = true)
        assertEquals(state, FriendshipPolicy.receive(state, peer, request, now, fromNearby = false))
    }

    @Test fun discoveryIsOffByDefaultAndUnknownRequestsAreDropped() {
        val state = FriendState()
        assertFalse(state.discoverable)
        assertEquals(state, receive(state))
        assertFalse(state.isFriend(peer.nodeId))
    }

    @Test fun validSingleUseInviteAllowsPendingRequestWhilePrivate() {
        val state = FriendState(inviteToken = "token", inviteExpiresAtMs = now + 1000)
        val received = receive(state, request.copy(inviteToken = "token"))
        assertEquals(request.requestId, received.record(peer.nodeId)?.incomingId)
        assertFalse(received.isFriend(peer.nodeId))
        assertEquals("", received.inviteToken)
        assertEquals(received, receive(received, request.copy(inviteToken = "token"), peer.copy(nodeId = "other")))
    }

    @Test fun expiredRevokedAndWrongInvitesAreRejected() {
        for (state in listOf(FriendState(), FriendState(inviteToken = "token", inviteExpiresAtMs = now),
            FriendState(inviteToken = "other", inviteExpiresAtMs = now + 1000))) {
            assertEquals(state, receive(state, request.copy(inviteToken = "token")))
        }
    }

    @Test fun discoverableRequestRequiresExplicitAcceptance() {
        val state = receive(FriendState(discoverable = true))
        assertNotNull(state.record(peer.nodeId))
        assertFalse(state.isFriend(peer.nodeId))
        assertEquals(state, receive(state))
    }

    @Test fun unsolicitedOrMismatchedAcceptCannotCreateFriendship() {
        val accept = request.copy(action = FriendPacket.ACCEPT)
        assertEquals(FriendState(), receive(FriendState(), accept))
        val state = FriendState(records = listOf(FriendRecord(peer.nodeId, peer.fingerprint, peer.alias,
            outgoingId = "different-request", updatedAtMs = now)))
        assertEquals(state, receive(state, accept))
    }

    @Test fun matchingRequestAndFingerprintCompletesMutualConsent() {
        val state = FriendState(records = listOf(FriendRecord(peer.nodeId, peer.fingerprint, peer.alias,
            outgoingId = request.requestId, updatedAtMs = now)))
        val accepted = receive(state, request.copy(action = FriendPacket.ACCEPT))
        assertTrue(accepted.isFriend(peer.nodeId, peer.fingerprint))
        assertFalse(accepted.isFriend(peer.nodeId, "changed-key"))
        assertEquals("photo", accepted.record(peer.nodeId)?.avatarData)
    }

    @Test fun changedKeysAndBlockedRequestsAreRejected() {
        val pending = receive(FriendState(discoverable = true))
        assertEquals(pending, receive(pending, request.copy(requestId = "other-request-123456"), peer.copy(fingerprint = "attacker")))
        val blocked = pending.copy(records = pending.records.map { it.copy(blocked = true) })
        assertEquals(blocked, receive(blocked, request.copy(requestId = "other-request-123456")))
        assertFalse(blocked.isFriend(peer.nodeId))
    }

    @Test fun requestFloodIsBoundedWithoutEvictingAcceptedFriends() {
        val records = (1..FriendshipPolicy.MAX_PENDING).map {
            FriendRecord("node-$it", "fp-$it", "Person", incomingId = "request-$it")
        }
        val state = FriendState(discoverable = true, records = records)
        assertEquals(state, receive(state))
    }

    @Test fun profileUpdateRequiresConsentAndCanRemovePhoto() {
        val profile = request.copy(action = FriendPacket.PROFILE, avatarData = "")
        assertEquals(FriendState(), receive(FriendState(), profile))
        val state = FriendState(records = listOf(FriendRecord(peer.nodeId, peer.fingerprint, peer.alias,
            avatarData = "photo", accepted = true, incomingId = request.requestId)))
        val updated = receive(state, profile)
        assertEquals("", updated.record(peer.nodeId)?.avatarData)
        assertNull(updated.record(peer.nodeId)?.incomingId)
    }

    @Test fun oversizedProfilesAndInvalidActionsAreRejected() {
        val state = FriendState(discoverable = true)
        for (packet in listOf(request.copy(avatarData = "x".repeat(24_001)),
            request.copy(alias = "x".repeat(33)), request.copy(action = "grant-admin"),
            request.copy(requestId = "short"))) assertEquals(state, receive(state, packet))
    }

    @Test fun staleAcceptDoesNotResurrectExpiredRequest() {
        val state = FriendState(records = listOf(FriendRecord(peer.nodeId, peer.fingerprint, peer.alias,
            outgoingId = request.requestId, updatedAtMs = now - FriendshipPolicy.REQUEST_LIFETIME_MS - 1)))
        assertEquals(state, receive(state, request.copy(action = FriendPacket.ACCEPT)))
    }

    @Test fun consentAndFingerprintSurviveSerialization() {
        val state = FriendState(records = listOf(FriendRecord(peer.nodeId, peer.fingerprint, peer.alias,
            outgoingId = request.requestId, accepted = true)))
        assertEquals(state, Json.decodeFromString<FriendState>(Json.encodeToString(state)))
        assertFalse(Json.decodeFromString<FriendState>("{}").discoverable)
    }
}
