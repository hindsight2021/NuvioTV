package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.isContinueWatchingNextUpDismissed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

private val GENERIC_EPISODE_REGEX = Regex("""(?i)^(episode|ep\.?)\s*\d+\.?$""")
private val GENERIC_SXXEYY_REGEX = Regex("""(?i)^s\d+e\d+$""")

/**
 * Returns true when [title] is a generic episode placeholder such as "Episode 7", "Episode 07",
 * "Ep. 7", "S4E7", or is null/blank.
 */
internal fun isGenericEpisodeTitle(title: String?): Boolean {
    if (title.isNullOrBlank()) return true
    val trimmed = title.trim()
    if (GENERIC_EPISODE_REGEX.matches(trimmed)) return true
    if (GENERIC_SXXEYY_REGEX.matches(trimmed)) return true
    return false
}

private fun extractItemImdbId(item: ContinueWatchingItem): String? {
    extractImdbIdFromVideoId(item.videoId())?.let { return it }
    val contentId = item.contentId()
    return if (contentId.startsWith("tt")) contentId else null
}

/**
 * Determines whether [a] and [b] refer to the same show/movie according to deduplication rules.
 */
internal fun areContinueWatchingDuplicates(
    a: ContinueWatchingItem,
    b: ContinueWatchingItem,
    siblingsMap: Map<String, Set<String>> = emptyMap()
): Boolean {
    val aId = a.contentId()
    val bId = b.contentId()

    // Rule 1: exact contentId match
    if (aId == bId) return true

    // Rule 2: sibling map match
    val aSiblings = siblingsMap[aId]
    if (aSiblings != null && bId in aSiblings) return true
    val bSiblings = siblingsMap[bId]
    if (bSiblings != null && aId in bSiblings) return true

    val aCanonical = resolveCanonicalClusterKey(aId, siblingsMap)
    val bCanonical = resolveCanonicalClusterKey(bId, siblingsMap)
    if (aCanonical != aId && aCanonical == bCanonical) return true

    // Rule 3: IMDb ID match
    val aImdb = extractItemImdbId(a)
    val bImdb = extractItemImdbId(b)
    if (aImdb != null && bImdb != null && aImdb == bImdb) return true

    // Rule 4: normalized series title match
    val aIsSeries = a.isSeries() || a is ContinueWatchingItem.NextUp || a.season() != null || a.episode() != null
    val bIsSeries = b.isSeries() || b is ContinueWatchingItem.NextUp || b.season() != null || b.episode() != null
    if (aIsSeries && bIsSeries) {
        val aImdb = extractItemImdbId(a)
        val bImdb = extractItemImdbId(b)
        if (aImdb != null && bImdb != null && aImdb != bImdb) {
            return false
        }
        val aTitle = normalizeShowTitleForGrouping(a.title())
        val bTitle = normalizeShowTitleForGrouping(b.title())
        if (aTitle.length >= 2 && aTitle == bTitle) return true
    }

    return false
}

/**
 * Selects the preferred representative between two duplicates of the same show/movie.
 */
internal fun selectPreferredContinueWatchingDuplicate(
    a: ContinueWatchingItem,
    b: ContinueWatchingItem
): ContinueWatchingItem {
    val aInProgress = a is ContinueWatchingItem.InProgress
    val bInProgress = b is ContinueWatchingItem.InProgress

    // InProgress beats NextUp
    if (aInProgress != bInProgress) {
        return if (aInProgress) a else b
    }

    if (a is ContinueWatchingItem.InProgress && b is ContinueWatchingItem.InProgress) {
        return if (b.progress.lastWatched > a.progress.lastWatched) b else a
    }

    // Both are NextUp
    val nextA = (a as ContinueWatchingItem.NextUp).info
    val nextB = (b as ContinueWatchingItem.NextUp).info

    // 1. Release alert beats non-release alert
    if (nextA.isReleaseAlert != nextB.isReleaseAlert) {
        return if (nextA.isReleaseAlert) a else b
    }

    // 2. Higher season/episode
    val aSeason = nextA.season
    val bSeason = nextB.season
    if (aSeason != bSeason) return if (bSeason > aSeason) b else a

    val aEpisode = nextA.episode
    val bEpisode = nextB.episode
    if (aEpisode != bEpisode) return if (bEpisode > aEpisode) b else a

    // 3. Non-generic episode title beats generic placeholder ("Creepy Little Secret" vs "Episode 7")
    val aGeneric = isGenericEpisodeTitle(nextA.episodeTitle)
    val bGeneric = isGenericEpisodeTitle(nextB.episodeTitle)
    if (aGeneric != bGeneric) return if (aGeneric) b else a

    // 4. Higher sortTimestamp / releaseTimestamp / lastWatched
    val aSort = nextA.sortTimestamp.takeIf { it > 0 } ?: nextA.lastWatched
    val bSort = nextB.sortTimestamp.takeIf { it > 0 } ?: nextB.lastWatched
    if (aSort != bSort) return if (bSort > aSort) b else a

    // 5. Prefer IMDb ID contentId
    val aIsImdb = nextA.contentId.startsWith("tt")
    val bIsImdb = nextB.contentId.startsWith("tt")
    if (aIsImdb != bIsImdb) return if (aIsImdb) a else b

    return a
}

