package com.meshchat.app.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

internal enum class MeshBackgroundStyle {
    STARFIELD,
    NEBULA,
    AURORA,
    TIDAL,
    EMBER,
    MOONLIT,
    RAIN_WINDOW
}

internal data class MeshAmbientPalette(
    val backgroundStart: Color,
    val backgroundEnd: Color,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val style: MeshBackgroundStyle = MeshBackgroundStyle.STARFIELD
)

internal fun ambientPaletteFromTheme(
    backgroundStart: Color,
    backgroundEnd: Color,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    style: MeshBackgroundStyle = MeshBackgroundStyle.STARFIELD
): MeshAmbientPalette {
    return MeshAmbientPalette(
        backgroundStart = backgroundStart,
        backgroundEnd = backgroundEnd,
        primary = primary,
        secondary = secondary,
        tertiary = tertiary,
        style = style
    )
}

internal enum class MeshRenderQuality(
    val blobCount: Int,
    val pulseCount: Int,
    val starCount: Int
) {
    FULL(blobCount = 3, pulseCount = 6, starCount = 56),
    REDUCED(blobCount = 2, pulseCount = 3, starCount = 24)
}

internal fun adaptiveMeshRenderQuality(
    sdkInt: Int,
    isLowRamDevice: Boolean,
    isPowerSaveMode: Boolean,
    shortestWidthDp: Int = Int.MAX_VALUE
): MeshRenderQuality {
    return if (
        sdkInt < Build.VERSION_CODES.O ||
            isLowRamDevice ||
            isPowerSaveMode ||
            shortestWidthDp in 1..411
    ) {
        MeshRenderQuality.REDUCED
    } else {
        MeshRenderQuality.FULL
    }
}

@Composable
internal fun rememberMeshRenderQuality(): MeshRenderQuality {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val shortestWidthDp = min(configuration.screenWidthDp, configuration.screenHeightDp)
    return remember(context, shortestWidthDp) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        adaptiveMeshRenderQuality(
            sdkInt = Build.VERSION.SDK_INT,
            isLowRamDevice = activityManager?.isLowRamDevice == true,
            isPowerSaveMode = powerManager?.isPowerSaveMode == true,
            shortestWidthDp = shortestWidthDp
        )
    }
}

private data class NormalizedPoint(
    val x: Float,
    val y: Float
)

private data class MeshEdge(
    val from: Int,
    val to: Int,
    val phaseOffset: Float
)

private data class MeshGraph(
    val points: List<NormalizedPoint>,
    val edges: List<MeshEdge>,
    val ringNodes: List<Int>
)

private data class AmbientParticle(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
    val driftX: Float,
    val driftY: Float,
    val twinkleSpeed: Float,
    val phase: Float
)

private val FullMeshGraph = MeshGraph(
    points = listOf(
        NormalizedPoint(0.50f, 0.49f),
        NormalizedPoint(0.10f, 0.15f),
        NormalizedPoint(0.38f, 0.10f),
        NormalizedPoint(0.87f, 0.20f),
        NormalizedPoint(0.17f, 0.49f),
        NormalizedPoint(0.82f, 0.47f),
        NormalizedPoint(0.14f, 0.84f),
        NormalizedPoint(0.51f, 0.82f),
        NormalizedPoint(0.89f, 0.86f)
    ),
    edges = listOf(
        MeshEdge(0, 1, 0.04f),
        MeshEdge(0, 2, 0.21f),
        MeshEdge(0, 3, 0.39f),
        MeshEdge(0, 4, 0.56f),
        MeshEdge(0, 5, 0.73f),
        MeshEdge(0, 6, 0.88f),
        MeshEdge(0, 7, 0.16f),
        MeshEdge(0, 8, 0.48f),
        MeshEdge(1, 2, 0.64f),
        MeshEdge(3, 5, 0.32f)
    ),
    ringNodes = listOf(0, 2, 5, 7)
)

