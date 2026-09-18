package com.nuvio.tv.core.control

import com.nuvio.tv.ui.screens.player.PlayerEvent
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
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
                        selectedSubtitleIndex = uiState.selectedSubtitleTrackIndex
                    )
                }.collect { snapshot ->
                    _playbackSnapshot.value = snapshot
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
}
