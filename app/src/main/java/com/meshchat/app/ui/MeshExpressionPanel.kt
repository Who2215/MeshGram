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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshchat.app.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MeshExpressionPanel(stickerLabel: String, emojiLabel: String, closeLabel: String,
    onDismiss: () -> Unit, onSendSticker: (String) -> Boolean, onInsert: (String) -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("sticker_picker", 0)
    var stickers by rememberSaveable { mutableStateOf(true) }
    var emojiCategory by rememberSaveable { mutableIntStateOf(0) }
    var pack by rememberSaveable { mutableStateOf(prefs.getString("pack", "noto") ?: "noto") }
    var category by rememberSaveable { mutableStateOf("all") }
    var preview by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf(prefs.getString("recent", "").orEmpty().split(',').filter { StickerCatalog.find(it) != null }) }
    val height = (LocalConfiguration.current.screenHeightDp * .4f).coerceIn(180f, 340f).dp
    val colors = MaterialTheme.colorScheme
    val packs = listOf("recent" to stringResource(R.string.stickers_recent), "neon" to "NEON BOTS >",
        "noto" to "Noto >", "fluent" to "Fluent 3D")
    val categories = listOf("all" to stringResource(R.string.stickers_all), "faces" to stringResource(R.string.stickers_faces),
        "animals" to stringResource(R.string.stickers_animals), "fun" to stringResource(R.string.stickers_fun))
    fun send(id: String) {
        if (onSendSticker(id)) {
            recent = StickerCatalog.recent(recent, id)
            prefs.edit().putString("recent", recent.joinToString(",")).apply()
        }
    }
    Surface(color = colors.surface) {
        Column(Modifier.fillMaxWidth().height(height)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f)) {
                    if (stickers) items(packs) { (key, label) ->
                        TextButton(onClick = { pack = key; category = "all"; prefs.edit().putString("pack", key).apply() }) {
                            Text(label, color = if (pack == key) colors.primary else colors.onSurfaceVariant)
                        }
                    } else items(listOf("😀", "❤️", "👋", "🐱", "☕").withIndex().toList()) { (i, glyph) ->
                        TextButton(onClick = { emojiCategory = i }) { Text(glyph, fontSize = 22.sp) }
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, closeLabel) }
            }
            if (stickers && pack == "noto") {
                LazyRow { items(categories) { (key, label) ->
                    TextButton(onClick = { category = key }) {
                        Text(label, color = if (category == key) colors.primary else colors.onSurfaceVariant)
                    }
                } }
            }
            if (stickers) {
                val entries = if (pack == "recent") recent.mapNotNull(StickerCatalog::find)
                    else StickerCatalog.entries.filter { it.pack == pack && (category == "all" || it.category == category) }
                if (entries.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.stickers_empty), color = colors.onSurfaceVariant)
                } else key(pack, category) {
                    LazyVerticalGrid(GridCells.Adaptive(80.dp), Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
                        items(entries, key = { it.id }) { entry ->
                            MeshStickerArt(entry.id, Modifier.size(88.dp).padding(4.dp).combinedClickable(
                                onClick = { send(entry.id) }, onLongClick = { preview = entry.id }),
                                animated = preview == null, loop = true, replayOnTap = false)
                        }
                    }
                }
            } else {
                LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
                    items(MeshExpressions.emojiGroups[emojiCategory]) { emoji ->
                        Box(Modifier.size(46.dp).clickable { onInsert(emoji) }, contentAlignment = Alignment.Center) {
                            MeshAnimatedEmoji(emoji, 38.dp)
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
                Text(StickerCatalog.find(id)?.attribution.orEmpty(), style = MaterialTheme.typography.labelSmall)
                if (StickerCatalog.find(id)?.pack == "noto") Text("googlefonts.github.io/noto-emoji-animation\ncreativecommons.org/licenses/by/4.0", style = MaterialTheme.typography.labelSmall)
            } },
            confirmButton = { TextButton(onClick = { send(id); preview = null }) { Text(stringResource(R.string.expression_send)) } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(closeLabel) } })
    }
}
