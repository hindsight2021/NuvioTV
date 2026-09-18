package com.nuvio.tv.data.simkl.calendar

import android.util.Log
import com.nuvio.tv.data.simkl.SimklListStatus
import com.nuvio.tv.data.simkl.SimklMediaType
import com.nuvio.tv.data.simkl.SimklSyncRepository
import com.nuvio.tv.data.simkl.idValue
import com.nuvio.tv.data.simkl.simklIdValue
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository responsible for fetching and aggregating Simkl calendar data
 * (TV episodes, digital/DVD releases, and theatrical movie releases).
 *
 * Tailored for Android TV / Shield Pro:
 * - Eliminates RAM bloat and duplicate-key crashes by strictly filtering TV episodes to shows
 *   the user is actively tracking/watching in Simkl, Continue Watching, or Library/Watchlists.
 * - Drops obscure random releases, only surfacing user-tracked or popular/trending movies.
 * - Produces unique composite keys for every episode instance.
 * - Results are cached in-memory for [CACHE_TTL_MS] and refreshed on demand.
 */
@Singleton
class SimklCalendarRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val simklSyncRepository: dagger.Lazy<SimklSyncRepository>,
    private val libraryRepository: dagger.Lazy<LibraryRepository>,
    private val watchProgressRepository: dagger.Lazy<WatchProgressRepository>
) {

    companion object {
        private const val TAG = "SimklCalendarRepo"

        private const val FALLBACK_CLIENT_ID =
            "6103c87d5e788fec2e00a818cb34adfe1fc3b9b76bfe1fcf8597d944e95e67b9"

        private const val APP_NAME = "NuvioTV"
        private const val APP_VERSION = "1.0"

        /** In-memory cache TTL: 2 hours. */
        private const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L

        /** Number of days shown in the calendar strip. */
        private const val DAYS_TO_SHOW = 7

        /** How many days back to include already-released digital titles on "Today". */
        private const val DIGITAL_LOOKBACK_DAYS = 7

        private const val TRENDING_TV_MAX_RANK = 25
        private const val TRENDING_TV_MIN_VOTES = 30
        private const val TRENDING_MOVIE_MAX_RANK = 40
        private const val TRENDING_MOVIE_MIN_VOTES = 10
        private const val TRENDING_MOVIE_MIN_RATING = 6.5f

        private val EPISODE_MARKER_REGEX = Regex("(?i)^(?:S(\\d+))?E(\\d+)$")

        internal fun normalizeTitle(title: String): String =
            title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

        internal fun parseEpisodeMarker(value: String?): Pair<Int?, Int>? {
            val match = value?.trim()?.let(EPISODE_MARKER_REGEX::matchEntire) ?: return null
            val season = match.groupValues[1].takeIf(String::isNotEmpty)?.toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull() ?: return null
            return Pair(season, episode)
        }
    }

    private val clientId: String =
        com.nuvio.tv.BuildConfig.SIMKL_CLIENT_ID.ifBlank { FALLBACK_CLIENT_ID }

    private val tvUrl: String =
        "https://data.simkl.in/calendar/tv.json?client_id=$clientId&app-name=$APP_NAME&app-version=$APP_VERSION"

    private val dvdUrl: String =
        "https://data.simkl.in/discover/dvd/releases_100.json?client_id=$clientId&app-name=$APP_NAME&app-version=$APP_VERSION"

    private val movieUrl: String =
        "https://data.simkl.in/calendar/movie_release.json?client_id=$clientId&app-name=$APP_NAME&app-version=$APP_VERSION"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val cacheMutex = Mutex()
    private var cachedData: CalendarData? = null
    private var cacheTimestamp: Long = 0L

    internal data class ShowWatchStatus(
        val isActivelyWatching: Boolean = false,
        val isWatchlist: Boolean = false,
        val nextSeason: Int? = null,
        val nextEpisode: Int? = null,
        val lastWatchedSeason: Int? = null,
        val lastWatchedEpisode: Int? = null,
        val seasonMaxEpisodes: Map<Int, Int> = emptyMap(),
        val totalWatchedEpisodes: Int = 0
    )

    internal data class TrackedMediaFilter(
        val imdbIds: Set<String> = emptySet(),
        val tmdbIds: Set<String> = emptySet(),
        val simklIds: Set<Long> = emptySet(),
        val normalizedTitles: Set<String> = emptySet()
    ) {
        val isEmpty: Boolean
            get() = imdbIds.isEmpty() && tmdbIds.isEmpty() && simklIds.isEmpty() && normalizedTitles.isEmpty()

        fun matches(imdb: String?, tmdb: String?, simklId: Long?, title: String?): Boolean {
            if (!imdb.isNullOrBlank() && imdb in imdbIds) return true
            if (!tmdb.isNullOrBlank() && tmdb in tmdbIds) return true
            if (simklId != null && simklId in simklIds) return true
            if (!title.isNullOrBlank()) {
                val norm = normalizeTitle(title)
                if (norm.length >= 3 && norm in normalizedTitles) return true
            }
            return false
        }
    }

    internal data class UserCalendarFilters(
        val userShows: TrackedMediaFilter,
        val userMovies: TrackedMediaFilter,
        val showStatusByImdb: Map<String, ShowWatchStatus> = emptyMap(),
        val showStatusByTmdb: Map<String, ShowWatchStatus> = emptyMap(),
        val showStatusBySimkl: Map<Long, ShowWatchStatus> = emptyMap(),
        val showStatusByTitle: Map<String, ShowWatchStatus> = emptyMap()
    ) {
        fun findShowStatus(imdb: String?, tmdb: String?, simklId: Long?, title: String?): ShowWatchStatus? {
            if (!imdb.isNullOrBlank()) showStatusByImdb[imdb]?.let { return it }
            if (!tmdb.isNullOrBlank()) showStatusByTmdb[tmdb]?.let { return it }
            if (simklId != null) showStatusBySimkl[simklId]?.let { return it }
            if (!title.isNullOrBlank()) {
                val norm = normalizeTitle(title)
                if (norm.length >= 3) showStatusByTitle[norm]?.let { return it }
            }
            return null
        }
    }

    /**
     * Returns aggregated calendar data. Uses a 2-hour in-memory cache unless
     * [forceRefresh] is true.
     */
    suspend fun getCalendarData(forceRefresh: Boolean = false): Result<CalendarData> {
        return try {
            cacheMutex.withLock {
                val now = System.currentTimeMillis()
                val cached = cachedData
                if (!forceRefresh && cached != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
                    return@withLock Result.success(cached)
                }

                val fresh = fetchAndBuild()
                cachedData = fresh
                cacheTimestamp = now
                Result.success(fresh)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load calendar data", t)
            Result.failure(t)
        }
    }

    // ---------------------------------------------------------------------
    // User Media Resolution (Tracking & Watchlist)
    // ---------------------------------------------------------------------

    internal suspend fun resolveUserFilters(): UserCalendarFilters =
        withContext(Dispatchers.IO) {
            val showImdb = mutableSetOf<String>()
            val showTmdb = mutableSetOf<String>()
            val showSimkl = mutableSetOf<Long>()
            val showTitles = mutableSetOf<String>()

            val movieImdb = mutableSetOf<String>()
            val movieTmdb = mutableSetOf<String>()
            val movieSimkl = mutableSetOf<Long>()
            val movieTitles = mutableSetOf<String>()

            val statusByImdb = mutableMapOf<String, ShowWatchStatus>()
            val statusByTmdb = mutableMapOf<String, ShowWatchStatus>()
            val statusBySimkl = mutableMapOf<Long, ShowWatchStatus>()
            val statusByTitle = mutableMapOf<String, ShowWatchStatus>()

            fun putShowStatus(
                imdb: String?,
                tmdb: String?,
                simklId: Long?,
                title: String?,
                status: ShowWatchStatus
            ) {
                if (!imdb.isNullOrBlank()) {
                    showImdb.add(imdb)
                    statusByImdb[imdb] = status
                }
                if (!tmdb.isNullOrBlank()) {
                    showTmdb.add(tmdb)
                    statusByTmdb[tmdb] = status
                }
                if (simklId != null) {
                    showSimkl.add(simklId)
                    statusBySimkl[simklId] = status
                }
                if (!title.isNullOrBlank()) {
                    val norm = normalizeTitle(title)
                    if (norm.length >= 3) {
                        showTitles.add(norm)
                        statusByTitle[norm] = status
                    }
                }
            }

            // 1. Simkl Sync Snapshot
            try {
                val snapshot = simklSyncRepository.get().state.value.snapshot
                snapshot.entries.forEach { entry ->
                    val media = entry.media ?: return@forEach
                    val imdb = media.ids.idValue("imdb")
                    val tmdb = media.ids.idValue("tmdb")
                    val simkl = media.ids.simklIdValue()?.toLongOrNull()
                    val title = media.title

                    val isMovie = entry.isMovieEntry()
                    if (isMovie) {
                        imdb?.let { movieImdb.add(it) }
                        tmdb?.let { movieTmdb.add(it) }
                        simkl?.let { movieSimkl.add(it) }
                        title?.let { movieTitles.add(normalizeTitle(it)) }
                    } else {
                        val isWatching = entry.status == SimklListStatus.WATCHING ||
                            entry.status == SimklListStatus.ON_HOLD ||
                            entry.watchedEpisodesCount > 0
                        val isPlanToWatch = entry.status == SimklListStatus.PLAN_TO_WATCH

                        val nextEp = parseEpisodeMarker(entry.nextToWatch)
                        val lastEp = parseEpisodeMarker(entry.lastWatched)

                        val seasonMaxMap = mutableMapOf<Int, Int>()
                        entry.seasons.forEach { s ->
                            val sNum = s.number ?: return@forEach
                            val maxNum = s.episodes.mapNotNull { it.number }.maxOrNull() ?: s.episodes.size
                            if (maxNum > 0) seasonMaxMap[sNum] = maxNum
                        }

                        val status = ShowWatchStatus(
                            isActivelyWatching = isWatching,
                            isWatchlist = isPlanToWatch && !isWatching,
                            nextSeason = nextEp?.first,
                            nextEpisode = nextEp?.second,
                            lastWatchedSeason = lastEp?.first,
                            lastWatchedEpisode = lastEp?.second,
                            seasonMaxEpisodes = seasonMaxMap,
                            totalWatchedEpisodes = entry.watchedEpisodesCount
                        )
                        putShowStatus(imdb, tmdb, simkl, title, status)
                    }
                }

                snapshot.playback.forEach { session ->
                    val media = session.media ?: return@forEach
                    val imdb = media.ids.idValue("imdb")
                    val tmdb = media.ids.idValue("tmdb")
                    val simkl = media.ids.simklIdValue()?.toLongOrNull()
                    val title = media.title

                    if (session.mediaType == SimklMediaType.MOVIES) {
                        imdb?.let { movieImdb.add(it) }
                        tmdb?.let { movieTmdb.add(it) }
                        simkl?.let { movieSimkl.add(it) }
                        title?.let { movieTitles.add(normalizeTitle(it)) }
                    } else {
                        val existing = (imdb?.let { statusByImdb[it] }
                            ?: tmdb?.let { statusByTmdb[it] }
                            ?: simkl?.let { statusBySimkl[it] }
                            ?: title?.let { statusByTitle[normalizeTitle(it)] })
                            ?: ShowWatchStatus()
                        val updated = existing.copy(isActivelyWatching = true)
                        putShowStatus(imdb, tmdb, simkl, title, updated)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.d(TAG, "Could not read Simkl sync snapshot", t)
            }

            // 2. Watch Progress Repository (Continue Watching / Recent History)
            try {
                val progressItems = withTimeoutOrNull(500L) {
                    watchProgressRepository.get().allProgress.first()
                }.orEmpty()

                progressItems.forEach { item ->
                    val id = item.contentId
                    val isShow = item.contentType == "series" || item.season != null
                    if (isShow) {
                        val existing = (if (id.isNotBlank()) statusByImdb[id] else null)
                            ?: (if (item.name.isNotBlank()) statusByTitle[normalizeTitle(item.name)] else null)
                            ?: ShowWatchStatus()
                        val updated = existing.copy(
                            isActivelyWatching = true,
                            lastWatchedSeason = existing.lastWatchedSeason ?: item.season,
                            lastWatchedEpisode = existing.lastWatchedEpisode ?: item.episode
                        )
                        putShowStatus(
                            imdb = id.takeIf { it.isNotBlank() },
                            tmdb = null,
                            simklId = null,
                            title = item.name.takeIf { it.isNotBlank() },
                            status = updated
                        )
                    } else {
                        if (id.isNotBlank()) movieImdb.add(id)
                        if (item.name.isNotBlank()) movieTitles.add(normalizeTitle(item.name))
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.d(TAG, "Could not read watch progress items", t)
            }

            // 3. Library Repository (User Watchlist / In-Library items)
            try {
                val libraryItems = withTimeoutOrNull(500L) {
                    libraryRepository.get().libraryItems.first()
                }.orEmpty()

                libraryItems.forEach { entry ->
                    val isShow = entry.type == "series"
                    val imdb = entry.imdbId ?: entry.id.takeIf { it.startsWith("tt") }
                    val tmdb = entry.tmdbId?.toString()
                    val simkl = entry.simklId
                    val title = entry.name

                    if (isShow) {
                        val existing = (imdb?.let { statusByImdb[it] }
                            ?: tmdb?.let { statusByTmdb[it] }
                            ?: simkl?.let { statusBySimkl[it] }
                            ?: if (title.isNotBlank()) statusByTitle[normalizeTitle(title)] else null)
                            ?: ShowWatchStatus()
                        val updated = if (!existing.isActivelyWatching) {
                            existing.copy(isWatchlist = true)
                        } else {
                            existing
                        }
                        putShowStatus(imdb, tmdb, simkl, title, updated)
                    } else {
                        imdb?.let { movieImdb.add(it) }
                        tmdb?.let { movieTmdb.add(it) }
                        simkl?.let { movieSimkl.add(it) }
                        if (title.isNotBlank()) movieTitles.add(normalizeTitle(title))
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.d(TAG, "Could not read library items", t)
            }

            UserCalendarFilters(
                userShows = TrackedMediaFilter(showImdb, showTmdb, showSimkl, showTitles),
                userMovies = TrackedMediaFilter(movieImdb, movieTmdb, movieSimkl, movieTitles),
                showStatusByImdb = statusByImdb,
                showStatusByTmdb = statusByTmdb,
                showStatusBySimkl = statusBySimkl,
                showStatusByTitle = statusByTitle
            )
        }

    // ---------------------------------------------------------------------
    // Fetching & Aggregation
    // ---------------------------------------------------------------------

    private suspend fun fetchAndBuild(): CalendarData = withContext(Dispatchers.IO) {
        val filters = resolveUserFilters()

        val tvItems = fetchTvItems(filters.userShows)
        val dvdItems = fetchDvdItems(filters.userMovies)
        val movieItems = fetchMovieItems(filters.userMovies)

        val mappedTv = tvItems.mapNotNull { mapTvItem(it, filters) }
        val mappedDvd = dvdItems.mapNotNull { mapDvdItem(it, filters) }
        val mappedMovies = movieItems.mapNotNull { mapMovieItem(it, filters) }

        val allItems = mappedTv + mappedDvd + mappedMovies
        val availableToStreamCount = allItems.count { it.isAvailableToStream }

        val days = buildDayGroups(mappedTv, mappedDvd, mappedMovies)

        CalendarData(
            days = days,
            allItems = allItems,
            availableToStreamCount = availableToStreamCount
        )
    }

    private fun fetchTvItems(userShows: TrackedMediaFilter): List<SimklTvCalendarItem> {
        val items = try {
            val body = fetchBody(tvUrl) ?: return emptyList()
            json.decodeFromString<List<SimklTvCalendarItem>>(body)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to fetch TV calendar", t)
            return emptyList()
        }

        val hasTrackedShows = !userShows.isEmpty
        return items.filter { item ->
            val imdb = item.ids.simklImdbId()
            val tmdb = item.ids.simklTmdbId()
            val simklId = item.ids.simklNumericId()
            val isTracked = userShows.matches(imdb, tmdb, simklId, item.title)

            if (hasTrackedShows) {
                isTracked
            } else {
                isTrendingTvShow(item)
            }
        }
    }

    private fun fetchDvdItems(userMovies: TrackedMediaFilter): List<SimklDvdReleaseItem> {
        val items = try {
            val body = fetchBody(dvdUrl) ?: return emptyList()
            json.decodeFromString<List<SimklDvdReleaseItem>>(body)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to fetch DVD/digital releases", t)
            return emptyList()
        }

        return items.filter { isTrendingDvdMovie(it, userMovies) }
    }

    private fun fetchMovieItems(userMovies: TrackedMediaFilter): List<SimklMovieCalendarItem> {
        val items = try {
            val body = fetchBody(movieUrl) ?: return emptyList()
            json.decodeFromString<List<SimklMovieCalendarItem>>(body)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to fetch movie calendar", t)
            return emptyList()
        }

        return items.filter { isTrendingTheatricalMovie(it, userMovies) }
    }

    private fun isTrendingTvShow(item: SimklTvCalendarItem): Boolean {
        val rank = item.rank
        if (rank != null && rank in 1..TRENDING_TV_MAX_RANK) return true
        val simklVotes = item.ratings?.simkl?.votes ?: 0
        if (simklVotes >= TRENDING_TV_MIN_VOTES) return true
        val imdbVotes = item.ratings?.imdb?.votes ?: 0
        return imdbVotes >= 100
    }

    private fun isTrendingDvdMovie(item: SimklDvdReleaseItem, userMovies: TrackedMediaFilter): Boolean {
        val imdb = item.ids.simklImdbId()
        val tmdb = item.ids.simklTmdbId()
        val simklId = item.ids.simklNumericId()
        if (userMovies.matches(imdb, tmdb, simklId, item.title)) return true

        val rank = item.rank
        if (rank != null && rank in 1..TRENDING_MOVIE_MAX_RANK) return true
        val simklVotes = item.ratings?.simkl?.votes ?: 0
        if (simklVotes >= TRENDING_MOVIE_MIN_VOTES) return true
        val imdbVotes = item.ratings?.imdb?.votes ?: 0
        if (imdbVotes >= 50) return true
        val simklRating = item.ratings?.simkl?.rating ?: 0f
        return simklRating >= TRENDING_MOVIE_MIN_RATING && simklVotes >= 5
    }

    private fun isTrendingTheatricalMovie(item: SimklMovieCalendarItem, userMovies: TrackedMediaFilter): Boolean {
        val imdb = item.ids.simklImdbId()
        val tmdb = item.ids.simklTmdbId()
        val simklId = item.ids.simklNumericId()
        if (userMovies.matches(imdb, tmdb, simklId, item.title)) return true

        val rank = item.rank
        return rank != null && rank in 1..TRENDING_MOVIE_MAX_RANK
    }

    private fun fetchBody(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "$APP_NAME/$APP_VERSION")
            .get()
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} for $url")
                    return null
                }
                response.body?.string()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "Network call failed for $url", t)
            null
        }
    }

    // ---------------------------------------------------------------------
    // Mapping
    // ---------------------------------------------------------------------

    private fun mapTvItem(
        item: SimklTvCalendarItem,
        userFilters: UserCalendarFilters
    ): CalendarMediaItem? {
        val parsed = parseFlexibleDate(item.date)
        val date = parsed?.date ?: return null

        val isAvailable = !date.isAfter(LocalDate.now())

        val imdb = item.ids.simklImdbId()
        val tmdb = item.ids.simklTmdbId()
        val simklId = item.ids.simklNumericId()

        val season = item.episode?.season
        val episode = item.episode?.episode
        val episodeName = item.episode?.name
        val baseId = buildId(imdb, tmdb, simklId, item.title)
        val uniqueId = if (season != null && episode != null) {
            "$baseId:s${season}e$episode"
        } else {
            baseId
        }

        val showStatus = userFilters.findShowStatus(imdb, tmdb, simklId, item.title)
        val isActivelyWatching = showStatus?.isActivelyWatching ?: false
        val isWatchlist = showStatus?.isWatchlist ?: false

        val isSeriesPremiere = (season == 1 && episode == 1) || (season == null && episode == 1)
        val isSeasonPremiere = (season != null && season > 1 && episode == 1)

        val isSeasonFinale = (season != null && episode != null && showStatus?.seasonMaxEpisodes?.get(season) == episode) ||
            (episodeName?.lowercase(Locale.ROOT)?.contains("finale") == true)

        val isNextUp = if (season != null && episode != null && showStatus != null) {
            if (showStatus.nextSeason != null && showStatus.nextEpisode != null) {
                season == showStatus.nextSeason && episode == showStatus.nextEpisode
            } else if (showStatus.lastWatchedSeason != null && showStatus.lastWatchedEpisode != null) {
                (season == showStatus.lastWatchedSeason && episode == showStatus.lastWatchedEpisode + 1) ||
                    (season == showStatus.lastWatchedSeason + 1 && episode == 1 &&
                        showStatus.seasonMaxEpisodes[showStatus.lastWatchedSeason] == showStatus.lastWatchedEpisode)
            } else false
        } else false

        val userStatusNote = when {
            isNextUp -> "Next up for you"
            isActivelyWatching -> when {
                showStatus?.lastWatchedSeason != null && showStatus.lastWatchedEpisode != null ->
                    "You watched S${showStatus.lastWatchedSeason}·E${showStatus.lastWatchedEpisode}"
                (showStatus?.totalWatchedEpisodes ?: 0) > 0 ->
                    "${showStatus?.totalWatchedEpisodes} eps watched"
                else -> "In your Watching list"
            }
            isWatchlist -> "In your Watchlist"
            else -> null
        }

        return CalendarMediaItem(
            id = uniqueId,
            type = CalendarItemType.TV_EPISODE,
            title = item.title ?: "Unknown Show",
            date = date,
            airTimeString = parsed.timeString,
            isAvailableToStream = isAvailable,
            season = season,
            episode = episode,
            episodeTitle = episodeName,
            posterUrl = simklPosterUrl(item.poster),
            rating = item.ratings?.simkl?.rating ?: item.ratings?.imdb?.rating,
            ratingVotes = item.ratings?.simkl?.votes ?: item.ratings?.imdb?.votes,
            rank = item.rank,
            imdbId = imdb,
            tmdbId = tmdb,
            simklId = simklId,
            isActivelyWatching = isActivelyWatching,
            isWatchlist = isWatchlist,
            isSeriesPremiere = isSeriesPremiere,
            isSeasonPremiere = isSeasonPremiere,
            isSeasonFinale = isSeasonFinale,
            isNextUpForUser = isNextUp,
            userStatusNote = userStatusNote
        )
    }

    private fun mapDvdItem(
        item: SimklDvdReleaseItem,
        userFilters: UserCalendarFilters
    ): CalendarMediaItem? {
        val digitalParsed = parseFlexibleDate(item.dvd_date)
        val theaterParsed = parseFlexibleDate(item.theater)
        val releaseParsed = parseFlexibleDate(item.release_date)

        val digitalDate = digitalParsed?.date
        val theatricalDate = theaterParsed?.date ?: releaseParsed?.date

        val date = digitalDate ?: theatricalDate ?: LocalDate.now()
        val isAvailable = digitalDate != null && !digitalDate.isAfter(LocalDate.now())

        val imdb = item.ids.simklImdbId()
        val tmdb = item.ids.simklTmdbId()
        val simklId = item.ids.simklNumericId()

        val isTracked = userFilters.userMovies.matches(imdb, tmdb, simklId, item.title)

        return CalendarMediaItem(
            id = buildId(imdb, tmdb, simklId, item.title),
            type = CalendarItemType.DIGITAL_MOVIE,
            title = item.title ?: "Unknown Movie",
            date = date,
            isAvailableToStream = isAvailable,
            digitalReleaseDate = digitalDate,
            theatricalReleaseDate = theatricalDate,
            posterUrl = simklPosterUrl(item.poster),
            backdropUrl = simklFanartUrl(item.fanart),
            overview = item.overview,
            genres = item.genres ?: emptyList(),
            rating = item.ratings?.simkl?.rating ?: item.ratings?.imdb?.rating,
            ratingVotes = item.ratings?.simkl?.votes ?: item.ratings?.imdb?.votes,
            rank = item.rank,
            imdbId = imdb,
            tmdbId = tmdb,
            simklId = simklId,
            isWatchlist = isTracked,
            userStatusNote = if (isTracked) "In your Watchlist" else null
        )
    }

    private fun mapMovieItem(
        item: SimklMovieCalendarItem,
        userFilters: UserCalendarFilters
    ): CalendarMediaItem? {
        val parsed = parseFlexibleDate(item.date)
        val date = parsed?.date ?: return null

        val imdb = item.ids.simklImdbId()
        val tmdb = item.ids.simklTmdbId()
        val simklId = item.ids.simklNumericId()

        val isTracked = userFilters.userMovies.matches(imdb, tmdb, simklId, item.title)

        return CalendarMediaItem(
            id = buildId(imdb, tmdb, simklId, item.title),
            type = CalendarItemType.THEATRICAL_MOVIE,
            title = item.title ?: "Unknown Movie",
            date = date,
            theatricalReleaseDate = date,
            isAvailableToStream = false,
            posterUrl = simklPosterUrl(item.poster),
            rank = item.rank,
            imdbId = imdb,
            tmdbId = tmdb,
            simklId = simklId,
            isWatchlist = isTracked,
            userStatusNote = if (isTracked) "In your Watchlist" else null
        )
    }

    private fun buildId(imdb: String?, tmdb: String?, simklId: Long?, title: String?): String {
        return imdb
            ?: tmdb?.let { "tmdb:$it" }
            ?: "simkl:${simklId ?: title ?: "unknown"}"
    }

    // ---------------------------------------------------------------------
    // Day grouping
    // ---------------------------------------------------------------------

    private fun buildDayGroups(
        tvItems: List<CalendarMediaItem>,
        dvdItems: List<CalendarMediaItem>,
        movieItems: List<CalendarMediaItem>
    ): List<CalendarDayGroup> {
        val today = LocalDate.now()
        val allMapped = tvItems + dvdItems + movieItems

        // Pre-index digital releases from the past week for the "Today" bucket.
        val lookbackStart = today.minusDays(DIGITAL_LOOKBACK_DAYS.toLong())
        val recentDigital = dvdItems.filter { item ->
            item.type == CalendarItemType.DIGITAL_MOVIE &&
                item.digitalReleaseDate != null &&
                !item.digitalReleaseDate.isBefore(lookbackStart) &&
                !item.digitalReleaseDate.isAfter(today)
        }

        val dayGroups = mutableListOf<CalendarDayGroup>()

        for (offset in 0 until DAYS_TO_SHOW) {
            val dayDate = today.plusDays(offset.toLong())

            val itemsForDay = allMapped.filter { it.date == dayDate }.toMutableList()

            // On "Today", also surface recent digital releases.
            if (offset == 0) {
                for (recent in recentDigital) {
                    if (itemsForDay.none { it.id == recent.id }) {
                        itemsForDay.add(recent)
                    }
                }
            }

            val sorted = itemsForDay
                .distinctBy { "${it.type}:${it.id}:${it.date}" }
                .sortedWith(dayItemComparator())

            val dowFull = dayDate.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.FULL, Locale.US
            )
            val month = dayDate.month.getDisplayName(
                java.time.format.TextStyle.SHORT, Locale.US
            )
            val shortDow = dayDate.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.SHORT, Locale.US
            ).uppercase(Locale.US)

            val (label, shortLabel) = when (offset) {
                0 -> "Today · $dowFull, $month ${dayDate.dayOfMonth}" to "TODAY · $shortDow ${dayDate.dayOfMonth}"
                1 -> "Tomorrow · $dowFull, $month ${dayDate.dayOfMonth}" to "TOMORROW · $shortDow ${dayDate.dayOfMonth}"
                else -> "$dowFull, $month ${dayDate.dayOfMonth}" to "$shortDow ${dayDate.dayOfMonth}"
            }

            dayGroups.add(
                CalendarDayGroup(
                    date = dayDate,
                    label = label,
                    shortLabel = shortLabel,
                    isToday = offset == 0,
                    items = sorted
                )
            )
        }

        return dayGroups
    }

    private fun dayItemComparator(): Comparator<CalendarMediaItem> = Comparator { a, b ->
        val aHasPoster = a.posterUrl != null
        val bHasPoster = b.posterUrl != null
        if (aHasPoster != bHasPoster) {
            if (aHasPoster) -1 else 1
        } else {
            val aRating = a.rating ?: 0f
            val bRating = b.rating ?: 0f
            bRating.compareTo(aRating)
        }
    }

    // ---------------------------------------------------------------------
    // Date parsing
    // ---------------------------------------------------------------------

    private data class ParsedDate(val date: LocalDate, val timeString: String?)

    private val isoOffsetFormatter: DateTimeFormatter =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME

    private val isoLocalDateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ISO_LOCAL_DATE_TIME

    private val usDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US)

    private val standardDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    private val airTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /**
     * Attempts to parse a date string in several common formats. Never throws.
     */
    private fun parseFlexibleDate(raw: String?): ParsedDate? {
        if (raw.isNullOrBlank()) return null

        val trimmed = raw.trim()

        // 1) ISO offset date-time (e.g. "2026-09-17T00:00:00-04:00" or "...Z")
        try {
            val odt = OffsetDateTime.parse(trimmed, isoOffsetFormatter)
            val localDate = odt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
            val timeString = formatAirTimeIfNeeded(odt.toLocalDateTime())
            return ParsedDate(localDate, timeString)
        } catch (_: DateTimeParseException) {
            // fall through
        } catch (_: Exception) {
            // fall through
        }

        // 2) ISO local date-time (no offset)
        try {
            val ldt = LocalDateTime.parse(trimmed, isoLocalDateTimeFormatter)
            val timeString = formatAirTimeIfNeeded(ldt)
            return ParsedDate(ldt.toLocalDate(), timeString)
        } catch (_: DateTimeParseException) {
            // fall through
        } catch (_: Exception) {
            // fall through
        }

        // 3) US date: MM/dd/yyyy
        try {
            val ld = LocalDate.parse(trimmed, usDateFormatter)
            return ParsedDate(ld, null)
        } catch (_: DateTimeParseException) {
            // fall through
        } catch (_: Exception) {
            // fall through
        }

        // 4) Standard date: yyyy-MM-dd
        try {
            val ld = LocalDate.parse(trimmed, standardDateFormatter)
            return ParsedDate(ld, null)
        } catch (_: DateTimeParseException) {
            // fall through
        } catch (_: Exception) {
            // fall through
        }

        Log.d(TAG, "Unparseable date: $raw")
        return null
    }

    private fun formatAirTimeIfNeeded(dateTime: LocalDateTime): String? {
        return if (dateTime.hour == 0 && dateTime.minute == 0) {
            null
        } else {
            try {
                dateTime.format(airTimeFormatter)
            } catch (_: Exception) {
                null
            }
        }
    }
}
