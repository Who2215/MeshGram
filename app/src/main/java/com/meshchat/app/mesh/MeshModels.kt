package com.meshchat.app.mesh

import kotlinx.serialization.Serializable

@Serializable
data class HelloPacket(
    val type: String = TYPE,
    val frameId: String,
    val originNodeId: String,
    val relayNodeId: String,
    val hops: Int,
    val maxHops: Int,
    val createdAtMs: Long,
    val alias: String,
    val encryptionPublicKey: String,
    val signingPublicKey: String,
    val fingerprint: String,
    val signature: String,
    // A small compressed public profile thumbnail. It is never the original file.
    val avatarData: String = ""
) {
    companion object {
        const val TYPE = "HELLO_V1"
    }
}

@Serializable
data class SecureMessagePacket(
    val type: String = TYPE,
    val id: String,
    val originNodeId: String,
    val senderAlias: String,
    val senderEncryptionPublicKey: String,
    val senderSigningPublicKey: String,
    val senderFingerprint: String,
    val targetNodeId: String,
    val relayNodeId: String,
    val hops: Int,
    val maxHops: Int,
    val createdAtMs: Long,
    val ephemeralPublicKey: String,
    val nonce: String,
    val ciphertext: String,
    val signature: String
) {
    companion object {
        const val TYPE = "SECURE_MESSAGE_V1"
    }
}

@Serializable
enum class ChatContentType {
    TEXT,
    FILE
}

@Serializable
enum class MessageDeliveryState {
    PENDING,
    SENT,
    RELAYED,
    DELIVERED
}

@Serializable
data class MessageAttachment(
    val transferId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val compressed: Boolean = false,
    val localUri: String? = null,
    val mediaAlbumId: String? = null,
    val mediaAlbumIndex: Int = 0,
    val mediaAlbumCount: Int = 1
)

@Serializable
data class MessageReaction(
    val emoji: String,
    val nodeId: String,
    val senderAlias: String? = null,
    val createdAtMs: Long
)

@Serializable
data class ChatMessage(
    val id: String,
    val text: String,
    val originNodeId: String,
    val targetNodeId: String? = null,
    val relayNodeId: String,
    val createdAtMs: Long,
    val isLocal: Boolean,
    val isEncrypted: Boolean = true,
    val isSystem: Boolean = false,
    val conversationId: String = LEGACY_BROADCAST_CONVERSATION_ID,
    val conversationType: ConversationType = ConversationType.DIRECT,
    val conversationTitle: String? = null,
    val senderAlias: String? = null,
    val memberNodeIds: List<String> = emptyList(),
    val collectiveOwnerNodeId: String? = null,
    val collectiveAdminNodeIds: List<String> = emptyList(),
    val collectiveModeratorNodeIds: List<String> = emptyList(),
    val collectiveBroadcastOnly: Boolean? = null,
    val collectiveAllowMemberReactions: Boolean? = null,
    val collectiveAllowMemberEditOwnMessages: Boolean? = null,
    val collectiveAllowMemberDeleteOwnMessages: Boolean? = null,
    val replyToMessageId: String? = null,
    val replyToPreview: String? = null,
    val isEdited: Boolean = false,
    val editedAtMs: Long? = null,
    val deliveryState: MessageDeliveryState = MessageDeliveryState.SENT,
    val deliveredAtMs: Long? = null,
    val deliveredToNodeIds: List<String> = emptyList(),
    val relayedByNodeIds: List<String> = emptyList(),
    val isDeleted: Boolean = false,
    val forwardedFromAlias: String? = null,
    val forwardedFromMessageId: String? = null,
    val savedTags: List<String> = emptyList(),
    val pinnedAtMs: Long? = null,
    val reactions: List<MessageReaction> = emptyList(),
    val contentType: ChatContentType = ChatContentType.TEXT,
    val attachment: MessageAttachment? = null
) {
    companion object {
        const val LEGACY_BROADCAST_CONVERSATION_ID = "mesh:broadcast"
    }
}

