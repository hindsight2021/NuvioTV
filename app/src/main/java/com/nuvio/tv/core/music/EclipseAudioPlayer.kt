package com.nuvio.tv.core.music

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class DjTransitionCurveFrame(
    val outgoingVolume: Float,
    val incomingVolume: Float,
    val outgoingLowGain: Float,
    val outgoingMidGain: Float,
    val outgoingHighGain: Float,
    val incomingLowGain: Float,
    val incomingMidGain: Float,
    val incomingHighGain: Float,
    val crossfader: Float,
)

/**
 * Pure equal-power crossfade + Bass Swap + Filter Sweep curve math for Pro DJ transitions.
 */
object DjTransitionCurve {
    fun evaluate(progress: Float, plan: EclipseDjTransitionPlan?): DjTransitionCurveFrame {
        val p = progress.coerceIn(0f, 1f)
        val angle = p * (PI.toFloat() / 2f)
        val outVol = cos(angle).coerceIn(0f, 1f)
        val inVol = sin(angle).coerceIn(0f, 1f)

        val bassSwap = plan?.bassSwap ?: true
        val swapAt = (plan?.bassSwapFraction ?: 0.58f).coerceIn(0.25f, 0.85f)
        val sweepMode = plan?.filterSweep?.lowercase() ?: "lowpass"

        val outLow: Float
        val inLow: Float
        if (bassSwap) {
            val swapHalfWindow = 0.14f
            val swapT = ((p - (swapAt - swapHalfWindow)) / (2f * swapHalfWindow)).coerceIn(0f, 1f)
            outLow = lerp(0.95f, 0.18f, swapT)
            inLow = lerp(0.20f, 1.0f, swapT)
        } else {
            outLow = lerp(1.0f, 0.35f, p)
            inLow = lerp(0.45f, 1.0f, p)
        }

        val inMid = lerp(0.55f, 1.0f, (p * 1.35f).coerceIn(0f, 1f))
        val inHigh = lerp(0.65f, 1.0f, (p * 1.5f).coerceIn(0f, 1f))

        val outMid: Float
        val outHigh: Float
        if (sweepMode == "highpass") {
            outMid = lerp(1.0f, 0.70f, p)
            outHigh = lerp(1.0f, 0.88f, p)
        } else {
            // Smooth lowpass-style sweep rolls off outgoing highs during late mix
            val sweepT = ((p - 0.30f) / 0.70f).coerceIn(0f, 1f)
            outMid = lerp(1.0f, 0.55f, sweepT)
            outHigh = lerp(1.0f, 0.25f, sweepT)
        }

        return DjTransitionCurveFrame(
            outgoingVolume = outVol,
            incomingVolume = inVol,
            outgoingLowGain = outLow,
            outgoingMidGain = outMid,
            outgoingHighGain = outHigh,
            incomingLowGain = inLow,
            incomingMidGain = inMid,
            incomingHighGain = inHigh,
            crossfader = p,
        )
    }

