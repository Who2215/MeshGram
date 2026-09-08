package com.meshchat.app.ui.audio

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optional immediate lock/cache-clear hook. Call on the main thread BEFORE generic cleanup. */
object InlineAudioPlayback {
    internal var active: SharedAudioController? = null

    @MainThread
    fun release() {
        check(Looper.myLooper() == Looper.getMainLooper())
        active?.releasePlayback()
    }
}

internal data class AudioPlaybackState(
    val key: AudioMessageKey? = null,
    val resolving: Boolean = false,
    val buffering: Boolean = false,
    val wantsPlay: Boolean = false,
    val playing: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val seekable: Boolean = false,
    val speed: Float = 1f,
    val error: Boolean = false
)

@OptIn(UnstableApi::class)
internal class SharedAudioController(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var preview: File? = null
    private var resolveJob: Job? = null
    private var ticker: Job? = null
    private var generation = 0L
    private var locked = true
    private var foreground = false
    private var disposed = false
    private val rows = mutableMapOf<AudioMessageKey, Int>()

    var state by mutableStateOf(AudioPlaybackState())
        private set
    var enabled by mutableStateOf(false)
        private set

    init {
        scope.launch(Dispatchers.IO) {
            runCatching { AudioPreviewFiles.clearUnused(this@SharedAudioController.context) }
        }
    }

    fun setLocked(value: Boolean) {
        locked = value
        enabled = !locked && foreground && !disposed
        if (value) releasePlayback()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        enabled = !locked && foreground && !disposed
        if (!value) {
            // Cancel pending auto-play as well as pausing an already prepared player.
            if (state.resolving) releasePlayback() else pause()
        }
    }

    fun attach(key: AudioMessageKey) {
        rows[key] = (rows[key] ?: 0) + 1
    }

    fun detach(key: AudioMessageKey) {
        val count = (rows[key] ?: 1) - 1
        if (count > 0) rows[key] = count else {
            rows.remove(key)
            if (state.key == key) releasePlayback()
        }
    }

    fun toggle(key: AudioMessageKey, speed: Float, resolveFile: suspend () -> File?) {
        if (!enabled || key.deleted || key.localUri == null) return
        if (state.key == key && !state.error) {
            if (state.resolving) {
                releasePlayback()
                return
            }
            player?.let { current ->
                if (current.playWhenReady && current.playbackState != Player.STATE_ENDED) {
                    pause()
                } else {
                    if (current.playbackState == Player.STATE_ENDED) current.seekTo(0L)
                    current.play()
                    publish(current)
                }
                return
            }
        }
        InlineAudioPlayback.active?.releasePlayback()
        releasePlayback()
        InlineAudioPlayback.active = this
        val request = generation
        state = AudioPlaybackState(key = key, resolving = true, wantsPlay = true, speed = speed)
        resolveJob = scope.launch {
            var candidate: File? = null
            try {
                // The old resolver is synchronous and may ignore cancellation. Assign
                // INSIDE the non-cancellable block so even a late file cannot be lost.
                withContext(NonCancellable + Dispatchers.IO) {
                    candidate = AudioPreviewFiles.acquire(context, resolveFile)
                }
                ensureActive()
                if (request != generation || !enabled) return@launch
                preview = candidate
                candidate = null
                val created = ExoPlayer.Builder(context)
                    // Local files only: no HTTP/HLS/DASH loading from untrusted media.
                    .setMediaSourceFactory(DefaultMediaSourceFactory(context)
                        .setDataSourceFactory(FileDataSource.Factory()))
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(if (key.voice) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        true
                    )
                    .setHandleAudioBecomingNoisy(true)
                    .build()
                player = created
                created.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        if (this@SharedAudioController.player === player) publish(player)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (player === created) fail(key)
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        // Do not unexpectedly resume speech after a call/focus interruption.
                        if (player === created && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                            created.pause()
                        }
                    }
                })
                created.setMediaItem(MediaItem.fromUri(Uri.fromFile(preview!!)))
                created.setPlaybackSpeed(if (key.voice) state.speed else 1f)
                state = state.copy(resolving = false, buffering = true)
                // Player methods stay on main; prepare() schedules extractor/decoder IO.
                created.prepare()
                created.play()
                publish(created)
                ticker = scope.launch {
                    while (isActive) {
                        if (player === created) publish(created)
                        delay(250L)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) fail(key)
            } finally {
                candidate?.let { file ->
                    withContext(NonCancellable + Dispatchers.IO) { AudioPreviewFiles.release(file) }
                }
            }
        }
    }

    fun seek(key: AudioMessageKey, fraction: Float) {
        if (!enabled || state.key != key || !state.seekable || !fraction.isFinite()) return
        player?.let {
            it.seekTo((fraction.coerceIn(0f, 1f) * state.durationMs).toLong())
            publish(it)
        }
    }

    fun setSpeed(key: AudioMessageKey, speed: Float) {
        if (!enabled || state.key != key || !key.voice || speed !in listOf(1f, 1.5f, 2f)) return
        state = state.copy(speed = speed)
        player?.setPlaybackSpeed(speed)
    }

    private fun pause() {
        player?.let { it.pause(); publish(it) }
    }

    private fun publish(player: Player) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        state = state.copy(
            buffering = player.playbackState == Player.STATE_BUFFERING,
            playing = player.isPlaying,
            wantsPlay = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
            durationMs = duration,
            positionMs = player.currentPosition.coerceAtLeast(0L).let {
                if (duration > 0) it.coerceAtMost(duration) else it
            },
            seekable = duration > 0L && player.isCurrentMediaItemSeekable
        )
    }

    private fun fail(key: AudioMessageKey) {
        val speed = state.speed
        releasePlayback()
        state = AudioPlaybackState(key = key, error = true, speed = speed)
    }

    fun releasePlayback() {
        generation++
        resolveJob?.cancel()
        resolveJob = null
        ticker?.cancel()
        ticker = null
        val previous = player
        player = null
        // Close extractors/file handles before dropping the decrypted-file lease.
        try {
            previous?.release()
        } finally {
            preview?.let { file ->
                scope.launch(NonCancellable + Dispatchers.IO) { AudioPreviewFiles.release(file) }
            }
            preview = null
            state = AudioPlaybackState()
            if (InlineAudioPlayback.active === this) InlineAudioPlayback.active = null
        }
    }

    fun dispose() {
        disposed = true
        enabled = false
        releasePlayback()
        rows.clear()
        scope.cancel()
    }
}
