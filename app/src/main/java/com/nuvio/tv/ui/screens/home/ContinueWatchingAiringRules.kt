package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.util.parseEpisodeReleaseInstant
import com.nuvio.tv.data.local.SeriesAirStatus
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.data.local.WatchedSeriesStateHolder

/**
 * Pure Continue Watching rules for currently-airing / fully-watched series.
 * Kept free of Android / ViewModel dependencies so unit tests can lock the
 * air-day regression (checkmark + new episode → series removed from CW).
 */

/**
 * What to do when a series is marked fully-watched (caught-up checkmark) but
 * next-up resolution still found a next episode.
 */
enum class FullyWatchedNextUpAction {
    /** Unaired next episode: keep on CW, leave the checkmark. */
    KEEP_WITH_BADGE,

    /**
     * Aired next episode: user is no longer caught up. Keep on CW and clear the
     * stale fully-watched badge. Pre-fix code dropped the item instead.
     */
    KEEP_AND_CLEAR_BADGE,
}

/**
 * Decision for a fully-watched series that already resolved a next-up item.
 *
 * Historical bug (`71e4fb6` + mid-season checkmarks from `a58809`):
 * `if (hasAired) drop` removed airing shows from CW the day a new episode aired.
 * Correct behavior: never drop; clear the badge when the next episode has aired.
 */
fun fullyWatchedNextUpAction(nextUpHasAired: Boolean): FullyWatchedNextUpAction {
    return if (nextUpHasAired) {
        FullyWatchedNextUpAction.KEEP_AND_CLEAR_BADGE
    } else {
        FullyWatchedNextUpAction.KEEP_WITH_BADGE
    }
}

/**
 * Whether a fully-watched next-up item should be excluded from Continue Watching.
 * Always false after the air-day fix — kept as an explicit API so tests can
 * assert the regression never returns.
 */
fun shouldDropFullyWatchedNextUpFromContinueWatching(nextUpHasAired: Boolean): Boolean {
    // Intentionally ignore [nextUpHasAired]: both aired and unaired next-ups stay.
    @Suppress("UNUSED_PARAMETER")
    val unused = nextUpHasAired
    return false
}

/**
 * Pre-fix behavior retained only for regression tests (must not be used in prod).
 */
internal fun legacyShouldDropFullyWatchedNextUp(nextUpHasAired: Boolean): Boolean = nextUpHasAired

/**
 * Inject an aired unwatched episode into the badge episode set so a later badge
 * pass does not re-apply the fully-watched checkmark from a stale aired list.
 */
fun mergeAiredNextEpisodeIntoBadgeCache(
    existingAiredEpisodes: Set<Pair<Int, Int>>?,
    nextSeason: Int?,
    nextEpisode: Int?,
): Set<Pair<Int, Int>>? {
    if (existingAiredEpisodes == null) return null
    if (nextSeason == null || nextEpisode == null) return existingAiredEpisodes
    return existingAiredEpisodes + (nextSeason to nextEpisode)
}

/**
 * After injecting the new aired episode, the series is fully watched only if
 * every aired episode (including the new one) is in the watched set.
 */
fun isFullyWatchedAgainstAiredList(
    airedEpisodes: Set<Pair<Int, Int>>,
    watchedEpisodes: Set<Pair<Int, Int>>,
): Boolean {
    if (airedEpisodes.isEmpty()) return false
    return airedEpisodes.all { it in watchedEpisodes }
}

private const val SIXTY_DAYS_MS: Long = 60L * 24 * 60 * 60 * 1000
private const val ONE_DAY_MS: Long = 24L * 60 * 60 * 1000

private val ONGOING_STATUSES: Set<String> = setOf(
    "continuing",
    "returning series",
    "in production",
    "planned"
)

private val TRAILING_DASHES: Set<Char> = setOf(
    '-',
    '\u2013', // en dash
    '\u2014'  // em dash
)

