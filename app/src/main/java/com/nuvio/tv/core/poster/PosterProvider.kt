package com.nuvio.tv.core.poster

import com.nuvio.tv.domain.model.PosterShape

/**
 * Available poster artwork providers.
 *
 * @property id Stable identifier used for DataStore persistence.
 * @property displayName Human readable label shown in the UI.
 * @property description Detailed explanation of the provider shown in the UI.
 */
enum class PosterProvider(
    val id: String,
    val displayName: String,
    val description: String
) {
    OFF(
        "off",
        "Off (Default Addon / TMDB)",
        "Use the default poster artwork provided by the addon or TMDB."
    ),
    POSTERS_PLUS(
        "posters_plus",
        "Posters+ (Slokker)",
        "Curated ratings, badges, and localized titles via slokker.cc Posters+."
    ),
    RPDB(
        "rpdb",
        "RatingPosterDB (RPDB)",
        "IMDb, Rotten Tomatoes, and Metacritic rating posters (requires API key)."
    ),
    CUSTOM(
        "custom",
        "Custom URL Pattern",
        "Custom image URL template with {id}, {type}, and {shape} tokens."
    );

    companion object {
        /**
         * Resolves a [PosterProvider] from its [id].
         *
         * @param id The provider id, case-insensitive and trimmed.
         * @return The matching provider, or [OFF] when [id] is null/blank/unknown.
         */
        fun fromId(id: String?): PosterProvider {
            val normalized = id?.trim()?.lowercase()
            if (normalized.isNullOrEmpty()) return OFF
            return entries.firstOrNull { it.id == normalized } ?: OFF
        }
    }
}

/** Default Posters+ URL pattern with placeholder tokens. */
const val DEFAULT_POSTERS_PLUS_PATTERN: String =
    "https://postersplus.slokker.cc/poster?tmdb_id={tmdb_id?}&imdb_id={imdb_id?}&stremio_id={id}&type={type}&shape={shape}&pc=stremio_tv_nuvio&tg=medium&bp=top_left&fti=1&mw=tk:.18,rt:.82,pc:.13,im:1,mc:.22,tm:.67&tw=tk:.4,rt:.78,pc:.35,im:1,mc:.65,mu:.55,tm:.53&pst=tvdb&fbs=photoreal&ol=en&slr=1.20&shr=0.135&bdm=7&bms=5"

/** Default RPDB poster URL pattern with placeholder tokens. */
const val DEFAULT_RPDB_POSTER_PATTERN: String =
    "https://api.ratingposterdb.com/{api_key}/imdb/poster-default/{imdb_id}.jpg"

/** Default RPDB landscape (backdrop) URL pattern with placeholder tokens. */
const val DEFAULT_RPDB_LANDSCAPE_PATTERN: String =
    "https://api.ratingposterdb.com/{api_key}/imdb/backdrop-default/{imdb_id}.jpg"

/**
 * Resolves poster and backdrop URLs for the supported [PosterProvider]s.
 */
object PosterUrlResolver {

    /**
     * Builds an enhanced poster or landscape URL for the given [provider].
     *
     * @param provider The active poster provider.
     * @param stremioId The Stremio content id (e.g. `tt1234567` or `kitsu:12`).
     * @param imdbId Optional IMDb id.
     * @param tmdbId Optional TMDB id.
     * @param type Optional content type (`movie`/`series`); normalized internally.
     * @param shape Requested poster shape (`POSTER`, `LANDSCAPE`, `SQUARE`).
     * @param preferLandscape When true, forces landscape shape regardless of [shape].
     * @param customPattern Custom URL pattern used when [provider] is [PosterProvider.CUSTOM].
     * @param rpdbApiKey RPDB API key used when [provider] is [PosterProvider.RPDB].
     * @return The resolved URL, or null when it cannot be built or provider is [PosterProvider.OFF].
     */
    fun resolvePosterUrl(
        provider: PosterProvider,
        stremioId: String?,
        imdbId: String? = null,
        tmdbId: String? = null,
        type: String? = null,
        shape: PosterShape = PosterShape.POSTER,
        preferLandscape: Boolean = false,
        customPattern: String? = null,
        rpdbApiKey: String? = null,
        postersPlusTextless: Boolean = false
    ): String? {
        if (provider == PosterProvider.OFF) return null
        if (stremioId.isNullOrBlank()) return null

        val cleanStremioId = stremioId.trim()
        val effectiveImdbId = imdbId?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (cleanStremioId.startsWith("tt", ignoreCase = true)) {
                cleanStremioId.substringBefore(":")
            } else null

        val effectiveTmdbId = tmdbId?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (cleanStremioId.startsWith("tmdb:", ignoreCase = true)) {
                cleanStremioId.removePrefix("tmdb:")
            } else null

        val normalizedType = normalizeType(type)
        val effectiveShape = if (preferLandscape || shape == PosterShape.LANDSCAPE) "landscape" else "poster"

        return when (provider) {
            PosterProvider.OFF -> null

            PosterProvider.POSTERS_PLUS -> {
                val pattern = if (postersPlusTextless) {
                    DEFAULT_POSTERS_PLUS_PATTERN.replace("fti=1", "fti=0&textless=true")
                } else {
                    DEFAULT_POSTERS_PLUS_PATTERN
                }
                pattern
                    .replace("{tmdb_id?}", effectiveTmdbId.orEmpty())
                    .replace("{imdb_id?}", effectiveImdbId.orEmpty())
                    .replace("{id}", cleanStremioId)
                    .replace("{stremio_id}", cleanStremioId)
                    .replace("{type}", normalizedType)
                    .replace("{shape}", effectiveShape)
                    .ifBlank { null }
            }

            PosterProvider.RPDB -> {
                val key = rpdbApiKey?.trim()
                val imdb = effectiveImdbId
                if (key.isNullOrEmpty() || imdb.isNullOrEmpty()) {
                    null
                } else {
                    val pattern = if (effectiveShape == "landscape") {
                        DEFAULT_RPDB_LANDSCAPE_PATTERN
                    } else {
                        DEFAULT_RPDB_POSTER_PATTERN
                    }
                    pattern
                        .replace("{api_key}", key)
                        .replace("{imdb_id}", imdb)
                }
            }

            PosterProvider.CUSTOM -> {
                val pattern = customPattern?.trim()
                if (pattern.isNullOrEmpty()) {
                    null
                } else {
                    pattern
                        .replace("{id}", cleanStremioId)
                        .replace("{stremio_id}", cleanStremioId)
                        .replace("{imdb_id?}", effectiveImdbId.orEmpty())
                        .replace("{imdb_id}", effectiveImdbId.orEmpty())
                        .replace("{tmdb_id?}", effectiveTmdbId.orEmpty())
                        .replace("{tmdb_id}", effectiveTmdbId.orEmpty())
                        .replace("{type}", normalizedType)
                        .replace("{shape}", effectiveShape)
                        .replace("{api_key?}", rpdbApiKey.orEmpty())
                        .ifBlank { null }
                }
            }
        }
    }

    /**
     * Normalizes a content type to either `movie` or `series`.
     *
     * @param type Raw type value.
     * @return `movie` or `series`; defaults to `movie` when unknown.
     */
    private fun normalizeType(type: String?): String {
        return when (type?.trim()?.lowercase()) {
            "series", "show", "tv", "tvshow", "tv_show" -> "series"
            else -> "movie"
        }
    }
}
