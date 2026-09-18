package com.nuvio.tv.core.control

import android.content.Context
import com.nuvio.tv.core.ai.AiManager
import com.nuvio.tv.core.ai.ThematicChannelGenerator
import com.nuvio.tv.core.playlist.PlaylistItem
import com.nuvio.tv.core.playlist.PlaylistManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine that translates natural-language user prompts into concrete app actions.
 *
 * The engine asks the AI layer to classify the prompt into a structured JSON intent,
 * then dispatches the resulting action to the appropriate subsystem (navigation,
 * playback, search, or thematic channel generation).
 */
@Singleton
class AiOperatorEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiManager: AiManager,
    private val thematicChannelGenerator: ThematicChannelGenerator,
    private val navigationCommander: NavigationCommander,
    private val playerPlaybackBridge: PlayerPlaybackBridge
) {

    /**
     * Processes a free-form user prompt and performs the corresponding action.
     *
     * @param userPrompt the raw natural-language command from the user.
     * @return a [CommandResult] describing the outcome.
     */
    suspend fun processPrompt(userPrompt: String): CommandResult = withContext(Dispatchers.IO) {
        if (userPrompt.isBlank()) {
            return@withContext CommandResult.Error("Empty prompt provided.")
        }

        val rawResult = aiManager.queryRaw(
            context = context,
            systemPrompt = SYSTEM_PROMPT,
            prompt = userPrompt,
            expectJson = true
        )

        val rawResponse = rawResult.getOrElse { t ->
            return@withContext CommandResult.Error("AI query failed: ${t.message}", t)
        }

        val intent = try {
            parseIntent(rawResponse)
        } catch (t: Throwable) {
            return@withContext CommandResult.Error("Failed to parse AI response: ${t.message}", t)
        }

        try {
            dispatch(intent, userPrompt)
        } catch (t: Throwable) {
            CommandResult.Error("Failed to execute '${intent.intent}': ${t.message}", t)
        }
    }

    private suspend fun dispatch(intent: ParsedIntent, originalPrompt: String): CommandResult {
        return when (intent.intent) {
            INTENT_PLAYBACK_CONTROL -> handlePlaybackControl(intent)
            INTENT_NAVIGATE -> handleNavigate(intent)
            INTENT_SEARCH -> handleSearch(intent, originalPrompt)
            INTENT_THEMATIC_CHANNEL -> handleThematicChannel(intent, originalPrompt)
            INTENT_PLAY -> handlePlay(intent, originalPrompt)
            else -> CommandResult.Error("Unsupported intent: '${intent.intent}'")
        }
    }

    private suspend fun handlePlaybackControl(intent: ParsedIntent): CommandResult {
        val action = intent.controlAction
            ?: return CommandResult.Error("playback_control intent missing 'controlAction'.")

        val command = when (action.lowercase()) {
            "pause" -> AppCommand.Pause
            "resume", "play" -> AppCommand.Play
            "next" -> AppCommand.Next
            "stop" -> AppCommand.Stop
            else -> return CommandResult.Error("Unknown control action: '$action'")
        }
        return playerPlaybackBridge.executePlaybackCommand(command)
    }

    private fun handleNavigate(intent: ParsedIntent): CommandResult {
        val screenName = intent.screen
            ?: return CommandResult.Error("navigate intent missing 'screen'.")

        val route = resolveScreenRoute(screenName)
            ?: return CommandResult.Error("Unknown screen: '$screenName'")

        navigationCommander.navigateTo(route)
        return CommandResult.Success("Navigated to '$screenName'.", mapOf("route" to route))
    }

    private fun handleSearch(intent: ParsedIntent, originalPrompt: String): CommandResult {
        val query = intent.title?.takeIf { it.isNotBlank() } ?: originalPrompt
        navigationCommander.search(query)
        return CommandResult.Success("Searching for '$query'.", mapOf("query" to query))
    }

    private suspend fun handleThematicChannel(intent: ParsedIntent, originalPrompt: String): CommandResult {
        val theme = intent.thematicPrompt?.takeIf { it.isNotBlank() }
            ?: intent.title?.takeIf { it.isNotBlank() }
            ?: originalPrompt

        val channelResult = thematicChannelGenerator.generateChannel(context, theme)
        val channel = channelResult.getOrElse { t ->
            return CommandResult.Error("Thematic channel generation failed: ${t.message}", t)
        }

        val items = channel.tracks.mapIndexed { index, track ->
            PlaylistItem(
                contentId = "thematic_${System.currentTimeMillis()}_$index",
                videoId = null,
                title = track.title,
                seriesTitle = if (track.type == "series") track.title else null,
                season = track.season,
                episode = track.episode,
                thumbnail = null,
                mediaType = track.type
            )
        }

        if (items.isEmpty()) {
            return CommandResult.Error("Thematic channel '$theme' produced no playable items.")
        }

        val firstItem = PlaylistManager.startThematicChannel(items)
        if (firstItem != null) {
            navigationCommander.search(firstItem.title)
        }

        return CommandResult.Success(
            "Started thematic channel '${channel.channelName}' with ${items.size} items.",
            mapOf("channelName" to channel.channelName, "tagline" to channel.tagline, "tracks" to channel.tracks.size)
        )
    }

    private fun handlePlay(intent: ParsedIntent, originalPrompt: String): CommandResult {
        val title = intent.title?.takeIf { it.isNotBlank() }
            ?: return CommandResult.Error("play intent missing 'title'.")

        navigationCommander.search(title)
        return CommandResult.Success(
            "Searching for '$title' to play.",
            mapOf("title" to title, "season" to intent.season, "episode" to intent.episode)
        )
    }

    private fun resolveScreenRoute(name: String): String? = when (name.lowercase().trim()) {
        "home" -> "home"
        "search" -> "search"
        "library" -> "library"
        "live_tv", "livetv", "live tv" -> "live_tv"
        "calendar" -> "calendar"
        "settings" -> "settings"
        "addon_manager", "addons" -> "addon_manager"
        "plugins" -> "plugins"
        else -> null
    }

    private fun parseIntent(raw: String): ParsedIntent {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val json = extractJsonObject(clean)
        return ParsedIntent(
            intent = json.optString("intent").lowercase(),
            title = json.optStringOrNull("title"),
            contentType = json.optStringOrNull("contentType"),
            season = json.optIntOrNull("season"),
            episode = json.optIntOrNull("episode"),
            screen = json.optStringOrNull("screen"),
            controlAction = json.optStringOrNull("controlAction"),
            thematicPrompt = json.optStringOrNull("thematicPrompt")
        )
    }

    private fun extractJsonObject(raw: String): JSONObject {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) {
            throw IllegalArgumentException("No JSON object found in AI response: $raw")
        }
        return JSONObject(raw.substring(start, end + 1))
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key, "").trim()
        return value.ifBlank { null }?.takeUnless { it.equals("null", ignoreCase = true) }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private data class ParsedIntent(
        val intent: String,
        val title: String?,
        val contentType: String?,
        val season: Int?,
        val episode: Int?,
        val screen: String?,
        val controlAction: String?,
        val thematicPrompt: String?
    )

    private companion object {
        const val INTENT_PLAY = "play"
        const val INTENT_THEMATIC_CHANNEL = "thematic_channel"
        const val INTENT_SEARCH = "search"
        const val INTENT_NAVIGATE = "navigate"
        const val INTENT_PLAYBACK_CONTROL = "playback_control"

        val SYSTEM_PROMPT: String = """
            You are an intent classifier for a smart TV streaming application.
            Analyze the user's request and respond with ONLY a single JSON object,
            no prose, no markdown, no code fences. Use this exact schema:

            {
              "intent": "play" | "thematic_channel" | "search" | "navigate" | "playback_control",
              "title": "string or null",
              "contentType": "movie" | "series" | null,
              "season": integer or null,
              "episode": integer or null,
              "screen": "string or null",
              "controlAction": "pause" | "resume" | "next" | "stop" | null,
              "thematicPrompt": "string or null"
            }

            Rules:
            - Use "play" when the user wants to watch a specific show, movie, or episode.
            - Use "thematic_channel" when the user wants a marathon, mood channel, or curated vibe.
            - Use "search" when the user wants to look up content or browse titles.
            - Use "navigate" when the user wants to go to a screen (home, search, library, live_tv, calendar, settings, addons, plugins).
            - Use "playback_control" for pause, resume, play, next, or stop playback.
            - Always return valid JSON.
        """.trimIndent()
    }
}
