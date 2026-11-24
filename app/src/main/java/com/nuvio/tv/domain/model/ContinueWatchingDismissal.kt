package com.nuvio.tv.domain.model

fun nextUpEpisodeDismissKey(contentId: String, season: Int?, episode: Int?): String =
    if (season != null && episode != null) "episode|${contentId.trim()}|$season|$episode"
    else contentId.trim()

fun nextUpSeriesDeleteDismissKey(contentId: String): String =
    "deleted|${contentId.trim()}"

/**
 * Checks whether a NextUp item is dismissed from Continue Watching.
 *
 * Rules:
 * 1. An explicit episode removal (e.g. "episode|id|s|e" or "id|s|e") always hides that specific episode.
 * 2. A series-delete dismissal ([nextUpSeriesDeleteDismissKey], e.g. "deleted|id") is strictly
 *    inviolable: the item stays dismissed even if a new episode airs, until the user actively
 *    watches or marks an episode again (which clears all dismissal keys for that show).
 * 3. A bare whole-show dismissal (trimmed contentId in dismissedKeys) is strictly inviolable when
 *    hasActiveProgress is false (e.g. legacy deleted show): it stays dismissed even on release alerts.
 * 4. When isReleaseAlert is true and hasActiveProgress is true, a bare show ID dismissal is ignored
 *    so newly released episodes for actively followed shows (South Park S29, Scrubs S2, Below Deck S13)
 *    wake up automatically.
 * 5. When a user actively watches an episode of any show, clearDismissalsForSeriesAndSiblings
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
    if (nextUpSeriesDeleteDismissKey(id) in dismissedKeys) return true
    if (id in dismissedKeys) {
        if (isReleaseAlert && hasActiveProgress) return false
        return true
    }
    return false
}
