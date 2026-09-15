package com.meshchat.app.ui

import android.content.Context
import com.meshchat.app.BuildConfig
import com.meshchat.app.R
import com.meshchat.app.stickers.StickerPackAssetKind
import com.meshchat.app.stickers.StickerPackStore
import java.io.File

enum class StickerKind { CANVAS, PNG, LOTTIE }
data class StickerDefinition(
    val id: String, val pack: String, val kind: StickerKind, val category: String,
    val asset: String? = null, val preview: Int? = null, val attribution: String,
    val localAsset: File? = null, val localPreview: File? = null,
    val packTitle: String? = null, val licenseUrl: String? = null
)

object StickerCatalog {
    private val fluentPreviews = listOf(R.drawable.fluentlaugh, R.drawable.fluentrofl,
        R.drawable.fluentcat, R.drawable.fluentheartcat, R.drawable.fluentghost,
        R.drawable.fluentalien, R.drawable.fluentpoop, R.drawable.fluentmindblown,
        R.drawable.fluentparty, R.drawable.fluentmelting, R.drawable.fluenteyes, R.drawable.fluentclown)
    val entries: List<StickerDefinition> by lazy {
        MeshExpressions.brandedStickers.map { StickerDefinition(it, "neon", StickerKind.CANVAS, "all", attribution = "MeshGram") } +
            MeshExpressions.fluentStickers.mapIndexed { i, id -> StickerDefinition(id, "fluent", StickerKind.PNG, "all",
                preview = fluentPreviews[i], attribution = "Microsoft / MIT") } + NotoStickerCatalog.entries
    }
    private val byId by lazy { entries.associateBy { it.id } }
    @Volatile private var installedEntries: List<StickerDefinition> = emptyList()
    @Volatile private var installedById: Map<String, StickerDefinition> = emptyMap()
    @Volatile private var installedLoaded = false

    fun find(id: String): StickerDefinition? = byId[MeshExpressions.artworkId(id)]
    fun entries(context: Context): List<StickerDefinition> {
        ensureInstalledLoaded(context)
        return entries + installedEntries
    }
    fun find(context: Context, id: String): StickerDefinition? {
        find(id)?.let { return it }
        ensureInstalledLoaded(context)
        return installedById[id]
    }
    @Synchronized
    fun refreshInstalled(context: Context) {
        val root = File(context.applicationContext.filesDir, "sticker_packs")
        val loaded = StickerPackStore(root).load(
            BuildConfig.MESHGRAM_RELEASE_PUBLIC_KEY_BASE64.trim(),
            MeshExpressions.stickers.toSet()
        ).flatMap { pack ->
            pack.manifest.stickers.map { sticker ->
                val stem = sticker.id.substringAfterLast('/')
                val assetName = if (sticker.kind == StickerPackAssetKind.LOTTIE) "$stem.json" else "$stem.png"
                StickerDefinition(
                    id = sticker.id,
                    pack = pack.manifest.packId,
                    kind = if (sticker.kind == StickerPackAssetKind.LOTTIE) StickerKind.LOTTIE else StickerKind.PNG,
                    category = sticker.category,
                    attribution = pack.manifest.attribution,
                    localAsset = File(pack.directory, assetName),
                    localPreview = File(pack.directory, "$stem.preview.png"),
                    packTitle = pack.manifest.title,
                    licenseUrl = pack.manifest.licenseUrl
                )
            }
        }
        installedEntries = loaded
        installedById = loaded.associateBy { it.id }
        installedLoaded = true
    }
    private fun ensureInstalledLoaded(context: Context) {
        if (!installedLoaded) refreshInstalled(context)
    }
    fun recent(previous: List<String>, sent: String): List<String> =
        (listOf(sent) + previous).distinct().filter { find(it) != null }.take(24)
    fun recent(context: Context, previous: List<String>, sent: String): List<String> =
        (listOf(sent) + previous).distinct().filter { find(context, it) != null }.take(24)
}
