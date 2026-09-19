package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.AnimeSkipSettingsDataStore
import com.nuvio.tv.data.remote.api.AniSkipApi
import com.nuvio.tv.data.remote.api.AnimeSkipApi
import com.nuvio.tv.data.remote.api.AnimeSkipRequest
import com.nuvio.tv.data.remote.api.IntroDbApi
import com.nuvio.tv.data.remote.api.IntroDbSegment
import com.nuvio.tv.data.remote.api.IntroDbSegmentsResponse
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class SkipInterval(
    val startTime: Double, // seconds
    val endTime: Double,   // seconds
    val type: String,      // intro/op, recap, outro/ed, movie-credits, post-credits
    val provider: String,  // "introdb", "aniskip", "animeskip", "skipme"
    val confidence: Double = 1.0
)

internal fun IntroDbSegmentsResponse.toSkipIntervals(movie: Boolean): List<SkipInterval> {
    if (!movie) return listOfNotNull(
        intro.toSkipIntervalOrNull("intro"),
        recap.toSkipIntervalOrNull("recap"),
        outro.toSkipIntervalOrNull("outro")
    )
    val credits = outro.toSkipIntervalOrNull("movie-credits")
    val scene = postCredits.toSkipIntervalOrNull("post-credits")
    // Skipping credits must not also skip the post-credits scene.
    val safeCredits = if (credits != null && scene != null &&
        scene.startTime < credits.endTime && scene.endTime > credits.startTime
    ) {
        credits.copy(endTime = scene.startTime).takeIf { it.endTime > it.startTime }
    } else credits
    return listOfNotNull(safeCredits, scene)
}

private fun IntroDbSegment?.toSkipIntervalOrNull(type: String): SkipInterval? {
    if (this == null) return null
    val start = startSec ?: startMs?.let { it / 1000.0 } ?: return null
    val end = endSec ?: endMs?.let { it / 1000.0 } ?: return null
    if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start) return null
    return SkipInterval(startTime = start, endTime = end, type = type, provider = "introdb")
}

