package com.nuvio.tv.data.simkl.calendar

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * Results are cached in-memory for [CACHE_TTL_MS] and refreshed on demand.
 */
@Singleton
class SimklCalendarRepository @Inject constructor(
    private val okHttpClient: OkHttpClient
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
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load calendar data", t)
            Result.failure(t)
        }
    }

    // ---------------------------------------------------------------------
    // Fetching
    // ---------------------------------------------------------------------

    private suspend fun fetchAndBuild(): CalendarData = withContext(Dispatchers.IO) {
        val tvItems = fetchTvItems()
        val dvdItems = fetchDvdItems()
        val movieItems = fetchMovieItems()

        val mappedTv = tvItems.mapNotNull { mapTvItem(it) }
        val mappedDvd = dvdItems.mapNotNull { mapDvdItem(it) }
        val mappedMovies = movieItems.mapNotNull { mapMovieItem(it) }

        val allItems = mappedTv + mappedDvd + mappedMovies
        val availableToStreamCount = allItems.count { it.isAvailableToStream }

        val days = buildDayGroups(mappedTv, mappedDvd, mappedMovies)

        CalendarData(
            days = days,
            allItems = allItems,
            availableToStreamCount = availableToStreamCount
        )
    }

    private fun fetchTvItems(): List<SimklTvCalendarItem> {
        return try {
            val body = fetchBody(tvUrl) ?: return emptyList()
            json.decodeFromString<List<SimklTvCalendarItem>>(body)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to fetch TV calendar", t)
            emptyList()
        }
    }

    private fun fetchDvdItems(): List<SimklDvdReleaseItem> {
        return try {
            val body = fetchBody(dvdUrl) ?: return emptyList()
            json.decodeFromString<List<SimklDvdReleaseItem>>(body)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to fetch DVD/digital releases", t)
            emptyList()
        }
    }

    private fun fetchMovieItems(): List<SimklMovieCalendarItem> {
        return try {
            val body = fetchBody(movieUrl) ?: return emptyList()
            json.decodeFromString<List<SimklMovieCalendarItem>>(body)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to fetch movie calendar", t)
            emptyList()
        }
    }

    private fun fetchBody(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "$APP_NAME/$APP_VERSION")
            .get()
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} for $url")
                return null
            }
            response.body?.string()
        }
    }

    // ---------------------------------------------------------------------
    // Mapping
    // ---------------------------------------------------------------------

    private fun mapTvItem(item: SimklTvCalendarItem): CalendarMediaItem? {
        val parsed = parseFlexibleDate(item.date)
        val date = parsed?.date ?: return null

        val isAvailable = !date.isAfter(LocalDate.now())

        val imdb = item.ids.simklImdbId()
        val tmdb = item.ids.simklTmdbId()
        val simklId = item.ids.simklNumericId()

        return CalendarMediaItem(
            id = buildId(imdb, tmdb, simklId, item.title),
            type = CalendarItemType.TV_EPISODE,
            title = item.title ?: "Unknown Show",
            date = date,
            airTimeString = parsed.timeString,
            isAvailableToStream = isAvailable,
            season = item.episode?.season,
            episode = item.episode?.episode,
            episodeTitle = item.episode?.name,
            posterUrl = simklPosterUrl(item.poster),
            rating = item.ratings?.simkl?.rating ?: item.ratings?.imdb?.rating,
            ratingVotes = item.ratings?.simkl?.votes ?: item.ratings?.imdb?.votes,
            rank = item.rank,
            imdbId = imdb,
            tmdbId = tmdb,
            simklId = simklId
        )
    }

    private fun mapDvdItem(item: SimklDvdReleaseItem): CalendarMediaItem? {
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
            simklId = simklId
        )
    }

    private fun mapMovieItem(item: SimklMovieCalendarItem): CalendarMediaItem? {
        val parsed = parseFlexibleDate(item.date)
        val date = parsed?.date ?: return null

        val imdb = item.ids.simklImdbId()
        val tmdb = item.ids.simklTmdbId()
        val simklId = item.ids.simklNumericId()

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
            simklId = simklId
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

            val sorted = itemsForDay.sortedWith(dayItemComparator())

            val (label, shortLabel) = when (offset) {
                0 -> "Today" to "TODAY"
                1 -> "Tomorrow" to "TOMORROW"
                else -> {
                    val dow = dayDate.dayOfWeek.getDisplayName(
                        java.time.format.TextStyle.FULL, Locale.US
                    )
                    val month = dayDate.month.getDisplayName(
                        java.time.format.TextStyle.SHORT, Locale.US
                    )
                    val longLabel = "$dow, $month ${dayDate.dayOfMonth}"
                    val shortDow = dayDate.dayOfWeek.getDisplayName(
                        java.time.format.TextStyle.SHORT, Locale.US
                    ).uppercase(Locale.US)
                    val short = "$shortDow ${dayDate.dayOfMonth}"
                    longLabel to short
                }
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