/**
 * Determines whether a series should be considered ongoing (still airing or expected to air more episodes).
 * Checks status (continuing, returning series, etc.) and releaseInfo trailing dashes (-, \u2013, \u2014).
 */
/**
 * Maps Cinemeta/TMDB metadata strings to a structured air status.
 * Replaces the scattered isOngoingSeries() boolean checks.
 */
fun resolveSeriesAirStatus(releaseInfo: String?, status: String?): SeriesAirStatus {
    val normalizedStatus = status?.trim()?.lowercase()
    return when {
        releaseInfo != null && releaseInfo.trim().lastOrNull() in TRAILING_DASHES -> SeriesAirStatus.ONGOING
        normalizedStatus in ONGOING_STATUSES -> SeriesAirStatus.ONGOING
        normalizedStatus == "ended" -> SeriesAirStatus.ENDED
        normalizedStatus in setOf("canceled", "cancelled") -> SeriesAirStatus.CANCELED
        else -> SeriesAirStatus.UNKNOWN
    }
}

/**
 * Scans metadata videos to find the earliest upcoming (unaired) episode
 * release timestamp. Returns null if no future episodes are known.
 */
internal fun resolveNextAirTimestamp(
    meta: CwMetaSummary,
    seedProgress: WatchProgress,
    nowMs: Long = System.currentTimeMillis()
): Long? {
    val seedS = seedProgress.season ?: 1
    val seedE = seedProgress.episode ?: 1
    return meta.videos
        .filter { video ->
            val s = video.season ?: return@filter false
            val e = video.episode ?: return@filter false
            s > seedS || (s == seedS && e > seedE)
        }
        .mapNotNull { video ->
            video.released?.let { parseEpisodeReleaseInstant(it)?.toEpochMilli() }
        }
        .filter { it > nowMs } // only future timestamps
        .minOrNull()
}

fun resolveNextAirTimestamp(
    meta: com.nuvio.tv.domain.model.Meta,
    seedProgress: WatchProgress,
    nowMs: Long = System.currentTimeMillis()
): Long? {
    val seedS = seedProgress.season ?: 1
    val seedE = seedProgress.episode ?: 1
    return meta.videos
        .filter { video ->
            val s = video.season ?: return@filter false
            val e = video.episode ?: return@filter false
            s > seedS || (s == seedS && e > seedE)
        }
        .mapNotNull { video ->
            video.released?.let { parseEpisodeReleaseInstant(it)?.toEpochMilli() }
        }
        .filter { it > nowMs }
        .minOrNull()
}

fun isOngoingSeries(releaseInfo: String?, status: String? = null): Boolean {
    return resolveSeriesAirStatus(releaseInfo, status) == SeriesAirStatus.ONGOING
}

/**
 * Determines whether a next-up TV episode qualifies as a release alert (new episode / new season).
 * Ensures episodes that aired recently (within 60 days) are highlighted, while preventing unreleased
 * future episodes from triggering alerts prematurely.
 */
fun computeNextUpReleaseAlert(
    hasAired: Boolean,
    releaseTimestamp: Long?,
    lastWatched: Long,
    isNewSeason: Boolean,
    nowMs: Long = System.currentTimeMillis()
): Boolean {
    if (!hasAired || releaseTimestamp == null) return false
    val age = nowMs - releaseTimestamp
    if (age !in 0L..SIXTY_DAYS_MS) return false
    return releaseTimestamp > lastWatched ||
        isNewSeason ||
        lastWatched < releaseTimestamp + ONE_DAY_MS
}

/** Matches an IMDb id: 'tt' followed by one or more digits. */
private val IMDB_ID_REGEX = Regex("tt\\d+")

/**
 * Extracts an IMDb id ('tt' followed by digits) from a video id or compound string.
 * e.g. "tt28251824:4:4" -> "tt28251824", "tt28251824" -> "tt28251824".
 */
fun extractImdbIdFromVideoId(videoId: String?): String? {
    if (videoId.isNullOrBlank()) return null
    return IMDB_ID_REGEX.find(videoId)?.value
}