private val ReducedMeshGraph = MeshGraph(
    points = listOf(
        NormalizedPoint(0.50f, 0.49f),
        NormalizedPoint(0.12f, 0.17f),
        NormalizedPoint(0.38f, 0.11f),
        NormalizedPoint(0.86f, 0.22f),
        NormalizedPoint(0.16f, 0.82f),
        NormalizedPoint(0.87f, 0.83f)
    ),
    edges = listOf(
        MeshEdge(0, 1, 0.08f),
        MeshEdge(0, 2, 0.31f),
        MeshEdge(0, 3, 0.54f),
        MeshEdge(0, 4, 0.77f),
        MeshEdge(0, 5, 0.18f),
        MeshEdge(1, 2, 0.63f)
    ),
    ringNodes = listOf(0, 2, 4)
)

private val FullParticleField = createParticleField(count = MeshRenderQuality.FULL.starCount, seed = 0x51A7)
private val ReducedParticleField = createParticleField(count = MeshRenderQuality.REDUCED.starCount, seed = 0x51A7)

private fun createParticleField(count: Int, seed: Int): List<AmbientParticle> {
    val random = Random(seed)
    return List(count) {
        AmbientParticle(
            x = random.nextFloat(),
            y = random.nextFloat(),
            radius = 0.7f + random.nextFloat() * 1.8f,
            alpha = 0.25f + random.nextFloat() * 0.60f,
            driftX = -0.0012f + random.nextFloat() * 0.0024f,
            driftY = -0.0010f + random.nextFloat() * 0.0020f,
            twinkleSpeed = 0.14f + random.nextFloat() * 0.42f,
            phase = random.nextFloat() * TWO_PI
        )
    }
}

@Composable
internal fun LiveMeshBackground(
    modifier: Modifier = Modifier,
    palette: MeshAmbientPalette,
    quality: MeshRenderQuality,
    motionScale: Float = 1f
) {
    // A real frame clock avoids Android animator-scale settings freezing the scene.
    val elapsedNanosState = remember { mutableLongStateOf(0L) }
    val animationReady = remember { mutableStateOf(false) }
    LaunchedEffect(quality, motionScale) {
        animationReady.value = false
        if (motionScale <= 0f) {
            elapsedNanosState.longValue = 0L
            return@LaunchedEffect
        }
        // Let the first lightweight gradient frame reach the window before compiling shaders.
        delay(180)
        animationReady.value = true
        var startNanos = 0L
        var lastFrame = 0L
        val frameInterval = if (quality == MeshRenderQuality.FULL) {
            FULL_FRAME_INTERVAL_NANOS
        } else {
            REDUCED_FRAME_INTERVAL_NANOS
        }
        while (true) {
            withFrameNanos { now ->
                if (startNanos == 0L) startNanos = now
                if (now - lastFrame >= frameInterval) {
                    elapsedNanosState.longValue = now - startNanos
                    lastFrame = now
                }
            }
        }
    }

    val elapsedNanos = elapsedNanosState.longValue % TIME_WRAP_NANOS
    val elapsedSeconds = elapsedNanos.toFloat() / NANOS_PER_SECOND.toFloat() * motionScale.coerceIn(0f, 1.5f)
    val graph = remember(quality) {
        if (quality == MeshRenderQuality.FULL) FullMeshGraph else ReducedMeshGraph
    }
    val particles = remember(quality) {
        if (quality == MeshRenderQuality.FULL) FullParticleField else ReducedParticleField
    }

    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        if (!animationReady.value) {
            // A solid first frame avoids shader compilation delaying Activity startup.
            drawRect(color = palette.backgroundStart)
            return@Canvas
        }

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(palette.backgroundStart, palette.backgroundEnd)
            )
        )

        when (palette.style) {
            MeshBackgroundStyle.STARFIELD -> drawStarfieldScene(palette, particles, elapsedSeconds, quality)
            MeshBackgroundStyle.NEBULA -> drawNebulaScene(palette, particles, graph, elapsedSeconds, quality)
            MeshBackgroundStyle.AURORA -> drawAuroraScene(palette, particles, elapsedSeconds, quality)
            MeshBackgroundStyle.TIDAL -> drawTidalScene(palette, particles, elapsedSeconds, quality)
            MeshBackgroundStyle.EMBER -> drawEmberScene(palette, particles, elapsedSeconds, quality)
            MeshBackgroundStyle.MOONLIT -> drawMoonlitScene(palette, particles, elapsedSeconds, quality)
            MeshBackgroundStyle.RAIN_WINDOW -> drawRainWindowScene(palette, particles, elapsedSeconds, quality)
        }
    }
}

