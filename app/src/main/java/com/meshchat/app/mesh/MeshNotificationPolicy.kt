package com.meshchat.app.mesh

/** Identity excludes mutable delivery, edit, reaction and attachment state. */
data class MeshNotificationMessageKey(
    val id: String,
    val conversationId: String,
    val originNodeId: String
)

/** One observer owns this policy. Seed durable history before subscribing to live snapshots. */
class MeshNotificationPolicy {
    private val observed = HashSet<MeshNotificationMessageKey>()

    fun seedHistory(keys: Iterable<MeshNotificationMessageKey>) {
        observed.addAll(keys.filter { it.id.isNotBlank() })
    }

    fun shouldNotify(
        key: MeshNotificationMessageKey,
        isLocal: Boolean = false,
        isDeleted: Boolean = false,
        isSystem: Boolean = false,
        isMuted: Boolean = false,
        isVisible: Boolean = false,
        notificationsEnabled: Boolean = true
    ): Boolean {
        if (key.id.isBlank() || !observed.add(key)) return false
        // Suppressed messages are consumed too: unmuting or leaving a chat must not replay them.
        return !isLocal && !isDeleted && !isSystem && !isMuted && !isVisible && notificationsEnabled
    }
}

/** The UI must set this only while the conversation is visible AND its activity is resumed. */
object MeshNotificationVisibility {
    @Volatile
    private var visibleConversationId: String? = null

    /** Pass null on pause/stop, disposal, or navigation away, not just on activity destruction. */
    fun setVisibleConversation(conversationId: String?) {
        visibleConversationId = conversationId?.takeIf { it.isNotBlank() }
    }

    fun isConversationVisible(conversationId: String): Boolean =
        conversationId.isNotBlank() && visibleConversationId == conversationId
}
