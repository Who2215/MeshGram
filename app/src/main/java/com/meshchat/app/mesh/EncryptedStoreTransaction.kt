package com.meshchat.app.mesh

import java.io.OutputStream

internal fun <S : OutputStream> writeEncryptedTransaction(
    encrypt: () -> ByteArray?,
    start: () -> S,
    commit: (S) -> Unit,
    rollback: (S) -> Unit
): Boolean {
    // Encryption must succeed before the previous file is touched.
    val blob = runCatching { encrypt() }.getOrNull() ?: return false
    try {
        val stream = runCatching { start() }.getOrNull() ?: return false
        return try {
            stream.write(blob)
            stream.flush()
            commit(stream)
            true
        } catch (_: Exception) {
            runCatching { rollback(stream) }
            false
        }
    } finally {
        blob.fill(0)
    }
}
