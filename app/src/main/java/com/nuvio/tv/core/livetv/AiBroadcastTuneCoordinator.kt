package com.nuvio.tv.core.livetv

import android.content.Context
import com.nuvio.tv.core.ai.AiTtsPlayer
import com.nuvio.tv.core.ai.AiVoicePersona
import com.nuvio.tv.core.control.AppCommand
import com.nuvio.tv.core.control.CommandResult
import com.nuvio.tv.core.control.JustPlayCoordinator
import com.nuvio.tv.core.playlist.PlaylistItem
import com.nuvio.tv.core.playlist.PlaylistManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveAiBroadcastStation(
    val channelId: String,
    val channelNumber: String,
    val channelName: String,
    val callSign: String,
    val accentColorHex: String,
    val djHostName: String,
    val programTitle: String,
    val contentId: String,
    val pendingLiveSeekMs: Long,
    val tunedAtMs: Long = System.currentTimeMillis()
)

/**
 * Coordinates tuning into a 24/7 AI Broadcast Network channel from TV UI, Phone PWA, or AI Voice:
 * - Queues the channel's upcoming schedule block into [PlaylistManager]
 * - Speaks a DJ bumper intro via [AiTtsPlayer] in the channel's [AiVoicePersona] when enabled
 * - Records any pending live wall-clock seek offset & exposes [onAirStation] for the player bug overlay
 */
object AiBroadcastTuneCoordinator {

    private val _onAirStation = MutableStateFlow<ActiveAiBroadcastStation?>(null)
    val onAirStation: StateFlow<ActiveAiBroadcastStation?> = _onAirStation.asStateFlow()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AiTuneEntryPoint {
        fun justPlayCoordinator(): JustPlayCoordinator
    }

    @Volatile
    private var ttsPlayer: AiTtsPlayer? = null

    suspend fun tuneAiChannel(
        context: Context,
        channelId: String,
        startFromBeginning: Boolean
    ): CommandResult {
        val coordinator = runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                AiTuneEntryPoint::class.java
            ).justPlayCoordinator()
        }.getOrElse {
            return CommandResult.Error("Playback coordinator unavailable: ${it.message}")
        }
        return tuneAiChannel(
            context = context,
            channelId = channelId,
            startFromBeginning = startFromBeginning,
            justPlayCoordinator = coordinator
        )
    }

    suspend fun tuneAiChannel(
        context: Context,
        channelId: String,
        startFromBeginning: Boolean,
        justPlayCoordinator: JustPlayCoordinator
    ): CommandResult {
        val config = AiBroadcastNetwork.findChannelById(context, channelId)
            ?: return CommandResult.Unavailable("AI channel not found: $channelId")

        val nowMs = System.currentTimeMillis()
        val slots = AiBroadcastNetwork.buildScheduleForChannel(config, nowMs = nowMs, slotCount = 5)
        val currentSlot = slots.firstOrNull()
            ?: return CommandResult.Unavailable("No scheduled programs on ${config.name}")

        val playlistItems = slots.map { slot ->
            val p = slot.program
            PlaylistItem(
                contentId = p.contentId,
                videoId = null,
                title = p.title,
                seriesTitle = if (p.mediaType == "series") p.title else null,
                season = p.season,
                episode = p.episode,
                thumbnail = p.backdropUrl,
                mediaType = p.mediaType
            )
        }
        PlaylistManager.startThematicChannel(playlistItems)

        val seekOffsetMs = if (startFromBeginning) 0L else currentSlot.elapsedMs
        _onAirStation.value = ActiveAiBroadcastStation(
            channelId = config.id,
            channelNumber = config.number,
            channelName = config.name,
            callSign = config.callSign,
            accentColorHex = config.accentColorHex,
            djHostName = config.djHostName,
            programTitle = currentSlot.program.title,
            contentId = currentSlot.program.contentId,
            pendingLiveSeekMs = if (seekOffsetMs > 15_000L) seekOffsetMs else 0L,
            tunedAtMs = nowMs
        )

        if (AiBroadcastPreferences(context).djBumpersEnabled) {
            speakDjBumper(context, config, currentSlot)
        }

        return justPlayCoordinator.request(
            AppCommand.PlayMedia(
                contentId = currentSlot.program.contentId,
                contentType = currentSlot.program.mediaType,
                season = currentSlot.program.season,
                episode = currentSlot.program.episode,
                title = currentSlot.program.title
            )
        )
    }

    /**
     * Consumes and returns the live wall-clock seek offset (ms) once playback starts for the tuned item.
     */
    fun consumePendingSeekIfMatches(contentId: String?): Long? {
        val current = _onAirStation.value ?: return null
        if (current.pendingLiveSeekMs <= 0L) return null
        if (contentId != null && !contentId.equals(current.contentId, ignoreCase = true)) return null
        _onAirStation.value = current.copy(pendingLiveSeekMs = 0L)
        return current.pendingLiveSeekMs
    }

    fun clearOnAir() {
        _onAirStation.value = null
    }

    private fun speakDjBumper(
        context: Context,
        config: AiBroadcastChannelConfig,
        slot: AiBroadcastEpgSlot
    ) {
        runCatching {
            val player = ttsPlayer ?: AiTtsPlayer(context.applicationContext).also { ttsPlayer = it }
            val persona = AiVoicePersona.fromId(config.voicePersonaId)
            val speech = AiBroadcastNetwork.buildDjIntroSpeech(config, slot)
            player.speak(speech, persona)
        }
    }
}
