package com.meshchat.app.mesh

import kotlinx.serialization.Serializable

@Serializable
data class FriendRecord(
    val nodeId: String,
    val fingerprint: String,
    val alias: String,
    val avatarData: String = "",
    val outgoingId: String? = null,
    val outgoingInviteToken: String = "",
    val incomingId: String? = null,
    val accepted: Boolean = false,
    val blocked: Boolean = false,
    val updatedAtMs: Long = 0
)

@Serializable
data class FriendState(
    val discoverable: Boolean = false,
    val inviteToken: String = "",
    val inviteExpiresAtMs: Long = 0,
    val records: List<FriendRecord> = emptyList()
) {
    fun record(nodeId: String) = records.firstOrNull { it.nodeId == nodeId }
    fun isFriend(nodeId: String, fingerprint: String? = null): Boolean = record(nodeId)?.let {
        it.accepted && !it.blocked && (fingerprint == null || fingerprint == it.fingerprint)
    } == true
}

@Serializable
data class FriendPacket(
    val type: String = TYPE,
    val action: String,
    val requestId: String,
    val alias: String,
    val avatarData: String = "",
    val inviteToken: String = ""
) {
    companion object {
        const val TYPE = "MESH_FRIEND_V1"
        const val REQUEST = "request"
        const val ACCEPT = "accept"
        const val PROFILE = "profile"
    }
}

/** Pure consent rules, evaluated only after packet signature and E2E verification. */
object FriendshipPolicy {
    const val MAX_RECORDS = 1000
    const val MAX_PENDING = 100
    const val REQUEST_LIFETIME_MS = 7 * 24 * 60 * 60 * 1000L
    const val INVITE_LIFETIME_MS = 24 * 60 * 60 * 1000L

    fun receive(state: FriendState, sender: PeerIdentity, packet: FriendPacket, now: Long,
        fromNearby: Boolean = false): FriendState {
        if (packet.type != FriendPacket.TYPE || packet.requestId.length !in 16..64 ||
            packet.alias.isBlank() || packet.alias.length > 32 || packet.avatarData.length > 24_000
        ) return state
        val previous = state.record(sender.nodeId)
        if (previous?.blocked == true ||
            (previous != null && previous.fingerprint != sender.fingerprint)
        ) return state
        val next = when (packet.action) {
            FriendPacket.REQUEST -> {
                if (previous?.incomingId == packet.requestId) return state
                if (previous?.accepted == true || previous?.incomingId != null) return state
                val invited = state.inviteToken.isNotBlank() && now < state.inviteExpiresAtMs &&
                    packet.inviteToken == state.inviteToken
                if (!(state.discoverable && fromNearby) && !invited && previous?.outgoingId == null) return state
                if (state.records.count { !it.accepted && !it.blocked } >= MAX_PENDING) return state
                (previous ?: FriendRecord(sender.nodeId, sender.fingerprint, packet.alias)).copy(
                    incomingId = packet.requestId, alias = packet.alias,
                    avatarData = packet.avatarData, updatedAtMs = now
                )
            }
            FriendPacket.ACCEPT -> {
                if (previous?.outgoingId != packet.requestId ||
                    now - previous.updatedAtMs > REQUEST_LIFETIME_MS
                ) return state
                previous.copy(accepted = true, alias = packet.alias,
                    avatarData = packet.avatarData, updatedAtMs = now)
            }
            FriendPacket.PROFILE -> {
                if (previous?.accepted != true) return state
                previous.copy(alias = packet.alias, avatarData = packet.avatarData, updatedAtMs = now,
                    incomingId = previous.incomingId.takeUnless { it == packet.requestId })
            }
            else -> return state
        }
        if (previous == null && state.records.size >= MAX_RECORDS) return state
        val consumed = packet.action == FriendPacket.REQUEST && packet.inviteToken.isNotEmpty() &&
            packet.inviteToken == state.inviteToken
        return state.copy(
            inviteToken = if (consumed) "" else state.inviteToken,
            inviteExpiresAtMs = if (consumed) 0 else state.inviteExpiresAtMs,
            records = state.records.filterNot { it.nodeId == next.nodeId } + next
        )
    }
}
