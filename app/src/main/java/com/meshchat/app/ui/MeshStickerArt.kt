package com.meshchat.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

/** Original MeshGram mascot, drawn on transparent canvas, without a tile or bubble. */
@Composable
fun MeshStickerArt(id: String, modifier: Modifier = Modifier, animated: Boolean = true) {
    val art = MeshExpressions.artworkId(id)
    val transition = rememberInfiniteTransition(label = "mesh-mascot")
    val phase by transition.animateFloat(0f, 6.283185f,
        infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "mascot-motion")
    val t = if (animated) phase else 0f
    val aqua = Color(0xFF65F5E3)
    val violet = Color(0xFF9C84FF)
    Canvas(modifier.semantics { contentDescription = "MeshGram $art" }) {
        val unit = size.minDimension / 200f
        translate((size.width - 200 * unit) / 2, (size.height - 200 * unit) / 2) {
            scale(unit, unit, Offset.Zero) {
                translate(0f, sin(t) * 4f) {
                    fun line(a: Offset, b: Offset, color: Color, width: Float = 4f) =
                        drawLine(color, a, b, width, StrokeCap.Round)
                    fun heart(x: Float, y: Float, s: Float, color: Color) {
                        val p = Path().apply {
                            moveTo(x, y + s)
                            cubicTo(x - s * 1.9f, y, x - s, y - s, x, y - s * .25f)
                            cubicTo(x + s, y - s, x + s * 1.9f, y, x, y + s)
                        }
                        drawPath(p, color)
                    }
                    // Antenna and mesh-node emblem tie the character to the app identity.
                    line(Offset(100f, 42f), Offset(100f, 24f), aqua)
                    drawCircle(aqua.copy(alpha = .15f), 12f, Offset(100f, 20f))
                    drawCircle(aqua, 5f, Offset(100f, 20f))
                    val handY = if (art == "hello") 66f + sin(t * 2) * 10 else 127f
                    line(Offset(52f, 125f), Offset(25f, handY), aqua, 12f)
                    line(Offset(148f, 125f), Offset(175f, if (art == "hug") 108f else 125f), violet, 12f)
                    drawCircle(aqua, 8f, Offset(25f, handY))
                    drawCircle(violet, 8f, Offset(175f, if (art == "hug") 108f else 125f))
                    drawRoundRect(Brush.linearGradient(listOf(aqua, Color(0xFF378EBA), violet)),
                        Offset(54f, 105f), Size(92f, 66f), CornerRadius(25f))
                    drawRoundRect(Brush.linearGradient(listOf(Color(0xFFE2FFF9), aqua, violet)),
                        Offset(37f, 39f), Size(126f, 88f), CornerRadius(31f))
                    drawRoundRect(Color(0xFF102438), Offset(46f, 49f), Size(108f, 65f), CornerRadius(24f))
                    when (art) {
                        "love" -> { heart(76f, 78f, 10f, Color(0xFFFF83B9)); heart(124f, 78f, 10f, Color(0xFFFF83B9)) }
                        "cool" -> {
                            drawRoundRect(Color(0xFF050D1B), Offset(53f, 66f), Size(40f, 25f), CornerRadius(7f))
                            drawRoundRect(Color(0xFF050D1B), Offset(107f, 66f), Size(40f, 25f), CornerRadius(7f))
                            line(Offset(90f, 73f), Offset(111f, 73f), aqua)
                            line(Offset(60f, 70f), Offset(73f, 70f), aqua, 2f)
                            line(Offset(115f, 70f), Offset(128f, 70f), aqua, 2f)
                        }
                        "sleep", "laugh" -> for (x in listOf(76f, 124f)) {
                            drawArc(aqua, if (art == "laugh") 190f else 10f, 160f, false,
                                Offset(x - 10, 71f), Size(20f, 15f), style = Stroke(4f, cap = StrokeCap.Round))
                        }
                        else -> for (x in listOf(76f, 124f)) {
                            val blink = if (sin(t) > .985f) 2f else 13f
                            drawRoundRect(aqua, Offset(x - 6, 78 - blink / 2), Size(12f, blink), CornerRadius(6f))
                        }
                    }
                    if (art == "wow" || art == "laugh") {
                        drawOval(if (art == "laugh") Color(0xFFFF86BB) else aqua, Offset(91f, 91f), Size(18f, 16f))
                    } else {
                        drawArc(aqua, 10f, 160f, false, Offset(89f, 89f), Size(22f, 13f), style = Stroke(3f, cap = StrokeCap.Round))
                    }
                    drawCircle(Color.White, 4f, Offset(100f, 145f))
                    for (p in listOf(Offset(85f, 136f), Offset(115f, 136f), Offset(85f, 154f), Offset(115f, 154f))) {
                        line(Offset(100f, 145f), p, Color.White.copy(alpha = .85f), 1.6f)
                        drawCircle(Color.White, 2.5f, p)
                    }
                    drawRoundRect(aqua, Offset(61f, 165f), Size(29f, 13f), CornerRadius(6f))
                    drawRoundRect(violet, Offset(110f, 165f), Size(29f, 13f), CornerRadius(6f))
                    when (art) {
                        "love", "hug" -> heart(158f, 34f + sin(t) * 5, 13f, Color(0xFFFF83B9))
                        "party" -> {
                            val hat = Path().apply { moveTo(68f, 45f); lineTo(87f, 6f); lineTo(114f, 41f); close() }
                            drawPath(hat, Color(0xFFFFC66E))
                            for (i in 0..5) {
                                val x = 20f + i * 31
                                val y = 23f + (i % 3) * 12 + sin(t + i) * 6
                                line(Offset(x, y), Offset(x + 4, y + 6), if (i % 2 == 0) aqua else violet, 3f)
                            }
                        }
                        "sleep" -> for (i in 0..2) drawCircle(aqua.copy(alpha = .7f - i * .15f), 3f + i,
                            Offset(164f + i * 8, 62f - i * 15 + sin(t) * 4))
                        "laugh" -> for (x in listOf(43f, 157f)) drawOval(Color(0xFF68CFFF), Offset(x, 86f + sin(t * 2) * 3), Size(9f, 18f))
                        else -> {
                            line(Offset(172f, 43f), Offset(172f, 57f), aqua, 2f)
                            line(Offset(165f, 50f), Offset(179f, 50f), aqua, 2f)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MeshAnimatedEmoji(emoji: String, size: Dp = 88.dp) {
    val transition = rememberInfiniteTransition(label = "emoji")
    val pulse by transition.animateFloat(.95f, 1.05f,
        infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "emoji-pulse")
    Box(Modifier.size(size).graphicsLayer { scaleX = pulse; scaleY = pulse }, contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = (size.value * .7f).sp)
    }
}
