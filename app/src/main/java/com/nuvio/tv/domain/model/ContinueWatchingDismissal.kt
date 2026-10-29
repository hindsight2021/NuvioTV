package com.nuvio.tv.domain.model

fun nextUpEpisodeDismissKey(contentId: String, season: Int?, episode: Int?): String =
    if (season != null && episode != null) "episode|${contentId.trim()}|$season|$episode"
    else contentId.trim()

/** Remove hides an episode; historical show-wide removals cannot veto a new release. */
fun isContinueWatchingNextUpDismissed(
    contentId: String,
    season: Int?,
    episode: Int?,
    isReleaseAlert: Boolean,
    dismissedKeys: Set<String>
): Boolean {
    val id = contentId.trim()
    if (season != null && episode != null && nextUpEpisodeDismissKey(id, season, episode) in dismissedKeys) return true
    if (isReleaseAlert) return false
    return dismissedKeys.any { it == id || it.startsWith("$id|") }
}