private fun DrawScope.drawStarfieldScene(
    palette: MeshAmbientPalette,
    particles: List<AmbientParticle>,
    elapsedSeconds: Float,
    quality: MeshRenderQuality
) {
    val minDimension = min(size.width, size.height)
    drawSoftBlob(
        center = Offset(
            x = size.width * (0.18f + 0.06f * sin(elapsedSeconds * 0.045f)),
            y = size.height * (0.28f + 0.05f * cos(elapsedSeconds * 0.035f))
        ),
        radius = minDimension * 0.76f,
        color = palette.primary,
        alpha = if (quality == MeshRenderQuality.FULL) 0.32f else 0.23f
    )
    drawSoftBlob(
        center = Offset(
            x = size.width * (0.82f + 0.08f * cos(elapsedSeconds * 0.031f)),
            y = size.height * (0.72f + 0.06f * sin(elapsedSeconds * 0.041f))
        ),
        radius = minDimension * 0.82f,
        color = palette.secondary,
        alpha = if (quality == MeshRenderQuality.FULL) 0.29f else 0.21f
    )
    drawParticleField(palette, particles, elapsedSeconds, alphaMultiplier = 1.0f)
    drawShootingStar(elapsedSeconds, periodSeconds = 23f, phaseOffset = 0.08f, color = palette.secondary, scale = minDimension / 420f)
    drawShootingStar(elapsedSeconds, periodSeconds = 37f, phaseOffset = 0.56f, color = palette.primary, scale = minDimension / 420f)
}

private fun DrawScope.drawNebulaScene(
    palette: MeshAmbientPalette,
    particles: List<AmbientParticle>,
    graph: MeshGraph,
    elapsedSeconds: Float,
    quality: MeshRenderQuality
) {
    val minDimension = min(size.width, size.height)
    drawSoftBlob(
        center = Offset(
            x = size.width * (0.10f + 0.14f * sin(elapsedSeconds * 0.032f)),
            y = size.height * (0.76f + 0.09f * cos(elapsedSeconds * 0.025f))
        ),
        radius = minDimension * 0.82f,
        color = palette.primary,
        alpha = if (quality == MeshRenderQuality.FULL) 0.40f else 0.28f
    )
    drawSoftBlob(
        center = Offset(
            x = size.width * (0.88f + 0.10f * cos(elapsedSeconds * 0.021f)),
            y = size.height * (0.22f + 0.11f * sin(elapsedSeconds * 0.029f))
        ),
        radius = minDimension * 0.70f,
        color = palette.secondary,
        alpha = if (quality == MeshRenderQuality.FULL) 0.36f else 0.25f
    )
    drawParticleField(palette, particles, elapsedSeconds, alphaMultiplier = 0.34f)

    val points = graph.points.mapIndexed { index, point ->
        val xDrift = size.width * 0.037f * sin(elapsedSeconds * (0.075f + index * 0.006f) + index * 1.7f)
        val yDrift = size.height * 0.034f * cos(elapsedSeconds * (0.061f + index * 0.005f) + index * 0.9f)
        Offset(
            x = (point.x * size.width + xDrift).coerceIn(0f, size.width),
            y = (point.y * size.height + yDrift).coerceIn(0f, size.height)
        )
    }
    drawMeshGraph(palette, graph, points, elapsedSeconds, quality)
}

