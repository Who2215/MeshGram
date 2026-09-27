package com.meshchat.app.mesh

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class EncryptedStoreInstrumentedTest {
    private fun isolated(test: (Context, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "qa-store-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = root
        }
        try { test(context, File(root, "mesh_store")) } finally {
            check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }

    private fun message(text: String) = ChatMessage(
        id = "qa-store", text = text, originNodeId = "qa", relayNodeId = "qa",
        createdAtMs = 1L, isLocal = true
    )

    @Test fun legacyEncryptedHistorySurvivesAtomicRewrite() = isolated { context, dir ->
        val store = SecureLocalStore(context)
        val file = File(dir, "messages_v2.dat")
        val old = message("legacy")
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(Json.encodeToString(listOf(old)).toByteArray()) }
        }.toByteArray()
        val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedFile.Builder(context, file, master, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB)
            .build().openFileOutput().use { it.write(compressed) }
        assertEquals(listOf(old), store.loadMessages())
        val updated = message("atomic")
        assertTrue(store.persistMessages(listOf(old, updated.copy(id = "qa-second"))))
        assertEquals(listOf(old, updated.copy(id = "qa-second")), SecureLocalStore(context).loadMessages())
        assertEquals("MSF1", file.inputStream().use { String(ByteArray(4).also { bytes -> it.read(bytes) }) })
    }

    @Test fun interruptedReplacementRecoversPreviousHistory() = isolated { context, dir ->
        val store = SecureLocalStore(context)
        val previous = listOf(message("keep after process death"))
        assertTrue(store.persistMessages(previous))
        val atomic = AtomicFile(File(dir, "messages_v2.dat"))
        // Close without finish/fail: equivalent on-disk state to interruption mid-write.
        atomic.startWrite().use { it.write(byteArrayOf(1, 2, 3)); it.fd.sync() }
        assertEquals(previous, SecureLocalStore(context).loadMessages())
        assertTrue(store.persistMessages(previous + message("next").copy(id = "qa-next")))
        assertEquals(2, SecureLocalStore(context).loadMessages().size)
    }

    @Test fun failedCommitRestoresPreviousAttachment() = isolated { context, _ ->
        val store = SecureLocalStore(context)
        val previous = "verified attachment".toByteArray()
        val path = store.saveAttachment("qa-transfer", "qa.txt", previous)!!
        val atomic = AtomicFile(File(path))
        assertFalse(writeEncryptedTransaction(
            encrypt = { byteArrayOf(1, 2, 3) }, start = { atomic.startWrite() },
            commit = { throw IOException("Simulated sync failure") },
            rollback = { atomic.failWrite(it) }
        ))
        assertArrayEquals(previous, SecureLocalStore(context).readAttachment(path))
    }
}
