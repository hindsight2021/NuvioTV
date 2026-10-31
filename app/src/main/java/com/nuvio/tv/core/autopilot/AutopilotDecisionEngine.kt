package com.nuvio.tv.core.autopilot

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.ai.AiManager
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import kotlinx.coroutines.flow.firstOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/**
 * Decision engine that determines the next episode to autoplay for the viewer when Autopilot is active.
 *
 * Supports two strategies:
 *  - [AutopilotMode.DEFAULT]: deterministic, prioritizes new release episodes across Continue Watching,
 *    then falls back to the most recently watched/in-line Continue Watching shows.
 *  - [AutopilotMode.AI_CURATED]: delegates selection to Gemini AI with a rich contextual prompt evaluating
 *    the available Continue Watching candidates, narrative tone, and viewing context.
 */
@Singleton
class AutopilotDecisionEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val continueWatchingEnrichmentCache: ContinueWatchingEnrichmentCache,
    private val aiManager: AiManager,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository? = null
) {

    /**
     * Selects the next episode to autoplay based on the current playback context and [mode].
     *
     * @return the chosen [AutopilotDecision], or `null` when no candidates are available.
     */
    suspend fun selectNextEpisode(
        currentContentId: String? = null,
        currentSeason: Int? = null,
        currentEpisode: Int? = null,
        currentTitle: String? = null,
        currentGenres: List<String> = emptyList(),
        mode: AutopilotMode = AutopilotMode.DEFAULT
    ): AutopilotDecision? {
        val candidates = loadCandidates(currentContentId)
        if (candidates.isEmpty()) {
            Log.d(TAG, "selectNextEpisode: no candidates available in Continue Watching")
            return null
        }
        return when (mode) {
            AutopilotMode.DEFAULT -> selectDefault(candidates)
            AutopilotMode.AI_CURATED -> selectAiCurated(
                candidates = candidates,
                currentTitle = currentTitle,
                currentSeason = currentSeason,
                currentEpisode = currentEpisode,
                currentGenres = currentGenres
            )
        }
    }

    /**
     * Loads and merges autopilot candidates from the Continue Watching enrichment cache.
     *
     * Combines "next up" and "in progress" snapshots, filters out the currently playing content,
     * drops unaired episodes and completed in-progress items, and deduplicates by [AutopilotCandidate.contentId].
     */
    suspend fun loadCandidates(currentContentId: String?): List<AutopilotCandidate> {
        val nextUpItems = runCatching { continueWatchingEnrichmentCache.getNextUpSnapshot() }
            .onFailure { Log.w(TAG, "Failed to load nextUp snapshot", it) }
            .getOrDefault(emptyList())

        val inProgressItems = runCatching { continueWatchingEnrichmentCache.getInProgressSnapshot() }
            .onFailure { Log.w(TAG, "Failed to load inProgress snapshot", it) }
            .getOrDefault(emptyList())

        val now = System.currentTimeMillis()
        val newReleaseWindowMs = 14L * 24 * 3600 * 1000L

        val nextUpCandidates = nextUpItems
            .asSequence()
            .filter { currentContentId.isNullOrBlank() || it.contentId != currentContentId }
            .filter { it.hasAired }
            .map { item ->
                val isNewRelease = item.isReleaseAlert ||
                    item.isNewSeasonRelease ||
                    (item.releaseTimestamp != null && now - item.releaseTimestamp <= newReleaseWindowMs)
                AutopilotCandidate(
                    contentId = item.contentId,
                    contentType = item.contentType,
                    title = item.name,
                    poster = item.poster,
                    backdrop = item.backdrop,
                    logo = item.logo,
                    videoId = item.videoId,
                    season = item.season,
                    episode = item.episode,
                    episodeTitle = item.episodeTitle,
                    episodeDescription = item.episodeDescription,
                    thumbnail = item.thumbnail,
                    genres = item.genres,
                    isNewRelease = isNewRelease,
                    releaseDateLabel = item.airDateLabel,
                    lastWatched = item.lastWatched,
                    imdbRating = item.imdbRating
                )
            }
            .toList()

        val inProgressCandidates = inProgressItems
            .asSequence()
            .filter { currentContentId.isNullOrBlank() || it.contentId != currentContentId }
            .filter { item -> item.progressPercent == null || item.progressPercent < 0.95f }
            .map { item ->
                AutopilotCandidate(
                    contentId = item.contentId,
                    contentType = item.contentType,
                    title = item.name,
                    poster = item.poster,
                    backdrop = item.backdrop,
                    logo = item.logo,
                    videoId = item.videoId,
                    season = item.season,
                    episode = item.episode,
                    episodeTitle = item.episodeTitle,
                    episodeDescription = item.episodeDescription,
                    thumbnail = item.episodeThumbnail,
                    genres = item.genres,
                    isNewRelease = false,
                    releaseDateLabel = null,
                    lastWatched = item.lastWatched,
                    imdbRating = item.episodeImdbRating
                )
            }
            .toList()

        // Deduplicate: if an item is already partially watched in inProgress, prefer it; otherwise use nextUp
        val inProgressIds = inProgressCandidates.map { it.contentId }.toSet()
        val combined = inProgressCandidates + nextUpCandidates.filter { it.contentId !in inProgressIds }

        if (combined.isNotEmpty()) {
            Log.d(TAG, "loadCandidates: found ${combined.size} valid CW candidates from cache")
            return combined
        }

        // Fallback: load directly from WatchProgressRepository if cache is empty
        val repo = watchProgressRepository
        val repoItems = if (repo != null) {
            val list = runCatching {
                repo.continueWatching.firstOrNull()
            }.getOrNull().orEmpty()
            list
                .filter { currentContentId.isNullOrBlank() || it.contentId != currentContentId }
                .filter { (it.duration <= 0L) || (it.position < (it.duration * 0.95f)) }
                .map { p ->
                    AutopilotCandidate(
                        contentId = p.contentId,
                        contentType = p.contentType,
                        title = p.name,
                        poster = p.poster,
                        backdrop = p.backdrop,
                        logo = p.logo,
                        videoId = p.videoId,
                        season = p.season,
                        episode = p.episode,
                        episodeTitle = p.episodeTitle,
                        episodeDescription = null,
                        thumbnail = p.backdrop ?: p.poster,
                        genres = emptyList(),
                        isNewRelease = false,
                        releaseDateLabel = null,
                        lastWatched = p.lastWatched,
                        imdbRating = null
                    )
                }
        } else {
            emptyList()
        }

        Log.d(TAG, "loadCandidates: found ${repoItems.size} valid CW candidates from repo fallback")
        return repoItems
    }

    /**
     * Deterministic selection:
     * 1. Check for any candidates with isNewRelease == true, sorted by lastWatched descending or release timestamp.
     * 2. If no new releases, take the top candidate sorted by lastWatched descending (most recently watched show).
     */
    fun selectDefault(candidates: List<AutopilotCandidate>): AutopilotDecision? {
        if (candidates.isEmpty()) return null

        val newReleases = candidates.filter { it.isNewRelease }
        val chosen = if (newReleases.isNotEmpty()) {
            newReleases.maxByOrNull { it.lastWatched } ?: newReleases.first()
        } else {
            candidates.maxByOrNull { it.lastWatched } ?: candidates.first()
        }

        val rationale = if (chosen.isNewRelease) {
            "New release: ${chosen.title}"
        } else {
            "Next in Continue Watching: ${chosen.title}"
        }

        Log.d(TAG, "selectDefault: chosen '${chosen.title}' (isNewRelease=${chosen.isNewRelease})")
        return AutopilotDecision(
            candidate = chosen,
            mode = AutopilotMode.DEFAULT,
            aiRationale = rationale
        )
    }

    /**
     * AI-curated selection using Gemini.
     *
     * Constructs candidate list JSON and sends to Gemini via AiManager.
     * Falls back seamlessly to Default strategy if Gemini call fails or produces invalid JSON.
     */
    suspend fun selectAiCurated(
        candidates: List<AutopilotCandidate>,
        currentTitle: String? = null,
        currentSeason: Int? = null,
        currentEpisode: Int? = null,
        currentGenres: List<String> = emptyList()
    ): AutopilotDecision? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) {
            return AutopilotDecision(
                candidate = candidates.first(),
                mode = AutopilotMode.AI_CURATED,
                aiRationale = "Only unwatched show in Continue Watching"
            )
        }

        return try {
            val candidateJsonArray = buildCandidateJson(candidates)
            val systemPrompt = buildString {
                append("You are Nuvio Autopilot, an intelligent TV streaming co-pilot that curates what to watch next.\n")
                append("The viewer just finished watching: '")
                append(currentTitle.orEmpty())
                append("'")
                if (currentSeason != null && currentEpisode != null) {
                    append(" (S${currentSeason}E${currentEpisode})")
                }
                if (currentGenres.isNotEmpty()) {
                    append(", Genres: ")
                    append(currentGenres.joinToString())
                }
                append(".\n")
                append("Choose the single best next episode to watch from their available Continue Watching list.\n")
                append("Decision criteria:\n")
                append("1. Prioritize newly released episodes of followed shows if compelling.\n")
                append("2. Consider narrative tone, mood, and pacing (e.g., transition from high-stakes drama to reality/comedy, or maintain binge momentum).\n")
                append("3. Provide a concise, engaging rationale (one punchy sentence).\n")
                append("Respond ONLY with valid JSON in this exact structure:\n")
                append("{\"selectedIndex\": <int>, \"rationale\": \"<one sentence>\"}")
            }

            val prompt = "Choose the next episode to watch from: $candidateJsonArray"
            val queryResult = aiManager.queryRaw(
                context = context,
                systemPrompt = systemPrompt,
                prompt = prompt,
                expectJson = true
            )

            val rawResponse = queryResult.getOrNull()
            val parsed = parseAiResponse(rawResponse)
            if (parsed == null) {
                Log.w(TAG, "selectAiCurated: failed to parse AI response, falling back to default")
                return fallbackAiCurated(candidates)
            }

            val (selectedIndex, rationale) = parsed
            if (selectedIndex !in candidates.indices) {
                Log.w(TAG, "selectAiCurated: selectedIndex=$selectedIndex out of bounds (size=${candidates.size})")
                return fallbackAiCurated(candidates)
            }

            val chosen = candidates[selectedIndex]
            Log.d(TAG, "selectAiCurated: Gemini chose '${chosen.title}' (index=$selectedIndex) - rationale: $rationale")
            AutopilotDecision(
                candidate = chosen,
                mode = AutopilotMode.AI_CURATED,
                aiRationale = rationale
            )
        } catch (t: Throwable) {
            Log.w(TAG, "selectAiCurated failed, falling back to default", t)
            fallbackAiCurated(candidates)
        }
    }

    private fun buildCandidateJson(candidates: List<AutopilotCandidate>): JSONArray {
        val array = JSONArray()
        candidates.forEachIndexed { index, candidate ->
            val obj = JSONObject().apply {
                put("index", index)
                put("title", candidate.title)
                put("episodeCode", candidate.formattedEpisodeCode() ?: "Movie")
                put("genres", JSONArray(candidate.genres))
                put("isNewRelease", candidate.isNewRelease)
                if (!candidate.episodeTitle.isNullOrBlank()) {
                    put("episodeTitle", candidate.episodeTitle)
                }
                put("overview", truncate(candidate.episodeDescription, 160))
            }
            array.put(obj)
        }
        return array
    }

    private fun parseAiResponse(response: String?): Pair<Int, String>? {
        if (response.isNullOrBlank()) return null
        return try {
            val json = JSONObject(extractJsonObject(response))
            val index = json.optInt("selectedIndex", -1)
            val rationale = json.optString("rationale", "").ifBlank { "Smart recommendation" }
            if (index < 0) null else index to rationale
        } catch (t: Throwable) {
            Log.w(TAG, "parseAiResponse: invalid JSON", t)
            null
        }
    }

    private fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
    }

    private fun fallbackAiCurated(candidates: List<AutopilotCandidate>): AutopilotDecision? {
        return selectDefault(candidates)?.copy(
            mode = AutopilotMode.AI_CURATED,
            aiRationale = "Smart recommendation: Next up in Continue Watching"
        )
    }

    private fun truncate(text: String?, maxLength: Int): String {
        if (text.isNullOrBlank()) return ""
        return if (text.length <= maxLength) text else text.take(maxLength).trimEnd() + "..."
    }

    companion object {
        private const val TAG = "AutopilotDecisionEngine"
    }
}
