package com.meshchat.app.mesh

import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class IncomingFileCompletionTest {
    private val content = "received voice bytes".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(content)
        .joinToString("") { "%02x".format(it) }

    @Test fun receiptFollowsVerificationAndStorage() {
        val events = mutableListOf<String>()
        var decoded: ByteArray? = null
        val result = completeIncomingFile(content.copyOf(), false, content.size.toLong(), hash, 1024,
            store = {
                assertArrayEquals(content, it)
                decoded = it
                events += "store"
                "encrypted-file"
            }, onStored = {
                assertEquals("encrypted-file", it)
                events += "message"
                events += "receipt"
            })
        assertEquals(IncomingFileCompletion.STORED, result)
        assertEquals(listOf("store", "message", "receipt"), events)
        assertTrue(decoded!!.all { it == 0.toByte() })
    }

    @Test fun corruptionNeverStoresOrAcknowledges() {
        val variants = listOf(
            Triple(content.copyOf().apply { this[0] = 0 }, false, content.size.toLong()),
            Triple(content.copyOf(), false, content.size.toLong() + 1),
            Triple(content.copyOf(), true, content.size.toLong())
        )
        for ((bytes, compressed, size) in variants) {
            val result = completeIncomingFile(bytes, compressed, size, hash, 1024,
                store = { fail("Invalid data must not be stored"); null },
                onStored = { fail("Invalid data must not be acknowledged") })
            assertEquals(IncomingFileCompletion.INVALID_DATA, result)
        }
    }

    @Test fun failedStorageCanRetryWithoutPrematureReceipt() {
        var attempts = 0
        var receipts = 0
        fun attempt() = completeIncomingFile(content.copyOf(), false, content.size.toLong(), hash, 1024,
            store = { attempts++; if (attempts == 1) null else "encrypted-file" },
            onStored = { receipts++ })
        assertEquals(IncomingFileCompletion.STORAGE_FAILED, attempt())
        assertEquals(0, receipts)
        assertEquals(IncomingFileCompletion.STORED, attempt())
        assertEquals(1, receipts)
    }

    @Test fun failedMessagePersistenceDoesNotAcknowledge() {
        var receipt = false
        val result = completeIncomingFile(content.copyOf(), false, content.size.toLong(), hash, 1024,
            store = { "encrypted-file" },
            onStored = {
                fun persist(): Boolean = throw IOException("Disk full")
                if (persist()) receipt = true
            })
        assertEquals(IncomingFileCompletion.STORAGE_FAILED, result)
        assertFalse(receipt)
    }

    @Test fun throwingStorageDoesNotAcknowledge() {
        val result = completeIncomingFile(content.copyOf(), false, content.size.toLong(), hash, 1024,
            store = { throw IOException("Disk unavailable") },
            onStored = { fail("Storage failure must not be acknowledged") })
        assertEquals(IncomingFileCompletion.STORAGE_FAILED, result)
    }

    @Test fun restoredReceiptMatchesOnlyTheSameSenderChatAndFile() {
        val message = ChatMessage(
            id = "file:transfer", text = "voice", originNodeId = "sender", relayNodeId = "relay",
            createdAtMs = 1L, isLocal = false, conversationId = "chat",
            contentType = ChatContentType.FILE,
            attachment = MessageAttachment("transfer", "voice.m4a", "audio/mp4",
                sizeBytes = content.size.toLong(), sha256 = hash, localUri = "encrypted-file")
        )
        val restored = Json.decodeFromString<ChatMessage>(Json.encodeToString(message))
        fun matches(value: ChatMessage) = value.canAcknowledgeStoredTransfer("sender", "chat", "transfer", content.size.toLong(), hash)
        assertTrue(matches(restored))
        assertFalse(matches(restored.copy(originNodeId = "other")))
        assertFalse(matches(restored.copy(conversationId = "other")))
        assertFalse(matches(restored.copy(isLocal = true)))
        val deleted = restored.copy(isDeleted = true, contentType = ChatContentType.TEXT, attachment = null)
        assertTrue(matches(Json.decodeFromString<ChatMessage>(Json.encodeToString(deleted))))
        assertFalse(matches(deleted.copy(originNodeId = "other")))
        assertFalse(matches(deleted.copy(id = "file:other")))
        assertFalse(matches(restored.copy(attachment = restored.attachment!!.copy(sha256 = "0".repeat(64)))))
        assertFalse(matches(restored.copy(attachment = restored.attachment!!.copy(localUri = null))))
    }
}