private fun DrawScope.drawAuroraScene(
    palette: MeshAmbientPalette,
    particles: List<AmbientParticle>,
    elapsedSeconds: Float,
    quality: MeshRenderQuality
) {
    val minDimension = min(size.width, size.height)
    drawSoftBlob(
        center = Offset(
            x = size.width * (0.18f + 0.12f * sin(elapsedSeconds * 0.018f)),
            y = size.height * (0.34f + 0.08f * cos(elapsedSeconds * 0.024f))
        ),
        radius = minDimension * 0.85f,
        color = palette.primary,
        alpha = if (quality == MeshRenderQuality.FULL) 0.28f else 0.21f
    )
    drawSoftBlob(
        center = Offset(
            x = size.width * (0.84f + 0.10f * cos(elapsedSeconds * 0.022f)),
            y = size.height * (0.62f + 0.08f * sin(elapsedSeconds * 0.019f))
        ),
        radius = minDimension * 0.78f,
        color = palette.secondary,
        alpha = if (quality == MeshRenderQuality.FULL) 0.26f else 0.19f
    )
    drawParticleField(palette, particles, elapsedSeconds, alphaMultiplier = 0.42f)
    drawAuroraRibbon(palette.primary, palette.secondary, baseY = 0.30f, amplitude = 0.12f, elapsedSeconds, 0.10f, 0.0f)
    drawAuroraRibbon(palette.secondary, palette.tertiary, baseY = 0.47f, amplitude = 0.14f, elapsedSeconds, 0.075f, 1.8f)
    drawAuroraRibbon(palette.tertiary, palette.primary, baseY = 0.65f, amplitude = 0.10f, elapsedSeconds, 0.055f, 3.2f)
}

private fun DrawScope.drawTidalScene(
    palette: MeshAmbientPalette,
    particles: List<AmbientParticle>,
    elapsedSeconds: Float,
    quality: MeshRenderQuality
) {
    val minDimension = min(size.width, size.height)
    drawSoftBlob(
        center = Offset(
            x = size.width * (0.22f + 0.08f * sin(elapsedSeconds * 0.023f)),
            y = size.height * (0.72f + 0.06f * cos(elapsedSeconds * 0.017f))
        ),
        radius = minDimension * 0.80f,
        color = palette.secondary,
        alpha = if (quality == MeshRenderQuality.FULL) 0.33f else 0.24f
    )
    drawSoftBlob(
        center = Offset(
            x = size.width * (0.80f + 0.09f * cos(elapsedSeconds * 0.019f)),
            y = size.height * (0.24f + 0.08f * sin(elapsedSeconds * 0.027f))
        ),
        radius = minDimension * 0.68f,
        color = palette.primary,
        alpha = if (quality == MeshRenderQuality.FULL) 0.25f else 0.19f
    )
    drawBubbleField(palette, particles, elapsedSeconds, quality)

    val center = Offset(size.width * 0.50f, size.height * 1.03f)
    repeat(if (quality == MeshRenderQuality.FULL) 5 else 3) { index ->
        val wave = 0.5f + 0.5f * sin(elapsedSeconds * (0.12f + index * 0.011f) + index)
        val radius = minDimension * (0.28f + index * 0.18f) + wave * minDimension * 0.025f
        drawArc(
            color = (if (index % 2 == 0) palette.primary else palette.secondary).copy(alpha = 0.16f + wave * 0.12f),
            startAngle = 198f + sin(elapsedSeconds * 0.07f + index) * 10f,
            sweepAngle = 110f + wave * 34f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
            style = Stroke(width = if (quality == MeshRenderQuality.FULL) 2.2f else 1.6f)
        )
    }
}

