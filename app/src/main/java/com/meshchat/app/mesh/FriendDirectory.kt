package com.meshchat.app.mesh

import android.net.Uri
import android.util.Base64
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FriendDirectory(
    private val nodeId: String,
    private val store: SecureLocalStore,
    private val crypto: () -> SecureCryptoEngine,
    private val identity: (String) -> PeerIdentity?,
    private val rememberIdentity: (PeerIdentity) -> Unit,
    private val send: (FriendPacket, PeerIdentity) -> Unit,
    private val changed: (FriendState) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var state = store.loadFriends()
    private val lastSync = mutableMapOf<String, Long>()

    @Synchronized fun initialize() = changed(state)

    @Synchronized private fun save(next: FriendState) {
        if (next == state) return
        check(store.persistFriends(next)) { "Unable to save contact consent" }
        state = next
        changed(next)
    }

    @Synchronized fun setDiscoverable(enabled: Boolean) = save(state.copy(discoverable = enabled))

    @Synchronized fun createInvite(): String {
        val token = UUID.randomUUID().toString()
        save(state.copy(inviteToken = token,
            inviteExpiresAtMs = System.currentTimeMillis() + FriendshipPolicy.INVITE_LIFETIME_MS))
        val hello = crypto().createHelloPacket(maxHops = 0, inviteToken = token)
        return INVITE_PREFIX + Base64.encodeToString(json.encodeToString(hello).toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    @Synchronized fun revokeInvite() = save(state.copy(inviteToken = "", inviteExpiresAtMs = 0))

    fun parseInvite(raw: String): HelloPacket? = runCatching {
        val code = normalizeInviteCode(raw)
        require(code.startsWith(INVITE_PREFIX) && code.length < 4000)
        val hello = json.decodeFromString<HelloPacket>(String(Base64.decode(
            code.removePrefix(INVITE_PREFIX), Base64.URL_SAFE or Base64.NO_WRAP)))
        val age = System.currentTimeMillis() - hello.createdAtMs
        require(hello.type == HelloPacket.TYPE && hello.profileVersion == 2 && hello.maxHops == 0)
        require(hello.originNodeId != nodeId && hello.originNodeId.length in 8..64)
        require(hello.frameId.length in 16..64 && hello.alias.length in 1..32)
        require(age in -300_000..FriendshipPolicy.INVITE_LIFETIME_MS)
        require(crypto().verifyHelloSignature(hello))
        val existing = identity(hello.originNodeId)
        require(existing == null || existing.fingerprint == hello.fingerprint)
        hello
    }.getOrNull()

    @Synchronized fun requestInvite(raw: String): Boolean {
        val hello = parseInvite(raw) ?: return false
        val now = System.currentTimeMillis()
        val peer = PeerIdentity(hello.originNodeId, hello.alias, hello.encryptionPublicKey,
            hello.signingPublicKey, hello.fingerprint, now, now)
        rememberIdentity(peer)
        return request(peer, hello.frameId)
    }

    @Synchronized fun requestNearby(id: String): Boolean {
        val peer = identity(id) ?: return false
        if (!state.discoverable || !peer.discoverable) return false
        return request(peer, "")
    }

    private fun request(peer: PeerIdentity, token: String): Boolean {
        if (peer.nodeId == nodeId || state.isFriend(peer.nodeId, peer.fingerprint)) return false
        val previous = state.record(peer.nodeId)
        if (previous?.outgoingId != null && !previous.blocked) { sync(peer.nodeId, true); return true }
        if (previous == null && state.records.size >= FriendshipPolicy.MAX_RECORDS) return false
        val record = FriendRecord(peer.nodeId, peer.fingerprint, peer.alias,
            outgoingId = UUID.randomUUID().toString(), outgoingInviteToken = token,
            incomingId = previous?.incomingId.takeUnless { previous?.blocked == true },
            updatedAtMs = System.currentTimeMillis())
        save(state.copy(records = state.records.filterNot { it.nodeId == peer.nodeId } + record))
        sync(peer.nodeId, true)
        return true
    }

    @Synchronized fun accept(id: String): Boolean {
        val record = state.record(id) ?: return false
        if (record.blocked || record.incomingId == null ||
            System.currentTimeMillis() - record.updatedAtMs > FriendshipPolicy.REQUEST_LIFETIME_MS
        ) return false
        save(state.copy(records = state.records.map {
            if (it.nodeId == id) it.copy(accepted = true) else it
        }))
        sync(id, true)
        return true
    }

    @Synchronized fun decline(id: String) {
        save(state.copy(records = state.records.map {
            if (it.nodeId == id) it.copy(blocked = true, accepted = false,
                outgoingId = null, incomingId = null, avatarData = "") else it
        }))
    }

    @Synchronized fun receive(peer: PeerIdentity, packet: FriendPacket, createdAtMs: Long,
        fromNearby: Boolean) {
        val now = System.currentTimeMillis()
        if (now - createdAtMs !in -300_000..FriendshipPolicy.REQUEST_LIFETIME_MS) return
        save(FriendshipPolicy.receive(state, peer, packet, now, fromNearby))
        val record = state.record(peer.nodeId) ?: return
        if (record.blocked) return
        if (packet.action == FriendPacket.REQUEST && record.accepted && record.incomingId == packet.requestId) {
            sync(peer.nodeId)
        }
        if (packet.action == FriendPacket.ACCEPT && record.accepted && record.outgoingId == packet.requestId) {
            send(profile(FriendPacket.PROFILE, packet.requestId), peer)
        }
    }

    @Synchronized fun sync(id: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - (lastSync[id] ?: 0) < 30_000) return
        val record = state.record(id) ?: return
        if (record.blocked || now - record.updatedAtMs > FriendshipPolicy.REQUEST_LIFETIME_MS) return
        val peer = identity(id)?.takeIf { it.fingerprint == record.fingerprint } ?: return
        val packet = when {
            record.accepted && record.incomingId != null -> profile(FriendPacket.ACCEPT, record.incomingId)
            !record.accepted && record.outgoingId != null -> profile(FriendPacket.REQUEST, record.outgoingId)
                .copy(inviteToken = record.outgoingInviteToken)
            else -> return
        }
        lastSync[id] = now
        send(packet, peer)
    }

    @Synchronized fun publishProfile() {
        state.records.filter { it.accepted && !it.blocked }.forEach { record ->
            identity(record.nodeId)?.takeIf { it.fingerprint == record.fingerprint }?.let { peer ->
                send(profile(FriendPacket.PROFILE, UUID.randomUUID().toString()), peer)
            }
        }
    }

    private fun profile(action: String, requestId: String) = FriendPacket(
        action = action, requestId = requestId,
        alias = crypto().localAlias(), avatarData = crypto().localAvatarData()
    )

    companion object {
        const val INVITE_PREFIX = "meshgram:friend:v1:"
        private const val INVITE_WEB_SCHEME = "https"
        private const val INVITE_WEB_HOST = "who2215.github.io"
        private const val INVITE_WEB_PATH = "/MeshGram/"

        fun toShareUrl(code: String): String = Uri.Builder()
            .scheme(INVITE_WEB_SCHEME)
            .authority(INVITE_WEB_HOST)
            .path(INVITE_WEB_PATH)
            .appendQueryParameter("friend_invite", code.trim())
            .build()
            .toString()

        fun normalizeInviteCode(raw: String): String {
            val value = raw.trim()
            if (value.startsWith(INVITE_PREFIX)) return value
            val uri = Uri.parse(value)
            val validWebLink = uri.scheme == INVITE_WEB_SCHEME &&
                uri.host == INVITE_WEB_HOST &&
                uri.path?.startsWith(INVITE_WEB_PATH) == true
            val validAppLink = uri.scheme == "meshgram" && uri.host == "friend"
            require(validWebLink || validAppLink)
            return uri.getQueryParameter("friend_invite")?.trim()
                ?.takeIf { it.startsWith(INVITE_PREFIX) }
                ?: error("missing friend invitation")
        }
    }
}
