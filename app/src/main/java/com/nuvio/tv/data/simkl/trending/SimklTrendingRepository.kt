package com.nuvio.tv.data.simkl.trending

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.simkl.calendar.simklFanartUrl
import com.nuvio.tv.data.simkl.calendar.simklPosterUrl
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.MetaTrailer
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.screens.home.HomeTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SimklTrendingResponse(
    @SerialName("tv") val tv: List<SimklTrendingItem> = emptyList(),
    @SerialName("movies") val movies: List<SimklTrendingItem> = emptyList(),
    @SerialName("anime") val anime: List<SimklTrendingItem> = emptyList()
)

@Serializable
data class SimklTrendingItem(
    val title: String? = null,
    val poster: String? = null,
    val fanart: String? = null,
    val ids: SimklTrendingIds? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val rank: Int? = null,
    val ratings: SimklRatings? = null,
    val runtime: String? = null,
    val status: String? = null,
    val network: String? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val trailer: String? = null,
    val metadata: String? = null
)

@Serializable
data class SimklTrendingIds(
    @SerialName("simkl_id") val simklId: Long? = null,
    val slug: String? = null,
    val imdb: String? = null,
    val tmdb: String? = null,
    val tvdb: String? = null
)

@Serializable
data class SimklRatings(
    val simkl: SimklRating? = null,
    val imdb: SimklRating? = null
)

@Serializable
data class SimklRating(
    val rating: Float? = null,
    val votes: Int? = null
)

/**
 * Repository that fetches, caches and maps Simkl trending content into [MetaPreview] domain models.
 */
@Singleton
class SimklTrendingRepository @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val cacheMutex = Mutex()
    private var cachedResponse: SimklTrendingResponse? = null
    private var cacheTimestamp: Long = 0L

    /**
     * Returns trending content mapped to [MetaPreview].
     *
     * @param tab the currently selected home tab (used when [separateMoviesTv] is true).
     * @param separateMoviesTv when true, returns only the content matching [tab].
     * @param forceRefresh bypasses the in-memory cache.
     */
    suspend fun getTrending(
        tab: HomeTab?,
        separateMoviesTv: Boolean,
        forceRefresh: Boolean = false
    ): List<MetaPreview> = withContext(Dispatchers.IO) {
        val response = getResponse(forceRefresh) ?: return@withContext emptyList()

        try {
            when {
                separateMoviesTv && tab == HomeTab.TV_SHOWS -> {
                    (response.tv + response.anime).mapNotNull { it.toMetaPreview(ContentType.SERIES) }
                }

                separateMoviesTv && tab == HomeTab.MOVIES -> {
                    response.movies.mapNotNull { it.toMetaPreview(ContentType.MOVIE) }
                }

                else -> interleave(response)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to map Simkl trending response", e)
            emptyList()
        }
    }

    /**
     * Fetches the trending response, using the in-memory cache when valid.
     */
    private suspend fun getResponse(forceRefresh: Boolean): SimklTrendingResponse? {
        cacheMutex.withLock {
            val now = System.currentTimeMillis()
            val cached = cachedResponse
            if (!forceRefresh && cached != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
                return cached
            }
        }

        val fetched = fetchTrending() ?: return cachedResponse

        cacheMutex.withLock {
            cachedResponse = fetched
            cacheTimestamp = System.currentTimeMillis()
        }
        return fetched
    }

    /**
     * Performs the network request and parses the JSON payload.
     */
    private fun fetchTrending(): SimklTrendingResponse? {
        val clientId = BuildConfig.SIMKL_CLIENT_ID.takeIf { it.isNotBlank() } ?: FALLBACK_CLIENT_ID
        val url = "$BASE_URL?client_id=$clientId&app-name=$APP_NAME&app-version=$APP_VERSION"

        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Simkl trending request failed with HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    Log.w(TAG, "Simkl trending response body was empty")
                    return null
                }
                json.decodeFromString<SimklTrendingResponse>(body)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network error fetching Simkl trending", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Simkl trending response", e)
            null
        }
    }

    /**
     * Interleaves tv, movies and anime items, alternating between sources up to [COMBINED_CAP].
     */
    private fun interleave(response: SimklTrendingResponse): List<MetaPreview> {
        val tvItems = response.tv.mapNotNull { it.toMetaPreview(ContentType.SERIES) }
        val movieItems = response.movies.mapNotNull { it.toMetaPreview(ContentType.MOVIE) }
        val animeItems = response.anime.mapNotNull { it.toMetaPreview(ContentType.SERIES) }

        val result = ArrayList<MetaPreview>(COMBINED_CAP)
        var tvIndex = 0
        var movieIndex = 0
        var animeIndex = 0

        while (result.size < COMBINED_CAP) {
            var added = false

            if (tvIndex < tvItems.size) {
                result.add(tvItems[tvIndex++])
                added = true
                if (result.size >= COMBINED_CAP) break
            }
            if (movieIndex < movieItems.size) {
                result.add(movieItems[movieIndex++])
                added = true
                if (result.size >= COMBINED_CAP) break
            }
            if (animeIndex < animeItems.size) {
                result.add(animeItems[animeIndex++])
                added = true
                if (result.size >= COMBINED_CAP) break
            }

            if (!added) break
        }

        return result
    }

    /**
     * Maps a raw [SimklTrendingItem] to a [MetaPreview], returning null when no usable id exists.
     */
    private fun SimklTrendingItem.toMetaPreview(contentType: ContentType): MetaPreview? {
        val resolvedId = resolveId() ?: return null

        val posterUrl = simklPosterUrl(poster)
        val fanartUrl = simklFanartUrl(fanart)

        val trailerList = trailer?.takeIf { it.isNotBlank() }?.let {
            listOf(MetaTrailer(source = it, type = "Trailer"))
        } ?: emptyList()

        return MetaPreview(
            id = resolvedId,
            type = contentType,
            name = title.orEmpty(),
            poster = posterUrl,
            background = fanartUrl,
            logo = null,
            description = overview,
            releaseInfo = releaseDate?.takeIf { it.isNotBlank() } ?: metadata,
            imdbRating = ratings?.imdb?.rating ?: ratings?.simkl?.rating,
            badgeText = BADGE_TEXT,
            posterShape = PosterShape.POSTER,
            genres = genres,
            runtime = runtime,
            status = status,
            imdbId = ids?.imdb?.takeIf { it.isNotBlank() },
            slug = ids?.slug,
            trailers = trailerList,
            trailerYtIds = listOfNotNull(trailer?.takeIf { it.isNotBlank() })
        )
    }

    /**
     * Resolves the best available identifier: imdb -> tmdb -> simkl.
     */
    private fun SimklTrendingItem.resolveId(): String? {
        val imdbId = ids?.imdb?.takeIf { it.isNotBlank() }
        if (imdbId != null) return imdbId

        val tmdbId = ids?.tmdb?.takeIf { it.isNotBlank() }
        if (tmdbId != null) return "tmdb:$tmdbId"

        val simklId = ids?.simklId
        if (simklId != null) return "simkl:$simklId"

        return null
    }

    companion object {
        private const val TAG = "SimklTrendingRepo"
        private const val BASE_URL = "https://data.simkl.in/discover/trending/today_100.json"
        private const val FALLBACK_CLIENT_ID =
            "dc20e0db975583b15096267cee79cd23b1f56d4bd301ce3c51e4a96a49c834a6"
        private const val APP_NAME = "NuvioTV"
        private const val APP_VERSION = "1.0"
        private const val BADGE_TEXT = "TRENDING"
        private const val COMBINED_CAP = 40
        private const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L
    }
}