/**
 * Resolves a canonical cluster key for a contentId using a siblings mapping (e.g. from Trakt or multi-source).
 * Resolves IMDB <-> TMDB siblings for the same show into a single canonical identifier.
 * Deterministic preference:
 * 1. The first IMDb ID (starting with "tt") in the cluster
 * 2. The first TMDB ID (starting with "tmdb:")
 * 3. Alphabetically first ID
 */
fun resolveCanonicalClusterKey(
    contentId: String,
    siblingsMap: Map<String, Set<String>>
): String {
    val siblings = siblingsMap[contentId]
    if (siblings.isNullOrEmpty() || "__ambiguous__" in siblings) {
        return contentId
    }
    val cluster = siblings + contentId
    return cluster.filter { it.startsWith("tt") }.minOrNull()
        ?: cluster.filter { it.startsWith("tmdb:") }.minOrNull()
        ?: cluster.minOrNull()
        ?: contentId
}

/**
 * Finds the next episode candidate that comes after a seed episode.
 *
 * If the seed (season, episode) is present in [episodes] at index i, returns episodes[i + 1].
 * If the seed is NOT present (e.g. metadata provider returned partial upcoming episodes like
 * S4E5..S4E10 while the seed was S4E4), finds the first episode strictly greater than the seed.
 */
fun findNextEpisodeCandidateAfterSeed(
    episodes: List<Pair<Int, Int>>,
    seedSeason: Int,
    seedEpisode: Int
): Pair<Int, Int>? {
    val seedIndex = episodes.indexOfFirst { it.first == seedSeason && it.second == seedEpisode }
    if (seedIndex >= 0) {
        return episodes.getOrNull(seedIndex + 1)
    }
    return episodes.firstOrNull { (season, episode) ->
        season > seedSeason || (season == seedSeason && episode > seedEpisode)
    }
}

/** Content types that are treated as series for Continue Watching rules. */
fun isSeriesTypeCW(contentType: String?): Boolean {
    val t = contentType?.trim()?.lowercase() ?: return false
    return t == "series" || t == "tv"
}

/**
 * Lightweight reference for deriving sibling clusters across watch-progress seeds.
 */
data class WatchProgressSeedRef(
    val contentId: String,
    val name: String? = null,
    val videoId: String? = null,
    val contentType: String? = null
)

/**
 * Derives and augments sibling mappings across content IDs from watch-progress seeds.
 *
 * 1. Groups seeds by normalized name (lowercased, trimmed) for series items, linking
 *    distinct contentIds (e.g. tmdb:234613 and tt28251824 both for "The Traitors Canada").
 * 2. Links contentId to any IMDb ID extracted from videoId (e.g. videoId "tt28251824:4:4").
 */
fun buildShowIdSiblingsFromSeeds(
    seeds: List<WatchProgressSeedRef>,
    existingSiblings: Map<String, Set<String>> = emptyMap()
): Map<String, Set<String>> {
    val siblings = mutableMapOf<String, MutableSet<String>>()
    for ((id, related) in existingSiblings) {
        siblings.getOrPut(id) { mutableSetOf() }.addAll(related)
    }

    fun link(a: String, b: String) {
        if (a.isBlank() || b.isBlank() || a == b) return
        siblings.getOrPut(a) { mutableSetOf() }.add(b)
        siblings.getOrPut(b) { mutableSetOf() }.add(a)
    }

    val groupsByName = seeds
        .filter { !it.name.isNullOrBlank() && isSeriesTypeCW(it.contentType) }
        .groupBy { it.name!!.trim().lowercase() }

    for ((_, group) in groupsByName) {
        val distinctIds = group.map { it.contentId }.filter { it.isNotBlank() }.distinct()
        for (i in distinctIds.indices) {
            for (j in i + 1 until distinctIds.size) {
                link(distinctIds[i], distinctIds[j])
            }
        }
    }

    for (seed in seeds) {
        val imdb = extractImdbIdFromVideoId(seed.videoId)
        if (imdb != null && imdb != seed.contentId) {
            link(seed.contentId, imdb)
        }
    }

    return siblings.mapValues { (_, set) -> set.toSet() }
}


