package com.nuvio.tv.core.control

import android.content.Context
import com.nuvio.tv.core.ai.ThematicChannelGenerator
import com.nuvio.tv.core.playlist.PlaylistItem
import com.nuvio.tv.core.playlist.PlaylistManager
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
    private val aiOperatorEngine: AiOperatorEngine,
    private val thematicChannelGenerator: ThematicChannelGenerator
) {

    /**
     * Dispatches a single [command] to its owning subsystem and returns a [CommandResult].
     */
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
                navigationCommander.search(command.title ?: command.contentId)
                CommandResult.Success("Initiated playback search for ${command.title ?: command.contentId}")
            }

            is AppCommand.PlayStream -> {
                CommandResult.Unavailable("Direct stream injection requires active player session")
            }

            // --- Queue commands ---
            is AppCommand.QueueAdd -> {
                PlaylistManager.addToQueue(command.item)
                CommandResult.Success("Added to queue: ${command.item.title}")
            }

            is AppCommand.PlayNextItem -> {
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

            // --- Thematic channel commands ---
            is AppCommand.PlayThematicChannel -> {
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
