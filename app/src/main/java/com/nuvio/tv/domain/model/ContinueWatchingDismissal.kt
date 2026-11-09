package com.nuvio.tv.domain.model

fun nextUpEpisodeDismissKey(contentId: String, season: Int?, episode: Int?): String =
    if (season != null && episode != null) "episode|${contentId.trim()}|$season|$episode"
    else contentId.trim()

/**
 * Checks whether a NextUp item is dismissed from Continue Watching.
 *
 * Rules:
 * 1. A whole-show dismissal is strictly inviolable: if the trimmed contentId is present in
 *    dismissedKeys, the item is always considered dismissed — even when isReleaseAlert is true.
 * 2. An explicit episode removal (e.g. "episode|id|s|e" or "id|s|e") always hides that specific episode.
 * 3. When a user actively watches an episode of any show, clearDismissalsForSeriesAndSiblings
 *    purges all dismissal keys for that show across all sibling IDs, allowing it to stick back to CW.
 */
fun isContinueWatchingNextUpDismissed(
    contentId: String,
    season: Int?,
    episode: Int?,
    isReleaseAlert: Boolean = false,
    dismissedKeys: Set<String>
): Boolean {
    val id = contentId.trim()
    if (id in dismissedKeys) return true
    if (season != null && episode != null) {
        if (nextUpEpisodeDismissKey(id, season, episode) in dismissedKeys) return true
        if ("$id|$season|$episode" in dismissedKeys) return true
    }
    return false
}