private fun DrawScope.drawEmberScene(
    palette: MeshAmbientPalette,
    particles: List<AmbientParticle>,
    elapsedSeconds: Float,
    quality: MeshRenderQuality
) {
    val minDimension = min(size.width, size.height)
    drawSoftBlob(
        center = Offset(
            x = size.width * (0.24f + 0.11f * sin(elapsedSeconds * 0.020f)),
            y = size.height * (0.82f + 0.05f * cos(elapsedSeconds * 0.027f))
        ),
        radius = minDimension * 0.80f,
        color = palette.primary,
        alpha = if (quality == MeshRenderQuality.FULL) 0.29f else 0.22f
    )
    drawSoftBlob(
        center = Offset(
            x = size.width * (0.78f + 0.10f * cos(elapsedSeconds * 0.024f)),
            y = size.height * (0.24f + 0.09f * sin(elapsedSeconds * 0.018f))
        ),
        radius = minDimension * 0.66f,
        color = palette.secondary,
        alpha = if (quality == MeshRenderQuality.FULL) 0.26f else 0.19f
    )
    drawEmberField(palette, particles, elapsedSeconds, quality)
}

private fun DrawScope.drawMoonlitScene(
    palette: MeshAmbientPalette,
    particles: List<AmbientParticle>,
    elapsedSeconds: Float,
    quality: MeshRenderQuality
) {
    val minDimension = min(size.width, size.height)
    drawSoftBlob(
        center = Offset(
            x = size.width * (0.14f + 0.05f * sin(elapsedSeconds * 0.018f)),
            y = size.height * (0.70f + 0.05f * cos(elapsedSeconds * 0.021f))
        ),
        radius = minDimension * 0.82f,
        color = palette.primary,
        alpha = if (quality == MeshRenderQuality.FULL) 0.20f else 0.15f
    )
    drawSoftBlob(
        center = Offset(
            x = size.width * (0.84f + 0.05f * cos(elapsedSeconds * 0.015f)),
            y = size.height * (0.48f + 0.07f * sin(elapsedSeconds * 0.017f))
        ),
        radius = minDimension * 0.74f,
        color = palette.secondary,
        alpha = if (quality == MeshRenderQuality.FULL) 0.18f else 0.13f
    )

    val moonCenter = Offset(
        x = size.width * (0.76f + 0.018f * sin(elapsedSeconds * 0.012f)),
        y = size.height * (0.18f + 0.012f * cos(elapsedSeconds * 0.014f))
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                palette.tertiary.copy(alpha = 0.24f),
                palette.secondary.copy(alpha = 0.08f),
                Color.Transparent
            ),
            center = moonCenter,
            radius = minDimension * 0.25f
        ),
        radius = minDimension * 0.25f,
        center = moonCenter
    )
    drawCircle(
        color = palette.tertiary.copy(alpha = 0.92f),
        radius = minDimension * 0.105f,
        center = moonCenter
    )
    drawCircle(
        color = palette.backgroundStart.copy(alpha = 0.72f),
        radius = minDimension * 0.095f,
        center = moonCenter + Offset(minDimension * 0.045f, -minDimension * 0.018f)
    )

    val horizon = Path().apply {
        moveTo(0f, size.height * 0.82f)
        cubicTo(
            size.width * 0.22f, size.height * 0.76f,
            size.width * 0.44f, size.height * 0.86f,
            size.width * 0.66f, size.height * 0.79f
        )
        cubicTo(
            size.width * 0.82f, size.height * 0.74f,
            size.width * 0.93f, size.height * 0.81f,
            size.width, size.height * 0.77f
        )
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }
    drawPath(path = horizon, color = palette.backgroundStart.copy(alpha = 0.68f))
    drawParticleField(palette, particles, elapsedSeconds, alphaMultiplier = 0.72f)
    drawShootingStar(
        elapsedSeconds,
        periodSeconds = 31f,
        phaseOffset = 0.21f,
        color = palette.tertiary,
        scale = minDimension / 420f
    )
}

