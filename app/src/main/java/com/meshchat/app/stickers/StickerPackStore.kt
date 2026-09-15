package com.meshchat.app.stickers

import java.io.File

/** Reads only fully committed, signed pack versions from app-private storage. */
class StickerPackStore(private val storageRoot: File) {
    fun load(
        trustedPublicKeyBase64: String,
        reservedIds: Set<String> = emptySet()
    ): List<InstalledStickerPack> {
        if (trustedPublicKeyBase64.isBlank() || !storageRoot.isDirectory) return emptyList()
        val root = runCatching { storageRoot.canonicalFile }.getOrNull() ?: return emptyList()
        return root.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(CURRENT_SUFFIX) && it.length() in 1..12 }
            .take(MAX_CURRENT_PACKS)
            .mapNotNull { pointer -> loadCurrent(root, pointer, trustedPublicKeyBase64, reservedIds) }
            .sortedBy { it.manifest.packId }
            .toList()
    }

    private fun loadCurrent(
        root: File,
        pointer: File,
        trustedPublicKeyBase64: String,
        reservedIds: Set<String>
    ): InstalledStickerPack? = runCatching {
        if (!isChild(root, pointer.canonicalFile)) return null
        val packId = pointer.name.removeSuffix(CURRENT_SUFFIX)
        val version = pointer.readText(Charsets.US_ASCII).trim().toIntOrNull()?.takeIf { it > 0 }
            ?: return null
        val directory = File(root, "$packId/$version").canonicalFile
        if (!isChild(root, directory) || !directory.isDirectory) return null

        val manifestFile = File(directory, "manifest.json")
        if (!manifestFile.isFile ||
            manifestFile.length() !in 1..StickerPackVerifier.MAX_MANIFEST_BYTES.toLong()
        ) return null
        val manifest = StickerPackVerifier.parse(manifestFile.readText(Charsets.UTF_8)) ?: return null
        if (manifest.packId != packId || manifest.version != version ||
            !StickerPackVerifier.validate(manifest, reservedIds) ||
            !StickerPackVerifier.verifySignature(manifest, trustedPublicKeyBase64) ||
            !hasDeclaredFiles(directory, manifest)
        ) return null
        InstalledStickerPack(manifest, directory)
    }.getOrNull()

    private fun hasDeclaredFiles(directory: File, manifest: StickerPackManifest): Boolean =
        manifest.stickers.all { sticker ->
            val stem = sticker.id.substringAfterLast('/')
            val assetName = if (sticker.kind == StickerPackAssetKind.LOTTIE) "$stem.json" else "$stem.png"
            val asset = File(directory, assetName)
            val preview = File(directory, "$stem.preview.png")
            asset.isFile && asset.length() == sticker.assetBytes &&
                preview.isFile && preview.length() == sticker.previewBytes
        }

    private fun isChild(root: File, candidate: File): Boolean {
        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
        return candidate.path.startsWith(rootPrefix) && candidate.path != root.path
    }

    companion object {
        private const val CURRENT_SUFFIX = ".current"
        private const val MAX_CURRENT_PACKS = 20
    }
}