@Serializable
data class MeshMessagePayload(
    val type: String = TYPE,
    val chatId: String,
    val chatType: String,
    val chatTitle: String? = null,
    val memberNodeIds: List<String> = emptyList(),
    val collectiveOwnerNodeId: String? = null,
    val collectiveAdminNodeIds: List<String> = emptyList(),
    val collectiveModeratorNodeIds: List<String> = emptyList(),
    val collectiveBroadcastOnly: Boolean? = null,
    val collectiveAllowMemberReactions: Boolean? = null,
    val collectiveAllowMemberEditOwnMessages: Boolean? = null,
    val collectiveAllowMemberDeleteOwnMessages: Boolean? = null,
    val text: String = "",
    val payloadKind: String = KIND_TEXT,
    val messageId: String? = null,
    val replyToMessageId: String? = null,
    val replyToPreview: String? = null,
    val forwardedFromAlias: String? = null,
    val forwardedFromMessageId: String? = null,
    val targetMessageId: String? = null,
    val ackMessageId: String? = null,
    val reactionEmoji: String? = null,
    val pinEnabled: Boolean? = null,
    val transferId: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSizeBytes: Long? = null,
    val fileSha256: String? = null,
    val fileCaption: String? = null,
    val mediaAlbumId: String? = null,
    val mediaAlbumIndex: Int = 0,
    val mediaAlbumCount: Int = 1,
    val chunkIndex: Int = 0,
    val chunkCount: Int = 1,
    val chunkBase64: String? = null,
    val ackChunkIndexes: List<Int> = emptyList(),
    val ackComplete: Boolean = false,
    val retryMissingChunks: Boolean = false,
    val compressed: Boolean = false,
    val sentAtMs: Long = 0L
) {
    companion object {
        const val TYPE = "CHAT_PAYLOAD_V1"
        const val CHAT_TYPE_DIRECT = "direct"
        const val CHAT_TYPE_GROUP = "group"
        const val CHAT_TYPE_CHANNEL = "channel"
        const val KIND_TEXT = "text"
        const val KIND_FILE_CHUNK = "file_chunk"
        const val KIND_FILE_ACK = "file_ack"
        const val KIND_MESSAGE_EDIT = "message_edit"
        const val KIND_MESSAGE_DELETE = "message_delete"
        const val KIND_MESSAGE_REACTION = "message_reaction"
        const val KIND_MESSAGE_PIN = "message_pin"
        const val KIND_MESSAGE_DELIVERY_ACK = "message_delivery_ack"
        const val KIND_MESSAGE_RELAY_ACK = "message_relay_ack"
        const val KIND_COLLECTIVE_UPDATE = "collective_update"
    }
}

@Serializable
data class CollectiveInvitePayload(
    val version: Int = 1,
    val collectiveId: String,
    val title: String,
    val type: String,
    val memberNodeIds: List<String> = emptyList(),
    val ownerNodeId: String? = null,
    val adminNodeIds: List<String> = emptyList(),
    val moderatorNodeIds: List<String> = emptyList(),
    val isBroadcastOnly: Boolean = false,
    val allowMemberReactions: Boolean = true,
    val allowMemberEditOwnMessages: Boolean = true,
    val allowMemberDeleteOwnMessages: Boolean = true,
    val issuedAtMs: Long
)

@Serializable
data class RelayFrameRecord(
    val frameId: String,
    val payloadBase64: String,
    val createdAtMs: Long,
    val lastSentAtMs: Long
)

@Serializable
data class OutgoingTransferRecipientRecord(
    val nodeId: String,
    val alias: String,
    val chunkCount: Int,
    val ackedChunkIndexes: List<Int> = emptyList(),
    val lastAckAtMs: Long = 0L,
    val lastSentAtMs: Long = 0L,
    val nextChunkCursor: Int = 0
)