@Singleton
class SkipIntroRepository @Inject constructor(
    private val introDbApi: IntroDbApi,
    private val aniSkipApi: AniSkipApi,
    private val animeSkipApi: AnimeSkipApi,
    private val simklResolver: SimklIdResolver,
    private val animeSkipSettingsDataStore: AnimeSkipSettingsDataStore,
    private val tmdbService: TmdbService,
    private val enhancedIntroDetector: com.nuvio.tv.core.player.EnhancedIntroDetector = com.nuvio.tv.core.player.EnhancedIntroDetector(),
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {
    private val cache = ConcurrentHashMap<String, List<SkipInterval>>()
    private val animeSkipShowIdCache = ConcurrentHashMap<String, String>()
    private val introDbConfigured = BuildConfig.INTRODB_API_URL.isNotEmpty()
    private val skipMeHttpClient by lazy {
        okHttpClient.newBuilder()
            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    suspend fun getMovieSkipIntervals(
        contentId: String?,
        videoId: String? = null,
        durationMs: Long? = null
    ): List<SkipInterval> = coroutineScope {
        val ids = listOfNotNull(contentId, videoId).distinct()
        val imdbId = ids.firstNotNullOfOrNull { id ->
            id.substringBefore(':').takeIf { it.matches(Regex("tt[0-9]+")) }
        } ?: ids.firstNotNullOfOrNull { id ->
            val parts = id.split(':')
            val value = parts.getOrNull(1) ?: return@firstNotNullOfOrNull null
            when (parts[0]) {
                "tmdb" -> value.toIntOrNull()?.let { tmdbService.tmdbToImdb(it, "movie") }
                "mal", "kitsu" -> simklResolver.resolveIds(parts[0], value)?.imdb
                else -> null
            }
        }
        val tmdbId = ids.firstNotNullOfOrNull { id ->
            if (id.startsWith("tmdb:")) id.substringAfter("tmdb:").toIntOrNull() else null
        }
        if (imdbId == null && tmdbId == null) return@coroutineScope emptyList()
        val key = "movie:${imdbId ?: "tmdb:$tmdbId"}"
        cache[key]?.let { return@coroutineScope it }

        val introDbDeferred = async {
            if (imdbId != null && introDbConfigured) fetchFromIntroDb(imdbId, isMovie = true) else emptyList()
        }
        val skipMeDeferred = async {
            fetchFromSkipMe(
                imdbId = imdbId,
                season = null,
                episode = null,
                isMovie = true,
                durationMs = durationMs,
                tmdbId = tmdbId
            )
        }
        val merged = mergeByPriority(introDbDeferred.await(), skipMeDeferred.await())
        merged.also { cache[key] = it }
    }

    /**
     * Standard path for IMDB-identified content.
     */
    suspend fun getSkipIntervals(
        imdbId: String?,
        season: Int,
        episode: Int,
        durationMs: Long? = null
    ): List<SkipInterval> = coroutineScope {
        val resolvedImdbId = when {
            imdbId.isNullOrBlank() -> null
            imdbId.startsWith("tt") -> imdbId.substringBefore(':')
            imdbId.startsWith("tmdb:") -> {
                val raw = imdbId.substringAfter("tmdb:").substringBefore(':')
                raw.toIntOrNull()?.let { tmdbService.tmdbToImdb(it, "tv") }
            }
            imdbId.toIntOrNull() != null -> {
                tmdbService.tmdbToImdb(imdbId.toInt(), "tv")
            }
            else -> null
        }
        val effectiveImdbId = resolvedImdbId ?: imdbId?.takeIf { it.startsWith("tt") }
        if (effectiveImdbId == null) return@coroutineScope emptyList()
        val cacheKey = "$effectiveImdbId:$season:$episode"
        cache[cacheKey]?.let { return@coroutineScope it }

        val introDbDeferred = async {
            if (introDbConfigured) fetchFromIntroDb(effectiveImdbId, season, episode) else emptyList()
        }
        val skipMeDeferred = async {
            fetchFromSkipMe(
                imdbId = effectiveImdbId,
                season = season,
                episode = episode,
                isMovie = false,
                durationMs = durationMs
            )
        }
        // Resolve IMDB -> season-specific MAL/AniList via Simkl episode mapping
        val simklIdsDeferred = async { simklResolver.resolveIdsForImdbEpisode(effectiveImdbId, season, episode) }
        val simklIds = simklIdsDeferred.await()
        val malId = simklIds?.mal
        val anilistId = simklIds?.anilist

        // Remap the TVDB episode number to the anime-entry-local episode number.
        // When the resolved entry owns a specific TVDB season, its episode mapping
        // tells us which anime episode corresponds to the requested TVDB episode.
        val animeEpisode = if (simklIds != null) {
            val mapping = simklResolver.getEpisodeMapping(simklIds.simklId, simklIds.type)
            mapping.firstOrNull { it.tvdbSeason == season && it.tvdbEpisode == episode }
                ?.animeEpisode
                ?: episode
        } else episode

        val aniSkipDeferred = async {
            if (malId != null) fetchFromAniSkip(malId, animeEpisode) else emptyList()
        }
        val animeSkipDeferred = async {
            if (anilistId != null) fetchFromAnimeSkip(anilistId, animeEpisode, season = null) else emptyList()
        }

        val merged = mergeByPriority(
            introDbDeferred.await(),
            skipMeDeferred.await(),
            animeSkipDeferred.await(),
            aniSkipDeferred.await()
        )
        if (merged.isNotEmpty()) {
            return@coroutineScope merged.also { cache[cacheKey] = it }
        }

        // Smart fallback for uncatalogued reality TV / network series (e.g. Below Deck)
        val learned = enhancedIntroDetector.getLearnedIntro(imdbId, season)
        val result = if (learned != null) listOf(learned) else emptyList()
        return@coroutineScope result.also { cache[cacheKey] = it }
    }

    suspend fun getSkipIntervalsForMal(
        malId: String,
        episode: Int,
        imdbId: String? = null,
        imdbSeason: Int? = null,
        imdbEpisode: Int? = null,
        durationMs: Long? = null
    ): List<SkipInterval> = coroutineScope {
        val cacheKey = "mal:$malId:$episode"
        cache[cacheKey]?.let { return@coroutineScope it }

        val aniSkipDeferred = async { fetchFromAniSkip(malId, episode) }

        val simklIdsDeferred = async { simklResolver.resolveIds("mal", malId) }
        val simklIds = simklIdsDeferred.await()
        val resolvedImdbId = imdbId ?: simklIds?.imdb

        val tvdbDeferred = async {
            if (resolvedImdbId != null && imdbSeason == null && simklIds != null) {
                simklResolver.resolveEpisodeTvdb("mal", malId, episode)
            } else null
        }

        var introDb = emptyList<SkipInterval>()
        var skipMe = emptyList<SkipInterval>()
        var animeSkip = emptyList<SkipInterval>()
        if (resolvedImdbId != null) {
            val tvdb = tvdbDeferred.await()
            val introDbSeason = imdbSeason ?: tvdb?.first ?: return@coroutineScope run {
                mergeByPriority(animeSkip, aniSkipDeferred.await()).also { cache[cacheKey] = it }
            }
            val introDbEpisode = imdbEpisode ?: tvdb?.second ?: episode
            val introDbDeferred = async {
                if (introDbConfigured) fetchFromIntroDb(resolvedImdbId, introDbSeason, introDbEpisode) else emptyList()
            }
            val skipMeDeferred = async {
                fetchFromSkipMe(
                    imdbId = resolvedImdbId,
                    season = introDbSeason,
                    episode = introDbEpisode,
                    isMovie = false,
                    durationMs = durationMs
                )
            }
            val anilistId = simklIds?.anilist
            val animeSkipDeferred = async {
                if (anilistId != null) fetchFromAnimeSkip(anilistId, episode, season = null) else emptyList()
            }
            introDb = introDbDeferred.await()
            skipMe = skipMeDeferred.await()
            animeSkip = animeSkipDeferred.await()
        } else {
            val anilistId = simklIds?.anilist
            if (anilistId != null) animeSkip = fetchFromAnimeSkip(anilistId, episode, season = null)
        }

        return@coroutineScope mergeByPriority(introDb, skipMe, animeSkip, aniSkipDeferred.await()).also { cache[cacheKey] = it }
    }

    suspend fun getSkipIntervalsForKitsu(
        kitsuId: String,
        episode: Int,
        imdbId: String? = null,
        imdbSeason: Int? = null,
        imdbEpisode: Int? = null,
        durationMs: Long? = null
    ): List<SkipInterval> = coroutineScope {
        val cacheKey = "kitsu:$kitsuId:$episode"
        cache[cacheKey]?.let { return@coroutineScope it }

        // Resolve all IDs via Simkl
        val simklIdsDeferred = async { simklResolver.resolveIds("kitsu", kitsuId) }
        val simklIds = simklIdsDeferred.await()
        val malIdStr = simklIds?.mal
        val resolvedImdbId = imdbId ?: simklIds?.imdb

        val aniSkipDeferred = async {
            if (malIdStr != null) fetchFromAniSkip(malIdStr, episode) else emptyList()
        }

        val tvdbDeferred = async {
            if (resolvedImdbId != null && imdbSeason == null && simklIds != null) {
                simklResolver.resolveEpisodeTvdb("kitsu", kitsuId, episode)
            } else null
        }

        var introDb = emptyList<SkipInterval>()
        var skipMe = emptyList<SkipInterval>()
        var animeSkip = emptyList<SkipInterval>()
        if (resolvedImdbId != null) {
            val tvdb = tvdbDeferred.await()
            val introDbSeason = imdbSeason ?: tvdb?.first ?: return@coroutineScope run {
                mergeByPriority(animeSkip, aniSkipDeferred.await()).also { cache[cacheKey] = it }
            }
            val introDbEpisode = imdbEpisode ?: tvdb?.second ?: episode
            val introDbDeferred = async {
                if (introDbConfigured) fetchFromIntroDb(resolvedImdbId, introDbSeason, introDbEpisode) else emptyList()
            }
            val skipMeDeferred = async {
                fetchFromSkipMe(
                    imdbId = resolvedImdbId,
                    season = introDbSeason,
                    episode = introDbEpisode,
                    isMovie = false,
                    durationMs = durationMs
                )
            }
            val anilistId = simklIds?.anilist
            val animeSkipDeferred = async {
                if (anilistId != null) fetchFromAnimeSkip(anilistId, episode, season = null) else emptyList()
            }
            introDb = introDbDeferred.await()
            skipMe = skipMeDeferred.await()
            animeSkip = animeSkipDeferred.await()
        } else {
            val anilistId = simklIds?.anilist
            if (anilistId != null) animeSkip = fetchFromAnimeSkip(anilistId, episode, season = null)
        }

        return@coroutineScope mergeByPriority(introDb, skipMe, animeSkip, aniSkipDeferred.await()).also { cache[cacheKey] = it }
    }

    /**
     * Merge provider results into one best-of: fill each segment category (opening / ending /
     * recap) from the highest-priority provider that has it. Arguments MUST be passed in priority
     * order (IntroDB, then SkipMe, then Anime-Skip, then AniSkip as fallback),
     * so a partial result from one provider never shadows a complete segment from another.
     */
    private fun mergeByPriority(vararg providerResults: List<SkipInterval>): List<SkipInterval> {
        val chosen = LinkedHashMap<String, SkipInterval>()
        for (result in providerResults) {
            for (interval in result) {
                val category = segmentCategory(interval.type) ?: continue
                chosen.putIfAbsent(category, interval)
            }
        }
        return chosen.values.toList()
    }

    private fun segmentCategory(type: String): String? = when (type.lowercase()) {
        "intro", "op", "mixed-op", "opening" -> "opening"
        "outro", "ed", "mixed-ed", "credits", "ending", "movie-credits" -> "ending"
        "recap" -> "recap"
        "preview" -> "preview"
        "post-credits" -> "post-credits"
        else -> null
    }

    private suspend fun fetchFromIntroDb(
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
        isMovie: Boolean = false
    ): List<SkipInterval> {
        return try {
            val response = introDbApi.getSegments(imdbId, season, episode, true.takeIf { isMovie })
            if (response.isSuccessful && response.body() != null) {
                response.body()!!.toSkipIntervals(isMovie)
            } else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("SkipIntro", "IntroDB: no data for $imdbId S${season}E${episode}")
            emptyList()
        }
    }

    private suspend fun fetchFromAniSkip(malId: String, episode: Int): List<SkipInterval> {
        return try {
            val types = listOf("op", "ed", "recap", "mixed-op", "mixed-ed")
            val response = aniSkipApi.getSkipTimes(malId, episode, types)
            if (response.isSuccessful && response.body()?.found == true) {
                response.body()!!.results?.map { result ->
                    SkipInterval(
                        startTime = result.interval.startTime,
                        endTime = result.interval.endTime,
                        type = result.skipType,
                        provider = "aniskip"
                    )
                } ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.d("SkipIntro", "AniSkip: no data for MAL $malId ep $episode")
            emptyList()
        }
    }

    // season: null when anilistId is season-specific; pass season number when using fallback ID
    private suspend fun fetchFromAnimeSkip(anilistId: String, episode: Int, season: Int?): List<SkipInterval> {
        val clientId = animeSkipSettingsDataStore.clientId.firstOrNull()?.trim()
        if (clientId.isNullOrBlank()) return emptyList()
        val enabled = animeSkipSettingsDataStore.enabled.firstOrNull() ?: false
        if (!enabled) return emptyList()
        return try {
            val showIds = resolveAnimeSkipShowIds(anilistId, clientId)
            if (showIds.isEmpty()) return emptyList()

            for (showId in showIds) {
                val episodesResponse = animeSkipApi.query(
                    clientId = clientId,
                    body = AnimeSkipRequest(
                        query = "{ findEpisodesByShowId(showId: \"$showId\") { season number timestamps { at type { name } } } }"
                    )
                )
                if (!episodesResponse.isSuccessful) continue

                val episodes = episodesResponse.body()?.data?.findEpisodesByShowId ?: continue
                val targetEpisode = episodes.firstOrNull { ep ->
                    ep.number?.toIntOrNull() == episode &&
                        (season == null || ep.season?.toIntOrNull() == season)
                } ?: continue

                val sorted = (targetEpisode.timestamps ?: continue).sortedBy { it.at }
                val result = sorted.mapIndexedNotNull { i, ts ->
                    val endTime = sorted.getOrNull(i + 1)?.at ?: Double.MAX_VALUE
                    val type = when (ts.type.name.lowercase()) {
                        "intro", "new intro" -> "op"
                        "credits", "new credits" -> "ed"
                        "mixed intro" -> "mixed-op"
                        "mixed credits" -> "mixed-ed"
                        "recap" -> "recap"
                        else -> return@mapIndexedNotNull null
                    }
                    SkipInterval(startTime = ts.at, endTime = endTime, type = type, provider = "animeskip")
                }
                if (result.isNotEmpty()) return result
            }
            emptyList()
        } catch (e: Exception) {
            Log.d("SkipIntro", "AnimeSkip: error for anilist $anilistId ep $episode: ${e.message}")
            emptyList()
        }
    }

    private suspend fun resolveAnimeSkipShowIds(anilistId: String, clientId: String): List<String> {
        animeSkipShowIdCache[anilistId]?.let { cached ->
            return if (cached == NO_ID) emptyList() else listOf(cached)
        }
        val showIds = try {
            animeSkipApi.query(
                clientId = clientId,
                body = AnimeSkipRequest(
                    query = "{ findShowsByExternalId(service: ANILIST, serviceId: \"$anilistId\") { id } }"
                )
            ).body()?.data?.findShowsByExternalId?.map { it.id } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        if (showIds.size == 1) animeSkipShowIdCache[anilistId] = showIds[0]
        else if (showIds.isEmpty()) animeSkipShowIdCache[anilistId] = NO_ID
        return showIds
    }

    suspend fun fetchFromSkipMe(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        isMovie: Boolean,
        durationMs: Long? = null,
        tmdbId: Int? = null,
        tvdbId: Int? = null,
        anilistId: Int? = null
    ): List<SkipInterval> = withContext(Dispatchers.IO) {
        val payloadObject = JSONObject().apply {
            imdbId?.takeIf { it.isNotBlank() }?.let { put("imdb_id", it) }
            season?.let { put("season", it) }
            episode?.let { put("episode", it) }
            durationMs?.takeIf { it > 0L }?.let { put("duration_ms", it) }
            tmdbId?.let { put("tmdb_id", it) }
            tvdbId?.let { put("tvdb_id", it) }
            anilistId?.let { put("anilist_id", it) }
        }
        if (payloadObject.length() == 0) return@withContext emptyList()
        val payload = JSONArray().put(payloadObject).toString()

        val body = executeSkipMeRequest(SKIPME_ENDPOINT_V3, payload)
            ?: executeSkipMeRequest(SKIPME_ENDPOINT_V1, payload)
        if (body.isNullOrBlank()) return@withContext emptyList()

        parseSkipMe(body, isMovie, season, episode)
    }

    private fun executeSkipMeRequest(url: String, payload: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SKIPME_USER_AGENT)
            .header("Accept", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            skipMeHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.d("SkipIntro", "SkipMe: request error for $url: ${e.message}")
            null
        }
    }

    companion object {
        private const val NO_ID = "__none__"
        private const val SKIPME_ENDPOINT_V3 = "https://db.skipme.workers.dev/v3/movies"
        private const val SKIPME_ENDPOINT_V1 = "https://db.skipme.workers.dev/v1/movies"
        private const val SKIPME_USER_AGENT = "SkipMe.db/0.0 NuvioTV/skip-metadata"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun parseSkipMe(
            json: String,
            isMovie: Boolean,
            season: Int? = null,
            episode: Int? = null
        ): List<SkipInterval> {
            if (json.isBlank()) return emptyList()
            val items = try {
                val trimmed = json.trim()
                if (trimmed.startsWith("[")) {
                    val array = JSONArray(trimmed)
                    List(array.length()) { array.optJSONObject(it) }.filterNotNull()
                } else if (trimmed.startsWith("{")) {
                    listOf(JSONObject(trimmed))
                } else {
                    return emptyList()
                }
            } catch (_: Exception) {
                return emptyList()
            }

            val results = mutableListOf<SkipInterval>()
            for (item in items) {
                val segments = item.optJSONArray("segments")
                if (segments != null) {
                    for (j in 0 until segments.length()) {
                        val segment = segments.optJSONObject(j) ?: continue
                        if (season != null && segment.has("season") && segment.optInt("season") != season) continue
                        if (episode != null && segment.has("episode") && segment.optInt("episode") != episode) continue

                        val rawType = segment.optString("segment")
                        val type = mapSkipMeType(rawType, isMovie) ?: continue
                        val startMs = segment.optLong("start_ms", -1L)
                        val endMs = segment.optLong("end_ms", -1L)
                        val submissions = segment.optInt("submissions", 1)
                        buildSkipMeInterval(startMs, endMs, type, submissions)?.let(results::add)
                    }
                    continue
                }

                for (key in listOf("intro", "recap", "credits", "preview")) {
                    val array = item.optJSONArray(key) ?: continue
                    val type = mapSkipMeType(key, isMovie) ?: continue
                    for (j in 0 until array.length()) {
                        val entry = array.optJSONObject(j) ?: continue
                        val startMs = entry.optLong("start_ms", -1L)
                        val endMs = entry.optLong("end_ms", -1L)
                        val submissions = entry.optInt("submissions", 1)
                        buildSkipMeInterval(startMs, endMs, type, submissions)?.let(results::add)
                    }
                }
            }
            return results
        }

        private fun mapSkipMeType(raw: String, isMovie: Boolean): String? = when (raw.trim().lowercase(java.util.Locale.US)) {
            "intro" -> "intro"
            "recap" -> "recap"
            "credits" -> if (isMovie) "movie-credits" else "outro"
            "movie-credits" -> "movie-credits"
            "preview" -> "preview"
            else -> null
        }

        private fun buildSkipMeInterval(
            startMs: Long,
            endMs: Long,
            type: String,
            submissions: Int
        ): SkipInterval? {
            if (startMs < 0L || endMs <= startMs) return null
            val count = submissions.coerceAtLeast(0)
            val confidence = (0.72 + (kotlin.math.ln(count + 1.0) / kotlin.math.ln(2.0)) * 0.08).coerceIn(0.7, 0.99)
            return SkipInterval(
                startTime = startMs / 1000.0,
                endTime = endMs / 1000.0,
                type = type,
                provider = "skipme",
                confidence = confidence
            )
        }
    }
}
