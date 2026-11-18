package com.nuvio.tv.core.music

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.nuvio.tv.ui.screens.player.SafeMediaSessionPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Dedicated audio-only Media3/ExoPlayer engine for Eclipse Music & Connect speaker playback.
 *
 * Intentionally decoupled from the video `PlayerRuntimeController` so music playback never
 * pollutes Trakt/Simkl scrobbling, WatchProgress, or Continue Watching.
 */
@Singleton
class EclipseAudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eclipseMusicClient: EclipseMusicClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _telemetry = MutableStateFlow(EclipseAudioTelemetry())
    val telemetry: StateFlow<EclipseAudioTelemetry> = _telemetry.asStateFlow()

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var tickerJob: Job? = null
    private var lyricsJob: Job? = null

    private fun ensurePlayerOnMain(): ExoPlayer {
        player?.let { return it }
        val created = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        created.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                syncPlayerSnapshot(fetchLyricsIfNeeded = true)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncPlayerSnapshot(fetchLyricsIfNeeded = false)
                if (isPlaying) startTicker() else stopTicker()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && created.repeatMode == Player.REPEAT_MODE_OFF) {
                    stopTicker()
                }
                syncPlayerSnapshot(fetchLyricsIfNeeded = false)
            }

            override fun onPlayerError(error: PlaybackException) {
                _telemetry.update {
                    it.copy(
                        isPlaying = false,
                        isBuffering = false,
                        error = error.message ?: "Audio playback error",
                    )
                }
            }
        })

        mediaSession = MediaSession.Builder(context, SafeMediaSessionPlayer(created))
            .setId("eclipse_music_session")
            .build()
        player = created
        return created
    }

    suspend fun playQueue(
        tracks: List<EclipseTrack>,
        startIndex: Int = 0,
        autoplay: Boolean = true,
        source: String = "eclipse",
        djSessionId: String? = null,
    ) = withContext(Dispatchers.Main.immediate) {
        val playable = tracks.filter { it.streamUrl.isNotBlank() }
        if (playable.isEmpty()) return@withContext

        val safeIndex = startIndex.coerceIn(0, playable.lastIndex)
        val exo = ensurePlayerOnMain()
        val mediaItems = playable.map { it.toMediaItem() }

        _telemetry.update { state ->
            state.copy(
                isActive = true,
                isPlaying = autoplay,
                isBuffering = true,
                currentIndex = safeIndex,
                currentTrack = playable[safeIndex],
                positionMs = 0L,
                durationMs = playable[safeIndex].durationSec * 1000L,
                source = source,
                queueSize = playable.size,
                queue = playable,
                djSessionId = djSessionId ?: state.djSessionId,
                error = null,
            )
        }

        exo.setMediaItems(mediaItems, safeIndex, 0L)
        exo.prepare()
        exo.playWhenReady = autoplay
        if (autoplay) startTicker()
        fetchLyricsForTrack(safeIndex, playable[safeIndex])
    }

    suspend fun queueTracks(
        tracks: List<EclipseTrack>,
        mode: String = "append",
    ) = withContext(Dispatchers.Main.immediate) {
        val playable = tracks.filter { it.streamUrl.isNotBlank() }
        if (playable.isEmpty()) return@withContext

        val normalizedMode = mode.trim().lowercase()
        val currentQueue = _telemetry.value.queue
        if (normalizedMode == "replace" || !_telemetry.value.isActive || currentQueue.isEmpty()) {
            playQueue(playable, startIndex = 0, autoplay = true)
            return@withContext
        }

        val exo = ensurePlayerOnMain()
        val mediaItems = playable.map { it.toMediaItem() }
        val updatedQueue = currentQueue.toMutableList()
        val insertIndex = if (normalizedMode == "play_next") {
            (_telemetry.value.currentIndex + 1).coerceIn(0, updatedQueue.size)
        } else {
            updatedQueue.size
        }
        updatedQueue.addAll(insertIndex, playable)
        exo.addMediaItems(insertIndex, mediaItems)

        _telemetry.update { state ->
            state.copy(
                queue = updatedQueue,
                queueSize = updatedQueue.size,
            )
        }
    }

    suspend fun control(action: String, value: Any? = null): Boolean = withContext(Dispatchers.Main.immediate) {
        val exo = player ?: return@withContext false
        val normalized = action.trim().lowercase()
        when (normalized) {
            "play" -> {
                exo.playWhenReady = true
                exo.play()
                startTicker()
            }
            "pause" -> {
                exo.pause()
            }
            "toggle", "play_pause", "toggle_play_pause" -> {
                if (exo.isPlaying) exo.pause() else {
                    exo.playWhenReady = true
                    exo.play()
                    startTicker()
                }
            }
            "stop" -> {
                stopInternal(exo)
                return@withContext true
            }
            "next", "skip_next" -> {
                if (exo.hasNextMediaItem()) {
                    exo.seekToNextMediaItem()
                } else if (exo.mediaItemCount > 0 && exo.repeatMode == Player.REPEAT_MODE_ALL) {
                    exo.seekTo(0, 0L)
                }
            }
            "previous", "prev", "skip_prev" -> {
                if (exo.currentPosition > 3_000L) {
                    exo.seekTo(0L)
                } else if (exo.hasPreviousMediaItem()) {
                    exo.seekToPreviousMediaItem()
                } else {
                    exo.seekTo(0L)
                }
            }
            "seek_ms", "seek" -> {
                val posMs = (value as? Number)?.toLong()?.coerceAtLeast(0L) ?: return@withContext false
                exo.seekTo(posMs)
            }
            "volume" -> {
                val vol = (value as? Number)?.toInt()?.coerceIn(0, 100) ?: return@withContext false
                exo.volume = vol / 100f
                _telemetry.update { it.copy(volume = vol) }
                return@withContext true
            }
            "shuffle" -> {
                val enabled = (value as? Boolean) ?: !exo.shuffleModeEnabled
                exo.shuffleModeEnabled = enabled
            }
            "repeat" -> {
                val mode = when (value) {
                    is String -> EclipseRepeatMode.fromWire(value)
                    else -> when (EclipseRepeatMode.fromWire(_telemetry.value.repeat)) {
                        EclipseRepeatMode.OFF -> EclipseRepeatMode.ALL
                        EclipseRepeatMode.ALL -> EclipseRepeatMode.ONE
                        EclipseRepeatMode.ONE -> EclipseRepeatMode.OFF
                    }
                }
                exo.repeatMode = when (mode) {
                    EclipseRepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    EclipseRepeatMode.ONE -> Player.REPEAT_MODE_ONE
                    EclipseRepeatMode.ALL -> Player.REPEAT_MODE_ALL
                }
            }
            else -> return@withContext false
        }
        syncPlayerSnapshot(fetchLyricsIfNeeded = false)
        true
    }

    suspend fun jumpTo(index: Int) = withContext(Dispatchers.Main.immediate) {
        val exo = player ?: return@withContext
        val queue = _telemetry.value.queue
        if (index !in queue.indices) return@withContext
        exo.seekTo(index, 0L)
        exo.playWhenReady = true
        exo.play()
        startTicker()
        syncPlayerSnapshot(fetchLyricsIfNeeded = true)
    }

    suspend fun removeAt(index: Int) = withContext(Dispatchers.Main.immediate) {
        val exo = player ?: return@withContext
        val queue = _telemetry.value.queue
        if (index !in queue.indices) return@withContext
        val updated = queue.toMutableList().apply { removeAt(index) }
        exo.removeMediaItem(index)
        if (updated.isEmpty()) {
            stopInternal(exo)
            return@withContext
        }
        val currentIdx = exo.currentMediaItemIndex.coerceIn(0, updated.lastIndex)
        _telemetry.update { state ->
            state.copy(
                queue = updated,
                queueSize = updated.size,
                currentIndex = currentIdx,
                currentTrack = updated.getOrNull(currentIdx),
            )
        }
    }

    fun pauseForVideo() {
        val exo = player ?: return
        scope.launch {
            if (exo.isPlaying) {
                exo.pause()
                syncPlayerSnapshot(fetchLyricsIfNeeded = false)
            }
        }
    }

    private fun stopInternal(exo: ExoPlayer) {
        stopTicker()
        lyricsJob?.cancel()
        exo.stop()
        exo.clearMediaItems()
        _telemetry.value = EclipseAudioTelemetry(volume = (exo.volume * 100f).roundToInt())
    }

    private fun syncPlayerSnapshot(fetchLyricsIfNeeded: Boolean) {
        val exo = player ?: return
        val state = _telemetry.value
        if (!state.isActive) return
        val queue = state.queue
        val idx = exo.currentMediaItemIndex.takeIf { it in queue.indices } ?: state.currentIndex
        val track = queue.getOrNull(idx)
        val durationMs = exo.duration.takeIf { it > 0L }
            ?: track?.durationSec?.takeIf { it > 0 }?.times(1000L)
            ?: 0L
        val repeatWire = when (exo.repeatMode) {
            Player.REPEAT_MODE_ONE -> EclipseRepeatMode.ONE.wireValue
            Player.REPEAT_MODE_ALL -> EclipseRepeatMode.ALL.wireValue
            else -> EclipseRepeatMode.OFF.wireValue
        }
        _telemetry.update { current ->
            current.copy(
                isPlaying = exo.isPlaying,
                isBuffering = exo.playbackState == Player.STATE_BUFFERING,
                currentIndex = idx,
                currentTrack = track,
                positionMs = exo.currentPosition.coerceAtLeast(0L),
                durationMs = durationMs,
                shuffle = exo.shuffleModeEnabled,
                repeat = repeatWire,
                volume = (exo.volume * 100f).roundToInt(),
                queueSize = queue.size,
            )
        }
        if (fetchLyricsIfNeeded && track != null) {
            fetchLyricsForTrack(idx, track)
        }
    }

    private fun fetchLyricsForTrack(index: Int, track: EclipseTrack) {
        if (!track.lyricsLrc.isNullOrBlank()) return
        lyricsJob?.cancel()
        lyricsJob = scope.launch {
            val lyrics = eclipseMusicClient.getLyrics(
                title = track.title,
                artist = track.artist,
                album = track.album.takeIf { it.isNotBlank() },
                durationSec = track.durationSec,
            )
            if (!lyrics.isNullOrBlank()) {
                _telemetry.update { state ->
                    val current = state.queue.getOrNull(index) ?: return@update state
                    if (current.id != track.id) return@update state
                    val updatedTrack = current.copy(lyricsLrc = lyrics)
                    val updatedQueue = state.queue.toMutableList().apply { set(index, updatedTrack) }
                    state.copy(
                        queue = updatedQueue,
                        currentTrack = if (state.currentIndex == index) updatedTrack else state.currentTrack,
                    )
                }
            }
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                syncPlayerSnapshot(fetchLyricsIfNeeded = false)
                delay(500L)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun EclipseTrack.toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album.takeIf { it.isNotBlank() })
            .apply {
                displayArtUrl().takeIf { it.isNotBlank() }?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(streamUrl)
            .setMediaMetadata(metadata)
            .build()
    }
}
