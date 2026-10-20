package com.nuvio.tv.ui.screens.home

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
fun isOngoingSeries(releaseInfo: String?, status: String? = null): Boolean {
    if (!status.isNullOrBlank()) {
        val normalizedStatus = status.trim().lowercase()
        if (normalizedStatus in ONGOING_STATUSES) {
            return true
        }
    }
    val trimmed = releaseInfo?.trim()
    if (!trimmed.isNullOrEmpty()) {
        val lastChar = trimmed.last()
        if (lastChar in TRAILING_DASHES) {
            return true
        }
    }
    return false
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
