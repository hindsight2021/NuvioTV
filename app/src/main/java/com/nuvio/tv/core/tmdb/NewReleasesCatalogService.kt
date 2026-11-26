package com.nuvio.tv.core.tmdb

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and caches the daily "New Releases" TV shows catalog row from TMDB,
 * sorted strictly by release date (newest first) and refreshed once per calendar day.
 */
@Singleton
class NewReleasesCatalogService @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val tmdbApi: TmdbApi,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore
) {
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getCachedRow(language: String? = null): CatalogRow? {
        val cache = readCache() ?: return null
        if (cache.items.isEmpty()) return null
        if (language != null && cache.language != language) return null
        return buildRow(cache.items)
    }

    fun isCacheFreshForToday(
        today: LocalDate = LocalDate.now(),
        language: String = DEFAULT_LANGUAGE
    ): Boolean {
        val cache = readCache() ?: return false
        return cache.items.isNotEmpty() &&
            cache.cachedDate == today.format(DATE_FORMATTER) &&
            cache.language == language
    }

    suspend fun getOrRefreshDailyRow(
        forceRefresh: Boolean = false,
        today: LocalDate = LocalDate.now()
    ): CatalogRow? = withContext(Dispatchers.IO) {
        val language = resolveLanguage()
        val todayStr = today.format(DATE_FORMATTER)
        val cached = readCache()

        if (!forceRefresh &&
            cached != null &&
            cached.items.isNotEmpty() &&
            cached.cachedDate == todayStr &&
            cached.language == language
        ) {
            return@withContext buildRow(cached.items)
        }

        if (BuildConfig.TMDB_API_KEY.isBlank()) {
            return@withContext cached?.takeIf { it.items.isNotEmpty() }?.let { buildRow(it.items) }
        }

        try {
            val items = fetchItems(today, language)
            if (items.isNotEmpty()) {
                writeCache(CacheEntry(todayStr, language, items))
                buildRow(items)
            } else {
                cached?.takeIf { it.items.isNotEmpty() }?.let { buildRow(it.items) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to refresh daily New Releases catalog", t)
            cached?.takeIf { it.items.isNotEmpty() }?.let { buildRow(it.items) }
        }
    }

    internal fun buildNewReleasesItems(
        results: List<TmdbDiscoverResult>,
        today: LocalDate
    ): List<MetaPreview> {
        val todayStr = today.format(DATE_FORMATTER)
        return results
            .asSequence()
            .mapNotNull { mapToMetaPreview(it) }
            .filter { item ->
                val released = item.released?.trim().orEmpty()
                released.isNotEmpty() && released <= todayStr
            }
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<MetaPreview> { it.released ?: "" }
                    .thenByDescending { it.voteCount ?: 0 }
                    .thenByDescending { it.imdbRating ?: 0f }
            )
            .take(MAX_ITEMS)
            .toList()
    }

    private suspend fun fetchItems(today: LocalDate, language: String): List<MetaPreview> {
        val firstAirDateGte = today.minusDays(WINDOW_DAYS).format(DATE_FORMATTER)
        val firstAirDateLte = today.format(DATE_FORMATTER)

        val strictPage1 = tmdbApi.discoverTv(
            apiKey = BuildConfig.TMDB_API_KEY,
            language = language,
            page = 1,
            sortBy = SORT_BY,
            firstAirDateGte = firstAirDateGte,
            firstAirDateLte = firstAirDateLte,
            voteCountGte = STRICT_VOTE_COUNT,
            withoutGenres = WITHOUT_GENRES
        ).body()?.results.orEmpty()

        val strictPage2 = tmdbApi.discoverTv(
            apiKey = BuildConfig.TMDB_API_KEY,
            language = language,
            page = 2,
            sortBy = SORT_BY,
            firstAirDateGte = firstAirDateGte,
            firstAirDateLte = firstAirDateLte,
            voteCountGte = STRICT_VOTE_COUNT,
            withoutGenres = WITHOUT_GENRES
        ).body()?.results.orEmpty()

        var items = buildNewReleasesItems(strictPage1 + strictPage2, today)

        if (items.size < MIN_ITEMS_BEFORE_FALLBACK) {
            val relaxedPage1 = tmdbApi.discoverTv(
                apiKey = BuildConfig.TMDB_API_KEY,
                language = language,
                page = 1,
                sortBy = SORT_BY,
                firstAirDateGte = firstAirDateGte,
                firstAirDateLte = firstAirDateLte,
                voteCountGte = RELAXED_VOTE_COUNT,
                withoutGenres = WITHOUT_GENRES
            ).body()?.results.orEmpty()

            val relaxedPage2 = tmdbApi.discoverTv(
                apiKey = BuildConfig.TMDB_API_KEY,
                language = language,
                page = 2,
                sortBy = SORT_BY,
                firstAirDateGte = firstAirDateGte,
                firstAirDateLte = firstAirDateLte,
                voteCountGte = RELAXED_VOTE_COUNT,
                withoutGenres = WITHOUT_GENRES
            ).body()?.results.orEmpty()

            items = buildNewReleasesItems(strictPage1 + strictPage2 + relaxedPage1 + relaxedPage2, today)
        }

        return items
    }

    private fun mapToMetaPreview(result: TmdbDiscoverResult): MetaPreview? {
        val posterPath = result.posterPath?.takeIf { it.isNotBlank() }
        val backdropPath = result.backdropPath?.takeIf { it.isNotBlank() }
        if (posterPath == null && backdropPath == null) return null

        val name = result.name?.takeIf { it.isNotBlank() }
            ?: result.title?.takeIf { it.isNotBlank() }
            ?: result.originalName?.takeIf { it.isNotBlank() }
            ?: result.originalTitle?.takeIf { it.isNotBlank() }
            ?: return null

        val firstAirDate = result.firstAirDate?.takeIf { it.isNotBlank() } ?: return null

        return MetaPreview(
            id = "tmdb:${result.id}",
            type = ContentType.SERIES,
            rawType = "series",
            name = name,
            poster = posterPath?.let { "$POSTER_BASE_URL$it" }
                ?: backdropPath?.let { "$BACKDROP_POSTER_FALLBACK_URL$it" },
            posterShape = PosterShape.POSTER,
            background = backdropPath?.let { "$BACKDROP_BASE_URL$it" },
            logo = null,
            description = result.overview?.takeIf { it.isNotBlank() },
            releaseInfo = firstAirDate.take(4),
            released = firstAirDate,
            imdbRating = result.voteAverage?.toFloat()?.takeIf { it > 0f },
            voteCount = result.voteCount,
            genres = emptyList()
        )
    }

    private fun buildRow(items: List<MetaPreview>): CatalogRow = CatalogRow(
        addonId = ADDON_ID,
        addonName = ADDON_NAME,
        addonBaseUrl = "",
        catalogId = CATALOG_ID,
        catalogName = CATALOG_NAME,
        type = ContentType.SERIES,
        rawType = "series",
        items = items,
        isLoading = false,
        hasMore = false,
        supportsSkip = false
    )

    private suspend fun resolveLanguage(): String {
        return runCatching {
            tmdbSettingsDataStore.settings.first().language.takeIf { it.isNotBlank() }
        }.getOrNull() ?: DEFAULT_LANGUAGE
    }

    private fun readCache(): CacheEntry? {
        val raw = prefs.getString(KEY_CACHE, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val cachedDate = root.optString("cachedDate", "")
            val language = root.optString("language", DEFAULT_LANGUAGE)
            val arr = root.optJSONArray("items") ?: JSONArray()
            val items = buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").takeIf { it.isNotBlank() } ?: continue
                    val name = obj.optString("name", "").takeIf { it.isNotBlank() } ?: continue
                    val rating = obj.optDouble("imdbRating", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
                    val voteCount = if (obj.has("voteCount") && !obj.isNull("voteCount")) obj.optInt("voteCount") else null
                    add(
                        MetaPreview(
                            id = id,
                            type = ContentType.SERIES,
                            rawType = "series",
                            name = name,
                            poster = obj.optString("poster", "").takeIf { it.isNotBlank() },
                            posterShape = PosterShape.POSTER,
                            background = obj.optString("background", "").takeIf { it.isNotBlank() },
                            logo = null,
                            description = obj.optString("description", "").takeIf { it.isNotBlank() },
                            releaseInfo = obj.optString("releaseInfo", "").takeIf { it.isNotBlank() },
                            released = obj.optString("released", "").takeIf { it.isNotBlank() },
                            imdbRating = rating,
                            voteCount = voteCount,
                            genres = emptyList()
                        )
                    )
                }
            }
            if (cachedDate.isBlank() || items.isEmpty()) null else CacheEntry(cachedDate, language, items)
        }.getOrNull()
    }

    private fun writeCache(entry: CacheEntry) {
        runCatching {
            val arr = JSONArray()
            entry.items.forEach { item ->
                arr.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("name", item.name)
                        put("poster", item.poster ?: "")
                        put("background", item.background ?: "")
                        put("description", item.description ?: "")
                        put("releaseInfo", item.releaseInfo ?: "")
                        put("released", item.released ?: "")
                        item.imdbRating?.let { put("imdbRating", it.toDouble()) }
                        item.voteCount?.let { put("voteCount", it) }
                    }
                )
            }
            val root = JSONObject().apply {
                put("cachedDate", entry.cachedDate)
                put("language", entry.language)
                put("items", arr)
            }
            prefs.edit().putString(KEY_CACHE, root.toString()).apply()
        }
    }

    private data class CacheEntry(
        val cachedDate: String,
        val language: String,
        val items: List<MetaPreview>
    )

    companion object {
        private const val TAG = "NewReleasesCatalog"
        private const val PREFS_NAME = "nuvio_new_releases_cache"
        private const val KEY_CACHE = "new_releases_tv_daily_v1"

        const val ADDON_ID = "tmdb"
        const val ADDON_NAME = "TMDB"
        const val CATALOG_ID = "new_releases_tv"
        const val CATALOG_NAME = "New Releases"

        private const val SORT_BY = "first_air_date.desc"
        private const val WITHOUT_GENRES = "10763,10767"
        private const val STRICT_VOTE_COUNT = 5
        private const val RELAXED_VOTE_COUNT = 1
        private const val MIN_ITEMS_BEFORE_FALLBACK = 10
        private const val WINDOW_DAYS = 90L
        private const val MAX_ITEMS = 24

        private const val POSTER_BASE_URL = "https://image.tmdb.org/t/p/w500"
        private const val BACKDROP_POSTER_FALLBACK_URL = "https://image.tmdb.org/t/p/w780"
        private const val BACKDROP_BASE_URL = "https://image.tmdb.org/t/p/w1280"
        private const val DEFAULT_LANGUAGE = "en-US"

        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
