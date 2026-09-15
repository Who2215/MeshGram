package com.meshchat.app.stickers

import android.content.Context
import com.meshchat.app.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

@Serializable
data class StickerPackIndex(
    val schemaVersion: Int = 1,
    val manifests: List<String> = emptyList()
)

data class StickerPackSyncResult(
    val installed: List<InstalledStickerPack>,
    val unchanged: Int,
    val failed: Int
)

object StickerPackIndexVerifier {
    const val MAX_INDEX_BYTES = 64 * 1024
    const val MAX_PACKS = 20
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(raw: String): StickerPackIndex? {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_INDEX_BYTES) return null
        return runCatching { json.decodeFromString<StickerPackIndex>(raw) }.getOrNull()
    }

    fun validate(index: StickerPackIndex): Boolean =
        index.schemaVersion == 1 &&
            index.manifests.size <= MAX_PACKS &&
            index.manifests.distinct().size == index.manifests.size &&
            index.manifests.all(::isHttpsUrl)

    internal fun isHttpsUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null && uri.fragment == null && !uri.host.isNullOrBlank()
    }
}

/** Synchronous by design; callers must run sync() on an IO dispatcher or background job. */
class StickerPackSyncManager(
    context: Context,
    private val client: OkHttpClient = defaultClient()
) {
    private val appContext = context.applicationContext
    private val installRoot = File(appContext.filesDir, "sticker_packs")
    private val stageRoot = File(appContext.cacheDir, "sticker_pack_stage")
    private val installer = StickerPackInstaller(installRoot)

    fun sync(
        indexUrl: String = BuildConfig.MESHGRAM_STICKER_INDEX_URL.trim(),
        trustedPublicKeyBase64: String = BuildConfig.MESHGRAM_RELEASE_PUBLIC_KEY_BASE64.trim(),
        reservedIds: Set<String> = emptySet()
    ): StickerPackSyncResult {
        if (!StickerPackIndexVerifier.isHttpsUrl(indexUrl) || trustedPublicKeyBase64.isBlank()) {
            return StickerPackSyncResult(emptyList(), unchanged = 0, failed = 0)
        }
        val indexBytes = fetch(indexUrl, StickerPackIndexVerifier.MAX_INDEX_BYTES.toLong())
            ?: return StickerPackSyncResult(emptyList(), unchanged = 0, failed = 1)
        val index = StickerPackIndexVerifier.parse(indexBytes.toString(Charsets.UTF_8))
            ?.takeIf(StickerPackIndexVerifier::validate)
            ?: return StickerPackSyncResult(emptyList(), unchanged = 0, failed = 1)

        val installed = mutableListOf<InstalledStickerPack>()
        var unchanged = 0
        var failed = 0
        index.manifests.forEach { manifestUrl ->
            val manifestBytes = fetch(manifestUrl, StickerPackVerifier.MAX_MANIFEST_BYTES.toLong())
            val manifest = manifestBytes?.toString(Charsets.UTF_8)?.let(StickerPackVerifier::parse)
            if (manifest == null ||
                !StickerPackVerifier.validate(manifest, reservedIds) ||
                !StickerPackVerifier.verifySignature(manifest, trustedPublicKeyBase64)
            ) {
                failed++
                return@forEach
            }
            if (alreadyInstalled(manifest)) {
                unchanged++
                return@forEach
            }

            val stage = File(stageRoot, "${manifest.packId}-${manifest.version}")
            if (stage.exists()) stage.deleteRecursively()
            if (!stage.mkdirs()) {
                failed++
                return@forEach
            }
            try {
                val files = linkedMapOf<String, StagedStickerFiles>()
                var downloadFailed = false
                manifest.stickers.forEach { sticker ->
                    val stem = sticker.id.substringAfterLast('/')
                    val asset = download(sticker.assetUrl, File(stage, "$stem.asset"), sticker.assetBytes)
                    val preview = download(sticker.previewUrl, File(stage, "$stem.preview"), sticker.previewBytes)
                    if (asset == null || preview == null) downloadFailed = true
                    else files[sticker.id] = StagedStickerFiles(asset, preview)
                }
                if (downloadFailed) {
                    failed++
                    return@forEach
                }
                val result = installer.install(
                    manifest,
                    files,
                    trustedPublicKeyBase64,
                    reservedIds
                )
                if (result == null) failed++ else installed += result
            } finally {
                stage.deleteRecursively()
            }
        }
        return StickerPackSyncResult(installed, unchanged, failed)
    }

    private fun alreadyInstalled(manifest: StickerPackManifest): Boolean = runCatching {
        val pointer = File(installRoot, "${manifest.packId}.current")
        val directory = File(installRoot, "${manifest.packId}/${manifest.version}")
        pointer.isFile && pointer.readText().trim() == manifest.version.toString() &&
            installer.verifyInstalled(directory, manifest)
    }.getOrDefault(false)

    private fun download(url: String, destination: File, expectedBytes: Long): File? {
        val bytes = fetch(url, expectedBytes) ?: return null
        if (bytes.size.toLong() != expectedBytes) return null
        return runCatching {
            destination.writeBytes(bytes)
            destination
        }.getOrNull()
    }

    private fun fetch(url: String, maximumBytes: Long): ByteArray? {
        if (!StickerPackIndexVerifier.isHttpsUrl(url) || maximumBytes !in 1..StickerPackVerifier.MAX_PACK_BYTES) return null
        val request = Request.Builder().url(url).get().build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || !response.request.url.isHttps) return null
                val body = response.body ?: return null
                if (body.contentLength() > maximumBytes) return null
                val source = body.source()
                val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024L).toInt())
                val buffer = okio.Buffer()
                var total = 0L
                while (true) {
                    val read = source.read(buffer, minOf(64 * 1024L, maximumBytes - total + 1))
                    if (read < 0) break
                    total += read
                    if (total > maximumBytes) return null
                    output.write(buffer.readByteArray(read))
                }
                output.toByteArray()
            }
        }.getOrNull()
    }

    companion object {
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .build()
    }
}
