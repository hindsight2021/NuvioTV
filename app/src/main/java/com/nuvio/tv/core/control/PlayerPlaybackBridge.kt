package com.nuvio.tv.core.control

import com.nuvio.tv.core.livetv.AiBroadcastTuneCoordinator
import com.nuvio.tv.core.player.NextEpisodePreWarmer
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.ui.screens.player.PlayerEvent
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.buildStreamInfoData
import com.nuvio.tv.ui.screens.player.hotSwapToStream
import com.nuvio.tv.ui.screens.player.onEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges playback control commands to the currently active [PlayerRuntimeController].
 *
 * Maintains a thread-safe registry of the active controller (held via [WeakReference] to avoid
 * leaking player instances) and continuously observes its state to expose a [PlaybackSnapshot] flow.
 */
@Singleton
class PlayerPlaybackBridge @Inject constructor(
    private val navigationCommander: NavigationCommander
) {

    private val lock = Any()
    private var controllerRef: WeakReference<PlayerRuntimeController>? = null
    private var observationJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playbackSnapshot = MutableStateFlow<PlaybackSnapshot?>(null)

    /** Latest snapshot of the active player's state, or null when no player is active. */
    val playbackSnapshot: StateFlow<PlaybackSnapshot?> = _playbackSnapshot.asStateFlow()

    /**
     * Registers [controller] as the active player and starts observing its state.
     */
    fun register(controller: PlayerRuntimeController) {
        synchronized(lock) {
            observationJob?.cancel()
            controllerRef = WeakReference(controller)

            val sessionId = java.util.UUID.randomUUID().toString()
            observationJob = scope.launch {
                combine(
                    controller.uiState,
                    controller.playbackTimeline
                ) { uiState, timeline ->
                    val audioTrackSnapshots = uiState.audioTracks.map { track ->
                        TrackSnapshot(
                            index = track.index,
                            name = track.name,
                            language = track.language,
                            codec = track.codec,
                            channelCount = track.channelCount,
                            isSelected = track.isSelected
                        )
                    }

                    val subtitleTrackSnapshots = uiState.subtitleTracks.map { track ->
                        TrackSnapshot(
                            index = track.index,
                            name = track.name,
                            language = track.language,
                            codec = track.codec,
                            channelCount = track.channelCount,
                            isSelected = track.isSelected
                        )
                    }

                    PlaybackSnapshot(
                        isActive = true,
                        isPlaying = uiState.isPlaying,
                        isBuffering = uiState.isBuffering,
                        playbackEnded = uiState.playbackEnded,
                        positionMs = timeline.currentPosition,
                        durationMs = timeline.duration,
                        bufferedPositionMs = timeline.bufferedPosition,
                        title = uiState.title,
                        contentName = uiState.contentName,
                        contentType = uiState.contentType,
                        season = uiState.currentSeason,
                        episode = uiState.currentEpisode,
                        episodeTitle = uiState.currentEpisodeTitle,
                        streamName = uiState.currentStreamName,
                        playbackSpeed = uiState.playbackSpeed,
                        audioTracks = audioTrackSnapshots,
                        subtitleTracks = subtitleTrackSnapshots,
                        selectedAudioIndex = uiState.selectedAudioTrackIndex,
                        selectedSubtitleIndex = uiState.selectedSubtitleTrackIndex,
                        contentId = controller.contentId,
                        videoId = controller.currentVideoId,
                        profileId = controller.profileId,
                        sessionId = sessionId
                    )
                }.collect { snapshot ->
                    _playbackSnapshot.value = snapshot
                    if (snapshot.isPlaying && !snapshot.isBuffering) {
                        val pendingSeek = AiBroadcastTuneCoordinator.consumePendingSeekIfMatches(snapshot.contentId)
                        if (pendingSeek != null && pendingSeek > 0L) {
                            controller.onEvent(PlayerEvent.OnSeekTo(pendingSeek))
                        }
                    }
                }
            }
        }
    }

    /**
     * Unregisters [controller] if it is the currently active one.
     */
    fun unregister(controller: PlayerRuntimeController) {
        synchronized(lock) {
            val current = controllerRef?.get()
            if (current !== controller) return

            observationJob?.cancel()
            observationJob = null
            controllerRef = null
            _playbackSnapshot.value = null
        }
    }

    /**
     * Executes a playback [command] against the active controller on [Dispatchers.Main].
     */
    suspend fun executePlaybackCommand(command: AppCommand): CommandResult {
        val controller = synchronized(lock) { controllerRef?.get() }
            ?: return CommandResult.Unavailable("Playback is not active")

        val snapshot = _playbackSnapshot.value
            ?: return CommandResult.Unavailable("Playback is not active")

        return withContext(Dispatchers.Main) {
            when (command) {
                AppCommand.PlayPause -> {
                    controller.onEvent(PlayerEvent.OnPlayPause)
                    CommandResult.Success()
                }

                AppCommand.Play -> {
                    if (!snapshot.isPlaying) {
                        controller.onEvent(PlayerEvent.OnPlayPause)
                    }
                    CommandResult.Success()
                }

                AppCommand.Pause -> {
                    if (snapshot.isPlaying) {
                        controller.onEvent(PlayerEvent.OnPlayPause)
                    }
                    CommandResult.Success()
                }

                AppCommand.Stop -> {
                    AiBroadcastTuneCoordinator.clearOnAir()
                    controller.stopAndRelease()
                    navigationCommander.popBack()
                    CommandResult.Success()
                }

                is AppCommand.SeekTo -> {
                    controller.onEvent(PlayerEvent.OnSeekTo(command.positionMs))
                    CommandResult.Success()
                }

                is AppCommand.SeekBy -> {
                    controller.onEvent(PlayerEvent.OnSeekBy(command.deltaMs))
                    CommandResult.Success()
                }

                AppCommand.Next -> {
                    controller.onEvent(PlayerEvent.OnPlayNextEpisode)
                    CommandResult.Success()
                }

                AppCommand.Previous -> {
                    controller.onEvent(PlayerEvent.OnSeekTo(0L))
                    CommandResult.Success()
                }

                AppCommand.SkipIntro -> {
                    controller.onEvent(PlayerEvent.OnSkipIntro)
                    CommandResult.Success()
                }

                is AppCommand.SetSpeed -> {
                    controller.onEvent(PlayerEvent.OnSetPlaybackSpeed(command.speed))
                    CommandResult.Success()
                }

                is AppCommand.SelectAudioTrack -> {
                    controller.onEvent(PlayerEvent.OnSelectAudioTrack(command.index))
                    CommandResult.Success()
                }

                is AppCommand.SelectSubtitleTrack -> {
                    controller.onEvent(PlayerEvent.OnSelectSubtitleTrack(command.index))
                    CommandResult.Success()
                }

                AppCommand.DisableSubtitles -> {
                    controller.onEvent(PlayerEvent.OnDisableSubtitles)
                    CommandResult.Success()
                }

                else -> CommandResult.Unavailable("Unsupported playback command: $command")
            }
        }
    }

    /**
     * On-demand AV telemetry sampling invoked ONLY when requested by the Phone PWA (/api/v1/telemetry).
     * Never spawns a continuous polling loop or allocates extra buffers.
     */
    suspend fun sampleTelemetry(): PlaybackTelemetrySnapshot {
        val preWarm = NextEpisodePreWarmer.status.value
        val preWarmSnap = PreWarmTelemetrySnapshot(
            state = preWarm.state.name,
            targetLabel = preWarm.targetLabel,
            cachedBytes = preWarm.cachedBytes,
            targetBytes = preWarm.targetBytes
        )
        val controller = synchronized(lock) { controllerRef?.get() }
            ?: return PlaybackTelemetrySnapshot(
                active = false,
                speculativePreWarm = preWarmSnap
            )

        return withContext(Dispatchers.Main) {
            val info = runCatching { controller.buildStreamInfoData() }.getOrNull()
            val player = controller._exoPlayer
            val bufferedAhead = player?.let { (it.bufferedPosition - it.currentPosition).coerceAtLeast(0L) }
                ?: (controller.playbackTimeline.value.bufferedPosition - controller.playbackTimeline.value.currentPosition).coerceAtLeast(0L)
            val dropped = runCatching { player?.videoDecoderCounters?.droppedBufferCount }.getOrNull() ?: 0
            val throughputMbps = (controller.playbackAnalyticsDiagnostics.currentBandwidthEstimateBps() ?: 0L) / 1_000_000.0
            val (cacheUsedMb, cacheMaxMb) = PlayerMediaSourceFactory.getVodCacheStats()
            val channelsInt = controller.uiState.value.audioTracks.firstOrNull { it.isSelected }?.channelCount ?: 0

            PlaybackTelemetrySnapshot(
                active = true,
                engine = info?.playerEngine ?: controller.currentInternalPlayerEngine.name,
                videoCodec = info?.videoCodec,
                width = info?.videoWidth ?: 0,
                height = info?.videoHeight ?: 0,
                frameRateFps = info?.videoFrameRate ?: 0f,
                droppedFrames = dropped,
                videoBitrateBps = info?.videoBitrate ?: 0,
                audioCodec = info?.audioCodec,
                audioChannels = channelsInt,
                bufferedDurationMs = bufferedAhead,
                totalBufferAheadMs = bufferedAhead,
                networkThroughputMbps = throughputMbps,
                vodCacheUsedMb = cacheUsedMb,
                vodCacheMaxMb = cacheMaxMb,
                speculativePreWarm = preWarmSnap
            )
        }
    }

    /** Returns alternate stream candidates cached in the active player controller for 1-tap hot-swap. */
    fun getCurrentStreams(): List<StreamCandidateSnapshot> {
        val controller = synchronized(lock) { controllerRef?.get() } ?: return emptyList()
        val uiState = controller.uiState.value
        val currentUrl = uiState.currentStreamUrl
        return uiState.sourceAllStreams
            .filter { !it.url.isNullOrBlank() }
            .map { stream ->
                val url = stream.url.orEmpty()
                StreamCandidateSnapshot(
                    url = url,
                    title = stream.title ?: stream.name ?: "Stream",
                    label = listOfNotNull(stream.name, stream.title).distinct().joinToString(" · ").ifBlank { url },
                    addonName = stream.addonName,
                    description = stream.description,
                    headers = stream.behaviorHints?.proxyHeaders?.request.orEmpty(),
                    isCurrent = currentUrl != null && url == currentUrl
                )
            }
    }

    /** Hot-swaps the active stream while preserving exact playback timestamp. */
    suspend fun hotSwapStream(
        streamUrl: String,
        title: String,
        headers: Map<String, String>? = null
    ): CommandResult {
        val controller = synchronized(lock) { controllerRef?.get() }
            ?: return CommandResult.Unavailable("No active player session to hot-swap")

        return withContext(Dispatchers.Main) {
            val stream = Stream(
                name = title,
                title = title,
                description = "Hot-swapped via Phone Remote",
                url = streamUrl,
                ytId = null,
                infoHash = null,
                fileIdx = null,
                externalUrl = null,
                behaviorHints = if (!headers.isNullOrEmpty()) {
                    StreamBehaviorHints(
                        notWebReady = null,
                        bingeGroup = null,
                        countryWhitelist = null,
                        proxyHeaders = ProxyHeaders(request = headers, response = null)
                    )
                } else null,
                addonName = "Phone Remote",
                addonLogo = null
            )
            controller.hotSwapToStream(stream)
            CommandResult.Success("Hot-swapped to $title")
        }
    }
}
