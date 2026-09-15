package com.meshchat.app.ui

import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.airbnb.lottie.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun motionAllowed(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    fun scale() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var enabled by remember { mutableStateOf(scale()) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            enabled = scale()
        }
        val settings = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = scale() }
        }
        lifecycle.addObserver(observer)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, settings)
        onDispose { lifecycle.removeObserver(observer); context.contentResolver.unregisterContentObserver(settings) }
    }
    return resumed && enabled
}

/** Elapsed time is retained while hidden, but no frame clock is subscribed in the background. */
@Composable
private fun stickerProgress(playing: Boolean, loop: Boolean, durationMs: Float): Float {
    var elapsed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, loop, durationMs) {
        if (!playing) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (loop || elapsed < durationMs * 2) {
            val now = withFrameNanos { it }
            elapsed += ((now - previous) / 1_000_000f).coerceAtMost(100f)
            previous = now
            if (loop && elapsed > durationMs) elapsed %= durationMs
        }
    }
    return if (!loop && elapsed >= durationMs * 2) 1f else (elapsed % durationMs) / durationMs
}

@Composable
fun MeshStickerArt(id: String, modifier: Modifier = Modifier, animated: Boolean = true,
    loop: Boolean = false, replayOnTap: Boolean = true) {
    val context = LocalContext.current
    val view = LocalView.current
    val entry = remember(id, context.applicationContext) { StickerCatalog.find(context, id) }
    if (entry == null) {
        MissingStickerArt(id, modifier)
        return
    }
    var visible by remember { mutableStateOf(false) }
    var wasVisible by remember(id) { mutableStateOf(false) }
    var replay by remember(id) { mutableIntStateOf(0) }
    val allowed = motionAllowed()
    Box(modifier.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        val visibleWidth = (min(bounds.right, view.width.toFloat()) - max(bounds.left, 0f)).coerceAtLeast(0f)
        val visibleHeight = (min(bounds.bottom, view.height.toFloat()) - max(bounds.top, 0f)).coerceAtLeast(0f)
        visible = visibleWidth * visibleHeight >= it.size.width.toFloat() * it.size.height * .5f
        if (visible) wasVisible = true
    }.then(if (replayOnTap && entry.kind != StickerKind.PNG) Modifier.clickable { replay++ } else Modifier)) {
        key(id, replay) {
            val play = animated && visible && allowed
            when (entry.kind) {
                StickerKind.PNG -> StickerPreview(entry, "Sticker $id")
                StickerKind.CANVAS -> {
                    val progress = stickerProgress(play, loop, 5400f)
                    MeshMascotFrame(id, Modifier.fillMaxSize(), if (animated && allowed) progress else .45f)
                }
                StickerKind.LOTTIE -> {
                    if (wasVisible && animated && allowed) {
                        val spec = remember(entry.asset, entry.localAsset) {
                            entry.asset?.let(LottieCompositionSpec::Asset)
                                ?: entry.localAsset?.absolutePath?.let(LottieCompositionSpec::File)
                        }
                        if (spec != null) {
                            val result = rememberLottieComposition(spec)
                            val composition = result.value
                            if (composition != null && !result.isFailure) {
                                val progress = stickerProgress(play, loop, composition.duration.coerceAtLeast(1f))
                                if (progress >= 1f) StickerPreview(entry, "Sticker $id")
                                else LottieAnimation(composition, progress = { progress }, modifier = Modifier.fillMaxSize().semantics { contentDescription = "Sticker $id" })
                            } else StickerPreview(entry, "Sticker $id")
                        } else StickerPreview(entry, "Sticker $id")
                    } else StickerPreview(entry, "Sticker $id")
                }
            }
        }
    }
}

@Composable
private fun StickerPreview(entry: StickerDefinition, description: String) {
    val previewResource = entry.preview
    if (previewResource != null) {
        Image(painterResource(previewResource), description, Modifier.fillMaxSize())
        return
    }
    val file = entry.localPreview
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file?.path, file?.lastModified()) {
        value = if (file?.isFile == true) withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        } else null
    }
    if (image != null) {
        Image(image!!, description, Modifier.fillMaxSize())
    } else {
        MissingStickerArt(entry.id, Modifier.fillMaxSize())
    }
}

@Composable
private fun MissingStickerArt(id: String, modifier: Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "✦ ${id.substringAfterLast('/').take(16)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium
        )
    }
}
