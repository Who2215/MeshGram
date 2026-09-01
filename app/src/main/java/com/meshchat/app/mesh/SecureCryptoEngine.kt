package com.meshchat.app.mesh

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureCryptoEngine(
    context: Context,
    private val nodeId: String
) {
    private val prefs: SharedPreferences = createSecurePrefs(context)
    private val random = SecureRandom()
    private val keyFactory: KeyFactory = KeyFactory.getInstance("EC")

    private val encryptionKeyPair: KeyPair = loadOrCreateKeyPair(
        privateKeyPref = KEY_ENCRYPTION_PRIVATE,
        publicKeyPref = KEY_ENCRYPTION_PUBLIC
    )
    private val signingKeyPair: KeyPair = loadOrCreateKeyPair(
        privateKeyPref = KEY_SIGNING_PRIVATE,
        publicKeyPref = KEY_SIGNING_PUBLIC
    )

    private var aliasCache: String = loadOrCreateAlias()
    private var avatarCache: String = prefs.getString(KEY_AVATAR_DATA, "").orEmpty()

    fun localAlias(): String = aliasCache

    fun localAvatarData(): String = avatarCache

    fun localFingerprint(): String = buildFingerprint(
        encryptionPublicKey = encodeBase64(encryptionKeyPair.public.encoded),
        signingPublicKey = encodeBase64(signingKeyPair.public.encoded)
    )

    fun localEncryptionPublicKey(): String = encodeBase64(encryptionKeyPair.public.encoded)

    fun localSigningPublicKey(): String = encodeBase64(signingKeyPair.public.encoded)

    fun signRelayChallenge(sessionId: String, challengeBase64: String): String {
        val signingPublicKey = localSigningPublicKey()
        val payload = listOf(
            "MESH_RELAY_AUTH_V1",
            sessionId,
            challengeBase64,
            nodeId,
            signingPublicKey
        ).joinToString("|")
        return encodeBase64(sign(payload.toByteArray(StandardCharsets.UTF_8)))
    }

    fun fingerprintForKeys(encryptionPublicKey: String, signingPublicKey: String): String {
        return buildFingerprint(
            encryptionPublicKey = encryptionPublicKey,
            signingPublicKey = signingPublicKey
        )
    }

    fun updateAlias(rawAlias: String): String {
        val cleaned = normalizeAlias(rawAlias)
        aliasCache = cleaned
        prefs.edit().putString(KEY_ALIAS, cleaned).apply()
        return cleaned
    }

    fun updateAvatarData(rawAvatarData: String): String {
        avatarCache = rawAvatarData.take(MAX_AVATAR_DATA_LENGTH)
        prefs.edit().putString(KEY_AVATAR_DATA, avatarCache).apply()
        return avatarCache
    }

    fun createHelloPacket(maxHops: Int): HelloPacket {
        val createdAt = System.currentTimeMillis()
        val encryptionPub = encodeBase64(encryptionKeyPair.public.encoded)
        val signingPub = encodeBase64(signingKeyPair.public.encoded)
        val fingerprint = buildFingerprint(encryptionPub, signingPub)
        val frameId = UUID.randomUUID().toString()
        val alias = aliasCache
        val avatarData = avatarCache
        val signingPayload = helloSigningPayload(
            frameId = frameId,
            originNodeId = nodeId,
            alias = alias,
            encryptionPublicKey = encryptionPub,
            signingPublicKey = signingPub,
            fingerprint = fingerprint,
            createdAtMs = createdAt,
            maxHops = maxHops,
            avatarData = avatarData
        )
        val signature = sign(signingPayload.toByteArray(StandardCharsets.UTF_8))

        return HelloPacket(
            frameId = frameId,
            originNodeId = nodeId,
            relayNodeId = nodeId,
            hops = 0,
            maxHops = maxHops,
            createdAtMs = createdAt,
            alias = alias,
            encryptionPublicKey = encryptionPub,
            signingPublicKey = signingPub,
            fingerprint = fingerprint,
            signature = encodeBase64(signature),
            avatarData = avatarData
        )
    }

    fun verifyHelloSignature(packet: HelloPacket): Boolean {
        val computedFingerprint = buildFingerprint(
            encryptionPublicKey = packet.encryptionPublicKey,
            signingPublicKey = packet.signingPublicKey
        )
        if (computedFingerprint != packet.fingerprint) return false

        val payload = helloSigningPayload(
            frameId = packet.frameId,
            originNodeId = packet.originNodeId,
            alias = packet.alias,
            encryptionPublicKey = packet.encryptionPublicKey,
            signingPublicKey = packet.signingPublicKey,
            fingerprint = packet.fingerprint,
            createdAtMs = packet.createdAtMs,
            maxHops = packet.maxHops,
            avatarData = packet.avatarData
        )

        val verifyKey = decodePublicKey(packet.signingPublicKey)
        return verify(
            publicKey = verifyKey,
            payload = payload.toByteArray(StandardCharsets.UTF_8),
            signature = decodeBase64(packet.signature)
        ) || verify(
            publicKey = verifyKey,
            payload = legacyHelloSigningPayload(packet).toByteArray(StandardCharsets.UTF_8),
            signature = decodeBase64(packet.signature)
        )
    }

    fun encryptForPeer(
        plaintext: String,
        targetNodeId: String,
        peerEncryptionPublicKey: String,
        maxHops: Int
    ): SecureMessagePacket {
        val messageId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val ephemeralKeyPair = generateEcKeyPair()
        val recipientPublicKey = decodePublicKey(peerEncryptionPublicKey)
        val sharedSecret = deriveSharedSecret(
            privateKey = ephemeralKeyPair.private,
            publicKey = recipientPublicKey
        )
        val salt = messageSalt(
            messageId = messageId,
            createdAtMs = createdAt,
            targetNodeId = targetNodeId
        )
        val encryptionKey = hkdfSha256(
            ikm = sharedSecret,
            salt = salt,
            info = HKDF_INFO_ENCRYPTION,
            size = 32
        )
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val senderAlias = localAlias()
        val senderEncryptionPublicKey = localEncryptionPublicKey()
        val senderSigningPublicKey = localSigningPublicKey()
        val senderFingerprint = fingerprintForKeys(
            encryptionPublicKey = senderEncryptionPublicKey,
            signingPublicKey = senderSigningPublicKey
        )
        val aad = messageAad(
            id = messageId,
            originNodeId = nodeId,
            targetNodeId = targetNodeId,
            createdAtMs = createdAt,
            maxHops = maxHops
        )
        val ciphertext = aesGcmEncrypt(
            key = encryptionKey,
            nonce = nonce,
            aad = aad,
            plaintext = plaintext.toByteArray(StandardCharsets.UTF_8)
        )

        val ephemeralPublicB64 = encodeBase64(ephemeralKeyPair.public.encoded)
        val nonceB64 = encodeBase64(nonce)
        val ciphertextB64 = encodeBase64(ciphertext)
        val signingPayload = messageSigningPayload(
            id = messageId,
            originNodeId = nodeId,
            senderAlias = senderAlias,
            senderEncryptionPublicKey = senderEncryptionPublicKey,
            senderSigningPublicKey = senderSigningPublicKey,
            senderFingerprint = senderFingerprint,
            targetNodeId = targetNodeId,
            createdAtMs = createdAt,
            maxHops = maxHops,
            ephemeralPublicKey = ephemeralPublicB64,
            nonce = nonceB64,
            ciphertext = ciphertextB64
        )
        val signature = sign(signingPayload.toByteArray(StandardCharsets.UTF_8))

        return SecureMessagePacket(
            id = messageId,
            originNodeId = nodeId,
            senderAlias = senderAlias,
            senderEncryptionPublicKey = senderEncryptionPublicKey,
            senderSigningPublicKey = senderSigningPublicKey,
            senderFingerprint = senderFingerprint,
            targetNodeId = targetNodeId,
            relayNodeId = nodeId,
            hops = 0,
            maxHops = maxHops,
            createdAtMs = createdAt,
            ephemeralPublicKey = ephemeralPublicB64,
            nonce = nonceB64,
            ciphertext = ciphertextB64,
            signature = encodeBase64(signature)
        )
    }

    fun verifyMessageSignature(
        packet: SecureMessagePacket,
        senderSigningPublicKey: String
    ): Boolean {
        val payload = messageSigningPayload(
            id = packet.id,
            originNodeId = packet.originNodeId,
            senderAlias = packet.senderAlias,
            senderEncryptionPublicKey = packet.senderEncryptionPublicKey,
            senderSigningPublicKey = packet.senderSigningPublicKey,
            senderFingerprint = packet.senderFingerprint,
            targetNodeId = packet.targetNodeId,
            createdAtMs = packet.createdAtMs,
            maxHops = packet.maxHops,
            ephemeralPublicKey = packet.ephemeralPublicKey,
            nonce = packet.nonce,
            ciphertext = packet.ciphertext
        )
        return verify(
            publicKey = decodePublicKey(senderSigningPublicKey),
            payload = payload.toByteArray(StandardCharsets.UTF_8),
            signature = decodeBase64(packet.signature)
        )
    }

    fun decryptIncomingMessage(packet: SecureMessagePacket): String {
        val ephemeralPublicKey = decodePublicKey(packet.ephemeralPublicKey)
        val sharedSecret = deriveSharedSecret(
            privateKey = encryptionKeyPair.private,
            publicKey = ephemeralPublicKey
        )
        val salt = messageSalt(
            messageId = packet.id,
            createdAtMs = packet.createdAtMs,
            targetNodeId = packet.targetNodeId
        )
        val decryptionKey = hkdfSha256(
            ikm = sharedSecret,
            salt = salt,
            info = HKDF_INFO_ENCRYPTION,
            size = 32
        )
        val aad = messageAad(
            id = packet.id,
            originNodeId = packet.originNodeId,
            targetNodeId = packet.targetNodeId,
            createdAtMs = packet.createdAtMs,
            maxHops = packet.maxHops
        )
        val plaintext = aesGcmDecrypt(
            key = decryptionKey,
            nonce = decodeBase64(packet.nonce),
            aad = aad,
            ciphertext = decodeBase64(packet.ciphertext)
        )
        return plaintext.toString(StandardCharsets.UTF_8)
    }

    private fun loadOrCreateAlias(): String {
        val existing = prefs.getString(KEY_ALIAS, null)?.trim()
        if (!existing.isNullOrEmpty()) return normalizeAlias(existing)

        val generated = "Node-${nodeId.take(4)}"
        check(prefs.edit().putString(KEY_ALIAS, generated).commit()) {
            "Unable to persist MeshGram identity alias"
        }
        return generated
    }

    private fun loadOrCreateKeyPair(
        privateKeyPref: String,
        publicKeyPref: String
    ): KeyPair {
        val privateB64 = prefs.getString(privateKeyPref, null)
        val publicB64 = prefs.getString(publicKeyPref, null)

        if (!privateB64.isNullOrBlank() && !publicB64.isNullOrBlank()) {
            return runCatching {
                val privateKey = keyFactory.generatePrivate(
                    PKCS8EncodedKeySpec(decodeBase64(privateB64))
                )
                val publicKey = keyFactory.generatePublic(
                    X509EncodedKeySpec(decodeBase64(publicB64))
                )
                KeyPair(publicKey, privateKey)
            }.getOrElse {
                generateAndPersistKeyPair(privateKeyPref, publicKeyPref)
            }
        }

        return generateAndPersistKeyPair(privateKeyPref, publicKeyPref)
    }

    private fun generateAndPersistKeyPair(
        privateKeyPref: String,
        publicKeyPref: String
    ): KeyPair {
        val keyPair = generateEcKeyPair()
        val persisted = prefs.edit()
            .putString(privateKeyPref, encodeBase64(keyPair.private.encoded))
            .putString(publicKeyPref, encodeBase64(keyPair.public.encoded))
            .commit()
        check(persisted) { "Unable to persist MeshGram identity key material" }
        return keyPair
    }

    private fun generateEcKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE_NAME))
        return generator.generateKeyPair()
    }

    private fun deriveSharedSecret(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return agreement.generateSecret()
    }

    private fun sign(payload: ByteArray): ByteArray {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(signingKeyPair.private, random)
        signature.update(payload)
        return signature.sign()
    }

    private fun verify(publicKey: PublicKey, payload: ByteArray, signature: ByteArray): Boolean {
        return runCatching {
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(payload)
            verifier.verify(signature)
        }.getOrDefault(false)
    }

    private fun aesGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        size: Int
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val effectiveSalt = if (salt.isNotEmpty()) salt else ByteArray(32)
        mac.init(SecretKeySpec(effectiveSalt, HMAC_ALGORITHM))
        val prk = mac.doFinal(ikm)

        val output = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (output.size() < size) {
            mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            output.write(previous)
            counter++
        }
        return output.toByteArray().copyOf(size)
    }

    private fun messageSalt(
        messageId: String,
        createdAtMs: Long,
        targetNodeId: String
    ): ByteArray {
        return "salt|$messageId|$createdAtMs|$targetNodeId"
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun messageAad(
        id: String,
        originNodeId: String,
        targetNodeId: String,
        createdAtMs: Long,
        maxHops: Int
    ): ByteArray {
        return "aad|$id|$originNodeId|$targetNodeId|$createdAtMs|$maxHops"
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun helloSigningPayload(
        frameId: String,
        originNodeId: String,
        alias: String,
        encryptionPublicKey: String,
        signingPublicKey: String,
        fingerprint: String,
        createdAtMs: Long,
        maxHops: Int,
        avatarData: String
    ): String {
        return listOf(
            frameId,
            originNodeId,
            alias,
            encryptionPublicKey,
            signingPublicKey,
            fingerprint,
            createdAtMs.toString(),
            maxHops.toString(),
            avatarData
        ).joinToString("|")
    }

    private fun legacyHelloSigningPayload(packet: HelloPacket): String {
        return listOf(
            packet.frameId,
            packet.originNodeId,
            packet.alias,
            packet.encryptionPublicKey,
            packet.signingPublicKey,
            packet.fingerprint,
            packet.createdAtMs.toString(),
            packet.maxHops.toString()
        ).joinToString("|")
    }

    private fun messageSigningPayload(
        id: String,
        originNodeId: String,
        senderAlias: String,
        senderEncryptionPublicKey: String,
        senderSigningPublicKey: String,
        senderFingerprint: String,
        targetNodeId: String,
        createdAtMs: Long,
        maxHops: Int,
        ephemeralPublicKey: String,
        nonce: String,
        ciphertext: String
    ): String {
        return listOf(
            id,
            originNodeId,
            senderAlias,
            senderEncryptionPublicKey,
            senderSigningPublicKey,
            senderFingerprint,
            targetNodeId,
            createdAtMs.toString(),
            maxHops.toString(),
            ephemeralPublicKey,
            nonce,
            ciphertext
        ).joinToString("|")
    }

    private fun buildFingerprint(
        encryptionPublicKey: String,
        signingPublicKey: String
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(
            "$encryptionPublicKey|$signingPublicKey".toByteArray(StandardCharsets.UTF_8)
        )
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun normalizeAlias(alias: String): String {
        val cleaned = alias
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_ALIAS_LENGTH)
        return if (cleaned.isBlank()) "Node-${nodeId.take(4)}" else cleaned
    }

    private fun decodePublicKey(publicKeyB64: String): PublicKey {
        return keyFactory.generatePublic(X509EncodedKeySpec(decodeBase64(publicKeyB64)))
    }

    private fun encodeBase64(value: ByteArray): String {
        return Base64.encodeToString(value, Base64.NO_WRAP)
    }

    private fun decodeBase64(value: String): ByteArray {
        return Base64.decode(value, Base64.NO_WRAP)
    }

    private fun createSecurePrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (error: Exception) {
            throw IllegalStateException(
                "Encrypted key storage is unavailable; refusing plaintext fallback",
                error
            )
        }
    }

    companion object {
        private const val PREF_NAME = "mesh_secure_prefs"
        private const val KEY_ALIAS = "alias"
        private const val KEY_AVATAR_DATA = "avatar_data"
        private const val MAX_AVATAR_DATA_LENGTH = 24_000
        private const val KEY_ENCRYPTION_PRIVATE = "enc_private"
        private const val KEY_ENCRYPTION_PUBLIC = "enc_public"
        private const val KEY_SIGNING_PRIVATE = "sign_private"
        private const val KEY_SIGNING_PUBLIC = "sign_public"

        private const val MAX_ALIAS_LENGTH = 24
        private const val CURVE_NAME = "secp256r1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val HMAC_ALGORITHM = "HmacSHA256"

        private val HKDF_INFO_ENCRYPTION = "mesh-e2e-v1".toByteArray(StandardCharsets.UTF_8)
    }
}