/**
 * Central predicate for whether an older (pre-cutoff) next-up item should be
 * retained in Continue Watching. ALL filter sites in the pipeline MUST call
 * this — no inline cutoff checks.
 *
 * An item passes if ANY of:
 * 1. No cutoff is active (unlimited history mode)
 * 2. Item was watched recently enough (within cutoff window)
 * 3. Item has an active release alert badge
 * 4. Item's series is known-ongoing with a stale/expired validation deadline
 */
internal fun shouldRetainBeyondCutoff(
    sortTimestamp: Long,
    isReleaseAlert: Boolean,
    contentId: String,
    cutoffMs: Long?,
    stateHolder: WatchedSeriesStateHolder
): Boolean {
    if (cutoffMs == null) return true
    if (sortTimestamp >= cutoffMs) return true
    if (isReleaseAlert) return true
    // Known-ongoing series are always retained — the deadline system
    // handles when to actually revalidate them.
    if (stateHolder.isKnownOngoing(contentId)) return true
    return false
}

internal fun shouldRetainBeyondCutoff(
    item: ContinueWatchingItem.NextUp,
    cutoffMs: Long?,
    stateHolder: WatchedSeriesStateHolder
): Boolean = shouldRetainBeyondCutoff(
    sortTimestamp = item.info.sortTimestamp,
    isReleaseAlert = item.info.isReleaseAlert,
    contentId = item.info.contentId,
    cutoffMs = cutoffMs,
    stateHolder = stateHolder
)

/**
 * Computes a boosted sort timestamp for the Continue Watching row.
 *
 * This resolves the cold-start "Catch-22" for caught-up ongoing shows: a show
 * whose watched episodes are all in the past would normally sort by its last
 * watched timestamp and sink below stale content. However, when a new episode
 * has already aired (nextAir <= now) but has not yet been watched, we want the
 * show to surface. By returning the next-air timestamp (which is strictly
 * greater than [lastWatched]), the caller can use it as the effective sort key
 * and float the show up without requiring a fresh watch event.
 *
 * @param contentId stable content identifier used as the primary state lookup key.
 * @param videoId optional video identifier; used to derive an IMDb id fallback.
 * @param lastWatched epoch-ms timestamp of the last watched episode.
 * @param stateHolder source of next-air and ongoing-status metadata.
 * @param siblings sibling content ids; the first non-ambiguous IMDb id is preferred.
 * @param nowMs current epoch-ms time, injectable for deterministic testing.
 * @return the next-air epoch-ms timestamp to use as a boosted sort key, or
 *         `null` when no boost applies.
 */
internal fun computePersistedNextAirBoost(
    contentId: String,
    videoId: String?,
    lastWatched: Long,
    stateHolder: WatchedSeriesStateHolder,
    siblings: Collection<String> = emptyList(),
    nowMs: Long = System.currentTimeMillis()
): Long? {
    // Prefer an explicit IMDb sibling id; fall back to deriving one from the video id.
    val imdbSibling: String? = siblings
        .firstOrNull { it.startsWith("tt") && it != "__ambiguous__" }
        ?: extractImdbIdFromVideoId(videoId)

    // Resolve the next-air timestamp, trying the content id first, then the IMDb id.
    val nextAir: Long? = stateHolder.getNextAirTimestamp(contentId)
        ?: imdbSibling?.let { stateHolder.getNextAirTimestamp(it) }

    // A show is "ongoing" if either identifier is known to be ongoing.
    val isOngoing: Boolean =
        stateHolder.isKnownOngoing(contentId) ||
            (imdbSibling != null && stateHolder.isKnownOngoing(imdbSibling))

    // Boost only when a new episode has aired since the last watch and the show is ongoing.
    return if (nextAir != null && nextAir <= nowMs && isOngoing && nextAir > lastWatched) {
        nextAir
    } else {
        null
    }
}

