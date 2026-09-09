package com.meshchat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MeshExpressionPanel(stickerLabel: String, emojiLabel: String, closeLabel: String,
    onDismiss: () -> Unit, onSendSticker: (String) -> Boolean, onInsert: (String) -> Unit) {
    var stickers by rememberSaveable { mutableStateOf(true) }
    var category by rememberSaveable { mutableStateOf(0) }
    val height = (LocalConfiguration.current.screenHeightDp * .32f).coerceIn(150f, 280f).dp
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface) {
        Column(Modifier.fillMaxWidth().height(height)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (stickers) {
                    Text("MeshGram / NEON BOTS", Modifier.weight(1f).padding(start = 16.dp),
                        style = MaterialTheme.typography.labelMedium, color = colors.primary)
                } else {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf("😀", "❤️", "👋", "🐱", "☕").forEachIndexed { i, glyph ->
                            IconButton(onClick = { category = i }, modifier = Modifier.size(40.dp)
                                .background(if (category == i) colors.primary.copy(alpha = .15f) else Color.Transparent, CircleShape)) {
                                Text(glyph, fontSize = 22.sp)
                            }
                        }
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, closeLabel) }
            }
            if (stickers) {
                LazyVerticalGrid(GridCells.Adaptive(88.dp), Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
                    items(MeshExpressions.stickers, key = { it }) { id ->
                        MeshStickerArt(id, Modifier.size(92.dp).clickable { onSendSticker(id) })
                    }
                }
            } else {
                LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
                    items(MeshExpressions.emojiGroups[category]) { emoji ->
                        Box(Modifier.size(46.dp).clickable { onInsert(emoji) }, contentAlignment = Alignment.Center) {
                            MeshAnimatedEmoji(emoji, 38.dp)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { stickers = true }) { Text(stickerLabel, color = if (stickers) colors.primary else colors.onSurfaceVariant) }
                TextButton(onClick = { stickers = false }) { Text(emojiLabel, color = if (!stickers) colors.primary else colors.onSurfaceVariant) }
            }
        }
    }
}
