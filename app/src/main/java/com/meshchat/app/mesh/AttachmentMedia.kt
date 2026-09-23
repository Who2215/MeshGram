package com.meshchat.app.mesh

import java.util.Locale

private val audioMimeTypes = mapOf(
    "m4a" to "audio/mp4",
    "mp3" to "audio/mpeg",
    "aac" to "audio/aac",
    "ogg" to "audio/ogg",
    "opus" to "audio/ogg",
    "wav" to "audio/wav",
    "flac" to "audio/flac",
    "amr" to "audio/amr"
)

/** Presentation metadata only; the local decoder still validates the media bytes. */
internal fun attachmentMimeType(
    reportedType: String?,
    fileName: String,
    voice: Boolean = false,
    inferredType: String? = null
): String {
    val reported = reportedType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
    if (voice) return reported.takeIf { it.startsWith("audio/") } ?: "audio/mp4"
    if (reported !in setOf("", "application/octet-stream", "binary/octet-stream", "*/*")) {
        return reported
    }
    val extension = fileName.trim().substringAfterLast('.', "").lowercase(Locale.ROOT)
    return audioMimeTypes[extension]
        ?: inferredType?.trim()?.takeIf { it.isNotEmpty() }
        ?: "application/octet-stream"
}

internal fun MessageAttachment.effectiveMimeType(): String =
    attachmentMimeType(mimeType, fileName, isVoiceMessage)
