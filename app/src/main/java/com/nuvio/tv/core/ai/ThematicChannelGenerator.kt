package com.nuvio.tv.core.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class ThematicTrack(
    val title: String,
    val type: String, // "movie" or "series"
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val reason: String,
    val contentId: String? = null
)

data class ThematicChannelResult(
    val channelName: String,
    val tagline: String,
    val tracks: List<ThematicTrack>
)

data class CuratedThematicMood(
    val id: String,
    val icon: String,
    val name: String,
    val description: String,
    val prompt: String
)

@Singleton
class ThematicChannelGenerator @Inject constructor(
    private val aiManager: AiManager
) {
    val curatedMoods: List<CuratedThematicMood> = listOf(
        CuratedThematicMood(
            id = "bravo_reality",
            icon = "\uD83C\uDF78",
            name = "Bravo & Reality Drama",
            description = "High drama, messy confrontations, and luxury shade",
            prompt = "Bravo reality TV high drama, Below Deck Mediterranean, Vanderpump Rules, and Real Housewives"
        ),
        CuratedThematicMood(
            id = "sitcom_comfort",
            icon = "\uD83D\uDECB\uFE0F",
            name = "90s & 2000s Sitcom Comfort",
            description = "Cozy comfort episodes from legendary sitcoms",
            prompt = "Classic comfort comedy sitcom episodes from Friends, Seinfeld, Frasier, The Office, Modern Family"
        ),
        CuratedThematicMood(
            id = "mind_bending_scifi",
            icon = "\uD83D\uDE80",
            name = "Mind-Bending Sci-Fi",
            description = "Twisted realities, existential suspense, and futuristic odysseys",
            prompt = "Mind-bending sci-fi movies and episodes like Black Mirror, Severance, Twilight Zone, Interstellar"
        ),
        CuratedThematicMood(
            id = "whodunit_mysteries",
            icon = "\uD83D\uDD0D",
            name = "Whodunit & Clever Sleuths",
            description = "Twisty mysteries, eccentric detectives, and delicious puzzles",
            prompt = "Whodunit mysteries, Agatha Christie style detective stories, Poker Face, Knives Out, Sherlock"
        ),
        CuratedThematicMood(
            id = "high_camp_classics",
            icon = "\u2728",
            name = "High Camp & Cult Glamour",
            description = "Fabulous, over-the-top, iconic camp cinema and TV",
            prompt = "High camp classics, fabulous over the top cinema and TV like Death Becomes Her, The White Lotus, Clue"
        ),
        CuratedThematicMood(
            id = "holiday_specials",
            icon = "\uD83E\uDD83",
            name = "Thanksgiving & Holiday Specials",
            description = "The funniest Thanksgiving and holiday episodes in sitcom history",
            prompt = "Iconic Thanksgiving and holiday sitcom episodes across Friends, HIMYM, Brooklyn Nine-Nine, New Girl"
        )
    )

    private val systemPrompt = """
        You are an elite TV & Film streaming curator creating custom "Infinite Thematic Channels" for a smart TV app.
        Given a theme or mood, choose 6 to 8 stellar titles (movies or specific standout TV episodes).
        For series, specify the best season and episode number.
        Return ONLY valid JSON matching this schema:
        {
          "channelName": "Short catchy channel name (e.g. Bravo Yacht Drama Channel)",
          "tagline": "One punchy sentence describing the channel vibe",
          "tracks": [
            {
              "title": "Show or Movie Title",
              "imdbId": "tt1234567 (standard IMDb ID if known)",
              "type": "series" or "movie",
              "season": 1 (or null if movie),
              "episode": 3 (or null if movie),
              "episodeTitle": "Episode Title or null",
              "reason": "One punchy sentence why this fits the channel vibe"
            }
          ]
        }
    """.trimIndent()

    suspend fun generateChannel(context: Context, userPrompt: String): Result<ThematicChannelResult> = withContext(Dispatchers.IO) {
        val prompt = "Create a custom streaming channel for this theme/mood: \"$userPrompt\""
        val rawResult = aiManager.queryRaw(context, systemPrompt, prompt, expectJson = true)
        rawResult.mapCatching { jsonStr ->
            val cleanJson = jsonStr.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(cleanJson)
            val channelName = obj.optString("channelName", "Custom Thematic Channel")
            val tagline = obj.optString("tagline", "Curated for your viewing pleasure")
            val tracksArray = obj.optJSONArray("tracks") ?: JSONArray()
            val tracks = mutableListOf<ThematicTrack>()
            for (i in 0 until tracksArray.length()) {
                val item = tracksArray.getJSONObject(i)
                val rawImdbId = item.optString("imdbId").trim()
                val contentId = if (rawImdbId.startsWith("tt")) rawImdbId else null
                tracks.add(
                    ThematicTrack(
                        title = item.optString("title", "Untitled"),
                        type = item.optString("type", "series"),
                        season = if (item.has("season") && !item.isNull("season")) item.getInt("season") else null,
                        episode = if (item.has("episode") && !item.isNull("episode")) item.getInt("episode") else null,
                        episodeTitle = item.optString("episodeTitle").takeIf { it.isNotBlank() },
                        reason = item.optString("reason", "Curated pick for this channel"),
                        contentId = contentId
                    )
                )
            }
            ThematicChannelResult(
                channelName = channelName,
                tagline = tagline,
                tracks = tracks
            )
        }
    }
}
