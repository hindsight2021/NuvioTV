package com.nuvio.tv.core.control

import com.nuvio.tv.core.playlist.PlaylistItem

/**
 * Represents a directional-pad / remote key that can be forwarded to the UI layer.
 */
enum class DpadKey { UP, DOWN, LEFT, RIGHT, SELECT, BACK, MENU }

/**
 * A high-level, transport-agnostic command that can be dispatched to the app.
 *
 * Commands are grouped by domain (playback, navigation, content, queue, thematic, AI).
 * Each command is a pure data carrier; execution semantics live in the command handler.
 */
sealed interface AppCommand {

    // ---------------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------------

    /** Start or resume playback of the current media. */
    data object Play : AppCommand

    /** Pause the current playback. */
    data object Pause : AppCommand

    /** Toggle between play and pause. */
    data object PlayPause : AppCommand

    /** Stop playback and release playback resources. */
    data object Stop : AppCommand

    /** Seek to an absolute position in the current media. */
    data class SeekTo(val positionMs: Long) : AppCommand

    /** Seek relative to the current position (negative values rewind). */
    data class SeekBy(val deltaMs: Long) : AppCommand

    /** Skip to the next item in the queue. */
    data object Next : AppCommand

    /** Return to the previous item in the queue. */
    data object Previous : AppCommand

    /** Skip the currently playing intro segment. */
    data object SkipIntro : AppCommand

    /** Set the playback speed multiplier (e.g. 1.0f, 1.5f). */
    data class SetSpeed(val speed: Float) : AppCommand

    /** Select an audio track by index. */
    data class SelectAudioTrack(val index: Int) : AppCommand

    /** Select a subtitle track by index. */
    data class SelectSubtitleTrack(val index: Int) : AppCommand

    /** Turn subtitles off entirely. */
    data object DisableSubtitles : AppCommand

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

    /** Navigate to a named screen (e.g. "home", "settings"). */
    data class OpenScreen(val screen: String) : AppCommand

    /** Open the details screen for a specific content item. */
    data class OpenDetails(val contentId: String, val contentType: String) : AppCommand

    /** Open search with a pre-filled query. */
    data class Search(val query: String) : AppCommand

    /** Forward a raw D-pad key event to the UI layer. */
    data class SendDpad(val key: DpadKey) : AppCommand

    // ---------------------------------------------------------------------
    // Content
    // ---------------------------------------------------------------------

    /**
     * Resolve and play a content item, optionally scoped to a season/episode.
     */
    data class PlayMedia(
        val contentId: String,
        val contentType: String,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
    ) : AppCommand

    /**
     * Play a direct stream URL, optionally with HTTP headers.
     */
    data class PlayStream(
        val streamUrl: String,
        val title: String,
        val headers: Map<String, String>? = null,
    ) : AppCommand

    // ---------------------------------------------------------------------
    // Queue
    // ---------------------------------------------------------------------

    /** Append an item to the end of the queue. */
    data class QueueAdd(val item: PlaylistItem) : AppCommand

    /** Insert an item to play immediately after the current one. */
    data class PlayNextItem(val item: PlaylistItem) : AppCommand

    /** Remove all items from the queue. */
    data object ClearQueue : AppCommand

    /** Advance to the next item in the queue. */
    data object AdvanceQueue : AppCommand

    // ---------------------------------------------------------------------
    // Thematic
    // ---------------------------------------------------------------------

    /** Start a thematic channel generated from a free-form prompt. */
    data class PlayThematicChannel(val prompt: String) : AppCommand

    /** Start a curated mood channel by its identifier. */
    data class PlayCuratedMood(val moodId: String) : AppCommand

    // ---------------------------------------------------------------------
    // AI
    // ---------------------------------------------------------------------

    /** Execute a natural-language prompt through the AI subsystem. */
    data class ExecuteAiPrompt(val prompt: String) : AppCommand
}

/**
 * Outcome of executing an [AppCommand].
 */
sealed class CommandResult {

    /**
     * The command completed successfully.
     *
     * @param message human-readable status message.
     * @param data optional payload produced by the command.
     */
    data class Success(val message: String = "OK", val data: Any? = null) : CommandResult()

    /**
     * The command failed during execution.
     *
     * @param message description of the failure.
     * @param cause optional underlying exception.
     */
    data class Error(val message: String, val cause: Throwable? = null) : CommandResult()

    /**
     * The command could not be executed because a required capability is missing.
     *
     * @param reason explanation of why the command is unavailable.
     */
    data class Unavailable(val reason: String) : CommandResult()
}
