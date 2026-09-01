package com.meshchat.app.mesh

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SecureLocalStore(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val random = SecureRandom()

    private val rootDir: File = File(context.filesDir, "mesh_store").apply { mkdirs() }
    private val messagesFile = File(rootDir, "messages_v2.dat")
    private val groupsFile = File(rootDir, "groups_v2.dat")
    private val identitiesFile = File(rootDir, "identities_v1.dat")
    private val conversationStatesFile = File(rootDir, "conversation_states_v1.dat")
    private val relayFramesFile = File(rootDir, "relay_frames_v1.dat")
    private val outgoingTransfersFile = File(rootDir, "outgoing_transfers_v1.dat")
    private val incomingTransfersFile = File(rootDir, "incoming_transfers_v1.dat")
    private val pendingPayloadsFile = File(rootDir, "pending_payloads_v1.dat")
    private val scheduledMessagesFile = File(rootDir, "scheduled_messages_v1.dat")
    private val attachmentsDir = File(rootDir, "attachments").apply { mkdirs() }

    fun loadMessages(): List<ChatMessage> {
        val bytes = readEncrypted(messagesFile) ?: return emptyList()
        val raw = ungzip(bytes) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<ChatMessage>>(raw.toString(StandardCharsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    fun persistMessages(messages: List<ChatMessage>) {
        val payload = runCatching { json.encodeToString(messages) }.getOrNull() ?: return
        val gz = gzip(payload.toByteArray(StandardCharsets.UTF_8)) ?: return
        writeEncrypted(messagesFile, gz)
    }

    fun loadGroups(): List<MeshGroup> {
        val bytes = readEncrypted(groupsFile) ?: return emptyList()
        val raw = ungzip(bytes) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<MeshGroup>>(raw.toString(StandardCharsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    fun persistGroups(groups: List<MeshGroup>) {
        val payload = runCatching { json.encodeToString(groups) }.getOrNull() ?: return
        val gz = gzip(payload.toByteArray(StandardCharsets.UTF_8)) ?: return
        writeEncrypted(groupsFile, gz)
    }

    fun loadPeerIdentities(): List<PeerIdentity> {
        val bytes = readEncrypted(identitiesFile) ?: return emptyList()
        val raw = ungzip(bytes) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<PeerIdentity>>(raw.toString(StandardCharsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    fun persistPeerIdentities(identities: List<PeerIdentity>) {
        val payload = runCatching { json.encodeToString(identities) }.getOrNull() ?: return
        val gz = gzip(payload.toByteArray(StandardCharsets.UTF_8)) ?: return
        writeEncrypted(identitiesFile, gz)
    }

    fun loadConversationStates(): List<ConversationLocalState> {
        val bytes = readEncrypted(conversationStatesFile) ?: return emptyList()
        val raw = ungzip(bytes) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<ConversationLocalState>>(raw.toString(StandardCharsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    fun persistConversationStates(states: List<ConversationLocalState>) {
        val payload = runCatching { json.encodeToString(states) }.getOrNull() ?: return
        val gz = gzip(payload.toByteArray(StandardCharsets.UTF_8)) ?: return
        writeEncrypted(conversationStatesFile, gz)
    }

    fun loadRelayFrames(): List<RelayFrameRecord> {
        val bytes = readEncrypted(relayFramesFile) ?: return emptyList()
        val raw = ungzip(bytes) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<RelayFrameRecord>>(raw.toString(StandardCharsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    fun persistRelayFrames(frames: List<RelayFrameRecord>) {
        val payload = runCatching { json.encodeToString(frames) }.getOrNull() ?: return
        val gz = gzip(payload.toByteArray(StandardCharsets.UTF_8)) ?: return
        writeEncrypted(relayFramesFile, gz)
    }

    fun loadOutgoingTransfers(): List<OutgoingFileTransferRecord> {
        val bytes = readEncrypted(outgoingTransfersFile) ?: return emptyList()
        val raw = ungzip(bytes) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<OutgoingFileTransferRecord>>(raw.toString(StandardCharsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    fun persistOutgoingTransfers(transfers: List<OutgoingFileTransferRecord>) {
        val payload = runCatching { json.encodeToString(transfers) }.getOrNull() ?: return
        val gz = gzip(payload.toByteArray(StandardCharsets.UTF_8)) ?: return
        writeEncrypted(outgoingTransfersFile, gz)
    }

    fun loadIncomingTransfers(): List<IncomingFileTransferRecord> {
        val bytes = readEncrypted(incomingTransfersFile) ?: return emptyList()
        val raw = ungzip(bytes) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<IncomingFileTransferRecord>>(raw.toString(StandardCharsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    fun persistIncomingTransfers(transfers: List<IncomingFileTransferRecord>) {
        val payload = runCatching { json.encodeToString(transfers) }.getOrNull() ?: return
        val gz = gzip(payload.toByteArray(StandardCharsets.UTF_8)) ?: return
        writeEncrypted(incomingTransfersFile, gz)
    }

    fun loadPendingPayloads(): List<PendingPayloadRecord> {
        val bytes = readEncrypted(pendingPayloadsFile) ?: return emptyList()
        val raw = ungzip(bytes) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<PendingPayloadRecord>>(raw.toString(StandardCharsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    fun persistPendingPayloads(pendingPayloads: List<PendingPayloadRecord>) {
        val payload = runCatching { json.encodeToString(pendingPayloads) }.getOrNull() ?: return
        val gz = gzip(payload.toByteArray(StandardCharsets.UTF_8)) ?: return
        writeEncrypted(pendingPayloadsFile, gz)
    }

    fun loadScheduledMessages(): List<ScheduledMessageRecord> {
        val bytes = readEncrypted(scheduledMessagesFile) ?: return emptyList()
        val raw = ungzip(bytes) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<ScheduledMessageRecord>>(raw.toString(StandardCharsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    fun persistScheduledMessages(messages: List<ScheduledMessageRecord>) {
        val payload = runCatching { json.encodeToString(messages) }.getOrNull() ?: return
        val gz = gzip(payload.toByteArray(StandardCharsets.UTF_8)) ?: return
        writeEncrypted(scheduledMessagesFile, gz)
    }

    fun saveAttachment(
        transferId: String,
        fileName: String,
        bytes: ByteArray
    ): String? {
        val safeTransferId = sanitizePathToken(transferId) ?: return null
        val safeName = sanitizeFileName(fileName)
        val target = safeAttachmentFile("${safeTransferId}_$safeName.bin") ?: return null
        val gz = gzip(bytes) ?: return null
        val ok = writeEncrypted(target, gz)
        return if (ok) target.absolutePath else null
    }

    fun readAttachment(path: String): ByteArray? {
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (!isInsideAttachments(file)) return null
        val encrypted = readEncrypted(file) ?: return null
        return ungzip(encrypted)
    }

    fun exportPortableBackup(
        targetFile: File,
        passphrase: String
    ): Boolean {
        val encrypted = exportPortableBackupBytes(passphrase) ?: return false
        return runCatching {
            targetFile.parentFile?.mkdirs()
            targetFile.writeBytes(encrypted)
            true
        }.getOrDefault(false)
    }

    fun exportPortableBackupBytes(passphrase: String): ByteArray? {
        val backup = PortableBackup(
            createdAtMs = System.currentTimeMillis(),
            messages = loadMessages(),
            groups = loadGroups(),
            identities = loadPeerIdentities(),
            conversationStates = loadConversationStates(),
            relayFrames = loadRelayFrames(),
            outgoingTransfers = loadOutgoingTransfers(),
            pendingPayloads = loadPendingPayloads(),
            scheduledMessages = loadScheduledMessages()
        )
        val plain = runCatching { json.encodeToString(backup) }.getOrNull() ?: return null
        val compressed = gzip(plain.toByteArray(StandardCharsets.UTF_8)) ?: return null
        return encryptPortable(compressed, passphrase)
    }

    fun importPortableBackup(
        sourceFile: File,
        passphrase: String
    ): Boolean {
        if (!sourceFile.exists()) return false
        val encrypted = runCatching { sourceFile.readBytes() }.getOrNull() ?: return false
        return importPortableBackupBytes(encrypted, passphrase)
    }

    fun importPortableBackupBytes(
        encryptedBackup: ByteArray,
        passphrase: String
    ): Boolean {
        val encrypted = encryptedBackup
        val plainGz = decryptPortable(encrypted, passphrase) ?: return false
        val plain = ungzip(plainGz) ?: return false
        val backup = runCatching {
            json.decodeFromString<PortableBackup>(plain.toString(StandardCharsets.UTF_8))
        }.getOrNull() ?: return false
        persistMessages(backup.messages)
        persistGroups(backup.groups)
        persistPeerIdentities(backup.identities)
        persistConversationStates(backup.conversationStates)
        persistRelayFrames(backup.relayFrames)
        persistOutgoingTransfers(backup.outgoingTransfers)
        persistPendingPayloads(backup.pendingPayloads)
        persistScheduledMessages(backup.scheduledMessages)
        return true
    }

    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .take(64)
        return if (cleaned.isBlank()) "file" else cleaned
    }

    private fun sanitizePathToken(raw: String): String? {
        val cleaned = raw.trim()
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(96)
        return cleaned.ifBlank { null }
    }

    private fun safeAttachmentFile(name: String): File? {
        val file = runCatching { File(attachmentsDir, name).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { isInsideAttachments(it) }
    }

    private fun isInsideAttachments(file: File): Boolean {
        val root = runCatching { attachmentsDir.canonicalFile }.getOrNull() ?: return false
        val rootPath = root.path + File.separator
        return file.path.startsWith(rootPath)
    }

    private fun encryptedFileFor(file: File): EncryptedFile? {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedFile.Builder(
                context,
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
        }.getOrNull()
    }

    private fun readEncrypted(file: File): ByteArray? {
        if (!file.exists()) return null
        val encryptedFile = encryptedFileFor(file)
        val primary = if (encryptedFile != null) {
            runCatching {
                encryptedFile.openFileInput().use { it.readBytes() }
            }.getOrNull()
        } else {
            null
        }
        if (primary != null) return primary

        val raw = runCatching { file.readBytes() }.getOrNull() ?: return null
        // Never treat unreadable bytes as plaintext. A failed decrypt is a hard failure.
        return decryptAtRestFallback(raw)
    }

    private fun writeEncrypted(file: File, payload: ByteArray): Boolean {
        file.parentFile?.mkdirs()
        if (file.exists()) {
            runCatching { file.delete() }
        }
        val encryptedFile = encryptedFileFor(file)
        if (encryptedFile != null) {
            val ok = runCatching {
                encryptedFile.openFileOutput().use { it.write(payload) }
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        val fallbackBlob = encryptAtRestFallback(payload) ?: return false
        return runCatching {
            file.writeBytes(fallbackBlob)
            true
        }.getOrDefault(false)
    }

    private fun encryptAtRestFallback(plain: ByteArray): ByteArray? {
        return runCatching {
            val key = fallbackAtRestKey() ?: return@runCatching null
            val nonce = ByteArray(FALLBACK_NONCE_SIZE).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            val ciphertext = cipher.doFinal(plain)
            ByteArrayOutputStream().use { out ->
                out.write(FALLBACK_MAGIC.toByteArray(StandardCharsets.UTF_8))
                out.write(FALLBACK_VERSION)
                out.write(nonce.size)
                out.write(nonce)
                out.write(ciphertext)
                out.toByteArray()
            }
        }.getOrNull()
    }

    private fun decryptAtRestFallback(blob: ByteArray): ByteArray? {
        return runCatching {
            if (blob.size < 8) return@runCatching null
            val magic = String(blob.copyOfRange(0, 4), StandardCharsets.UTF_8)
            if (magic != FALLBACK_MAGIC) return@runCatching null
            val version = blob[4].toInt() and 0xFF
            if (version != FALLBACK_VERSION) return@runCatching null
            val nonceLen = blob[5].toInt() and 0xFF
            val nonceStart = 6
            val nonceEnd = nonceStart + nonceLen
            if (nonceLen <= 0 || nonceEnd >= blob.size) return@runCatching null

            val nonce = blob.copyOfRange(nonceStart, nonceEnd)
            val ciphertext = blob.copyOfRange(nonceEnd, blob.size)
            val key = fallbackAtRestKey() ?: return@runCatching null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.doFinal(ciphertext)
        }.getOrNull()
    }

    private fun fallbackAtRestKey(): SecretKey? {
        return runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = keyStore.getKey(FALLBACK_KEY_ALIAS, null) as? SecretKey
            if (existing != null) return@runCatching existing
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@runCatching null

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                FALLBACK_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }.getOrNull()
    }

    private fun gzip(data: ByteArray): ByteArray? {
        return runCatching {
            val out = ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(out).use { it.write(data) }
            out.toByteArray()
        }.getOrNull()
    }

    private fun ungzip(data: ByteArray): ByteArray? {
        return runCatching {
            java.util.zip.GZIPInputStream(data.inputStream()).use { it.readBytes() }
        }.getOrNull()
    }

    private fun encryptPortable(plain: ByteArray, passphrase: String): ByteArray? {
        if (passphrase.length < 8) return null
        return runCatching {
            val salt = ByteArray(PORTABLE_SALT_SIZE).also { random.nextBytes(it) }
            val nonce = ByteArray(PORTABLE_NONCE_SIZE).also { random.nextBytes(it) }
            val key = derivePortableKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            val ciphertext = cipher.doFinal(plain)

            ByteArrayOutputStream().use { out ->
                out.write(PORTABLE_MAGIC.toByteArray(StandardCharsets.UTF_8))
                out.write(PORTABLE_VERSION)
                out.write(salt.size)
                out.write(salt)
                out.write(nonce.size)
                out.write(nonce)
                out.write(ciphertext)
                out.toByteArray()
            }
        }.getOrNull()
    }

    private fun decryptPortable(blob: ByteArray, passphrase: String): ByteArray? {
        return runCatching {
            if (blob.size < 8) return@runCatching null
            val magic = String(blob.copyOfRange(0, 4), StandardCharsets.UTF_8)
            if (magic != PORTABLE_MAGIC) return@runCatching null
            val version = blob[4].toInt() and 0xFF
            if (version != PORTABLE_VERSION) return@runCatching null
            val saltLen = blob[5].toInt() and 0xFF
            val saltStart = 6
            val saltEnd = saltStart + saltLen
            if (saltEnd >= blob.size) return@runCatching null
            val nonceLen = blob[saltEnd].toInt() and 0xFF
            val nonceStart = saltEnd + 1
            val nonceEnd = nonceStart + nonceLen
            if (nonceEnd >= blob.size) return@runCatching null

            val salt = blob.copyOfRange(saltStart, saltEnd)
            val nonce = blob.copyOfRange(nonceStart, nonceEnd)
            val ciphertext = blob.copyOfRange(nonceEnd, blob.size)
            val key = derivePortableKey(passphrase, salt)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.doFinal(ciphertext)
        }.getOrNull()
    }

    private fun derivePortableKey(passphrase: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PORTABLE_PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    @Serializable
    private data class PortableBackup(
        val createdAtMs: Long,
        val messages: List<ChatMessage>,
        val groups: List<MeshGroup>,
        val identities: List<PeerIdentity> = emptyList(),
        val conversationStates: List<ConversationLocalState> = emptyList(),
        val relayFrames: List<RelayFrameRecord> = emptyList(),
        val outgoingTransfers: List<OutgoingFileTransferRecord> = emptyList(),
        val pendingPayloads: List<PendingPayloadRecord> = emptyList(),
        val scheduledMessages: List<ScheduledMessageRecord> = emptyList()
    )

    companion object {
        private const val PORTABLE_MAGIC = "MESH"
        private const val PORTABLE_VERSION = 1
        private const val PORTABLE_SALT_SIZE = 16
        private const val PORTABLE_NONCE_SIZE = 12
        private const val PORTABLE_PBKDF2_ITERATIONS = 120_000
        private const val FALLBACK_MAGIC = "MSF1"
        private const val FALLBACK_VERSION = 1
        private const val FALLBACK_NONCE_SIZE = 12
        private const val FALLBACK_KEY_ALIAS = "meshgram_at_rest_fallback_aes"
    }
}
