package com.meshchat.app.mesh

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentMediaTest {
    @Test fun restoredVoiceWithGenericMetadataKeepsAudioControls() {
        val source = MessageAttachment(
            transferId = "voice-transfer", fileName = "attachment.bin",
            mimeType = "application/octet-stream", isVoiceMessage = true,
            sizeBytes = 128, sha256 = "hash"
        )
        val restored = Json.decodeFromString<MessageAttachment>(Json.encodeToString(source))
        assertEquals("audio/mp4", restored.effectiveMimeType())
    }

    @Test fun genericProviderTypesAllowAudioFilenameFallback() {
        for (mime in listOf(null, "", "application/octet-stream", "binary/octet-stream", "*/*")) {
            assertEquals("audio/mp4", attachmentMimeType(mime, "voice_123.M4A"))
            assertEquals("audio/mpeg", attachmentMimeType(mime, "music.mp3"))
            assertEquals("audio/ogg", attachmentMimeType(mime, "note.opus"))
        }
    }

    @Test fun explicitNonAudioTypesAreNotReclassifiedByFilename() {
        assertEquals("application/pdf", attachmentMimeType("application/pdf", "document.mp3"))
        assertEquals("video/mp4", attachmentMimeType("video/mp4", "movie.m4a"))
        assertEquals("application/octet-stream", attachmentMimeType(null, "song.mp3.exe"))
    }

    @Test fun mimeParametersAndCaseAreNormalized() {
        assertEquals("audio/ogg", attachmentMimeType(" AUDIO/OGG; codecs=opus ", "file.bin"))
        assertEquals("audio/ogg", attachmentMimeType(" AUDIO/OGG; codecs=opus ", "file.bin", voice = true))
    }

    @Test fun platformInferenceStillWorksForNonAudioFiles() {
        assertEquals("image/png", attachmentMimeType("application/octet-stream", "photo.png", inferredType = "image/png"))
    }
}
