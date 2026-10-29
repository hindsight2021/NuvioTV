package com.nuvio.tv.domain.model

fun nextUpEpisodeDismissKey(contentId: String, season: Int?, episode: Int?): String =
    if (season != null && episode != null) "episode|${contentId.trim()}|$season|$episode"
    else contentId.trim()

/** Filters out bare show IDs or malformed keys that lack episode coordinates. */
fun sanitizeDismissedNextUpKeys(dismissedKeys: Set<String>): Set<String> =
    dismissedKeys.filter { it.contains('|') }.toSet()

/**
 * Remove hides a specific episode; historical whole-show dismissals or other episode
 * dismissals cannot veto a different episode or future release.
 */
fun isContinueWatchingNextUpDismissed(
    contentId: String,
    season: Int?,
    episode: Int?,
    isReleaseAlert: Boolean,
    dismissedKeys: Set<String>
): Boolean {
    if (season == null || episode == null) return false
    val id = contentId.trim()
    val modernKey = nextUpEpisodeDismissKey(id, season, episode)
    if (modernKey in dismissedKeys) return true
    val legacyKey = "$id|$season|$episode"
    return legacyKey in dismissedKeys
}

