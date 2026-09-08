package com.meshchat.app.ui.audio

import com.meshchat.app.mesh.ChatMessage
import java.util.Locale

/** The wire model has no voice flag. Only the recorder's filename convention is inferred. */
fun isInlineVoiceMessage(message: ChatMessage): Boolean =
    message.attachment?.let {
        it.mimeType.startsWith("audio/", ignoreCase = true) &&
            Regex("voice_[0-9]+\\.m4a", RegexOption.IGNORE_CASE).matches(it.fileName)
    } == true

internal data class AudioMessageKey(
    val conversationId: String,
    val messageId: String,
    val transferId: String?,
    val localUri: String?,
    val sha256: String?,
    val deleted: Boolean,
    val voice: Boolean
)

internal fun ChatMessage.audioKey(voice: Boolean) = AudioMessageKey(
    conversationId, id, attachment?.transferId, attachment?.localUri,
    attachment?.sha256, isDeleted, voice
)

internal fun audioTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000
    return if (seconds >= 3_600) {
        String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    } else {
        String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
}

internal fun nextAudioSpeed(speed: Float): Float = when (speed) {
    1f -> 1.5f
    1.5f -> 2f
    else -> 1f
}
