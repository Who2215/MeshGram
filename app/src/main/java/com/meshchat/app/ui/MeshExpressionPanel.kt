package com.meshchat.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshchat.app.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MeshExpressionPanel(stickerLabel: String, emojiLabel: String, closeLabel: String,
    onDismiss: () -> Unit, onSendSticker: (String) -> Boolean, onInsert: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("sticker_picker", 0)
    val catalogEntries = remember(context.applicationContext) { StickerCatalog.entries(context) }
    var stickers by rememberSaveable { mutableStateOf(true) }
    var emojiCategory by rememberSaveable { mutableIntStateOf(1) }
    var pack by rememberSaveable { mutableStateOf(prefs.getString("pack", "noto") ?: "noto") }
    var category by rememberSaveable { mutableStateOf("all") }
    var preview by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf(prefs.getString("recent", "").orEmpty().split(',').filter { StickerCatalog.find(context, it) != null }) }
    var recentEmoji by remember {
        mutableStateOf(
            prefs.getString("recent_emoji", "").orEmpty().split('|')
                .filter { emoji -> MeshExpressions.animatedEmoji(emoji) != null }
        )
    }
    val height = (LocalConfiguration.current.screenHeightDp * .4f).coerceIn(180f, 340f).dp
    val colors = MaterialTheme.colorScheme
    val localizedPackLabels = mapOf(
        "neon" to stringResource(R.string.sticker_pack_neon),
        "noto" to stringResource(R.string.sticker_pack_noto),
        "fluent" to stringResource(R.string.sticker_pack_fluent),
        "noto.reactions" to stringResource(R.string.sticker_pack_noto_reactions),
        "noto.animals" to stringResource(R.string.sticker_pack_noto_animals)
    )
    val dynamicPacks = catalogEntries.filter { it.localAsset != null }
        .distinctBy { it.pack }
        .map { it.pack to (localizedPackLabels[it.pack] ?: it.packTitle ?: it.pack) }
    val packs = listOf(
        "recent" to stringResource(R.string.stickers_recent),
        "neon" to localizedPackLabels.getValue("neon"),
        "noto" to localizedPackLabels.getValue("noto"),
        "fluent" to localizedPackLabels.getValue("fluent")
    ) + dynamicPacks
    val categoryLabels = mapOf(
        "faces" to stringResource(R.string.stickers_faces),
        "reactions" to stringResource(R.string.stickers_faces),
        "animals" to stringResource(R.string.stickers_animals),
        "fun" to stringResource(R.string.stickers_fun)
    )
    val selectedCategories = catalogEntries.asSequence().filter { it.pack == pack }
        .map { it.category }.filter { it != "all" }.distinct().toList()
    val categories = listOf("all" to stringResource(R.string.stickers_all)) +
        selectedCategories.map { it to (categoryLabels[it] ?: it.replace('_', ' ')) }
    fun send(id: String) {
        if (onSendSticker(id)) {
            recent = StickerCatalog.recent(context, recent, id)
            prefs.edit().putString("recent", recent.joinToString(",")).apply()
        }
    }
    Surface(color = colors.surface) {
        Column(Modifier.fillMaxWidth().height(height)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (stickers) items(packs) { (key, label) ->
                        FilterChip(
                            selected = pack == key,
                            onClick = {
                                pack = key
                                category = "all"
                                prefs.edit().putString("pack", key).apply()
                            },
                            label = { Text(label, maxLines = 1) }
                        )
                    } else items((listOf("🕘") + MeshExpressions.emojiCategoryIcons).withIndex().toList()) { (i, glyph) ->
                        TextButton(onClick = { emojiCategory = i }) { Text(glyph, fontSize = 22.sp) }
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, closeLabel) }
            }
            if (stickers && selectedCategories.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) { items(categories) { (key, label) ->
                    FilterChip(
                        selected = category == key,
                        onClick = { category = key },
                        label = { Text(label, maxLines = 1) }
                    )
                } }
            }
            if (stickers) {
                val entries = if (pack == "recent") recent.mapNotNull { StickerCatalog.find(context, it) }
                    else catalogEntries.filter { it.pack == pack && (category == "all" || it.category == category) }
                if (entries.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.stickers_empty), color = colors.onSurfaceVariant)
                } else key(pack, category) {
                    val gridState = rememberLazyGridState()
                    val isScrolling by remember { derivedStateOf { gridState.isScrollInProgress } }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(80.dp),
                        modifier = Modifier.weight(1f),
                        state = gridState,
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            val accessibleName = entry.id.substringAfterLast('/')
                                .replace('_', ' ')
                                .replace('-', ' ')
                            MeshStickerArt(entry.id, Modifier.size(88.dp).padding(4.dp)
                                .semantics { contentDescription = "$stickerLabel: $accessibleName" }
                                .combinedClickable(
                                    onClick = { send(entry.id) },
                                    onLongClick = { preview = entry.id }
                                ),
                                animated = preview == null && !isScrolling, loop = true, replayOnTap = false)
                        }
                    }
                }
            } else {
                val emojiEntries = if (emojiCategory == 0) {
                    recentEmoji
                } else {
                    MeshExpressions.emojiGroups.getOrElse(emojiCategory - 1) {
                        MeshExpressions.emojiGroups.first()
                    }
                }
                if (emojiEntries.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.emoji_recent_empty), color = colors.onSurfaceVariant)
                    }
                } else {
                    val emojiGridState = rememberLazyGridState()
                    val isScrolling by remember { derivedStateOf { emojiGridState.isScrollInProgress } }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(44.dp),
                        modifier = Modifier.weight(1f),
                        state = emojiGridState,
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        items(emojiEntries, key = { it }) { emoji ->
                            Box(
                                Modifier.size(46.dp)
                                    .semantics { contentDescription = "$emojiLabel: $emoji" }
                                    .clickable {
                                        recentEmoji = MeshExpressions.recentEmoji(recentEmoji, emoji)
                                        prefs.edit().putString("recent_emoji", recentEmoji.joinToString("|")).apply()
                                        onInsert(emoji)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                MeshAnimatedEmoji(emoji, 38.dp, animated = !isScrolling)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { stickers = true }) { Text(stringResource(R.string.stickers_tab), color = if (stickers) colors.primary else colors.onSurfaceVariant) }
                TextButton(onClick = { stickers = false }) { Text(emojiLabel, color = if (!stickers) colors.primary else colors.onSurfaceVariant) }
            }
        }
    }
    preview?.let { id ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(stringResource(R.string.stickers_preview)) },
            text = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MeshStickerArt(id, Modifier.fillMaxWidth().heightIn(max = 240.dp).aspectRatio(1f), loop = true)
                val entry = StickerCatalog.find(context, id)
                Text(entry?.attribution.orEmpty(), style = MaterialTheme.typography.labelSmall)
                if (entry?.pack == "noto") Text("googlefonts.github.io/noto-emoji-animation\ncreativecommons.org/licenses/by/4.0", style = MaterialTheme.typography.labelSmall)
                else entry?.licenseUrl?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            } },
            confirmButton = { TextButton(onClick = { send(id); preview = null }) { Text(stringResource(R.string.expression_send)) } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(closeLabel) } })
    }
}
