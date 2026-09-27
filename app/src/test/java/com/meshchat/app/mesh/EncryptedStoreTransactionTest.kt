package com.meshchat.app.mesh

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class EncryptedStoreTransactionTest {
    @Test fun encryptionFailureNeverStartsWriting() {
        for (throws in listOf(false, true)) {
            assertFalse(writeEncryptedTransaction(
                encrypt = { if (throws) throw IOException("Keystore unavailable") else null },
                start = { fail("Must preserve the existing file"); ByteArrayOutputStream() },
                commit = { fail("No commit") }, rollback = { fail("No transaction") }
            ))
        }
    }

    @Test fun startFailureReturnsFalseAndClearsTemporaryBuffer() {
        val blob = byteArrayOf(1, 2)
        assertFalse(writeEncryptedTransaction<ByteArrayOutputStream>(
            { blob }, { throw IOException("Disk full") }, { fail("No commit") }, { fail("No stream") }
        ))
        assertArrayEquals(byteArrayOf(0, 0), blob)
    }

    @Test fun successCommitsOnlyAfterWritingAndFlushing() {
        val events = mutableListOf<String>()
        val stream = object : ByteArrayOutputStream() {
            override fun flush() { events += "flush" }
        }
        val blob = byteArrayOf(1, 2, 3)
        assertTrue(writeEncryptedTransaction(
            { blob }, { events += "start"; stream },
            { assertArrayEquals(byteArrayOf(1, 2, 3), it.toByteArray()); events += "commit" },
            { fail("No rollback") }
        ))
        assertEquals(listOf("start", "flush", "commit"), events)
        assertTrue(blob.all { it == 0.toByte() })
    }

    @Test fun partialWriteRestoresPreviousSnapshot() {
        var stored = "previous"
        var rolledBack = false
        val stream = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                super.write(bytes, offset, 1)
                throw IOException("Disk full")
            }
        }
        assertFalse(writeEncryptedTransaction(
            { "next".toByteArray() }, { stream },
            { stored = it.toString(); fail("Partial data must not commit") },
            { rolledBack = true; it.close() }
        ))
        assertEquals("previous", stored)
        assertTrue(rolledBack)
    }

    @Test fun commitFailureRollsBackWithoutEscapingToCaller() {
        var rollback = false
        assertFalse(writeEncryptedTransaction(
            { byteArrayOf(1) }, { ByteArrayOutputStream() },
            { throw IOException("Sync failed") },
            { rollback = true; throw IOException("Rollback unavailable") }
        ))
        assertTrue(rollback)
    }
}
