package com.meshchat.app.mesh

import java.io.ByteArrayInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class TransferBuffersTest {
    @Test
    fun boundedReadRejectsExpansionPastLimit() {
        assertThrows(Exception::class.java) {
            TransferBuffers.readBounded(ByteArrayInputStream(ByteArray(5)), 4)
        }
    }

    @Test
    fun verifiedGzipRoundTripChecksSizeAndHash() {
        val raw = "meshgram-transfer".repeat(80).toByteArray()
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(raw) }
        }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it) }

        assertArrayEquals(raw, TransferBuffers.decodeVerified(compressed, true, raw.size.toLong(), hash, 4096))
        assertThrows(IllegalArgumentException::class.java) {
            TransferBuffers.decodeVerified(compressed, true, (raw.size + 1).toLong(), hash, 4096)
        }
    }

    @Test
    fun chunkGeometryRejectsShortNonFinalAndOversizedMetadata() {
        assertTrue(TransferBuffers.validChunk(0, 2, 320, 320, 1024))
        assertTrue(TransferBuffers.validChunk(1, 2, 4, 320, 1024))
        assertFalse(TransferBuffers.validChunk(0, 2, 4, 320, 1024))
        assertFalse(TransferBuffers.validChunk(2, 2, 4, 320, 1024))
        assertFalse(TransferBuffers.validHash("not-a-sha256"))
    }
}