@Serializable
data class OutgoingFileTransferRecord(
    val transferId: String,
    val chatId: String,
    val chatType: String,
    val chatTitle: String? = null,
    val memberNodeIds: List<String> = emptyList(),
    val collectiveOwnerNodeId: String? = null,
    val collectiveAdminNodeIds: List<String> = emptyList(),
    val collectiveModeratorNodeIds: List<String> = emptyList(),
    val collectiveBroadcastOnly: Boolean = false,
    val collectiveAllowMemberReactions: Boolean = true,
    val collectiveAllowMemberEditOwnMessages: Boolean = true,
    val collectiveAllowMemberDeleteOwnMessages: Boolean = true,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val caption: String = "",
    val mediaAlbumId: String? = null,
    val mediaAlbumIndex: Int = 0,
    val mediaAlbumCount: Int = 1,
    val compressed: Boolean,
    val sentAtMs: Long,
    val chunkCount: Int,
    val chunksBase64: List<String>,
    val recipients: List<OutgoingTransferRecipientRecord>,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

data class FileTransferRecipientProgress(
    val nodeId: String,
    val alias: String,
    val acknowledgedChunks: Int,
    val totalChunks: Int,
    val lastAcknowledgedAtMs: Long
) {
    val progress: Float
        get() = if (totalChunks <= 0) 0f else acknowledgedChunks.toFloat() / totalChunks
}

data class OutgoingFileTransferProgress(
    val transferId: String,
    val conversationId: String,
    val fileName: String,
    val sizeBytes: Long,
    val acknowledgedChunks: Int,
    val totalChunks: Int,
    val completedRecipients: Int,
    val totalRecipients: Int,
    val recipients: List<FileTransferRecipientProgress>,
    val createdAtMs: Long,
    val updatedAtMs: Long
) {
    val progress: Float
        get() = if (totalChunks <= 0) 0f else acknowledgedChunks.toFloat() / totalChunks
}

data class IncomingFileTransferProgress(
    val transferId: String,
    val conversationId: String,
    val senderNodeId: String,
    val senderAlias: String,
    val fileName: String,
    val sizeBytes: Long,
    val receivedChunks: Int,
    val totalChunks: Int,
    val updatedAtMs: Long
) {
    val progress: Float
        get() = if (totalChunks <= 0) 0f else receivedChunks.toFloat() / totalChunks
}

@Serializable
data class IncomingFileChunkRecord(
    val index: Int,
    val payloadBase64: String
)

@Serializable
data class IncomingFileTransferRecord(
    val transferId: String,
    val originNodeId: String,
    val senderAlias: String,
    val conversationId: String,
    val conversationType: ConversationType,
    val conversationTitle: String? = null,
    val memberNodeIds: List<String> = emptyList(),
    val collectiveOwnerNodeId: String? = null,
    val collectiveAdminNodeIds: List<String> = emptyList(),
    val collectiveModeratorNodeIds: List<String> = emptyList(),
    val collectiveBroadcastOnly: Boolean? = null,
    val collectiveAllowMemberReactions: Boolean? = null,
    val collectiveAllowMemberEditOwnMessages: Boolean? = null,
    val collectiveAllowMemberDeleteOwnMessages: Boolean? = null,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val caption: String = "",
    val mediaAlbumId: String? = null,
    val mediaAlbumIndex: Int = 0,
    val mediaAlbumCount: Int = 1,
    val compressed: Boolean,
    val chunkCount: Int,
    val sentAtMs: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val chunks: List<IncomingFileChunkRecord>
)

@Serializable
data class PendingPayloadRecord(
    val queueId: String,
    val messageId: String? = null,
    val plaintext: String,
    val targetNodeIds: List<String>,
    val createdAtMs: Long,
    val lastAttemptAtMs: Long
)

@Serializable
data class ScheduledMessageRecord(
    val id: String,
    val text: String,
    val conversationId: String,
    val conversationTitle: String,
    val conversationType: ConversationType,
    val targetNodeId: String? = null,
    val memberNodeIds: List<String> = emptyList(),
    val adminNodeIds: List<String> = emptyList(),
    val moderatorNodeIds: List<String> = emptyList(),
    val isBroadcastOnly: Boolean = false,
    val allowMemberReactions: Boolean = true,
    val allowMemberEditOwnMessages: Boolean = true,
    val allowMemberDeleteOwnMessages: Boolean = true,
    val replyToMessageId: String? = null,
    val replyToPreview: String? = null,
    val scheduledAtMs: Long,
    val createdAtMs: Long,
    val lastAttemptAtMs: Long = 0L
)

data class Peer(
    val address: String,
    val nodeId: String? = null,
    val alias: String,
    val fingerprintShort: String? = null,
    val isConnected: Boolean,
    val lastSeenMs: Long
)

@Serializable
data class PeerIdentity(
    val nodeId: String,
    val alias: String,
    val encryptionPublicKey: String,
    val signingPublicKey: String,
    val fingerprint: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val avatarData: String = ""
)

@Serializable
enum class ConversationType {
    DIRECT,
    GROUP,
    CHANNEL
}

@Serializable
enum class CollectiveRole {
    OWNER,
    ADMIN,
    MODERATOR,
    MEMBER
}

@Serializable
enum class MeshTab {
    MAP,
    CHATS,
    GROUPS,
    PROFILE,
    // Kept for saved state compatibility; it is no longer shown as a bottom tab.
    SETTINGS
}

data class MeshContact(
    val nodeId: String,
    val alias: String,
    val fingerprintShort: String? = null,
    val isOnline: Boolean = false,
    val avatarData: String = ""
)

@Serializable
data class MeshGroup(
    val id: String,
    val title: String,
    val memberNodeIds: List<String>,
    val createdAtMs: Long,
    val createdByNodeId: String,
    val type: ConversationType = ConversationType.GROUP,
    val adminNodeIds: List<String> = emptyList(),
    val moderatorNodeIds: List<String> = emptyList(),
    val isBroadcastOnly: Boolean = false,
    val allowMemberReactions: Boolean = true,
    val allowMemberEditOwnMessages: Boolean = true,
    val allowMemberDeleteOwnMessages: Boolean = true
)

data class ConversationSummary(
    val id: String,
    val type: ConversationType,
    val title: String,
    val subtitle: String,
    val memberNodeIds: List<String>,
    val avatarData: String = "",
    val isOnline: Boolean = false,
    val lastMessageAtMs: Long = 0L,
    val lastMessagePreview: String = "",
    val isPinned: Boolean = false,
    val unreadCount: Int = 0,
    val draftText: String = "",
    val isMuted: Boolean = false,
    val isArchived: Boolean = false,
    val ownerNodeId: String? = null,
    val adminNodeIds: List<String> = emptyList(),
    val moderatorNodeIds: List<String> = emptyList(),
    val isBroadcastOnly: Boolean = false,
    val allowMemberReactions: Boolean = true,
    val allowMemberEditOwnMessages: Boolean = true,
    val allowMemberDeleteOwnMessages: Boolean = true
)

data class MeshUiState(
    val nodeId: String = "",
    val nodeAlias: String = "",
    val nodeAvatarData: String = "",
    val nodeFingerprint: String = "",
    val encryptionLabel: String = "ECDH + AES-256-GCM + ECDSA",
    val isRunning: Boolean = false,
    val status: String = "Idle",
    val peers: List<Peer> = emptyList(),
    val contacts: List<MeshContact> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
    val groups: List<MeshGroup> = emptyList(),
    val activeConversationId: String? = null,
    val activeConversationTitle: String = "Mesh Chat",
    val activeConversationSubtitle: String = "Select a chat",
    val activeConversationType: ConversationType = ConversationType.DIRECT,
    val activeConversationRole: CollectiveRole = CollectiveRole.MEMBER,
    val activeConversationCanPost: Boolean = true,
    val activeConversationCanModerate: Boolean = false,
    val activeConversationCanPin: Boolean = true,
    val activeConversationCanReact: Boolean = true,
    val activeConversationCanEditOwn: Boolean = true,
    val activeConversationCanDeleteOwn: Boolean = true,
    val activeConversationCanManageRoles: Boolean = false,
    val activeConversationIsAdmin: Boolean = false,
    val activeConversationIsModerator: Boolean = false,
    val activeMessages: List<ChatMessage> = emptyList(),
    val activeScheduledMessages: List<ScheduledMessageRecord> = emptyList(),
    val activeFileTransfers: List<OutgoingFileTransferProgress> = emptyList(),
    val activeIncomingFileTransfers: List<IncomingFileTransferProgress> = emptyList(),
    val activeDraft: String = "",
    val wifiLanActive: Boolean = false,
    val relayEnabled: Boolean = false,
    val relayUrl: String = "",
    val relayConnected: Boolean = false,
    val selectedTab: MeshTab = MeshTab.MAP,
    val isConversationOpen: Boolean = false
)

@Serializable
data class ConversationLocalState(
    val conversationId: String,
    val draftText: String = "",
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false,
    val updatedAtMs: Long = 0L
)

fun directConversationId(firstNodeId: String, secondNodeId: String): String {
    val pair = listOf(firstNodeId.trim(), secondNodeId.trim())
        .filter { it.isNotBlank() }
        .sorted()
    if (pair.size != 2) return ChatMessage.LEGACY_BROADCAST_CONVERSATION_ID
    return "dm:${pair[0]}:${pair[1]}"
}

const val SAVED_MESSAGES_CONVERSATION_ID = "mesh:saved"

fun isSavedMessagesConversation(conversationId: String): Boolean {
    return conversationId.trim() == SAVED_MESSAGES_CONVERSATION_ID
}
