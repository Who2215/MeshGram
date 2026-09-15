package com.meshchat.app.ui.audio

import com.meshchat.app.mesh.ChatContentType
import com.meshchat.app.mesh.ChatMessage
import com.meshchat.app.mesh.MessageAttachment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPolicyTest {
    private fun message(attachment: MessageAttachment) = ChatMessage(
        id = "message-1",
        text = "",
        originNodeId = "sender",
        relayNodeId = "sender",
        createdAtMs = 1L,
        isLocal = false,
        contentType = ChatContentType.FILE,
        attachment = attachment
    )

    @Test fun explicitVoiceFlagSurvivesNonRecorderFilename() {
        assertTrue(isInlineVoiceMessage(message(MessageAttachment(
            transferId = "transfer-1",
            fileName = "attachment.bin",
            mimeType = "audio/mp4",
            isVoiceMessage = true,
            sizeBytes = 12,
            sha256 = "hash"
        ))))
    }

    @Test fun legacyRecorderFilenameRemainsPlayable() {
        assertTrue(isInlineVoiceMessage(message(MessageAttachment(
            transferId = "transfer-2",
            fileName = "voice_123.m4a",
            mimeType = "audio/mp4",
            sizeBytes = 12,
            sha256 = "hash"
        ))))
    }

    @Test fun ordinaryAudioFileIsNotForcedIntoVoiceControls() {
        assertFalse(isInlineVoiceMessage(message(MessageAttachment(
            transferId = "transfer-3",
            fileName = "song.m4a",
            mimeType = "audio/mp4",
            sizeBytes = 12,
            sha256 = "hash"
        ))))
    }

    @Test fun playbackSpeedCyclesThroughCompactControls() {
        assertEquals(1.5f, nextAudioSpeed(1f))
        assertEquals(2f, nextAudioSpeed(1.5f))
        assertEquals(1f, nextAudioSpeed(2f))
    }
}
