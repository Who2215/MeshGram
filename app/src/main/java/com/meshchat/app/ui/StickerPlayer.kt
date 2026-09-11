package com.meshchat.app.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.airbnb.lottie.compose.*

@Composable
private fun motionAllowed(): Boolean {
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
    val entry = StickerCatalog.find(id) ?: return
    var visible by remember { mutableStateOf(false) }
    var wasVisible by remember(id) { mutableStateOf(false) }
    var replay by remember(id) { mutableIntStateOf(0) }
    val allowed = motionAllowed()
    Box(modifier.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = bounds.width * bounds.height >= it.size.width.toFloat() * it.size.height * .5f && bounds.width > 0
        if (visible) wasVisible = true
    }.then(if (replayOnTap && entry.kind != StickerKind.PNG) Modifier.clickable { replay++ } else Modifier)) {
        key(id, replay) {
            val play = animated && visible && allowed
            when (entry.kind) {
                StickerKind.PNG -> Image(painterResource(entry.preview!!), "Fluent Emoji $id", Modifier.fillMaxSize())
                StickerKind.CANVAS -> {
                    val progress = stickerProgress(play, loop, 5400f)
                    MeshMascotFrame(id, Modifier.fillMaxSize(), if (animated && allowed) progress else .45f)
                }
                StickerKind.LOTTIE -> {
                    // Bundled assets only. The preview also covers parse failures and reduced motion.
                    if (wasVisible && animated && allowed) {
                        val result = rememberLottieComposition(LottieCompositionSpec.Asset(entry.asset!!))
                        val composition = result.value
                        if (composition != null && !result.isFailure) {
                            val progress = stickerProgress(play, loop, composition.duration.coerceAtLeast(1f))
                            if (progress >= 1f) Image(painterResource(entry.preview!!), "Noto $id", Modifier.fillMaxSize())
                            else LottieAnimation(composition, progress = { progress }, modifier = Modifier.fillMaxSize().semantics { contentDescription = "Noto $id" })
                        } else Image(painterResource(entry.preview!!), "Noto $id", Modifier.fillMaxSize())
                    } else Image(painterResource(entry.preview!!), "Noto $id", Modifier.fillMaxSize())
                }
            }
        }
    }
}
