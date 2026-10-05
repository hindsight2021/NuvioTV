package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Represents a single metadata source for an item (e.g. Cinemeta, MyTrakt, Better Posters, TMDB, or All Merged).
 */
@Immutable
data class MetaSource(
    val id: String,
    val displayName: String,
    val seasonCount: Int = 0,
    val episodeCount: Int = 0,
    val isMerged: Boolean = false,
    val isPrimary: Boolean = false
)

/**
 * Utility object responsible for merging multiple [Meta] instances for a series,
 * taking the superset of episodes, deduplicating by (season, episode), filling in
 * missing metadata, and detecting stub/tracker responses.
 */
object MetaMerger {

    /**
     * Determines whether [meta] is a "stub" — e.g. a progress-tracker response from an addon
     * like MyTrakt that only returns 1 or 2 upcoming episodes instead of the full series catalog.
     */
    fun isStub(meta: Meta): Boolean {
        val isSeries = meta.type == ContentType.SERIES ||
            meta.apiType.equals("series", ignoreCase = true) ||
            meta.apiType.equals("tv", ignoreCase = true)

        if (!isSeries) return false

        val videos = meta.videos
        if (videos.isEmpty()) return false

        // Exactly 1 episode for a TV show is almost always an "Up Next" / tracker stub
        if (videos.size == 1) return true

        // Up to 2 episodes where either episode is not season 1 episode 1/2
        if (videos.size <= 2) {
            return videos.any { (it.season ?: 0) > 1 || (it.episode ?: 0) > 2 }
        }

        return false
    }

    /**
     * Merges [secondary] into [primary].
     *
     * Videos are deduplicated by (season, episode).
     * If both have the same episode, primary is kept, but missing thumbnail or overview
     * is filled from secondary.
     * If secondary has episodes/seasons that primary is missing (e.g. S10E01), they are added.
     * The resulting list is sorted by season then episode.
     */
    fun merge(primary: Meta, secondary: Meta): Meta {
        val primaryByKey: MutableMap<Pair<Int, Int>, Video> = LinkedHashMap()
        for (video in primary.videos) {
            val s = video.season ?: 0
            val e = video.episode ?: 0
            primaryByKey[s to e] = video
        }

        for (secondaryVideo in secondary.videos) {
            val s = secondaryVideo.season ?: 0
            val e = secondaryVideo.episode ?: 0
            val key = s to e
            val existing = primaryByKey[key]
            if (existing == null) {
                // New episode from secondary (e.g. newly aired season!)
                primaryByKey[key] = secondaryVideo
            } else {
                // Enrich existing episode if primary lacks thumbnail, overview, or rating
                val mergedThumbnail = existing.thumbnail?.takeIf { it.isNotBlank() }
                    ?: secondaryVideo.thumbnail
                val mergedOverview = existing.overview?.takeIf { it.isNotBlank() }
                    ?: secondaryVideo.overview
                val mergedRating = existing.rating ?: secondaryVideo.rating
                val mergedReleased = existing.released?.takeIf { it.isNotBlank() }
                    ?: secondaryVideo.released

                if (mergedThumbnail != existing.thumbnail ||
                    mergedOverview != existing.overview ||
                    mergedRating != existing.rating ||
                    mergedReleased != existing.released
                ) {
                    primaryByKey[key] = existing.copy(
                        thumbnail = mergedThumbnail,
                        overview = mergedOverview,
                        rating = mergedRating,
                        released = mergedReleased
                    )
                }
            }
        }

        val mergedVideos = primaryByKey.values.sortedWith(
            compareBy<Video>({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE })
        )

        // Enrich missing top-level fields from secondary
        val mergedPoster = primary.poster?.takeIf { it.isNotBlank() } ?: secondary.poster
        val mergedBackground = primary.background?.takeIf { it.isNotBlank() } ?: secondary.background
        val mergedLogo = primary.logo?.takeIf { it.isNotBlank() } ?: secondary.logo
        val mergedDescription = primary.description?.takeIf { it.isNotBlank() } ?: secondary.description
        val mergedReleaseInfo = primary.releaseInfo?.takeIf { it.isNotBlank() } ?: secondary.releaseInfo
        val mergedImdbRating = primary.imdbRating ?: secondary.imdbRating
        val mergedCastMembers = primary.castMembers.ifEmpty { secondary.castMembers }
        val mergedCast = primary.cast.ifEmpty { secondary.cast }
        val mergedTrailers = if (primary.trailers.isEmpty()) secondary.trailers else primary.trailers
        val mergedNetworks = primary.networks.ifEmpty { secondary.networks }
        val mergedProductionCompanies = primary.productionCompanies.ifEmpty { secondary.productionCompanies }

        return primary.copy(
            videos = mergedVideos,
            poster = mergedPoster,
            background = mergedBackground,
            logo = mergedLogo,
            description = mergedDescription,
            releaseInfo = mergedReleaseInfo,
            imdbRating = mergedImdbRating,
            castMembers = mergedCastMembers,
            cast = mergedCast,
            trailers = mergedTrailers,
            networks = mergedNetworks,
            productionCompanies = mergedProductionCompanies
        )
    }

    /**
     * Merges multiple metadata instances into a unified [Meta].
     * Prefers a non-stub metadata with the largest episode count as the base,
     * and sequentially merges all other sources into it.
     */
    fun mergeAll(primary: Meta, others: List<Meta>): Meta {
        if (others.isEmpty()) return primary

        val all = listOf(primary) + others
        // Select base: prefer non-stub, then largest episode count
        val base = all.sortedWith(
            compareByDescending<Meta> { !isStub(it) }
                .thenByDescending { it.videos.size }
        ).first()

        var current = base
        for (other in all) {
            if (other === base) continue
            current = merge(current, other)
        }
        return current
    }
}