private fun DrawScope.drawRainWindowScene(
    palette: MeshAmbientPalette,
    particles: List<AmbientParticle>,
    elapsedSeconds: Float,
    quality: MeshRenderQuality
) {
    val minDimension = min(size.width, size.height)
    drawSoftBlob(
        center = Offset(
            x = size.width * (0.22f + 0.10f * sin(elapsedSeconds * 0.016f)),
            y = size.height * (0.30f + 0.08f * cos(elapsedSeconds * 0.019f))
        ),
        radius = minDimension * 0.74f,
        color = palette.secondary,
        alpha = if (quality == MeshRenderQuality.FULL) 0.22f else 0.16f
    )
    drawSoftBlob(
        center = Offset(
            x = size.width * (0.82f + 0.08f * cos(elapsedSeconds * 0.013f)),
            y = size.height * (0.76f + 0.06f * sin(elapsedSeconds * 0.017f))
        ),
        radius = minDimension * 0.86f,
        color = palette.primary,
        alpha = if (quality == MeshRenderQuality.FULL) 0.19f else 0.14f
    )
    drawParticleField(palette, particles, elapsedSeconds, alphaMultiplier = 0.50f)

    val drops = if (quality == MeshRenderQuality.FULL) 34 else 18
    repeat(drops) { index ->
        val speed = 0.10f + (index % 5) * 0.012f
        val progress = (elapsedSeconds * speed + index * 0.071f) % 1f
        val xNorm = (index * 0.173f + 0.08f * sin(elapsedSeconds * 0.024f + index)) % 1f
        val y = progress * (size.height + minDimension * 0.12f) - minDimension * 0.06f
        val x = xNorm * size.width
        val length = minDimension * (0.025f + (index % 4) * 0.009f)
        val drift = minDimension * 0.012f
        drawLine(
            color = palette.tertiary.copy(alpha = 0.12f + (index % 4) * 0.025f),
            start = Offset(x, y),
            end = Offset(x + drift, y + length),
            strokeWidth = if (quality == MeshRenderQuality.FULL) 1.4f else 1.0f,
            cap = StrokeCap.Round
        )
        if (index % 6 == 0) {
            drawCircle(
                color = palette.tertiary.copy(alpha = 0.10f),
                radius = minDimension * 0.018f,
                center = Offset(x + drift, y + length)
            )
        }
    }
}

private fun DrawScope.drawParticleField(
    palette: MeshAmbientPalette,
    particles: List<AmbientParticle>,
    elapsedSeconds: Float,
    alphaMultiplier: Float
) {
    val scale = min(size.width, size.height) / 420f
    particles.forEachIndexed { index, particle ->
        val x = wrapUnit(particle.x + particle.driftX * elapsedSeconds + sin(elapsedSeconds * 0.012f + particle.phase) * 0.006f)
        val y = wrapUnit(particle.y + particle.driftY * elapsedSeconds + cos(elapsedSeconds * 0.010f + particle.phase) * 0.005f)
        val twinkle = 0.58f + 0.42f * sin(elapsedSeconds * particle.twinkleSpeed + particle.phase)
        val radius = max(0.75f, particle.radius * scale)
        val color = when (index % 3) {
            0 -> palette.tertiary
            1 -> palette.secondary
            else -> Color.White
        }
        val center = Offset(x * size.width, y * size.height)
        // Small stars stay as solid dots; radial shaders are reserved for visible glows.
        if (radius > 2.8f) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = particle.alpha * twinkle * alphaMultiplier * 0.34f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius * 7f
                ),
                radius = radius * 7f,
                center = center
            )
        }
        drawCircle(
            color = color.copy(alpha = particle.alpha * twinkle * alphaMultiplier),
            radius = radius,
            center = center
        )
    }
}

private fun DrawScope.drawBubbleField(
    palette: MeshAmbientPalette,
    particles: List<AmbientParticle>,
    elapsedSeconds: Float,
    quality: MeshRenderQuality
) {
    val scale = min(size.width, size.height) / 360f
    val count = if (quality == MeshRenderQuality.FULL) particles.size else particles.size / 2
    particles.take(count).forEachIndexed { index, particle ->
        val x = wrapUnit(particle.x + sin(elapsedSeconds * 0.017f + particle.phase) * 0.035f)
        val y = wrapUnit(particle.y - elapsedSeconds * (0.0012f + particle.radius * 0.00035f))
        val radius = max(2.2f, particle.radius * scale * 2.1f)
        val color = if (index % 2 == 0) palette.primary else palette.secondary
        val center = Offset(x * size.width, y * size.height)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.28f), Color.Transparent),
                center = center,
                radius = radius * 4.6f
            ),
            radius = radius * 4.6f,
            center = center
        )
        drawCircle(
            color = color.copy(alpha = 0.44f),
            radius = radius,
            center = center,
            style = Stroke(width = max(1f, scale * 0.8f))
        )
    }
}

