package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.core.player.TrailerVideoPolicy
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbVideoResult
import com.nuvio.tv.data.remote.api.TrailerApi
import java.time.Clock
import java.net.URI
import java.time.Instant
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "TrailerService"
private const val TMDB_TRAILER_FALLBACK_LANGUAGE = "en-US"
private val YOUTUBE_SOURCE_CACHE_TTL: Duration = Duration.ofHours(3)
private val YOUTUBE_VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
private const val MAX_YOUTUBE_CANDIDATES = 3

private data class CuratedUpcomingTrailer(
    val title: String,
    val youtubeKey: String,
    val releaseDate: String? = null,
    val tmdbId: Int? = null
)

private val CURATED_UPCOMING_TRAILERS: List<CuratedUpcomingTrailer> = listOf(
    CuratedUpcomingTrailer("Superman", "uhUht6vAsMY", "2025-07-11"),
    CuratedUpcomingTrailer("Mission: Impossible – The Final Reckoning", "NOhDyUmT9z0", "2025-05-23"),
    CuratedUpcomingTrailer("Captain America: Brave New World", "uJYTz1CgN14", "2025-02-14"),
    CuratedUpcomingTrailer("Thunderbolts*", "v-94Snw-H4o", "2025-05-02"),
    CuratedUpcomingTrailer("A Minecraft Movie", "bkEl6Ib3DpQ", "2025-04-04"),
    CuratedUpcomingTrailer("F1", "3qX4xNkEHPU", "2025-06-27"),
    CuratedUpcomingTrailer("Tron: Ares", "YShVEXb7-ic", "2025-10-10"),
    CuratedUpcomingTrailer("Mickey 17", "osYpGSz_0i4", "2025-04-18"),
    CuratedUpcomingTrailer("From the World of John Wick: Ballerina", "yNN2PoilSp4", "2025-06-06"),
    CuratedUpcomingTrailer("How To Train Your Dragon", "5lzoxHSn0C0", "2025-06-13")
)

// Top level so the lambda doesn't capture the TrailerService instance from its constructor call.
private fun trailerHeightCap(trailerSettingsDataStore: TrailerSettingsDataStore): suspend () -> Int = {
    val allow4k = runCatching { trailerSettingsDataStore.settings.first().allow4k }.getOrDefault(true)
    TrailerVideoPolicy.maxTrailerVideoHeight(allow4k)
}

