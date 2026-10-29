package com.nuvio.tv.domain.model

fun nextUpEpisodeDismissKey(contentId: String, season: Int?, episode: Int?): String =
    if (season != null && episode != null) "episode|${contentId.trim()}|$season|$episode"
    else contentId.trim()

/**
 * Checks whether a NextUp item is dismissed from Continue Watching.
 *
 * Rules:
 * 1. An explicit episode removal (e.g. "episode|id|s|e" or "id|s|e") always hides that specific episode.
 * 2. A newly released episode (isReleaseAlert == true) is NEVER hidden by historical removals or
 *    whole-show dismissals — new episodes (e.g. South Park S29E4, Scrubs S2E6) always appear on CW.
 * 3. For non-release-alerts (isReleaseAlert == false), historical whole-show dismissals keep previously
 *    dismissed/completed shows (Futurama, The Middle, Below Deck, White Lotus, etc.) off CW so
 *    they do not reappear unprompted.
 * 4. When a user actively watches an episode of any show, clearDismissalsForSeriesAndSiblings
 *    purges all dismissal keys for that show across all sibling IDs, allowing it to stick back to CW.
 */
fun isContinueWatchingNextUpDismissed(
    contentId: String,
    season: Int?,
    episode: Int?,
    isReleaseAlert: Boolean,
    dismissedKeys: Set<String>
): Boolean {
    val id = contentId.trim()
    if (season != null && episode != null) {
        if (nextUpEpisodeDismissKey(id, season, episode) in dismissedKeys) return true
        if ("$id|$season|$episode" in dismissedKeys) return true
    }
    if (isReleaseAlert) return false
    return id in dismissedKeys
}
