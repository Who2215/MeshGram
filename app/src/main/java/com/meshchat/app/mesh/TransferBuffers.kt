package com.meshchat.app.mesh

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

internal object TransferBuffers {
    fun readBounded(input: InputStream, limit: Int): ByteArray {
        require(limit >= 0)
        val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(minOf(limit.toLong() + 1, 16 * 1024L).toInt())
        var zeroReads = 0
        while (true) {
            // Read at most one byte beyond the limit, without appending that byte.
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limit.toLong() - output.size() + 1).toInt())
            if (count < 0) return output.toByteArray()
            if (count == 0) {
                if (++zeroReads >= 3) throw IOException("File provider returned no data")
                continue
            }
            zeroReads = 0
            if (count > limit - output.size()) throw IOException("Transfer exceeds size limit")
            output.write(buffer, 0, count)
        }
    }

    fun validHash(hash: String): Boolean = hash.length == 64 && hash.all {
        it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
    }

    fun decodeVerified(bytes: ByteArray, compressed: Boolean, size: Long, hash: String, limit: Int): ByteArray {
        require(size in 1L..limit.toLong() && validHash(hash)) { "Invalid file metadata" }
        val raw = if (compressed) {
            GZIPInputStream(bytes.inputStream()).use { readBounded(it, size.toInt()) }
        } else bytes
        require(raw.size.toLong() == size) { "File size mismatch" }
        val actual = MessageDigest.getInstance("SHA-256").digest(raw)
        val expected = ByteArray(32) { hash.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        require(MessageDigest.isEqual(actual, expected)) { "File hash mismatch" }
        return raw
    }

    fun validChunk(index: Int, count: Int, bytes: Int, chunkSize: Int, limit: Int): Boolean {
        if (count !in 1..((limit.toLong() + chunkSize - 1) / chunkSize) || index !in 0 until count) return false
        return bytes in 1..chunkSize && (index == count - 1 || bytes == chunkSize) &&
            index.toLong() * chunkSize + bytes <= limit
    }
}
