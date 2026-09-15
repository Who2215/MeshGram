package com.meshchat.app.stickers

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

data class StagedStickerFiles(val asset: File, val preview: File)

data class InstalledStickerPack(val manifest: StickerPackManifest, val directory: File)

/** Installs already-downloaded files without archives, preventing path traversal on extraction. */
class StickerPackInstaller(private val storageRoot: File) {
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    fun install(
        manifest: StickerPackManifest,
        stagedFiles: Map<String, StagedStickerFiles>,
        trustedPublicKeyBase64: String,
        reservedIds: Set<String> = emptySet()
    ): InstalledStickerPack? {
        if (!StickerPackVerifier.validate(manifest, reservedIds) ||
            !StickerPackVerifier.verifySignature(manifest, trustedPublicKeyBase64) ||
            stagedFiles.keys != manifest.stickers.mapTo(linkedSetOf()) { it.id }
        ) return null

        val root = storageRoot.canonicalFile
        if ((!root.exists() && !root.mkdirs()) || !root.isDirectory) return null
        val temporary = File(root, ".install-${manifest.packId}-${manifest.version}-${UUID.randomUUID()}")
        val destination = File(root, "${manifest.packId}/${manifest.version}")
        if (!isChild(root, temporary) || !isChild(root, destination) || !temporary.mkdirs()) return null

        try {
            manifest.stickers.forEach { sticker ->
                val staged = stagedFiles[sticker.id] ?: return null
                if (!StickerPackVerifier.verifyFile(staged.asset, sticker.assetBytes, sticker.assetSha256) ||
                    !StickerPackVerifier.verifyFile(staged.preview, sticker.previewBytes, sticker.previewSha256) ||
                    !validPreview(staged.preview) ||
                    !validAsset(sticker, staged.asset)
                ) return null

                val stem = sticker.id.substringAfterLast('/')
                val assetName = if (sticker.kind == StickerPackAssetKind.LOTTIE) "$stem.json" else "$stem.png"
                staged.asset.copyTo(File(temporary, assetName), overwrite = false)
                staged.preview.copyTo(File(temporary, "$stem.preview.png"), overwrite = false)
            }
            File(temporary, "manifest.json").writeText(json.encodeToString(manifest), Charsets.UTF_8)

            destination.parentFile?.mkdirs()
            if (destination.exists()) {
                val existing = readInstalledManifest(destination)
                if (existing == manifest) {
                    deleteInside(root, temporary)
                    if (!updateCurrent(root, manifest)) return null
                    return InstalledStickerPack(manifest, destination)
                }
                return null
            }
            if (!temporary.renameTo(destination)) return null
            if (!updateCurrent(root, manifest)) {
                deleteInside(root, destination)
                return null
            }
            return InstalledStickerPack(manifest, destination)
        } catch (_: Exception) {
            return null
        } finally {
            if (temporary.exists()) deleteInside(root, temporary)
        }
    }

    private fun validPreview(file: File): Boolean {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        return runCatching {
            val actual = ByteArray(signature.size)
            file.inputStream().use { input ->
                var offset = 0
                while (offset < actual.size) {
                    val count = input.read(actual, offset, actual.size - offset)
                    if (count < 0) return false
                    offset += count
                }
            }
            actual.contentEquals(signature)
        }.getOrDefault(false)
    }

    private fun validAsset(sticker: StickerPackItem, file: File): Boolean = when (sticker.kind) {
        StickerPackAssetKind.PNG -> validPreview(file)
        StickerPackAssetKind.LOTTIE -> validLottie(sticker, file)
    }

    private fun validLottie(sticker: StickerPackItem, file: File): Boolean = runCatching {
        val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        val width = root["w"]?.jsonPrimitive?.doubleOrNull ?: return false
        val height = root["h"]?.jsonPrimitive?.doubleOrNull ?: return false
        val frameRate = root["fr"]?.jsonPrimitive?.doubleOrNull ?: return false
        val firstFrame = root["ip"]?.jsonPrimitive?.doubleOrNull ?: return false
        val lastFrame = root["op"]?.jsonPrimitive?.doubleOrNull ?: return false
        val duration = ((lastFrame - firstFrame) / frameRate * 1_000.0)
        val assets = root["assets"] as? JsonArray ?: JsonArray(emptyList())
        val externalAssets = assets.any { asset ->
            (asset as? JsonObject)?.get("p")?.jsonPrimitive?.content?.isNotBlank() == true
        }
        width.toInt() == sticker.width &&
            height.toInt() == sticker.height &&
            frameRate > 0 && frameRate <= 60 &&
            kotlin.math.abs(frameRate - sticker.frameRate) < 0.01 &&
            duration in 100.0..5_000.0 &&
            kotlin.math.abs(duration - sticker.durationMs) <= 50.0 &&
            !externalAssets && jsonComplexityWithin(root)
    }.getOrDefault(false)

    private fun jsonComplexityWithin(root: JsonElement): Boolean {
        var nodes = 0
        fun visit(element: JsonElement, depth: Int): Boolean {
            if (depth > 32 || ++nodes > 20_000) return false
            return when (element) {
                is JsonObject -> element.values.all { visit(it, depth + 1) }
                is JsonArray -> element.all { visit(it, depth + 1) }
                else -> true
            }
        }
        return visit(root, 0)
    }

    private fun readInstalledManifest(directory: File): StickerPackManifest? =
        runCatching { StickerPackVerifier.parse(File(directory, "manifest.json").readText()) }.getOrNull()

    private fun updateCurrent(root: File, manifest: StickerPackManifest): Boolean {
        val pointer = File(root, "${manifest.packId}.current")
        val temporary = File(root, "${manifest.packId}.current.tmp")
        val backup = File(root, "${manifest.packId}.current.bak")
        if (temporary.exists()) temporary.delete()
        temporary.writeText(manifest.version.toString(), Charsets.US_ASCII)
        if (!pointer.exists()) return temporary.renameTo(pointer)
        if (backup.exists()) deleteInside(root, backup)
        if (!pointer.renameTo(backup)) return false
        if (!temporary.renameTo(pointer)) {
            backup.renameTo(pointer)
            return false
        }
        backup.delete()
        return true
    }

    private fun isChild(root: File, candidate: File): Boolean {
        val rootPrefix = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        val candidatePath = candidate.canonicalFile.path
        return candidatePath.startsWith(rootPrefix) && candidatePath != root.canonicalPath
    }

    private fun deleteInside(root: File, candidate: File) {
        if (isChild(root, candidate)) candidate.deleteRecursively()
    }
}