    private fun lerp(start: Float, end: Float, t: Float): Float = start + (end - start) * t.coerceIn(0f, 1f)
}

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

    private val _djTelemetry = MutableStateFlow(DjConsoleTelemetry())
    val djTelemetry: StateFlow<DjConsoleTelemetry> = _djTelemetry.asStateFlow()

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var tickerJob: Job? = null
    private var lyricsJob: Job? = null

    // Dual-deck Pro DJ Engine
    private var deckAPlayer: ExoPlayer? = null
    private var deckBPlayer: ExoPlayer? = null
    private val deckAProcessor = DjDeckAudioProcessor(DjDeckId.A)
    private val deckBProcessor = DjDeckAudioProcessor(DjDeckId.B)
    private var djTickerJob: Job? = null
    private var djAnalyzeJob: Job? = null
    private var djActiveDeck: DjDeckId = DjDeckId.A
    private var djDeckATrackIndex: Int = -1
    private var djDeckBTrackIndex: Int = -1
    private var djMixInProgress: Boolean = false
    private var djMixStartWallMs: Long = 0L
    private var djMixDurationMs: Long = 14_000L
    private var djMixTargetDeck: DjDeckId = DjDeckId.B
    private var djMixActivePlan: EclipseDjTransitionPlan? = null
    private var djLastMixCompletedWallMs: Long = 0L
    private var djMasterVolume: Float = 1.0f

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
        stopDjMixInternal(resetTelemetry = true)
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
        if (_djTelemetry.value.isActive) {
            return@withContext controlDj(action, value)
        }
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
        scope.launch {
            if (_djTelemetry.value.isActive) {
                deckAPlayer?.pause()
                deckBPlayer?.pause()
                syncDjSnapshots()
            }
            val exo = player ?: return@launch
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

    // -------------------------------------------------------------------------
    // Dual-Deck Pro AI DJ Engine
    // -------------------------------------------------------------------------

    suspend fun startDjMixSet(
        tracks: List<EclipseTrack>,
        djSessionId: String? = null,
        commentary: String? = null,
        mood: String? = null,
    ) = withContext(Dispatchers.Main.immediate) {
        val playable = tracks.filter { it.streamUrl.isNotBlank() }
        if (playable.isEmpty()) return@withContext

        // Tear down standard single-deck queue cleanly before activating dual DJ decks
        player?.let {
            stopTicker()
            lyricsJob?.cancel()
            it.stop()
            it.clearMediaItems()
        }
        stopDjMixInternal(resetTelemetry = false)

        val deckA = ensureDjDeckPlayer(DjDeckId.A)
        ensureDjDeckPlayer(DjDeckId.B)

        djActiveDeck = DjDeckId.A
        djDeckATrackIndex = 0
        djDeckBTrackIndex = if (playable.size > 1) 1 else -1
        djMixInProgress = false
        djMixActivePlan = null
        djLastMixCompletedWallMs = 0L

        val first = playable[0]
        val second = playable.getOrNull(1)
        val firstCuePlan = first.djMeta?.transitionPlan

        _djTelemetry.value = DjConsoleTelemetry(
            isActive = true,
            isMixing = false,
            activeDeck = DjDeckId.A,
            deckA = buildInitialDeckSnapshot(DjDeckId.A, first, isPlaying = true),
            deckB = buildInitialDeckSnapshot(DjDeckId.B, second, isPlaying = false, isCued = second != null),
            crossfader = 0f,
            djSessionId = djSessionId,
            commentary = commentary ?: "Pro AI DJ engaged — harmonic beatmatching & bass swap active",
            mood = mood,
            activeTransitionNote = firstCuePlan?.djNote,
            masterBpm = first.resolvedBpm(),
            beatNumber = 1,
            clubLightPulse = 0.6f,
            queue = playable,
            currentIndex = 0,
            nextTrack = second,
            error = null,
        )

        prepareDjDeck(
            deckId = DjDeckId.A,
            track = first,
            trackIndex = 0,
            cueMs = 0L,
            volume = djMasterVolume,
            autoPlay = true,
        )
        if (second != null) {
            val cueMs = firstCuePlan?.incomingCueMs ?: second.djMeta?.mixInMs ?: 10_000L
            prepareDjDeck(
                deckId = DjDeckId.B,
                track = second,
                trackIndex = 1,
                cueMs = cueMs,
                volume = 0f,
                autoPlay = false,
            )
        }

        // Sync standard telemetry so bottom mini-bar and NowPlaying remain accurate
        syncStandardTelemetryFromDj(first, playable, 0, djSessionId)
        deckA.playWhenReady = true
        deckA.play()
        Log.d("EclipseAudioPlayer", "startDjMixSet started with ${playable.size} tracks; deckA=${first.title}, deckB=${second?.title}")
        startDjTicker()
        scheduleUpcomingWaveformAnalysis(startIdx = 0)
    }

    suspend fun triggerDjMixNow(): Boolean = withContext(Dispatchers.Main.immediate) {
        val state = _djTelemetry.value
        if (!state.isActive || djMixInProgress) return@withContext false
        val incomingIdx = (state.currentIndex + 1).takeIf { it in state.queue.indices } ?: return@withContext false
        beginMixTransition(outgoingDeck = djActiveDeck, incomingTrackIndex = incomingIdx, manualTrigger = true)
        true
    }

    suspend fun injectDjSurprises(
        surprises: List<EclipseTrack>,
        commentary: String? = null,
    ) = withContext(Dispatchers.Main.immediate) {
        val valid = surprises.filter { it.streamUrl.isNotBlank() }
        if (valid.isEmpty()) return@withContext
        val state = _djTelemetry.value
        if (!state.isActive) {
            startDjMixSet(valid, djSessionId = state.djSessionId, commentary = commentary, mood = "wildcard")
            return@withContext
        }
        val insertAt = (state.currentIndex + 1).coerceIn(0, state.queue.size)
        val updated = state.queue.toMutableList().apply { addAll(insertAt, valid) }
        val standbyDeck = if (djActiveDeck == DjDeckId.A) DjDeckId.B else DjDeckId.A
        val nextTrack = updated.getOrNull(insertAt)

        _djTelemetry.update {
            it.copy(
                queue = updated,
                nextTrack = nextTrack,
                commentary = commentary ?: "Wildcard surprise crate pick dropped onto Deck ${standbyDeck.name}!",
            )
        }
        if (!djMixInProgress && nextTrack != null) {
            val outTrack = updated.getOrNull(state.currentIndex)
            val cueMs = outTrack?.djMeta?.transitionPlan?.incomingCueMs ?: nextTrack.djMeta?.mixInMs ?: 10_000L
            prepareDjDeck(
                deckId = standbyDeck,
                track = nextTrack,
                trackIndex = insertAt,
                cueMs = cueMs,
                volume = 0f,
                autoPlay = false,
            )
        }
        scheduleUpcomingWaveformAnalysis(startIdx = state.currentIndex)
    }

    suspend fun stopDjMix() = withContext(Dispatchers.Main.immediate) {
        stopDjMixInternal(resetTelemetry = true)
        _telemetry.value = EclipseAudioTelemetry(volume = (djMasterVolume * 100f).roundToInt())
    }

    private fun controlDj(action: String, value: Any?): Boolean {
        val activePlayer = getDeckPlayer(djActiveDeck) ?: return false
        val standbyPlayer = getDeckPlayer(if (djActiveDeck == DjDeckId.A) DjDeckId.B else DjDeckId.A)
        when (action.trim().lowercase()) {
            "play" -> {
                activePlayer.playWhenReady = true
                activePlayer.play()
                if (djMixInProgress) standbyPlayer?.play()
                startDjTicker()
            }
            "pause" -> {
                activePlayer.pause()
                standbyPlayer?.pause()
            }
            "toggle", "play_pause", "toggle_play_pause" -> {
                if (activePlayer.isPlaying) {
                    activePlayer.pause()
                    standbyPlayer?.pause()
                } else {
                    activePlayer.playWhenReady = true
                    activePlayer.play()
                    if (djMixInProgress) standbyPlayer?.play()
                    startDjTicker()
                }
            }
            "stop" -> {
                stopDjMixInternal(resetTelemetry = true)
                _telemetry.value = EclipseAudioTelemetry(volume = (djMasterVolume * 100f).roundToInt())
                return true
            }
            "next", "skip_next" -> {
                scope.launch { triggerDjMixNow() }
            }
            "seek_ms", "seek" -> {
                val posMs = (value as? Number)?.toLong()?.coerceAtLeast(0L) ?: return false
                activePlayer.seekTo(posMs)
            }
            "volume" -> {
                val vol = (value as? Number)?.toInt()?.coerceIn(0, 100) ?: return false
                djMasterVolume = vol / 100f
                if (!djMixInProgress) {
                    activePlayer.volume = djMasterVolume
                }
                _telemetry.update { it.copy(volume = vol) }
                return true
            }
            else -> return false
        }
        syncDjSnapshots()
        return true
    }

    @OptIn(UnstableApi::class)
    private fun ensureDjDeckPlayer(deckId: DjDeckId): ExoPlayer {
        val existing = if (deckId == DjDeckId.A) deckAPlayer else deckBPlayer
        if (existing != null) return existing

        val processor = if (deckId == DjDeckId.A) deckAProcessor else deckBProcessor
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(processor))
                    .build()
            }
        }
        val created = ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        created.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && _djTelemetry.value.isActive && deckId == djActiveDeck) {
                    if (djMixInProgress) {
                        completeMixTransition()
                    } else {
                        val nextIdx = _djTelemetry.value.currentIndex + 1
                        if (nextIdx in _djTelemetry.value.queue.indices) {
                            beginMixTransition(djActiveDeck, nextIdx, manualTrigger = true)
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("EclipseAudioPlayer", "DJ Deck ${deckId.name} playback error: ${error.errorCodeName} ${error.message}", error)
                if (_djTelemetry.value.isActive && deckId == djActiveDeck) {
                    val nextIdx = _djTelemetry.value.currentIndex + 1
                    if (nextIdx in _djTelemetry.value.queue.indices) {
                        beginMixTransition(djActiveDeck, nextIdx, manualTrigger = true)
                        completeMixTransition()
                    } else {
                        _djTelemetry.update { it.copy(error = error.message ?: "DJ Deck ${deckId.name} error") }
                    }
                }
            }
        })

        if (deckId == DjDeckId.A) deckAPlayer = created else deckBPlayer = created
        return created
    }

    private fun prepareDjDeck(
        deckId: DjDeckId,
        track: EclipseTrack,
        trackIndex: Int,
        cueMs: Long,
        volume: Float,
        autoPlay: Boolean,
    ) {
        val exo = ensureDjDeckPlayer(deckId)
        val proc = getDeckProcessor(deckId)
        proc.resetEq()
        if (deckId == DjDeckId.A) djDeckATrackIndex = trackIndex else djDeckBTrackIndex = trackIndex

        val safeCue = cueMs.coerceIn(0L, ((track.durationSec - 15).coerceAtLeast(0)) * 1000L)
        exo.volume = volume.coerceIn(0f, 1f)
        exo.playbackParameters = PlaybackParameters.DEFAULT
        exo.setMediaItem(track.toMediaItem(), safeCue)
        exo.prepare()
        exo.playWhenReady = autoPlay
        if (autoPlay) exo.play() else exo.pause()
    }

    private fun beginMixTransition(
        outgoingDeck: DjDeckId,
        incomingTrackIndex: Int,
        manualTrigger: Boolean,
    ) {
        val state = _djTelemetry.value
        val queue = state.queue
        val inTrack = queue.getOrNull(incomingTrackIndex) ?: return
        val outTrack = queue.getOrNull(state.currentIndex)
        val targetDeck = if (outgoingDeck == DjDeckId.A) DjDeckId.B else DjDeckId.A
        val inPlayer = ensureDjDeckPlayer(targetDeck)
        val outPlayer = getDeckPlayer(outgoingDeck)

        val plan = outTrack?.djMeta?.transitionPlan
        val currentAssignedIdx = if (targetDeck == DjDeckId.A) djDeckATrackIndex else djDeckBTrackIndex
        if (currentAssignedIdx != incomingTrackIndex) {
            val cueMs = plan?.incomingCueMs ?: inTrack.djMeta?.mixInMs ?: 10_000L
            prepareDjDeck(
                deckId = targetDeck,
                track = inTrack,
                trackIndex = incomingTrackIndex,
                cueMs = cueMs,
                volume = 0f,
                autoPlay = false,
            )
        }

        // Tempo-lock incoming track toward outgoing BPM (bounded smooth range)
        val tempoRatio = (plan?.targetTempoRatio ?: 1.0f).coerceIn(0.92f, 1.08f)
        inPlayer.playbackParameters = PlaybackParameters(tempoRatio, 1.0f)
        inPlayer.volume = 0f
        getDeckProcessor(targetDeck).apply {
            lowGain = if (plan?.bassSwap != false) 0.22f else 0.45f
            midGain = 0.55f
            highGain = 0.65f
        }
        inPlayer.playWhenReady = true
        inPlayer.play()

        djMixInProgress = true
        djMixStartWallMs = System.currentTimeMillis()
        djMixDurationMs = if (manualTrigger) {
            (plan?.fadeMs ?: 10_000L).coerceIn(6_000L, 12_000L)
        } else {
            (plan?.fadeMs ?: 14_000L).coerceIn(6_000L, 20_000L)
        }
        djMixTargetDeck = targetDeck
        djMixActivePlan = plan ?: EclipseDjTransitionPlan(
            fadeMs = djMixDurationMs,
            outgoingBpm = outTrack?.resolvedBpm() ?: 122f,
            incomingBpm = inTrack.resolvedBpm(),
            targetTempoRatio = tempoRatio,
            djNote = "16-Beat Beatmix • ${outTrack?.resolvedBpm()?.roundToInt() ?: 122}→${inTrack.resolvedBpm().roundToInt()} BPM",
        )

        if (outPlayer?.isPlaying != true && outPlayer != null) {
            outPlayer.play()
        }
        startDjTicker()
        _djTelemetry.update {
            it.copy(
                isMixing = true,
                mixProgress = 0f,
                activeTransitionNote = djMixActivePlan?.djNote,
                commentary = inTrack.djMeta?.djWhy ?: it.commentary,
            )
        }
    }

    private fun completeMixTransition() {
        if (!djMixInProgress) return
        val incomingDeck = djMixTargetDeck
        val outgoingDeck = if (incomingDeck == DjDeckId.A) DjDeckId.B else DjDeckId.A
        val incomingIdx = if (incomingDeck == DjDeckId.A) djDeckATrackIndex else djDeckBTrackIndex

        getDeckPlayer(outgoingDeck)?.let { outP ->
            outP.pause()
            outP.volume = 0f
            outP.playbackParameters = PlaybackParameters.DEFAULT
        }
        getDeckProcessor(outgoingDeck).resetEq()

        getDeckPlayer(incomingDeck)?.let { inP ->
            inP.volume = djMasterVolume
        }
        getDeckProcessor(incomingDeck).resetEq()

        djActiveDeck = incomingDeck
        djMixInProgress = false
        djLastMixCompletedWallMs = System.currentTimeMillis()

        val state = _djTelemetry.value
        val currentTrack = state.queue.getOrNull(incomingIdx)
        val afterIdx = incomingIdx + 1
        val afterTrack = state.queue.getOrNull(afterIdx)

        _djTelemetry.update {
            it.copy(
                isMixing = false,
                activeDeck = incomingDeck,
                crossfader = if (incomingDeck == DjDeckId.A) 0f else 1f,
                mixProgress = 0f,
                currentIndex = incomingIdx,
                nextTrack = afterTrack,
                masterBpm = currentTrack?.resolvedBpm() ?: it.masterBpm,
                activeTransitionNote = currentTrack?.djMeta?.transitionPlan?.djNote ?: it.activeTransitionNote,
            )
        }
        if (currentTrack != null) {
            syncStandardTelemetryFromDj(currentTrack, state.queue, incomingIdx, state.djSessionId)
        }
        if (afterTrack != null) {
            val cueMs = currentTrack?.djMeta?.transitionPlan?.incomingCueMs ?: afterTrack.djMeta?.mixInMs ?: 10_000L
            prepareDjDeck(
                deckId = outgoingDeck,
                track = afterTrack,
                trackIndex = afterIdx,
                cueMs = cueMs,
                volume = 0f,
                autoPlay = false,
            )
        }
        scheduleUpcomingWaveformAnalysis(startIdx = incomingIdx)
    }

    private fun startDjTicker() {
        if (djTickerJob?.isActive == true) return
        djTickerJob = scope.launch {
            while (isActive && _djTelemetry.value.isActive) {
                tickDjEngine()
                delay(65L)
            }
        }
    }

    private fun tickDjEngine() {
        val state = _djTelemetry.value
        if (!state.isActive) return
        val activePlayer = getDeckPlayer(djActiveDeck) ?: return

        if (djMixInProgress) {
            val elapsed = (System.currentTimeMillis() - djMixStartWallMs).coerceAtLeast(0L)
            val progress = (elapsed.toFloat() / djMixDurationMs.toFloat()).coerceIn(0f, 1f)
            val incomingPlayer = getDeckPlayer(djMixTargetDeck)
            val outProc = getDeckProcessor(djActiveDeck)
            val inProc = getDeckProcessor(djMixTargetDeck)
            val curve = DjTransitionCurve.evaluate(progress, djMixActivePlan)

            activePlayer.volume = curve.outgoingVolume * djMasterVolume
            incomingPlayer?.volume = curve.incomingVolume * djMasterVolume
            outProc.lowGain = curve.outgoingLowGain
            outProc.midGain = curve.outgoingMidGain
            outProc.highGain = curve.outgoingHighGain
            inProc.lowGain = curve.incomingLowGain
            inProc.midGain = curve.incomingMidGain
            inProc.highGain = curve.incomingHighGain

            val visualCrossfader = if (djMixTargetDeck == DjDeckId.B) curve.crossfader else (1f - curve.crossfader)
            _djTelemetry.update {
                it.copy(
                    isMixing = true,
                    crossfader = visualCrossfader,
                    mixProgress = progress,
                )
            }

            if (progress >= 1.0f || incomingPlayer?.playbackState == Player.STATE_ENDED) {
                completeMixTransition()
            }
        } else {
            // Gradually relax incoming track pitch back to 1.0x after completing a transition
            val sinceMix = System.currentTimeMillis() - djLastMixCompletedWallMs
            val currentRatio = activePlayer.playbackParameters.speed
            if (abs(currentRatio - 1.0f) > 0.002f && sinceMix > 500L) {
                val relaxT = ((sinceMix - 500L) / 8_000f).coerceIn(0f, 1f)
                val nextSpeed = currentRatio + (1.0f - currentRatio) * relaxT * 0.25f
                activePlayer.playbackParameters = PlaybackParameters(
                    if (relaxT >= 0.98f) 1.0f else nextSpeed,
                    1.0f,
                )
            }

            // Auto-trigger next beatmix when reaching the planned mix-out cue point
            if (activePlayer.isPlaying) {
                val posMs = activePlayer.currentPosition.coerceAtLeast(0L)
                val durMs = activePlayer.duration.takeIf { it > 0L }
                    ?: (state.queue.getOrNull(state.currentIndex)?.durationSec?.times(1000L) ?: 0L)
                val nextIdx = state.currentIndex + 1
                if (nextIdx in state.queue.indices && durMs > 25_000L) {
                    val outTrack = state.queue.getOrNull(state.currentIndex)
                    val plannedStart = outTrack?.djMeta?.transitionPlan?.transitionStartMs ?: (durMs - 16_000L)
                    val triggerAtMs = plannedStart.coerceIn(20_000L, (durMs - 8_000L).coerceAtLeast(20_000L))
                    if (posMs >= triggerAtMs) {
                        beginMixTransition(djActiveDeck, nextIdx, manualTrigger = false)
                    }
                }
            }
        }

        syncDjSnapshots()
    }

    private fun syncDjSnapshots() {
        val state = _djTelemetry.value
        if (!state.isActive) return
        val snapA = captureDeckSnapshot(DjDeckId.A, state.queue.getOrNull(djDeckATrackIndex))
        val snapB = captureDeckSnapshot(DjDeckId.B, state.queue.getOrNull(djDeckBTrackIndex))
        val activeSnap = if (djActiveDeck == DjDeckId.A) snapA else snapB

        val masterBpm = activeSnap.bpm.coerceIn(60f, 190f)
        val beatMs = (60_000.0 / masterBpm.toDouble()).coerceAtLeast(250.0)
        val totalBeats = (activeSnap.positionMs.toDouble() / beatMs).toInt()
        val beatNum = (totalBeats % 4) + 1
        val beatPulse = (activeSnap.beatPulse * 0.65f +
            (1.0f - ((activeSnap.positionMs % beatMs) / beatMs).toFloat()).coerceIn(0f, 1f).let { it * it } * 0.35f)
            .coerceIn(0f, 1f)

        _djTelemetry.update {
            it.copy(
                deckA = snapA,
                deckB = snapB,
                masterBpm = masterBpm,
                beatNumber = beatNum,
                clubLightPulse = if (activeSnap.isPlaying) beatPulse else 0.1f,
            )
        }
        val activeTrack = state.queue.getOrNull(state.currentIndex)
        if (activeTrack != null) {
            _telemetry.update {
                it.copy(
                    isActive = true,
                    isPlaying = activeSnap.isPlaying,
                    isBuffering = activeSnap.isBuffering,
                    currentIndex = state.currentIndex,
                    currentTrack = activeTrack,
                    positionMs = activeSnap.positionMs,
                    durationMs = activeSnap.durationMs,
                    source = "eclipse_dj",
                    queueSize = state.queue.size,
                    queue = state.queue,
                    djSessionId = state.djSessionId,
                )
            }
        }
    }

    private fun captureDeckSnapshot(deckId: DjDeckId, track: EclipseTrack?): DjDeckSnapshot {
        val p = getDeckPlayer(deckId)
        val proc = getDeckProcessor(deckId)
        val meter = proc.snapshot()
        val durationMs = p?.duration?.takeIf { it > 0L }
            ?: track?.durationSec?.takeIf { it > 0 }?.times(1000L)
            ?: 0L
        return DjDeckSnapshot(
            deck = deckId,
            track = track,
            isPlaying = p?.isPlaying == true,
            isBuffering = p?.playbackState == Player.STATE_BUFFERING,
            isCued = track != null && p?.isPlaying != true,
            positionMs = p?.currentPosition?.coerceAtLeast(0L) ?: 0L,
            durationMs = durationMs,
            bpm = (track?.resolvedBpm() ?: 122f) * (p?.playbackParameters?.speed ?: 1f),
            musicalKey = track?.resolvedKey() ?: "8A",
            tempoRatio = p?.playbackParameters?.speed ?: 1f,
            lowGain = proc.lowGain,
            midGain = proc.midGain,
            highGain = proc.highGain,
            volume = p?.volume ?: 0f,
            vuLevel = meter.vuLevel,
            bassEnergy = meter.bassEnergy,
            midEnergy = meter.midEnergy,
            highEnergy = meter.highEnergy,
            beatPulse = meter.beatPulse,
            waveform = track?.resolvedWaveform().orEmpty(),
            liveWaveform = meter.liveWaveform,
        )
    }

    private fun buildInitialDeckSnapshot(
        deckId: DjDeckId,
        track: EclipseTrack?,
        isPlaying: Boolean,
        isCued: Boolean = false,
    ): DjDeckSnapshot = DjDeckSnapshot(
        deck = deckId,
        track = track,
        isPlaying = isPlaying,
        isCued = isCued,
        durationMs = track?.durationSec?.times(1000L) ?: 0L,
        bpm = track?.resolvedBpm() ?: 122f,
        musicalKey = track?.resolvedKey() ?: "8A",
        waveform = track?.resolvedWaveform().orEmpty(),
    )

    private fun scheduleUpcomingWaveformAnalysis(startIdx: Int) {
        djAnalyzeJob?.cancel()
        djAnalyzeJob = scope.launch {
            val snapshotQueue = _djTelemetry.value.queue
            for (idx in startIdx..(startIdx + 2)) {
                val track = snapshotQueue.getOrNull(idx) ?: continue
                if (track.djMeta?.analyzed == true) continue
                val res = eclipseMusicClient.analyzeTrackAudio(track) ?: continue
                if (!res.ok) continue
                _djTelemetry.update { state ->
                    val existing = state.queue.getOrNull(idx) ?: return@update state
                    if (existing.id != track.id) return@update state
                    val mergedMeta = (existing.djMeta ?: EclipseDjTrackMeta()).copy(
                        bpm = res.bpm,
                        musicalKey = res.musicalKey,
                        energy = res.energy,
                        waveform = res.waveform.ifEmpty { existing.resolvedWaveform() },
                        mixInMs = res.mixInMs,
                        mixOutMs = res.mixOutMs,
                        analyzed = res.analyzed,
                    )
                    val updatedTrack = existing.copy(djMeta = mergedMeta)
                    val updatedQueue = state.queue.toMutableList().apply { set(idx, updatedTrack) }
                    state.copy(
                        queue = updatedQueue,
                        nextTrack = if (state.currentIndex + 1 == idx) updatedTrack else state.nextTrack,
                    )
                }
            }
        }
    }

    private fun syncStandardTelemetryFromDj(
        current: EclipseTrack,
        queue: List<EclipseTrack>,
        idx: Int,
        djSessionId: String?,
    ) {
        _telemetry.update {
            it.copy(
                isActive = true,
                isPlaying = true,
                isBuffering = false,
                currentIndex = idx,
                currentTrack = current,
                positionMs = 0L,
                durationMs = current.durationSec * 1000L,
                source = "eclipse_dj",
                queueSize = queue.size,
                queue = queue,
                djSessionId = djSessionId,
                error = null,
            )
        }
    }

    private fun stopDjMixInternal(resetTelemetry: Boolean) {
        djTickerJob?.cancel()
        djTickerJob = null
        djAnalyzeJob?.cancel()
        djAnalyzeJob = null
        djMixInProgress = false
        djActiveDeck = DjDeckId.A
        djDeckATrackIndex = -1
        djDeckBTrackIndex = -1
        deckAPlayer?.let {
            it.stop()
            it.clearMediaItems()
            it.playbackParameters = PlaybackParameters.DEFAULT
        }
        deckBPlayer?.let {
            it.stop()
            it.clearMediaItems()
            it.playbackParameters = PlaybackParameters.DEFAULT
        }
        deckAProcessor.resetEq()
        deckBProcessor.resetEq()
        if (resetTelemetry) {
            _djTelemetry.value = DjConsoleTelemetry()
        }
    }

    private fun getDeckPlayer(deckId: DjDeckId): ExoPlayer? =
        if (deckId == DjDeckId.A) deckAPlayer else deckBPlayer

    private fun getDeckProcessor(deckId: DjDeckId): DjDeckAudioProcessor =
        if (deckId == DjDeckId.A) deckAProcessor else deckBProcessor

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
