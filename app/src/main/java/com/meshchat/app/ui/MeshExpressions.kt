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
    private val dynamicStickerId = Regex("[a-z][a-z0-9._-]{2,47}/[a-z0-9][a-z0-9_-]{0,47}")
    val emojiCategoryIcons = listOf("😀", "🥹", "❤️", "👋", "🐱", "🌿", "🍓", "🍕", "⚽", "🚀", "📱", "🎉")
    val emojiGroups = listOf(
        listOf("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "🥹", "😊", "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚", "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🥸", "🤩", "🥳", "🙂‍↕️", "😏", "😒", "🙂‍↔️", "😞", "😔", "😟", "😕", "🙁", "☹️", "😣", "😖", "😫", "😩", "🥺"),
        listOf("😢", "😭", "😤", "😠", "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗", "🤔", "🫣", "🤭", "🫢", "🫡", "🤫", "🫠", "🤥", "😶", "😐", "😑", "😬", "🙄", "😯", "😦", "😧", "😮", "😲", "🥱", "😴", "🤤", "😪", "😵", "😵‍💫", "🫨", "🤐", "🥴", "🤢", "🤮", "🤧", "🤒", "🤕"),
        listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "🩷", "🩵", "🩶", "💔", "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "❤️‍🔥", "❤️‍🩹", "♥️", "💋", "🫶", "🙌", "👏", "🤝", "👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "👋"),
        listOf("🤌", "🤏", "✍️", "🙏", "💪", "🦾", "🦿", "🦵", "🦶", "👂", "👃", "🧠", "🫀", "🫁", "🦷", "🦴", "👀", "👁️", "👅", "👄", "🫦", "🧑", "👨", "👩", "👶", "🧒", "👦", "👧", "🧔", "👴", "👵", "🙍", "🙎", "🙅", "🙆", "💁", "🙋", "🧏", "🙇", "🤦", "🤷", "👮", "🕵️", "👷", "🥷", "🤴", "👸", "🧙"),
        listOf("🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐻‍❄️", "🐨", "🐯", "🦁", "🐮", "🐷", "🐽", "🐸", "🐵", "🙈", "🙉", "🙊", "🐒", "🐔", "🐧", "🐦", "🐤", "🐣", "🐥", "🦆", "🦅", "🦉", "🦇", "🐺", "🐗", "🐴", "🦄", "🫎", "🐝", "🪱", "🐛", "🦋", "🐌", "🪲", "🐞", "🦗", "🪳", "🕷️", "🦂", "🐢"),
        listOf("🐍", "🦎", "🐙", "🦑", "🦐", "🦞", "🦀", "🐡", "🐠", "🐟", "🐬", "🐳", "🐋", "🦈", "🐊", "🐅", "🐆", "🦓", "🦍", "🦧", "🐘", "🦛", "🦏", "🐪", "🐫", "🦒", "🦘", "🦬", "🐃", "🐂", "🐄", "🐎", "🐖", "🐏", "🐑", "🦙", "🐐", "🦌", "🐕", "🐈", "🪶", "🌸", "🌺", "🌻", "🌹", "🌷", "🌱", "🌲"),
        listOf("🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐", "🍈", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🥑", "🫛", "🥦", "🥬", "🥒", "🌶️", "🫑", "🌽", "🥕", "🫒", "🧄", "🧅", "🥔", "🍠", "🫘", "🥐", "🥯", "🍞", "🥖", "🥨", "🧀", "🥚", "🍳", "🧈", "🥞", "🧇", "🥓", "🥩", "🍗", "🍖"),
        listOf("🌭", "🍔", "🍟", "🍕", "🫓", "🥪", "🥙", "🧆", "🌮", "🌯", "🫔", "🥗", "🥘", "🫕", "🥫", "🍝", "🍜", "🍲", "🍛", "🍣", "🍱", "🥟", "🦪", "🍤", "🍙", "🍚", "🍘", "🍥", "🥠", "🥮", "🍢", "🍡", "🍧", "🍨", "🍦", "🥧", "🧁", "🍰", "🎂", "🍮", "🍭", "🍬", "🍫", "🍿", "🍩", "🍪", "☕", "🍺"),
        listOf("⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🥏", "🎱", "🪀", "🏓", "🏸", "🏒", "🏑", "🥍", "🏏", "🪃", "🥅", "⛳", "🪁", "🏹", "🎣", "🤿", "🥊", "🥋", "🎽", "🛹", "🛼", "🛷", "⛸️", "🥌", "🎿", "⛷️", "🏂", "🪂", "🏋️", "🤸", "🤺", "⛹️", "🤾", "🏌️", "🏇", "🧘", "🏄", "🚣", "🏊", "🚴"),
        listOf("🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒", "🚐", "🛻", "🚚", "🚛", "🚜", "🏍️", "🛵", "🚲", "🛴", "🚨", "🚔", "🚍", "🚘", "🚖", "🚡", "🚠", "🚟", "🚃", "🚋", "🚞", "🚝", "🚄", "🚅", "🚈", "🚂", "🚆", "✈️", "🛫", "🛬", "🚀", "🛸", "🚁", "⛵", "🚤", "🛥️", "🛳️", "⛴️", "🚢", "⚓"),
        listOf("⌚", "📱", "💻", "⌨️", "🖥️", "🖨️", "🖱️", "🕹️", "💾", "💿", "📀", "📷", "📸", "📹", "🎥", "📽️", "🎞️", "📞", "☎️", "📟", "📠", "📺", "📻", "🎙️", "🎚️", "🎛️", "🧭", "⏱️", "⏰", "🕰️", "⌛", "⏳", "📡", "🔋", "🪫", "🔌", "💡", "🔦", "🕯️", "🧯", "🛒", "💸", "💰", "💎", "🔧", "🔨", "🪛", "🧲"),
        listOf("🎉", "🎊", "🎈", "🎁", "🎀", "🪄", "🔮", "🧿", "🪩", "🎭", "🎨", "🎬", "🎤", "🎧", "🎼", "🎹", "🥁", "🎷", "🎺", "🎸", "🎻", "🎲", "♟️", "🎯", "🎳", "🎮", "🧩", "🧸", "🪅", "🪆", "🃏", "🀄", "🔥", "✨", "⭐", "🌟", "💫", "⚡", "💥", "💯", "✅", "❌", "❗", "❓", "🚩", "🏁", "🏆", "🥇")
    )
    fun token(id: String): String {
        require(id in stickers || id in legacy || dynamicStickerId.matches(id))
        return "[[sticker:$id]]"
    }
    fun stickerId(text: String): String? = Regex("^\\[\\[sticker:([a-z0-9._/-]{1,96})\\]\\]$")
        .matchEntire(text.trim())?.groupValues?.get(1)
        ?.takeIf { it in stickers || it in legacy || dynamicStickerId.matches(it) }
    fun animatedEmoji(text: String): String? = text.trim().takeIf { value ->
        emojiGroups.any { value in it }
    }
    fun recentEmoji(previous: List<String>, selected: String, limit: Int = 32): List<String> {
        val knownPrevious = previous.filter { animatedEmoji(it) != null }
        if (animatedEmoji(selected) == null) return knownPrevious.distinct().take(limit)
        return (listOf(selected) + knownPrevious).distinct().take(limit)
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