/**
 * Deduplicates Continue Watching items so that each logical show/movie appears at most once.
 */
fun deduplicateContinueWatchingItems(
    items: List<ContinueWatchingItem>,
    siblingsMap: Map<String, Set<String>> = emptyMap()
): List<ContinueWatchingItem> {
    if (items.size <= 1) return items

    val result = ArrayList<ContinueWatchingItem>(items.size)

    for (item in items) {
        val duplicateIndex = result.indexOfFirst { existing ->
            areContinueWatchingDuplicates(existing, item, siblingsMap)
        }

        if (duplicateIndex == -1) {
            result.add(item)
        } else {
            val existing = result[duplicateIndex]
            result[duplicateIndex] = selectPreferredContinueWatchingDuplicate(existing, item)
        }
    }

    return result
}

internal fun reconcileConclusiveNextUpItems(
    currentItems: List<ContinueWatchingItem>,
    resolvedItems: List<ContinueWatchingItem.NextUp>,
    conclusivelyProcessedContentIds: Set<String>,
    dismissedNextUpKeys: Set<String>,
    siblingsMap: Map<String, Set<String>> = emptyMap()
): List<ContinueWatchingItem> {
    val resolvedByContentId = resolvedItems
        .distinctBy { item -> item.info.contentId }
        .associateBy { item -> item.info.contentId }
    val inProgressContentIds = currentItems.mapNotNullTo(mutableSetOf()) { item ->
        (item as? ContinueWatchingItem.InProgress)?.progress?.contentId
    }
    val retainedItems = currentItems.filterNot { item ->
        item is ContinueWatchingItem.NextUp &&
            item.info.contentId in conclusivelyProcessedContentIds
    }
    val acceptedResolvedItems = resolvedByContentId.values.filter { item ->
        item.info.contentId !in inProgressContentIds &&
            item.info.contentId in conclusivelyProcessedContentIds &&
            !isContinueWatchingNextUpDismissed(
                item.info.contentId,
                item.info.season,
                item.info.episode,
                item.info.isReleaseAlert,
                dismissedNextUpKeys
            )
    }
    val combined = retainedItems + acceptedResolvedItems
    return deduplicateContinueWatchingItems(combined, siblingsMap)
}

internal fun mergeConcurrentContinueWatchingEnrichment(
    currentItems: List<ContinueWatchingItem>,
    originalItems: List<ContinueWatchingItem>,
    enrichedItems: List<ContinueWatchingItem>
): List<ContinueWatchingItem> {
    val replacements = originalItems.zip(enrichedItems).associate { (original, enriched) ->
        original.reconciliationKey() to enriched
    }
    return currentItems.map { current ->
        replacements[current.reconciliationKey()] ?: current
    }
}

