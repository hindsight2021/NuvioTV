package com.nuvio.tv.core.control

import android.content.Context
import com.nuvio.tv.core.ai.ThematicChannelGenerator
import com.nuvio.tv.core.livetv.AiBroadcastNetwork
import com.nuvio.tv.core.livetv.AiBroadcastTuneCoordinator
import com.nuvio.tv.core.livetv.LiveTvManager
import com.nuvio.tv.core.playlist.PlaylistItem
import com.nuvio.tv.core.playlist.PlaylistManager
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ambient.coordinator.AmbientCoordinator
import com.nuvio.tv.ambient.coordinator.AmbientIdleController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central command bus that routes [AppCommand] instances to the appropriate subsystem.
 *
 * The bus decouples command producers (voice, AI, remote input, REST API) from the concrete
 * implementations that fulfill them (playback, navigation, playlist, thematic channels, AI).
 */
@Singleton
class AppCommandBus @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerPlaybackBridge: PlayerPlaybackBridge,
    private val navigationCommander: NavigationCommander,
    private val justPlayCoordinator: JustPlayCoordinator,
    private val aiOperatorEngine: AiOperatorEngine,
    private val thematicChannelGenerator: ThematicChannelGenerator,
    private val ambientCoordinator: AmbientCoordinator? = null,
    private val ambientIdleController: AmbientIdleController? = null,
) {

    /**
     * Dispatches a single [command] to its owning subsystem and returns a [CommandResult].
     */
    fun playbackRequestStatus(id: String) = justPlayCoordinator.status(id)
    fun cancelPlaybackRequest(id: String) { justPlayCoordinator.cancel(id) }

    private fun dropScreensaver() {
        ambientCoordinator?.stopAmbient()
        ambientIdleController?.notifyUserActivity()
    }

    suspend fun dispatch(command: AppCommand): CommandResult {
        return when (command) {
            // --- Playback commands: delegated to the playback bridge ---
            AppCommand.Play,
            AppCommand.Pause,
            AppCommand.PlayPause,
            AppCommand.Stop,
            is AppCommand.SeekTo,
            is AppCommand.SeekBy,
            AppCommand.Next,
            AppCommand.Previous,
            AppCommand.SkipIntro,
            is AppCommand.SetSpeed,
            is AppCommand.SelectAudioTrack,
            is AppCommand.SelectSubtitleTrack,
            AppCommand.DisableSubtitles -> {
                when (command) {
                    AppCommand.Play,
                    AppCommand.PlayPause -> dropScreensaver()
                    else -> Unit
                }
                playerPlaybackBridge.executePlaybackCommand(command)
            }

            // --- Navigation commands ---
            is AppCommand.OpenScreen -> {
                navigationCommander.navigateTo(command.screen)
                CommandResult.Success("Navigated to ${command.screen}")
            }

            is AppCommand.OpenDetails -> {
                navigationCommander.openDetails(command.contentId, command.contentType)
                CommandResult.Success("Opened details for ${command.contentId}")
            }

            is AppCommand.Search -> {
                navigationCommander.search(command.query)
                CommandResult.Success("Searching for ${command.query}")
            }

            is AppCommand.SendDpad -> {
                navigationCommander.sendDpad(command.key)
                CommandResult.Success("Sent Dpad key ${command.key}")
            }

            // --- Content commands ---
            is AppCommand.PlayMedia -> {
                dropScreensaver()
                justPlayCoordinator.request(command)
            }

            is AppCommand.PlayStream -> {
                dropScreensaver()
                if (playerPlaybackBridge.playbackSnapshot.value?.isActive == true) {
                    playerPlaybackBridge.hotSwapStream(command.streamUrl, command.title, command.headers)
                } else {
                    navigationCommander.navigateTo(
                        Screen.Player.createRoute(
                            streamUrl = command.streamUrl,
                            title = command.title,
                            headers = command.headers
                        )
                    )
                    CommandResult.Success("Launching stream: ${command.title}")
                }
            }

            is AppCommand.HotSwapStream -> {
                dropScreensaver()
                playerPlaybackBridge.hotSwapStream(command.streamUrl, command.title, command.headers)
            }

            // --- Queue commands ---
            is AppCommand.QueueAdd -> {
                PlaylistManager.addToQueue(command.item)
                CommandResult.Success("Added to queue: ${command.item.title}")
            }

            is AppCommand.PlayNextItem -> {
                dropScreensaver()
                PlaylistManager.playNext(command.item)
                CommandResult.Success("Set to play next: ${command.item.title}")
            }

            AppCommand.ClearQueue -> {
                PlaylistManager.clear()
                CommandResult.Success("Queue cleared")
            }

            AppCommand.AdvanceQueue -> {
                val next = PlaylistManager.next()
                CommandResult.Success("Advanced queue", next)
            }

            is AppCommand.ReorderQueue -> {
                val queue = PlaylistManager.queue.value
                if (command.fromIndex in queue.indices && command.toIndex in queue.indices) {
                    PlaylistManager.move(command.fromIndex, command.toIndex)
                    CommandResult.Success("Reordered queue")
                } else {
                    CommandResult.Unavailable("Invalid queue indices: ${command.fromIndex} -> ${command.toIndex}")
                }
            }

            is AppCommand.RemoveQueueItem -> {
                val target = PlaylistManager.queue.value.getOrNull(command.index)
                if (target != null) {
                    PlaylistManager.removeAt(command.index)
                    CommandResult.Success("Removed ${target.title}")
                } else {
                    CommandResult.Unavailable("Invalid queue index: ${command.index}")
                }
            }

            is AppCommand.TuneLiveChannel -> {
                dropScreensaver()
                val aiConfig = AiBroadcastNetwork.findChannelById(context, command.channelId)
                if (aiConfig != null) {
                    AiBroadcastTuneCoordinator.tuneAiChannel(
                        context = context,
                        channelId = aiConfig.id,
                        startFromBeginning = command.startFromBeginning,
                        justPlayCoordinator = justPlayCoordinator
                    )
                } else {
                    val channel = LiveTvManager.getChannelById(command.channelId)
                        ?: return CommandResult.Unavailable("Channel not found: ${command.channelId}")
                    val url = channel.streamUrl
                    if (!url.isNullOrBlank()) {
                        navigationCommander.navigateTo(
                            Screen.Player.createRoute(
                                streamUrl = url,
                                title = channel.name,
                                contentName = channel.currentProgram.title,
                                contentType = "live",
                                poster = channel.currentProgram.posterUrl,
                                backdrop = channel.currentProgram.backdropUrl,
                                videoId = channel.id
                            )
                        )
                        CommandResult.Success("Tuned ${channel.name}")
                    } else {
                        LiveTvManager.tuneToChannel(context, channel)
                        CommandResult.Success("Launched ${channel.name}")
                    }
                }
            }

            // --- Thematic channel commands ---
            is AppCommand.PlayThematicChannel -> {
                dropScreensaver()
                val channelResult = thematicChannelGenerator.generateChannel(context, command.prompt)
                val channel = channelResult.getOrElse { t ->
                    return CommandResult.Error("Failed to generate channel: ${t.message}", t)
                }
                val items = channel.tracks.mapIndexed { idx, track ->
                    PlaylistItem(
                        contentId = "thematic_${System.currentTimeMillis()}_$idx",
                        videoId = null,
                        title = track.title,
                        seriesTitle = if (track.type == "series") track.title else null,
                        season = track.season,
                        episode = track.episode,
                        thumbnail = null,
                        mediaType = track.type
                    )
                }
                val first = PlaylistManager.startThematicChannel(items)
                if (first != null) {
                    navigationCommander.search(first.title)
                }
                CommandResult.Success("Started thematic channel '${channel.channelName}'", items)
            }

            is AppCommand.PlayCuratedMood -> {
                dropScreensaver()
                val mood = thematicChannelGenerator.curatedMoods.firstOrNull { it.id == command.moodId }
                    ?: return CommandResult.Unavailable("Unknown mood: ${command.moodId}")

                val channelResult = thematicChannelGenerator.generateChannel(context, mood.prompt)
                val channel = channelResult.getOrElse { t ->
                    return CommandResult.Error("Failed to generate mood channel: ${t.message}", t)
                }
                val items = channel.tracks.mapIndexed { idx, track ->
                    PlaylistItem(
                        contentId = "curated_${mood.id}_$idx",
                        videoId = null,
                        title = track.title,
                        seriesTitle = if (track.type == "series") track.title else null,
                        season = track.season,
                        episode = track.episode,
                        thumbnail = null,
                        mediaType = track.type
                    )
                }
                val first = PlaylistManager.startThematicChannel(items)
                if (first != null) {
                    navigationCommander.search(first.title)
                }
                CommandResult.Success("Started mood channel '${mood.name}'", items)
            }

            // --- AI commands ---
            is AppCommand.ExecuteAiPrompt -> {
                aiOperatorEngine.processPrompt(command.prompt)
            }
        }
    }
}
