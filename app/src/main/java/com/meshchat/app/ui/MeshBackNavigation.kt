package com.meshchat.app.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CancellationException

/** The IME consumes the first back action; navigation commits only on gesture completion. */
@Composable
fun MeshBackNavigation(enabled: Boolean, onProgress: (Float) -> Unit = {}, onBack: () -> Unit) {
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    if (Build.VERSION.SDK_INT >= 34) {
        PredictiveBackHandler(enabled = enabled && !keyboardVisible) { events ->
            try {
                events.collect { onProgress(it.progress) }
                onBack()
            } catch (_: CancellationException) {
                // Cancellation restores the current screen and its draft.
            } finally {
                onProgress(0f)
            }
        }
    } else {
        BackHandler(enabled = enabled && !keyboardVisible, onBack = onBack)
    }
}

enum class BackDestination { MEDIA, INFO, SEARCH, SELECTION, EDIT, REPLY, PROFILE, CHAT, TAB, SYSTEM }

fun backDestination(media: Boolean, info: Boolean, search: Boolean, selection: Boolean,
    edit: Boolean, reply: Boolean, profile: Boolean, chat: Boolean, tab: Boolean = false): BackDestination = when {
    media -> BackDestination.MEDIA
    info -> BackDestination.INFO
    search -> BackDestination.SEARCH
    selection -> BackDestination.SELECTION
    edit -> BackDestination.EDIT
    reply -> BackDestination.REPLY
    profile -> BackDestination.PROFILE
    chat -> BackDestination.CHAT
    tab -> BackDestination.TAB
    else -> BackDestination.SYSTEM
}