@Singleton
class TrailerService(
    private val trailerApi: TrailerApi,
    private val tmdbApi: TmdbApi,
    private val inAppYouTubeExtractor: InAppYouTubeExtractor,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tmdbService: TmdbService,
    private val clock: Clock = Clock.systemUTC(),
    private val maxTrailerVideoHeight: suspend () -> Int = { Int.MAX_VALUE }
) {
    @Inject
    constructor(
        trailerApi: TrailerApi,
        tmdbApi: TmdbApi,
        inAppYouTubeExtractor: InAppYouTubeExtractor,
        tmdbSettingsDataStore: TmdbSettingsDataStore,
        tmdbService: TmdbService,
        trailerSettingsDataStore: TrailerSettingsDataStore
    ) : this(
        trailerApi = trailerApi,
        tmdbApi = tmdbApi,
        inAppYouTubeExtractor = inAppYouTubeExtractor,
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        tmdbService = tmdbService,
        clock = Clock.systemUTC(),
        maxTrailerVideoHeight = trailerHeightCap(trailerSettingsDataStore)
    )

    // Cache: "title|year|tmdbId|type|maxHeight" -> trailer playback source (NEGATIVE_CACHE sentinel for misses).
    // The height is part of the key so toggling 4K trailers takes effect without a restart.
    private val cache = ConcurrentHashMap<String, TrailerPlaybackSource>()
    private val NEGATIVE_CACHE = TrailerPlaybackSource(videoUrl = "")
    // Time-bound cache: youtubeVideoId -> resolved playback source (success-only)
    private val youtubeSourceCache = ConcurrentHashMap<String, CachedTrailerPlaybackSource>()
    // Extractions running right now, by the same key as youtubeSourceCache
    private val inFlightYoutubeSources = ConcurrentHashMap<String, CompletableDeferred<TrailerPlaybackSource?>>()

    /**
     * True while YouTube is refusing requests. A missing trailer then says nothing about the
     * title, so callers should not remember it as "no trailer".
     */
    fun isRateLimited(): Boolean = inAppYouTubeExtractor.isRateLimited()

    /**
     * Search for a trailer by title, year, tmdbId, and type.
     * Returns the trailer playback source (video URL + optional separate audio URL) or null.
     */
    suspend fun getTrailerPlaybackSource(
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null,
        ignoreUseTrailersGate: Boolean = false
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        // Read the TMDB settings once and reuse for both the "Disable Trailers"
        // gate and the trailer language lookup below. The gate respects the
        // user's "Disable Trailers in TMDB Enrichment" toggle: the TMDB path
        // below is the only trailer source surfaced through this function,
        // so when the toggle is off we return no trailer at all rather than
        // silently falling through to TMDB's /videos endpoint. See #1647.
        // Post-play recommendations bypass this gate because they have no
        // meta-addon trailer to fall back on.
        val tmdbSettings = runCatching { tmdbSettingsDataStore.settings.first() }.getOrNull()
        if (!ignoreUseTrailersGate && tmdbSettings?.useTrailers != true) {
            Log.d(TAG, "Trailers disabled in TMDB enrichment settings; skipping lookup")
            return@withContext null
        }
        val tmdbLanguage = normalizeTmdbTrailerLanguage(tmdbSettings?.language)

        val cacheKey = "$title|$year|$tmdbId|$type|${maxTrailerVideoHeight()}"

        cache[cacheKey]?.let { cached ->
            val hit = cached !== NEGATIVE_CACHE
            Log.d(TAG, "Cache hit for $cacheKey: $hit")
            return@withContext if (hit) cached else null
        }

        try {
            Log.d(TAG, "Searching trailer: title=$title, year=$year, tmdbId=$tmdbId, type=$type")

            // TMDB-first path. Gated on `useTrailers` above so the
            // user's toggle in TMDB enrichment settings is honored.
            val tmdbSource = getTrailerPlaybackSourceFromTmdbId(
                tmdbId = tmdbId,
                type = type,
                title = title,
                year = year,
                languageOverride = tmdbLanguage
            )
            if (tmdbSource != null) {
                cache[cacheKey] = tmdbSource
                return@withContext tmdbSource
            }
            Log.w(TAG, "TMDB path exhausted; no YouTube trailer key resolved for backend /trailer fallback")
            // Only cache negative result if tmdbId was available — if null, enrichment
            // may not have completed yet and a retry with tmdbId could succeed. A miss while
            // YouTube is rate limiting says nothing about the title, so it isn't cached either.
            if (tmdbId != null && !inAppYouTubeExtractor.isRateLimited()) {
                cache[cacheKey] = NEGATIVE_CACHE
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The detail screen cancels the previous trailer job every time it starts a new
            // one, so this is routine. Swallowing it returned null, which the caller cannot
            // tell apart from "this title has no trailer" -- it wrote that null over a URL a
            // later job had already resolved, and the NEGATIVE_CACHE line above pinned the
            // miss for the rest of the process, so the trailer button stayed gone.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching trailer for $title: ${e.message}", e)
            null
        }
    }

    /**
     * Search for a trailer and return its primary video URL for existing call sites.
     */
    suspend fun getTrailerUrl(
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null
    ): String? {
        return getTrailerPlaybackSource(
            title = title,
            year = year,
            tmdbId = tmdbId,
            type = type
        )?.videoUrl
    }

    suspend fun getExternalTrailerUrl(
        tmdbId: String?,
        type: String?
    ): String? = withContext(Dispatchers.IO) {
        // Parse the id first so an invalid/null tmdbId short-circuits without
        // touching the settings DataStore at all.
        val numericTmdbId = tmdbId?.toIntOrNull() ?: return@withContext null
        // Read settings once and use for both the "Disable Trailers" gate and
        // the trailer language. See #1647 for the gate rationale.
        val tmdbSettings = runCatching { tmdbSettingsDataStore.settings.first() }.getOrNull()
        if (tmdbSettings?.useTrailers != true) {
            return@withContext null
        }
        val mediaType = normalizeTmdbMediaType(type)
        val tmdbLanguage = normalizeTmdbTrailerLanguage(tmdbSettings.language)
        val tmdbResults = when (mediaType) {
            "movie" -> fetchTmdbMovieVideos(numericTmdbId, tmdbLanguage)
            "tv" -> fetchTmdbTvVideos(numericTmdbId, tmdbLanguage)
            else -> fetchTmdbMovieVideos(numericTmdbId, tmdbLanguage) + fetchTmdbTvVideos(numericTmdbId, tmdbLanguage)
        }
        rankTmdbVideoCandidates(tmdbResults, preferredLanguageCode = tmdbLanguage)
            .firstOrNull()
            ?.key
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { "https://www.youtube.com/watch?v=$it" }
    }

    private fun isExcludedTitle(title: String?, originalTitle: String?, excludeTitle: String?): Boolean {
        if (excludeTitle.isNullOrBlank()) return false
        val exclude = clean(excludeTitle)
        if (exclude.isBlank()) return false
        val excludeBare = stripArticles(exclude)

        if (!title.isNullOrBlank()) {
            val t = clean(title)
            val tBare = stripArticles(t)
            if (t == exclude || tBare == excludeBare) return true
            if (t.startsWith("$exclude:") || t.startsWith("$exclude -") || t.startsWith("$exclude–")) return true
        }

        if (!originalTitle.isNullOrBlank()) {
            val o = clean(originalTitle)
            val oBare = stripArticles(o)
            if (o == exclude || oBare == excludeBare) return true
            if (o.startsWith("$exclude:") || o.startsWith("$exclude -") || o.startsWith("$exclude–")) return true
        }

        return false
    }

    private fun clean(str: String): String =
        str.trim().lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun stripArticles(s: String): String =
        s.removePrefix("the ").removePrefix("a ").removePrefix("an ").trim()

    /**
     * Retrieves upcoming theatrical trailers for unreleased or newly upcoming movies,
     * specifically excluding the movie currently being watched.
     *
     * Falls back to the curated upcoming theatrical catalog if TMDB is unavailable,
     * returns 401, or provides fewer than [limit] trailers.
     */
    suspend fun getUpcomingTheatricalTrailers(
        excludeTitle: String? = null,
        excludeTmdbId: Int? = null,
        limit: Int = 2
    ): List<UpcomingTheatricalTrailer> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val collected = mutableListOf<UpcomingTheatricalTrailer>()
        val seenTitles = mutableSetOf<String>()
        val seenKeys = mutableSetOf<String>()

        fun addTrailer(title: String, source: TrailerPlaybackSource, key: String, tmdbId: Int?, releaseDate: String?): Boolean {
            if (collected.size >= limit) return false
            if (source.videoUrl.isBlank()) return false
            if (isExcludedTitle(title, null, excludeTitle)) return false
            val cleanT = title.trim().lowercase()
            if (cleanT in seenTitles || key in seenKeys) return false
            seenTitles.add(cleanT)
            seenKeys.add(key)
            collected.add(
                UpcomingTheatricalTrailer(
                    title = title,
                    videoUrl = source.videoUrl,
                    audioUrl = source.audioUrl,
                    tmdbId = tmdbId,
                    releaseDate = releaseDate
                )
            )
            return true
        }

        val todayStr = runCatching { java.time.LocalDate.now(clock).toString() }.getOrDefault("2025-01-01")

        // 1. Try TMDB if API key is present
        try {
            val apiKey = runCatching { tmdbService.apiKey() }.getOrDefault("")
            if (apiKey.isNotBlank()) {
                val upcomingResponse = runCatching {
                    tmdbApi.getUpcomingMovies(apiKey = apiKey, region = "US")
                }.getOrNull()

                val candidates = if (upcomingResponse?.isSuccessful == true && !upcomingResponse.body()?.results.isNullOrEmpty()) {
                    upcomingResponse.body()?.results.orEmpty()
                } else {
                    val discoverResponse = runCatching {
                        tmdbApi.discoverMovies(
                            apiKey = apiKey,
                            releaseDateGte = todayStr,
                            sortBy = "popularity.desc"
                        )
                    }.getOrNull()
                    discoverResponse?.body()?.results.orEmpty()
                }

                val filtered = candidates.filter { c ->
                    val title = c.title?.trim()
                    if (title.isNullOrBlank() || c.id <= 0) return@filter false
                    if (excludeTmdbId != null && c.id == excludeTmdbId) return@filter false
                    if (isExcludedTitle(c.title, c.originalTitle, excludeTitle)) return@filter false
                    val rDate = c.releaseDate?.trim()
                    if (!rDate.isNullOrBlank() && rDate < todayStr) return@filter false
                    true
                }

                val tmdbLanguage = getPreferredTmdbTrailerLanguage()

                for (candidate in filtered) {
                    if (collected.size >= limit) break
                    if (inAppYouTubeExtractor.isRateLimited()) break
                    val title = candidate.title ?: continue

                    val videos = fetchTmdbMovieVideos(candidate.id, tmdbLanguage)
                    val youtubeVideos = videos.filter {
                        it.site.equals("YouTube", ignoreCase = true) && !it.key.isNullOrBlank()
                    }
                    if (youtubeVideos.isEmpty()) continue

                    val trailers = youtubeVideos.filter { it.type.equals("Trailer", ignoreCase = true) }
                    val teasers = youtubeVideos.filter { it.type.equals("Teaser", ignoreCase = true) }

                    val bestVideo = trailers.firstOrNull { it.official == true }
                        ?: trailers.firstOrNull()
                        ?: teasers.firstOrNull { it.official == true }
                        ?: teasers.firstOrNull()
                        ?: continue

                    val key = bestVideo.key ?: continue
                    val source = getTrailerPlaybackSourceFromYouTubeUrl(
                        youtubeUrl = "https://www.youtube.com/watch?v=$key",
                        title = title
                    )

                    if (source != null && source.videoUrl.isNotBlank()) {
                        Log.d(TAG, "Resolved upcoming theatrical trailer from TMDB: '$title' (key=${obfuscateYoutubeKey(key)})")
                        addTrailer(title, source, key, candidate.id, candidate.releaseDate)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error resolving upcoming theatrical trailers from TMDB: ${t.message}", t)
        }

        // 2. Fallback / Backfill from Curated Catalog to ensure `limit` trailers are ALWAYS returned
        if (collected.size < limit && !inAppYouTubeExtractor.isRateLimited()) {
            val curatedFiltered = CURATED_UPCOMING_TRAILERS
                .filter { !isExcludedTitle(it.title, null, excludeTitle) }
                .shuffled()

            for (curated in curatedFiltered) {
                if (collected.size >= limit) break
                if (inAppYouTubeExtractor.isRateLimited()) break
                val source = getTrailerPlaybackSourceFromYouTubeUrl(
                    youtubeUrl = "https://www.youtube.com/watch?v=${curated.youtubeKey}",
                    title = curated.title
                )
                if (source != null && source.videoUrl.isNotBlank()) {
                    Log.d(TAG, "Resolved upcoming theatrical trailer from curated catalog: '${curated.title}' (key=${obfuscateYoutubeKey(curated.youtubeKey)})")
                    addTrailer(curated.title, source, curated.youtubeKey, curated.tmdbId, curated.releaseDate)
                }
            }
        }

        collected
    }

    /**
     * TMDB-first resolution using /movie/{id}/videos or /tv/{id}/videos.
     */
    suspend fun getTrailerPlaybackSourceFromTmdbId(
        tmdbId: String?,
        type: String?,
        title: String? = null,
        year: String? = null,
        languageOverride: String? = null
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        val numericTmdbId = tmdbId?.toIntOrNull() ?: return@withContext null
        val mediaType = normalizeTmdbMediaType(type)
        val tmdbLanguage = languageOverride ?: getPreferredTmdbTrailerLanguage()
        Log.d(
            TAG,
            "TMDB trailer lookup start: tmdbId=$numericTmdbId type=${mediaType ?: "unknown"} language=$tmdbLanguage"
        )

        val tmdbResults = when (mediaType) {
            "movie" -> fetchTmdbMovieVideos(numericTmdbId, tmdbLanguage)
            "tv" -> fetchTmdbTvVideos(numericTmdbId, tmdbLanguage)
            else -> fetchTmdbMovieVideos(numericTmdbId, tmdbLanguage) + fetchTmdbTvVideos(numericTmdbId, tmdbLanguage)
        }

        val candidates = rankTmdbVideoCandidates(tmdbResults, preferredLanguageCode = tmdbLanguage)
        Log.d(TAG, "TMDB candidate count: ${candidates.size}")

        // Big titles list a dozen trailers and teasers. When the best few all fail, the rest
        // almost always fail the same way, and trying them only adds YouTube requests.
        for (candidate in candidates.take(MAX_YOUTUBE_CANDIDATES)) {
            if (inAppYouTubeExtractor.isRateLimited()) {
                Log.w(TAG, "YouTube is rate limiting, not trying the remaining TMDB candidates")
                break
            }
            val key = candidate.key?.trim().orEmpty()
            if (key.isBlank()) continue
            Log.d(
                TAG,
                "TMDB selected candidate: type=${candidate.type.orEmpty()} " +
                    "official=${candidate.official == true} key=${obfuscateYoutubeKey(key)}"
            )

            val youtubeUrl = "https://www.youtube.com/watch?v=$key"
            val source = getTrailerPlaybackSourceFromYouTubeUrl(
                youtubeUrl = youtubeUrl,
                title = title,
                year = year
            )
            if (source != null) {
                return@withContext source
            }

            Log.d(
                TAG,
                "TMDB candidate extraction failed, trying next: key=${obfuscateYoutubeKey(key)}"
            )
        }

        null
    }

    /**
     * Resolve a YouTube trailer URL to a playback source (prefers in-app extraction).
     */
    suspend fun getTrailerPlaybackSourceFromYouTubeUrl(
        youtubeUrl: String,
        title: String? = null,
        year: String? = null
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        try {
            val maxHeight = maxTrailerVideoHeight()
            val youtubeKey = extractYouTubeVideoId(youtubeUrl)
            // Same video, different source per height cap, so the cap is part of the cache key.
            val sourceCacheKey = youtubeKey?.takeIf { it.isNotBlank() }?.let { "$it|$maxHeight" }
                ?: return@withContext resolveYouTubeSource(youtubeUrl, null, maxHeight, title, year)

            getValidCachedYoutubeSource(sourceCacheKey)?.let { cached ->
                Log.d(TAG, "YouTube cache hit for key=${obfuscateYoutubeKey(youtubeKey.orEmpty())}")
                return@withContext cached
            }

            resolveYouTubeSourceOnce(sourceCacheKey) {
                // Re-check: another caller may have just finished this video.
                getValidCachedYoutubeSource(sourceCacheKey)
                    ?: resolveYouTubeSource(youtubeUrl, sourceCacheKey, maxHeight, title, year)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error getting trailer from YouTube: ${e.message}", e)
            null
        }
    }

    /**
     * The home hero, the focused card and the detail screen can all ask for the same video at
     * once. Only one of them runs [resolve]; the others wait for its result. The work stays in
     * the first caller's coroutine, so cancelling it still stops the requests; a waiting caller
     * then takes the work over.
     */
    private suspend fun resolveYouTubeSourceOnce(
        sourceCacheKey: String,
        resolve: suspend () -> TrailerPlaybackSource?
    ): TrailerPlaybackSource? {
        while (true) {
            val pending = CompletableDeferred<TrailerPlaybackSource?>()
            val inFlight = inFlightYoutubeSources.putIfAbsent(sourceCacheKey, pending)
            if (inFlight == null) {
                try {
                    return resolve().also { pending.complete(it) }
                } catch (e: Throwable) {
                    pending.completeExceptionally(e)
                    throw e
                } finally {
                    inFlightYoutubeSources.remove(sourceCacheKey, pending)
                }
            }
            try {
                return inFlight.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Rethrows if this caller was cancelled; otherwise the one doing the work was.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private suspend fun resolveYouTubeSource(
        youtubeUrl: String,
        sourceCacheKey: String?,
        maxHeight: Int,
        title: String?,
        year: String?
    ): TrailerPlaybackSource? {
        Log.d(TAG, "Attempting in-app YouTube extraction for ${summarizeUrl(youtubeUrl)}")
        val localSource = inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl, maxHeight)
        if (localSource != null) {
            if (sourceCacheKey != null) {
                youtubeSourceCache[sourceCacheKey] = CachedTrailerPlaybackSource(
                    playbackSource = localSource,
                    cachedAt = Instant.now(clock),
                    expiresAt = extractUrlExpireInstant(localSource)
                )
            }
            Log.d(
                TAG,
                "Using in-app YouTube source for ${summarizeUrl(youtubeUrl)} " +
                    "(audioPresent=${!localSource.audioUrl.isNullOrBlank()})"
            )
            return localSource
        }

        // Fallback to remote trailer resolver if in-app extraction fails.
        Log.w(TAG, "In-app extraction failed, falling back to backend resolver for ${summarizeUrl(youtubeUrl)}")
        val response = trailerApi.getTrailer(youtubeUrl = youtubeUrl, title = title, year = year)
        if (!response.isSuccessful) {
            Log.w(TAG, "Backend trailer fallback failed (${response.code()}) for ${summarizeUrl(youtubeUrl)}")
            return null
        }

        val fallbackUrl = response.body()?.url ?: return null
        if (!isValidUrl(fallbackUrl)) return null

        val fallbackSource = TrailerPlaybackSource(videoUrl = fallbackUrl)
        if (sourceCacheKey != null) {
            youtubeSourceCache[sourceCacheKey] = CachedTrailerPlaybackSource(
                playbackSource = fallbackSource,
                cachedAt = Instant.now(clock),
                expiresAt = extractUrlExpireInstant(fallbackSource)
            )
        }
        Log.d(TAG, "Using backend fallback source for ${summarizeUrl(youtubeUrl)}")
        return fallbackSource
    }

    /**
     * Compatibility method for existing callers expecting a single URL.
     */
    suspend fun getTrailerFromYouTubeUrl(
        youtubeUrl: String,
        title: String? = null,
        year: String? = null
    ): String? {
        return getTrailerPlaybackSourceFromYouTubeUrl(
            youtubeUrl = youtubeUrl,
            title = title,
            year = year
        )?.videoUrl
    }

    private suspend fun fetchTmdbMovieVideos(tmdbId: Int, preferredLanguage: String): List<TmdbVideoResult> {
        val localized = fetchTmdbMovieVideosOnce(tmdbId, preferredLanguage)
        if (localized.isNotEmpty() || preferredLanguage.equals(TMDB_TRAILER_FALLBACK_LANGUAGE, ignoreCase = true)) {
            return localized
        }
        Log.d(TAG, "TMDB movie videos localized miss for $tmdbId ($preferredLanguage), retrying $TMDB_TRAILER_FALLBACK_LANGUAGE")
        return fetchTmdbMovieVideosOnce(tmdbId, TMDB_TRAILER_FALLBACK_LANGUAGE)
    }

    private suspend fun fetchTmdbTvVideos(tmdbId: Int, preferredLanguage: String): List<TmdbVideoResult> {
        val localized = fetchTmdbTvVideosOnce(tmdbId, preferredLanguage)
        if (localized.isNotEmpty() || preferredLanguage.equals(TMDB_TRAILER_FALLBACK_LANGUAGE, ignoreCase = true)) {
            return localized
        }
        Log.d(TAG, "TMDB tv videos localized miss for $tmdbId ($preferredLanguage), retrying $TMDB_TRAILER_FALLBACK_LANGUAGE")
        return fetchTmdbTvVideosOnce(tmdbId, TMDB_TRAILER_FALLBACK_LANGUAGE)
    }

    private suspend fun fetchTmdbMovieVideosOnce(tmdbId: Int, language: String): List<TmdbVideoResult> {
        return try {
            val response = tmdbApi.getMovieVideos(
                movieId = tmdbId,
                apiKey = tmdbService.apiKey(),
                language = language
            )
            if (!response.isSuccessful) {
                Log.w(TAG, "TMDB movie videos request failed ($tmdbId/$language): ${response.code()}")
                emptyList()
            } else {
                response.body()?.results.orEmpty()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "TMDB movie videos error ($tmdbId/$language): ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchTmdbTvVideosOnce(tmdbId: Int, language: String): List<TmdbVideoResult> {
        return try {
            val response = tmdbApi.getTvVideos(
                tvId = tmdbId,
                apiKey = tmdbService.apiKey(),
                language = language
            )
            if (!response.isSuccessful) {
                Log.w(TAG, "TMDB tv videos request failed ($tmdbId/$language): ${response.code()}")
                emptyList()
            } else {
                response.body()?.results.orEmpty()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "TMDB tv videos error ($tmdbId/$language): ${e.message}")
            emptyList()
        }
    }

    private suspend fun getPreferredTmdbTrailerLanguage(): String {
        val rawLanguage = runCatching { tmdbSettingsDataStore.settings.first().language }.getOrNull()
        return normalizeTmdbTrailerLanguage(rawLanguage)
    }

    private fun isValidUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.startsWith("http://") || url.startsWith("https://")
    }

    private fun summarizeUrl(url: String): String {
        return runCatching {
            val uri = URI(url)
            val host = uri.host ?: "unknown-host"
            val path = uri.path ?: "/"
            "$host$path"
        }.getOrDefault(url.take(80))
    }

    private fun obfuscateYoutubeKey(key: String): String {
        if (key.length <= 4) return "****"
        return "***${key.takeLast(4)}"
    }

    private fun getValidCachedYoutubeSource(youtubeKey: String): TrailerPlaybackSource? {
        val cached = youtubeSourceCache[youtubeKey] ?: return null
        val now = Instant.now(clock)

        // Use URL expire timestamp if available, otherwise fall back to TTL
        val expired = cached.expiresAt?.let { now.isAfter(it) }
            ?: (Duration.between(cached.cachedAt, now) > YOUTUBE_SOURCE_CACHE_TTL)

        if (!expired) {
            return cached.playbackSource
        }

        youtubeSourceCache.remove(youtubeKey, cached)
        return null
    }

    fun clearCache() {
        cache.clear()
        youtubeSourceCache.clear()
    }

    /**
     * Extracts the YouTube URL expiration timestamp from the `/expire/EPOCH/` path segment.
     */
    private fun extractUrlExpireInstant(source: TrailerPlaybackSource): Instant? {
        val url = source.videoUrl
        val expireRegex = Regex("/expire/(\\d+)/")
        val match = expireRegex.find(url) ?: return null
        val epoch = match.groupValues[1].toLongOrNull() ?: return null
        return Instant.ofEpochSecond(epoch)
    }

    private fun extractYouTubeVideoId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.matches(YOUTUBE_VIDEO_ID_REGEX)) return trimmed

        return runCatching {
            val uri = URI(trimmed)
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: return@runCatching null
            when {
                host == "youtu.be" -> {
                    val id = uri.path?.trim('/')?.substringBefore('/')?.trim().orEmpty()
                    id.takeIf { it.matches(YOUTUBE_VIDEO_ID_REGEX) }
                }

                host == "youtube.com" || host.endsWith(".youtube.com") -> {
                    val path = uri.path.orEmpty()
                    val query = uri.rawQuery.orEmpty()

                    if (path.startsWith("/watch")) {
                        query.split("&")
                            .asSequence()
                            .mapNotNull { entry ->
                                val index = entry.indexOf('=')
                                if (index <= 0) return@mapNotNull null
                                val key = entry.substring(0, index)
                                val value = entry.substring(index + 1)
                                if (key == "v") value else null
                            }
                            .firstOrNull { it.matches(YOUTUBE_VIDEO_ID_REGEX) }
                    } else {
                        val segments = path.trim('/').split("/")
                        val candidate = when (segments.firstOrNull()?.lowercase()) {
                            "embed", "shorts", "live" -> segments.getOrNull(1)
                            else -> null
                        }
                        candidate?.takeIf { it.matches(YOUTUBE_VIDEO_ID_REGEX) }
                    }
                }

                else -> null
            }
        }.getOrNull()
    }

    private data class CachedTrailerPlaybackSource(
        val playbackSource: TrailerPlaybackSource,
        val cachedAt: Instant,
        val expiresAt: Instant? = null
    )
}

internal fun normalizeTmdbTrailerLanguage(language: String?): String {
    val normalized = language
        ?.trim()
        ?.replace('_', '-')
        ?.takeIf { it.isNotBlank() }
        ?: return TMDB_TRAILER_FALLBACK_LANGUAGE

    val formatted = if (normalized.contains('-')) {
        val parts = normalized.split("-", limit = 2)
        val locale = parts[0].lowercase()
        val region = parts.getOrNull(1)?.uppercase()?.takeIf { it.isNotBlank() }
        if (region != null) "$locale-$region" else locale
    } else {
        normalized.lowercase()
    }

    if (formatted == "en") return TMDB_TRAILER_FALLBACK_LANGUAGE

    // Map codes unsupported by TMDB to their closest equivalent
    return when (formatted) {
        "es-419" -> "es-MX"
        else -> formatted
    }
}

internal fun normalizeTmdbMediaType(type: String?): String? {
    return when (type?.lowercase()) {
        "movie", "film" -> "movie"
        "tv", "series", "show", "tvshow" -> "tv"
        else -> null
    }
}

/**
 * Ranks candidates for the user's chosen TMDB trailer language first (e.g. "en-US",
 * "fr-FR"), falling back to English as a safety net when nothing matches that
 * language, and only then to whatever else is available.
 */
internal fun rankTmdbVideoCandidates(
    results: List<TmdbVideoResult>,
    preferredLanguageCode: String = TMDB_TRAILER_FALLBACK_LANGUAGE
): List<TmdbVideoResult> {
    val preferredLanguage = preferredLanguageCode.substringBefore('-').lowercase()

    fun languageRank(iso6391: String?): Int {
        val lang = iso6391?.trim()?.lowercase()
        return when {
            lang == preferredLanguage -> 0
            lang == "en" -> 1
            else -> 2
        }
    }

    return results
        .asSequence()
        .filter { (it.site ?: "").equals("YouTube", ignoreCase = true) }
        .filter { !it.key.isNullOrBlank() }
        .filter {
            val normalizedType = it.type?.trim()?.lowercase()
            normalizedType == "trailer" || normalizedType == "teaser"
        }
        .distinctBy { it.key }
        .sortedWith(
            compareBy<TmdbVideoResult> { videoTypePriority(it.type) }
                .thenBy { languageRank(it.iso6391) }
                .thenBy { if (it.official == true) 0 else 1 }
                .thenByDescending { it.size ?: 0 }
                .thenByDescending { parsePublishedAtEpoch(it.publishedAt) }
        )
        .toList()
}

private fun videoTypePriority(type: String?): Int {
    return when (type?.trim()?.lowercase()) {
        "trailer" -> 0
        "teaser" -> 1
        else -> 2
    }
}

private fun parsePublishedAtEpoch(value: String?): Long {
    if (value.isNullOrBlank()) return Long.MIN_VALUE
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
}