internal suspend fun HomeViewModel.applyConclusiveOlderNextUpResults(
    resolvedItems: List<ContinueWatchingItem.NextUp>,
    conclusivelyProcessedContentIds: Set<String>,
    dismissedNextUpKeys: Set<String>,
    sortMode: ContinueWatchingSortMode,
    pipelineProfileId: Int,
    persistSnapshot: Boolean
) {
    if (profileManager.activeProfileId.value != pipelineProfileId) return
    if (resolvedItems.isEmpty() && conclusivelyProcessedContentIds.isEmpty()) return

    val acceptedResolvedItems = resolvedItems
        .distinctBy { item -> item.info.contentId }
        .filter { item ->
            item.info.contentId in conclusivelyProcessedContentIds &&
                !isContinueWatchingNextUpDismissed(
                    item.info.contentId,
                    item.info.season,
                    item.info.episode,
                    item.info.isReleaseAlert,
                    dismissedNextUpKeys
                )
        }
    val reconciledContentIds = conclusivelyProcessedContentIds

    synchronized(discoveredOlderNextUpItems) {
        discoveredOlderNextUpItems.removeAll { item ->
            item.info.contentId in reconciledContentIds
        }
        discoveredOlderNextUpItems.addAll(acceptedResolvedItems)
    }
    reconciledContentIds.forEach(cwEnrichedNextUpOverlay::remove)

    _uiState.update { state ->
        val reconciled = reconcileConclusiveNextUpItems(
            currentItems = state.continueWatchingItems + state.upcomingItems,
            resolvedItems = acceptedResolvedItems,
            conclusivelyProcessedContentIds = conclusivelyProcessedContentIds,
            dismissedNextUpKeys = dismissedNextUpKeys,
            siblingsMap = cwLastShowIdSiblings
        )
        val sorted = sortContinueWatchingItems(reconciled, sortMode)
        val (main, upcoming) = splitUpcomingItems(sorted, sortMode, separateNextUpRow, cwLastShowIdSiblings)
        if (state.continueWatchingItems == main && state.upcomingItems == upcoming) {
            state
        } else {
            state.copy(continueWatchingItems = main, upcomingItems = upcoming)
        }
    }

    if (!persistSnapshot) return
    withContext(Dispatchers.IO) {
        if (profileManager.activeProfileId.value != pipelineProfileId) return@withContext
        val snapshot = (_uiState.value.continueWatchingItems + _uiState.value.upcomingItems)
            .toCachedNextUpSnapshot(com.nuvio.tv.ui.components.brokenImageUrls)
        runCatching {
            cwEnrichmentCache.saveNextUpSnapshot(snapshot, force = true)
        }
    }
}

internal fun List<ContinueWatchingItem>.toCachedNextUpSnapshot(
    brokenImageUrls: Set<String>
): List<CachedNextUpItem> = mapNotNull { item ->
    val info = (item as? ContinueWatchingItem.NextUp)?.info ?: return@mapNotNull null
    CachedNextUpItem(
        contentId = info.contentId,
        contentType = info.contentType,
        name = info.name,
        poster = info.poster,
        backdrop = info.backdrop,
        logo = info.logo,
        videoId = info.videoId,
        season = info.season,
        episode = info.episode,
        episodeTitle = info.episodeTitle,
        episodeDescription = info.episodeDescription,
        thumbnail = info.thumbnail?.takeIf { imageUrl -> imageUrl !in brokenImageUrls },
        released = info.released,
        hasAired = info.hasAired,
        airDateLabel = info.airDateLabel,
        lastWatched = info.lastWatched,
        imdbRating = info.imdbRating,
        genres = info.genres,
        releaseInfo = info.releaseInfo,
        sortTimestamp = info.sortTimestamp,
        releaseTimestamp = info.releaseTimestamp,
        isReleaseAlert = info.isReleaseAlert,
        isNewSeasonRelease = info.isNewSeasonRelease,
        seedSeason = info.seedSeason,
        seedEpisode = info.seedEpisode,
        contentLanguage = info.contentLanguage,
        watchedEpisodeCount = info.watchedEpisodeCount,
        isInLibrary = info.isInLibrary
    )
}

private fun ContinueWatchingItem.reconciliationKey(): String = when (this) {
    is ContinueWatchingItem.InProgress -> listOf(
        "progress",
        progress.contentId,
        progress.season,
        progress.episode,
        progress.videoId
    ).joinToString("|")

    is ContinueWatchingItem.NextUp -> listOf(
        "next",
        info.contentId,
        info.season,
        info.episode,
        info.seedSeason,
        info.seedEpisode
    ).joinToString("|")
}
