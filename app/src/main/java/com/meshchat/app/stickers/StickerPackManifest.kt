package com.meshchat.app.stickers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
enum class StickerPackAssetKind { LOTTIE, PNG }

@Serializable
data class StickerPackItem(
    val id: String,
    val label: String,
    val category: String,
    val kind: StickerPackAssetKind,
    val assetUrl: String,
    val previewUrl: String,
    val assetSha256: String,
    val previewSha256: String,
    val assetBytes: Long,
    val previewBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Int = 0,
    val frameRate: Int = 0
)

@Serializable
data class StickerPackManifest(
    val schemaVersion: Int = 1,
    val packId: String,
    val version: Int,
    val title: String,
    val attribution: String,
    val licenseUrl: String,
    val stickers: List<StickerPackItem>,
    val manifestSignature: String = ""
) {
    /** Length prefixes avoid delimiter ambiguity while keeping signing tools simple. */
    fun canonicalPayload(): String {
        val fields = mutableListOf(
            schemaVersion.toString(),
            packId,
            version.toString(),
            title,
            attribution,
            licenseUrl,
            stickers.size.toString()
        )
        stickers.forEach { sticker ->
            fields += listOf(
                sticker.id,
                sticker.label,
                sticker.category,
                sticker.kind.name,
                sticker.assetUrl,
                sticker.previewUrl,
                sticker.assetSha256.lowercase(),
                sticker.previewSha256.lowercase(),
                sticker.assetBytes.toString(),
                sticker.previewBytes.toString(),
                sticker.width.toString(),
                sticker.height.toString(),
                sticker.durationMs.toString(),
                sticker.frameRate.toString()
            )
        }
        return fields.joinToString(separator = "") { value ->
            val size = value.toByteArray(Charsets.UTF_8).size
            "$size:$value"
        }
    }
}

object StickerPackVerifier {
    const val MAX_MANIFEST_BYTES = 256 * 1024
    const val MAX_STICKERS_PER_PACK = 100
    const val MAX_PACK_BYTES = 24L * 1024L * 1024L
    const val MAX_LOTTIE_BYTES = 512L * 1024L
    const val MAX_IMAGE_BYTES = 2L * 1024L * 1024L
    const val MAX_PREVIEW_BYTES = 256L * 1024L

    private val json = Json { ignoreUnknownKeys = false }
    private val packIdPattern = Regex("[a-z][a-z0-9._-]{2,47}")
    private val itemIdPattern = Regex("[a-z][a-z0-9._-]{2,47}/[a-z0-9][a-z0-9_-]{0,47}")
    private val categoryPattern = Regex("[a-z][a-z0-9_-]{0,31}")
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")

    fun parse(rawJson: String): StickerPackManifest? {
        if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) return null
        return runCatching { json.decodeFromString<StickerPackManifest>(rawJson) }.getOrNull()
    }

    fun validate(manifest: StickerPackManifest, reservedIds: Set<String> = emptySet()): Boolean {
        if (manifest.schemaVersion != 1 ||
            manifest.version <= 0 ||
            !manifest.packId.matches(packIdPattern) ||
            !safeText(manifest.title, 64) ||
            !safeText(manifest.attribution, 160) ||
            !isHttpsUrl(manifest.licenseUrl) ||
            manifest.stickers.isEmpty() ||
            manifest.stickers.size > MAX_STICKERS_PER_PACK
        ) return false

        val ids = HashSet<String>(manifest.stickers.size)
        var declaredBytes = 0L
        for (sticker in manifest.stickers) {
            val maxAssetBytes = when (sticker.kind) {
                StickerPackAssetKind.LOTTIE -> MAX_LOTTIE_BYTES
                StickerPackAssetKind.PNG -> MAX_IMAGE_BYTES
            }
            if (!sticker.id.matches(itemIdPattern) ||
                !sticker.id.startsWith("${manifest.packId}/") ||
                sticker.id in reservedIds ||
                !ids.add(sticker.id) ||
                !safeText(sticker.label, 64) ||
                !sticker.category.matches(categoryPattern) ||
                !isHttpsUrl(sticker.assetUrl) ||
                !isHttpsUrl(sticker.previewUrl) ||
                !sticker.assetSha256.matches(sha256Pattern) ||
                !sticker.previewSha256.matches(sha256Pattern) ||
                sticker.assetBytes !in 1..maxAssetBytes ||
                sticker.previewBytes !in 1..MAX_PREVIEW_BYTES ||
                sticker.width !in 1..512 ||
                sticker.height !in 1..512
            ) return false

            if (sticker.kind == StickerPackAssetKind.LOTTIE) {
                if (sticker.durationMs !in 100..5_000 || sticker.frameRate !in 1..60) return false
            } else if (sticker.durationMs != 0 || sticker.frameRate != 0) {
                return false
            }

            declaredBytes = runCatching {
                Math.addExact(declaredBytes, Math.addExact(sticker.assetBytes, sticker.previewBytes))
            }.getOrElse { return false }
            if (declaredBytes > MAX_PACK_BYTES) return false
        }
        return true
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun verifySignature(manifest: StickerPackManifest, trustedPublicKeyBase64: String): Boolean {
        if (manifest.manifestSignature.isBlank() || trustedPublicKeyBase64.isBlank()) return false
        return runCatching {
            val publicKey = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.Default.decode(trustedPublicKeyBase64))
            )
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(manifest.canonicalPayload().toByteArray(Charsets.UTF_8))
            verifier.verify(Base64.Default.decode(manifest.manifestSignature))
        }.getOrDefault(false)
    }

    fun verifyFile(file: File, expectedBytes: Long, expectedSha256: String): Boolean {
        if (!file.isFile || file.length() != expectedBytes || !expectedSha256.matches(sha256Pattern)) return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(file.inputStream()).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) } == expectedSha256.lowercase()
        }.getOrDefault(false)
    }

    private fun safeText(value: String, maxLength: Int): Boolean =
        value.isNotBlank() && value.length <= maxLength && value.none { it.isISOControl() }

    private fun isHttpsUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null && uri.fragment == null && !uri.host.isNullOrBlank()
    }
}
