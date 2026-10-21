package com.nuvio.tv.core.tmdb

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TmdbApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TmdbService"
private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY

// Sentinel values used for negative caching.
private const val NEGATIVE_TMDB_ID = -1
private const val NEGATIVE_IMDB_ID = ""

private fun Response<*>.closeErrorBodyQuietly() {
    try {
        errorBody()?.close()
    } catch (_: Exception) {
        // Intentionally ignored: closing is best-effort cleanup.
    }
}

/**
 * Service to handle TMDB ID conversions and lookups.
 * Provides caching to avoid redundant API calls.
 */
@Singleton
class TmdbService @Inject constructor(
    private val tmdbApi: TmdbApi
) {
    // Cache: IMDB ID -> TMDB ID (keyed by "$imdbId:$mediaType")
    // A value of NEGATIVE_TMDB_ID indicates a known "not found" result.
    private val imdbToTmdbCache = ConcurrentHashMap<String, Int>()

    // Cache: TMDB ID -> IMDB ID (keyed by "$tmdbId:$mediaType")
    // A value of NEGATIVE_IMDB_ID indicates a known "not found" result.
    private val tmdbToImdbCache = ConcurrentHashMap<String, String>()

    private val imdbToTmdbInFlight = ConcurrentHashMap<String, CompletableDeferred<Int?>>()
    private val tmdbToImdbInFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    // Mutex for thread-safe cache operations
    private val cacheMutex = Mutex()

    // Set when TMDB responds with 401 Unauthorized (invalid/missing API key).
    // Prevents hammering the network while the key remains invalid.
    @Volatile
    private var isUnauthorized: Boolean = false

    /**
     * Convert an IMDB ID to a TMDB ID.
     *
     * @param imdbId The IMDB ID (e.g., "tt0133093")
     * @param mediaType The media type ("movie" or "series"/"tv")
     * @return The TMDB ID, or null if not found
     */
    suspend fun imdbToTmdb(imdbId: String, mediaType: String): Int? = withContext(Dispatchers.IO) {
        // Validate IMDB ID format
        if (!imdbId.startsWith("tt")) {
            Log.w(TAG, "Invalid IMDB ID format: $imdbId")
            return@withContext null
        }

        val normalizedType = normalizeMediaType(mediaType)
        val cacheKey = "$imdbId:$normalizedType"

        // Check cache first
        imdbToTmdbCache[cacheKey]?.let { cached ->
            if (cached == NEGATIVE_TMDB_ID) {
                Log.d(TAG, "Negative cache hit: IMDB $imdbId ($normalizedType) -> not found")
                return@withContext null
            }
            Log.d(TAG, "Cache hit: IMDB $imdbId ($normalizedType) -> TMDB $cached")
            return@withContext cached
        }

        // Short-circuit if TMDB has already told us the API key is unauthorized.
        if (isUnauthorized) {
            Log.w(TAG, "Skipping IMDB->TMDB lookup for $imdbId: TMDB API unauthorized")
            return@withContext null
        }

        val requestKey = cacheKey
        val requestDeferred = CompletableDeferred<Int?>()
        imdbToTmdbInFlight.putIfAbsent(requestKey, requestDeferred)?.let { existing ->
            return@withContext existing.await()
        }

        try {
            Log.d(TAG, "Looking up TMDB ID for IMDB: $imdbId (type: $mediaType)")

            val response = tmdbApi.findByExternalId(
                externalId = imdbId,
                apiKey = TMDB_API_KEY,
                externalSource = "imdb_id"
            )

            if (response.code() == 401) {
                isUnauthorized = true
                Log.e(TAG, "TMDB API unauthorized (401). Disabling further lookups.")
                response.closeErrorBodyQuietly()
                requestDeferred.complete(null)
                return@withContext null
            }

            if (!response.isSuccessful) {
                Log.e(TAG, "TMDB API error: ${response.code()} - ${response.message()}")
                response.closeErrorBodyQuietly()
                // Negative-cache 404s so we don't repeatedly query missing IDs.
                if (response.code() == 404) {
                    cacheMutex.withLock {
                        imdbToTmdbCache[cacheKey] = NEGATIVE_TMDB_ID
                    }
                }
                requestDeferred.complete(null)
                return@withContext null
            }

            val body = response.body()
            if (body == null) {
                response.closeErrorBodyQuietly()
                requestDeferred.complete(null)
                return@withContext null
            }

            // Check movie results
            if (normalizedType == "movie" && !body.movieResults.isNullOrEmpty()) {
                val tmdbId = body.movieResults.first().id
                Log.d(TAG, "Found TMDB Movie ID: $tmdbId for IMDB: $imdbId")

                // Cache both directions
                cacheMutex.withLock {
                    imdbToTmdbCache[cacheKey] = tmdbId
                    tmdbToImdbCache["$tmdbId:$normalizedType"] = imdbId
                }

                requestDeferred.complete(tmdbId)
                return@withContext tmdbId
            }

            // Check TV results
            if (normalizedType == "tv" && !body.tvResults.isNullOrEmpty()) {
                val tmdbId = body.tvResults.first().id
                Log.d(TAG, "Found TMDB TV ID: $tmdbId for IMDB: $imdbId")

                // Cache both directions
                cacheMutex.withLock {
                    imdbToTmdbCache[cacheKey] = tmdbId
                    tmdbToImdbCache["$tmdbId:$normalizedType"] = imdbId
                }

                requestDeferred.complete(tmdbId)
                return@withContext tmdbId
            }

            // Check any results if type didn't match
            val fallbackId = body.movieResults?.firstOrNull()?.id ?: body.tvResults?.firstOrNull()?.id
            if (fallbackId != null) {
                val detectedType = if (!body.movieResults.isNullOrEmpty()) "movie" else "tv"
                Log.d(TAG, "Found TMDB ID: $fallbackId (detected type: $detectedType) for IMDB: $imdbId")

                // Cache with both the detected type and requested type
                cacheMutex.withLock {
                    imdbToTmdbCache[cacheKey] = fallbackId
                    imdbToTmdbCache["$imdbId:$detectedType"] = fallbackId
                    tmdbToImdbCache["$fallbackId:$detectedType"] = imdbId
                }

                requestDeferred.complete(fallbackId)
                return@withContext fallbackId
            }

            Log.w(TAG, "No TMDB result found for IMDB: $imdbId")
            cacheMutex.withLock {
                imdbToTmdbCache[cacheKey] = NEGATIVE_TMDB_ID
            }
            requestDeferred.complete(null)
            null

        } catch (e: CancellationException) {
            requestDeferred.cancel(e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up TMDB ID for $imdbId: ${e.message}", e)
            requestDeferred.complete(null)
            null
        } finally {
            imdbToTmdbInFlight.remove(requestKey, requestDeferred)
        }
    }

    /**
     * Convert a TMDB ID to an IMDB ID.
     *
     * @param tmdbId The TMDB ID
     * @param mediaType The media type ("movie" or "series"/"tv")
     * @return The IMDB ID, or null if not found
     */
    suspend fun tmdbToImdb(tmdbId: Int, mediaType: String): String? = withContext(Dispatchers.IO) {
        val normalizedType = normalizeMediaType(mediaType)
        val cacheKey = "$tmdbId:$normalizedType"

        // Check cache first
        tmdbToImdbCache[cacheKey]?.let { cached ->
            if (cached == NEGATIVE_IMDB_ID) {
                Log.d(TAG, "Negative cache hit: TMDB $tmdbId ($normalizedType) -> not found")
                return@withContext null
            }
            Log.d(TAG, "Cache hit: TMDB $tmdbId ($normalizedType) -> IMDB $cached")
            return@withContext cached
        }

        // Short-circuit if TMDB has already told us the API key is unauthorized.
        if (isUnauthorized) {
            Log.w(TAG, "Skipping TMDB->IMDB lookup for $tmdbId: TMDB API unauthorized")
            return@withContext null
        }

        val requestKey = cacheKey
        val requestDeferred = CompletableDeferred<String?>()
        tmdbToImdbInFlight.putIfAbsent(requestKey, requestDeferred)?.let { existing ->
            return@withContext existing.await()
        }

        try {
            Log.d(TAG, "Looking up IMDB ID for TMDB: $tmdbId (type: $mediaType)")

            val response = when (normalizedType) {
                "movie" -> tmdbApi.getMovieExternalIds(tmdbId, TMDB_API_KEY)
                "tv", "series" -> tmdbApi.getTvExternalIds(tmdbId, TMDB_API_KEY)
                else -> tmdbApi.getMovieExternalIds(tmdbId, TMDB_API_KEY)
            }

            if (response.code() == 401) {
                isUnauthorized = true
                Log.e(TAG, "TMDB API unauthorized (401). Disabling further lookups.")
                response.closeErrorBodyQuietly()
                requestDeferred.complete(null)
                return@withContext null
            }

            if (!response.isSuccessful) {
                Log.e(TAG, "TMDB API error: ${response.code()} - ${response.message()}")
                response.closeErrorBodyQuietly()
                if (response.code() == 404) {
                    cacheMutex.withLock {
                        tmdbToImdbCache[cacheKey] = NEGATIVE_IMDB_ID
                    }
                }
                requestDeferred.complete(null)
                return@withContext null
            }

            val body = response.body()
            if (body == null) {
                response.closeErrorBodyQuietly()
                requestDeferred.complete(null)
                return@withContext null
            }

            body.imdbId?.let { imdbId ->
                Log.d(TAG, "Found IMDB ID: $imdbId for TMDB: $tmdbId")

                // Cache both directions
                cacheMutex.withLock {
                    tmdbToImdbCache[cacheKey] = imdbId
                    imdbToTmdbCache["$imdbId:$normalizedType"] = tmdbId
                }

                requestDeferred.complete(imdbId)
                return@withContext imdbId
            }

            Log.w(TAG, "No IMDB ID found for TMDB: $tmdbId")
            cacheMutex.withLock {
                tmdbToImdbCache[cacheKey] = NEGATIVE_IMDB_ID
            }
            requestDeferred.complete(null)
            null

        } catch (e: CancellationException) {
            requestDeferred.cancel(e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up IMDB ID for $tmdbId: ${e.message}", e)
            requestDeferred.complete(null)
            null
        } finally {
            tmdbToImdbInFlight.remove(requestKey, requestDeferred)
        }
    }

    /**
     * Get a TMDB ID from a video ID string.
     * Handles both IMDB IDs (tt...) and TMDB IDs.
     *
     * @param videoId The video ID (can be IMDB or TMDB format)
     * @param mediaType The media type
     * @param fallbackImdbId Optional IMDB ID from meta response
     * @return The TMDB ID as a string, or null if conversion failed
     */
    suspend fun ensureTmdbId(videoId: String, mediaType: String, fallbackImdbId: String? = null): String? {
        // Check if it's already a TMDB ID (numeric or prefixed)
        val cleanId = videoId
            .removePrefix("tmdb:")
            .removePrefix("movie:")
            .removePrefix("series:")

        // Stremio-style series ids can look like: tt1234567:season:episode
        // Plugins/TMDB lookup need the base external id only.
        val idPart = cleanId
            .substringBefore(':')
            .substringBefore('/')
            .trim()

        // If it's an IMDB ID, convert it
        if (idPart.startsWith("tt")) {
            val tmdbId = imdbToTmdb(idPart, normalizeMediaType(mediaType))
            return tmdbId?.toString()
        }

        // If it looks like a numeric ID, assume it's already a TMDB ID
        if (idPart.all { it.isDigit() }) {
            return idPart
        }

        // Fallback: use the IMDB ID supplied by the addon's meta response
        val normalizedFallback = fallbackImdbId
            ?.trim()
            ?.substringBefore(':')
            ?.takeIf { it.startsWith("tt", ignoreCase = true) }
        if (normalizedFallback != null) {
            val tmdbId = imdbToTmdb(normalizedFallback, normalizeMediaType(mediaType))
            if (tmdbId != null) return tmdbId.toString()
        }

        // Unknown format
        Log.w(TAG, "Unknown video ID format: $videoId")
        return null
    }

    /**
     * Normalize media type to consistent format
     */
    private fun normalizeMediaType(mediaType: String): String {
        return when (mediaType.lowercase()) {
            "series", "tv", "show", "tvshow" -> "tv"
            "movie", "film" -> "movie"
            else -> mediaType.lowercase()
        }
    }

    /**
     * Clear all caches
     */
    fun clearCache() {
        imdbToTmdbCache.clear()
        tmdbToImdbCache.clear()
        imdbToTmdbInFlight.clear()
        tmdbToImdbInFlight.clear()
        isUnauthorized = false
        Log.d(TAG, "Cache cleared")
    }

    /**
     * Pre-populate cache with known mappings
     */
    fun preCacheMapping(imdbId: String, tmdbId: Int, mediaType: String = "movie") {
        val normalizedType = normalizeMediaType(mediaType)
        imdbToTmdbCache["$imdbId:$normalizedType"] = tmdbId
        tmdbToImdbCache["$tmdbId:$normalizedType"] = imdbId
    }

    /** Returns the cached TMDB ID for an IMDB ID without making any network call. */
    fun cachedTmdbId(imdbId: String): Int? =
        (imdbToTmdbCache["$imdbId:movie"] ?: imdbToTmdbCache["$imdbId:tv"])
            ?.takeIf { it != NEGATIVE_TMDB_ID }

    fun apiKey(): String = TMDB_API_KEY

    /**
     * Fetches backdrop and poster URLs from TMDB for the given IMDB ID.
     * Returns null if the IMDB ID doesn't start with "tt" or if TMDB has no data.
     * Results are NOT cached here — callers should persist what they need.
     */
    suspend fun fetchImdbImages(imdbId: String, mediaType: String): TmdbImages? =
        withContext(Dispatchers.IO) {
            if (!imdbId.startsWith("tt")) return@withContext null
            if (isUnauthorized) {
                Log.w(TAG, "Skipping image fetch for $imdbId: TMDB API unauthorized")
                return@withContext null
            }
            val tmdbId = imdbToTmdb(imdbId, mediaType) ?: return@withContext null
            runCatching {
                val isMovie = normalizeMediaType(mediaType) == "movie"
                val response = if (isMovie)
                    tmdbApi.getMovieDetails(tmdbId, TMDB_API_KEY)
                else
                    tmdbApi.getTvDetails(tmdbId, TMDB_API_KEY)
                if (response.code() == 401) {
                    isUnauthorized = true
                    Log.e(TAG, "TMDB API unauthorized (401). Disabling further lookups.")
                    response.closeErrorBodyQuietly()
                    return@runCatching null
                }
                if (!response.isSuccessful) {
                    response.closeErrorBodyQuietly()
                    return@runCatching null
                }
                val body = response.body()
                if (body == null) {
                    response.closeErrorBodyQuietly()
                    return@runCatching null
                }
                TmdbImages(
                    backdropUrl = body.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
                    posterUrl = body.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                    runtimeMinutes = body.runtime
                )
            }.getOrNull()
        }
}

data class TmdbImages(val backdropUrl: String?, val posterUrl: String?, val runtimeMinutes: Int? = null)
