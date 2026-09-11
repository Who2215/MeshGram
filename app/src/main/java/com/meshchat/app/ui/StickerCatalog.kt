package com.meshchat.app.ui

import com.meshchat.app.R

enum class StickerKind { CANVAS, PNG, LOTTIE }
data class StickerDefinition(
    val id: String, val pack: String, val kind: StickerKind, val category: String,
    val asset: String? = null, val preview: Int? = null, val attribution: String
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
    fun find(id: String): StickerDefinition? = byId[MeshExpressions.artworkId(id)]
    fun recent(previous: List<String>, sent: String): List<String> =
        (listOf(sent) + previous).distinct().filter { find(it) != null }.take(24)
}
