package com.meshchat.app.mesh

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshModelsCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun oldAttachmentJsonDefaultsVoiceFlagToFalse() {
        val attachment = json.decodeFromString<MessageAttachment>("""
            {"transferId":"t","fileName":"photo.jpg","mimeType":"image/jpeg","sizeBytes":4,"sha256":"hash"}
        """.trimIndent())
        assertFalse(attachment.isVoiceMessage)
    }

    @Test fun timelineUsesMonotonicPositionWhenAvailable() {
        val message = ChatMessage(
            id = "m",
            text = "test",
            originNodeId = "sender",
            relayNodeId = "sender",
            createdAtMs = 9_000L,
            timelineAtMs = 3_000L,
            isLocal = false
        )
        assertEquals(3_000L, message.timelineOrderMs())
    }

    @Test fun legacyTimelineMigrationPreservesStoredArrivalOrderDespiteClockSkew() {
        val messages = listOf(
            message(id = "local", createdAtMs = 50_000L, isLocal = true),
            message(id = "remote", createdAtMs = 1_000L, isLocal = false)
        )

        val normalized = normalizeLegacyMessageTimeline(messages, nowMs = 100_000L)

        assertEquals(listOf("local", "remote"), normalized.sortedBy { it.timelineOrderMs() }.map { it.id })
        assertTrue(normalized.all { it.timelineAtMs > 0L })
    }

    @Test fun receivedMessageUsesLocalArrivalTimeInsteadOfSenderClock() {
        val remote = message(id = "remote", createdAtMs = 1_000L, isLocal = false)
        assertEquals(75_000L, resolveMessageTimelineForAppend(null, remote, nowMs = 75_000L))
    }

    @Test fun duplicateMessageKeepsItsExistingTimelinePosition() {
        val existing = message(id = "same", createdAtMs = 1_000L, isLocal = false)
            .copy(timelineAtMs = 42_000L)
        val update = message(id = "same", createdAtMs = 99_000L, isLocal = false)
        assertEquals(42_000L, resolveMessageTimelineForAppend(existing, update, nowMs = 100_000L))
    }

    @Test fun voicePayloadRoundTripsExplicitKind() {
        val payload = MeshMessagePayload(
            chatId = "dm:a:b",
            chatType = MeshMessagePayload.CHAT_TYPE_DIRECT,
            payloadKind = MeshMessagePayload.KIND_FILE_CHUNK,
            transferId = "t",
            fileName = "recording.bin",
            mimeType = "audio/mp4",
            isVoiceMessage = true,
            fileSizeBytes = 4,
            fileSha256 = "hash",
            chunkBase64 = "AQIDBA=="
        )
        val decoded = json.decodeFromString<MeshMessagePayload>(
            json.encodeToString(MeshMessagePayload.serializer(), payload)
        )
        assertTrue(decoded.isVoiceMessage)
    }

    private fun message(id: String, createdAtMs: Long, isLocal: Boolean) = ChatMessage(
        id = id,
        text = "test",
        originNodeId = if (isLocal) "self" else "peer",
        relayNodeId = if (isLocal) "self" else "peer",
        createdAtMs = createdAtMs,
        isLocal = isLocal
    )
}