private fun DrawScope.drawEmberField(
    palette: MeshAmbientPalette,
    particles: List<AmbientParticle>,
    elapsedSeconds: Float,
    quality: MeshRenderQuality
) {
    val scale = min(size.width, size.height) / 420f
    val count = if (quality == MeshRenderQuality.FULL) particles.size else particles.size / 2
    particles.take(count).forEachIndexed { index, particle ->
        val x = wrapUnit(particle.x + sin(elapsedSeconds * 0.025f + particle.phase) * 0.045f)
        val y = wrapUnit(particle.y - elapsedSeconds * (0.0022f + particle.radius * 0.00055f))
        val flicker = 0.55f + 0.45f * sin(elapsedSeconds * (particle.twinkleSpeed + 0.24f) + particle.phase)
        val radius = max(1.1f, particle.radius * scale * 1.15f)
        val color = when (index % 3) {
            0 -> palette.primary
            1 -> palette.secondary
            else -> palette.tertiary
        }
        val center = Offset(x * size.width, y * size.height)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.34f * flicker), Color.Transparent),
                center = center,
                radius = radius * 7f
            ),
            radius = radius * 7f,
            center = center
        )
        drawCircle(
            color = color.copy(alpha = 0.58f + flicker * 0.32f),
            radius = radius,
            center = center
        )
    }
}

private fun DrawScope.drawMeshGraph(
    palette: MeshAmbientPalette,
    graph: MeshGraph,
    points: List<Offset>,
    elapsedSeconds: Float,
    quality: MeshRenderQuality
) {
    graph.edges.forEachIndexed { index, edge ->
        val start = points[edge.from]
        val end = points[edge.to]
        val lineColor = if (index % 2 == 0) palette.primary else palette.secondary
        drawLine(
            color = lineColor.copy(alpha = if (quality == MeshRenderQuality.FULL) 0.15f else 0.10f),
            start = start,
            end = end,
            strokeWidth = if (quality == MeshRenderQuality.FULL) 10f else 7f
        )
        drawLine(
            color = lineColor.copy(alpha = if (quality == MeshRenderQuality.FULL) 0.42f else 0.30f),
            start = start,
            end = end,
            strokeWidth = if (quality == MeshRenderQuality.FULL) 1.6f else 1.2f
        )
        if (index < quality.pulseCount) {
            val travel = wrapUnit(elapsedSeconds * (0.018f + index * 0.0022f) + edge.phaseOffset)
            val pulsePoint = Offset(
                x = start.x + (end.x - start.x) * travel,
                y = start.y + (end.y - start.y) * travel
            )
            val radius = if (quality == MeshRenderQuality.FULL) 30f else 23f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(lineColor.copy(alpha = 0.78f), lineColor.copy(alpha = 0.22f), Color.Transparent),
                    center = pulsePoint,
                    radius = radius
                ),
                radius = radius,
                center = pulsePoint
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.82f),
                radius = if (quality == MeshRenderQuality.FULL) 3.4f else 2.8f,
                center = pulsePoint
            )
        }
    }

    val nodeBreath = 0.5f + 0.5f * sin(elapsedSeconds * 0.32f)
    points.forEachIndexed { index, point ->
        val nodeColor = when (index % 3) {
            0 -> palette.primary
            1 -> palette.secondary
            else -> palette.tertiary
        }
        val radius = if (index == 0) 76f + nodeBreath * 8f else 47f + nodeBreath * 6f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(nodeColor.copy(alpha = 0.33f + nodeBreath * 0.13f), nodeColor.copy(alpha = 0.08f), Color.Transparent),
                center = point,
                radius = radius
            ),
            radius = radius,
            center = point
        )
        drawCircle(
            color = nodeColor.copy(alpha = 0.90f),
            radius = if (index == 0) 7f + nodeBreath * 1.6f else 4.2f + nodeBreath,
            center = point
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.76f),
            radius = if (index == 0) 1.9f else 1.2f,
            center = point
        )
    }

    graph.ringNodes.forEachIndexed { index, nodeIndex ->
        val ringProgress = wrapUnit(elapsedSeconds * 0.065f + index * 0.27f)
        drawCircle(
            color = if (index % 2 == 0) palette.primary else palette.secondary,
            alpha = 0.38f * (1f - ringProgress),
            radius = 18f + min(size.width, size.height) * 0.17f * ringProgress,
            center = points[nodeIndex],
            style = Stroke(width = if (quality == MeshRenderQuality.FULL) 2.1f else 1.6f)
        )
    }
}

