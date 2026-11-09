package com.nuvio.tv.domain.model

fun nextUpEpisodeDismissKey(contentId: String, season: Int?, episode: Int?): String =
    if (season != null && episode != null) "episode|${contentId.trim()}|$season|$episode"
    else contentId.trim()

/**
 * Checks whether a NextUp item is dismissed from Continue Watching.
 *
 * Rules:
 * 1. An explicit episode removal (e.g. "episode|id|s|e" or "id|s|e") always hides that specific episode.
 * 2. A whole-show dismissal (bare trimmed contentId in dismissedKeys) is strictly inviolable when
 *    hasActiveProgress is false (e.g. user explicitly deleted progress for The Simpsons): the item
 *    is always considered dismissed, even if a new episode airs.
 * 3. When isReleaseAlert is true and hasActiveProgress is true, a bare show ID dismissal is ignored
 *    so newly released episodes for actively followed shows (South Park S29, Scrubs S2) wake up.
 * 4. When a user actively watches an episode of any show, clearDismissalsForSeriesAndSiblings
 *    purges all dismissal keys for that show across all sibling IDs, allowing it to stick back to CW.
 */
fun isContinueWatchingNextUpDismissed(
    contentId: String,
    season: Int?,
    episode: Int?,
    isReleaseAlert: Boolean = false,
    dismissedKeys: Set<String>,
    hasActiveProgress: Boolean = true
): Boolean {
    val id = contentId.trim()
    if (season != null && episode != null) {
        if (nextUpEpisodeDismissKey(id, season, episode) in dismissedKeys) return true
        if ("$id|$season|$episode" in dismissedKeys) return true
    }
    if (id in dismissedKeys) {
        if (isReleaseAlert && hasActiveProgress) return false
        return true
    }
    return false
}
