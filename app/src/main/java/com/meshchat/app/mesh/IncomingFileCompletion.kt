package com.meshchat.app.mesh

internal enum class IncomingFileCompletion { STORED, INVALID_DATA, STORAGE_FAILED }

/** The completion callback may acknowledge delivery only after verified bytes are stored. */
internal fun completeIncomingFile(
    bytes: ByteArray,
    compressed: Boolean,
    size: Long,
    hash: String,
    limit: Int,
    store: (ByteArray) -> String?,
    onStored: (String) -> Unit
): IncomingFileCompletion {
    val decoded = runCatching {
        TransferBuffers.decodeVerified(bytes, compressed, size, hash, limit)
    }.getOrNull() ?: return IncomingFileCompletion.INVALID_DATA
    try {
        val path = runCatching { store(decoded) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return IncomingFileCompletion.STORAGE_FAILED
        if (runCatching { onStored(path) }.isFailure) return IncomingFileCompletion.STORAGE_FAILED
        return IncomingFileCompletion.STORED
    } finally {
        decoded.fill(0)
    }
}

internal fun ChatMessage.canAcknowledgeStoredTransfer(
    sender: String,
    conversation: String,
    transfer: String,
    size: Long,
    hash: String
): Boolean {
    if (isLocal || id != "file:$transfer" || originNodeId != sender || conversationId != conversation) return false
    // Deletion intentionally removes attachment metadata; a late retry must not resurrect it.
    if (isDeleted) return true
    return contentType == ChatContentType.FILE && attachment?.let {
        it.transferId == transfer && it.sizeBytes == size &&
            it.sha256.equals(hash, ignoreCase = true) && !it.localUri.isNullOrBlank()
    } == true
}