private fun DrawScope.drawAuroraRibbon(
    startColor: Color,
    endColor: Color,
    baseY: Float,
    amplitude: Float,
    elapsedSeconds: Float,
    speed: Float,
    phase: Float
) {
    val path = Path()
    val sampleCount = 26
    repeat(sampleCount) { index ->
        val progress = index.toFloat() / (sampleCount - 1).toFloat()
        val x = size.width * (progress * 1.18f - 0.09f)
        val y = size.height * (
            baseY + amplitude * sin(progress * 8.4f + elapsedSeconds * speed + phase) +
                amplitude * 0.34f * sin(progress * 17.0f - elapsedSeconds * speed * 0.61f + phase)
        )
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(
                startColor.copy(alpha = 0.02f),
                startColor.copy(alpha = 0.26f),
                endColor.copy(alpha = 0.22f),
                Color.Transparent
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        ),
        style = Stroke(width = size.height * 0.075f, cap = StrokeCap.Round)
    )
    drawPath(
        path = path,
        color = startColor.copy(alpha = 0.32f),
        style = Stroke(width = max(1.5f, size.height * 0.006f), cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawShootingStar(
    elapsedSeconds: Float,
    periodSeconds: Float,
    phaseOffset: Float,
    color: Color,
    scale: Float
) {
    val cycle = wrapUnit(elapsedSeconds / periodSeconds + phaseOffset)
    val activeWindow = 0.13f
    if (cycle >= activeWindow) return

    val progress = smoothStep(cycle / activeWindow)
    val head = Offset(
        x = size.width * (0.07f + progress * 0.86f),
        y = size.height * (0.14f + progress * 0.30f)
    )
    val tail = Offset(head.x - size.width * 0.15f, head.y - size.height * 0.065f)
    drawLine(
        color.copy(alpha = 0.52f * (1f - progress)),
        tail,
        head,
        strokeWidth = max(1.2f, scale * 1.8f),
        cap = StrokeCap.Round
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.70f * (1f - progress)), Color.Transparent),
            center = head,
            radius = max(12f, scale * 18f)
        ),
        radius = max(12f, scale * 18f),
        center = head
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.88f * (1f - progress)),
        radius = max(1.4f, scale * 1.8f),
        center = head
    )
}

private fun DrawScope.drawSoftBlob(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = alpha),
                color.copy(alpha = alpha * 0.40f),
                color.copy(alpha = alpha * 0.10f),
                Color.Transparent
            ),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

private fun wrapUnit(value: Float): Float {
    val wrapped = value % 1f
    return if (wrapped < 0f) wrapped + 1f else wrapped
}

private fun smoothStep(value: Float): Float {
    val clamped = value.coerceIn(0f, 1f)
    return clamped * clamped * (3f - 2f * clamped)
}

private const val TWO_PI = 6.2831855f
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val TIME_WRAP_NANOS = 86_400_000_000_000L
private const val FULL_FRAME_INTERVAL_NANOS = 24_000_000L
private const val REDUCED_FRAME_INTERVAL_NANOS = 33_000_000L
