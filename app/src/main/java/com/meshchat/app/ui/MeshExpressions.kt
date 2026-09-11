package com.meshchat.app.ui

/** Stable wire IDs: changing these would break already delivered stickers. */
object MeshExpressions {
    val brandedStickers = listOf("hello", "love", "laugh", "cool", "wow", "party", "sleep", "hug",
        "facepalm", "popcorn", "coffee", "dance", "cry", "rage")
    val fluentStickers = listOf("fluentlaugh", "fluentrofl", "fluentcat", "fluentheartcat",
        "fluentghost", "fluentalien", "fluentpoop", "fluentmindblown", "fluentparty",
        "fluentmelting", "fluenteyes", "fluentclown")
    val stickers = brandedStickers + fluentStickers + NotoStickerCatalog.entries.map { it.id }
    private val legacy = setOf("nebula", "orbit", "wave", "spark")
    val emojiGroups = listOf(
        listOf("😀", "😂", "🥹", "😍", "😎", "🤔", "😭", "🥳", "😅", "😴", "😡", "🤯", "😘", "🙃", "😇", "🤗"),
        listOf("❤️", "💜", "💙", "💚", "💛", "🖤", "💔", "💕", "🔥", "✨", "⭐", "💫", "💯", "🎉", "🎁", "🚀"),
        listOf("👍", "👎", "👌", "✌️", "🤞", "🤟", "🤝", "👏", "🙌", "🙏", "💪", "👋", "🫶", "🤙", "✊", "🤘"),
        listOf("🐱", "🐶", "🦊", "🐼", "🐸", "🦋", "🌸", "🌻", "🌙", "☀️", "🌈", "🌊", "🌍", "🌲", "🍀", "❄️"),
        listOf("☕", "🍕", "🍔", "🍩", "🍰", "🍎", "🍒", "🍉", "⚽", "🎮", "🎧", "🎸", "🚗", "✈️", "🏠", "📱")
    )
    fun token(id: String): String {
        require(id in stickers || id in legacy)
        return "[[sticker:$id]]"
    }
    fun stickerId(text: String): String? = Regex("^\\[\\[sticker:([a-z]+)\\]\\]$")
        .matchEntire(text.trim())?.groupValues?.get(1)?.takeIf { it in stickers || it in legacy }
    fun animatedEmoji(text: String): String? = text.trim().takeIf { value ->
        emojiGroups.any { value in it }
    }
    fun artworkId(id: String): String = when (id) {
        "nebula" -> "love"
        "orbit" -> "cool"
        "wave" -> "hello"
        "spark" -> "party"
        else -> id
    }
    fun shouldConsumeDraft(sentText: String): Boolean = stickerId(sentText) == null
}
