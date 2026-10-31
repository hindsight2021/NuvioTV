package com.nuvio.tv.ui.components.posteroptions

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalImpact
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalConfirmation
import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.tv.core.tracking.supportsMembershipFor
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.localizedTitle
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.ui.components.NuvioDialog
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.nuvio.tv.ui.components.watchlist.TraktSimklWatchlistDialog
import com.nuvio.tv.ui.components.playlist.QueueSubmenuDialog
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.MetaPreview


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterOptionsDialog(
    title: String,
    isInLibrary: Boolean,
    isLibraryPending: Boolean,
    showManageLists: Boolean,
    isMovie: Boolean,
    isSeries: Boolean = false,
    isWatched: Boolean,
    isWatchedPending: Boolean,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onWatchlistClick: (() -> Unit)? = null,
    onQueueClick: (() -> Unit)? = null,
    onPlayNextClick: (() -> Unit)? = null,
    onAddToQueueClick: (() -> Unit)? = null,
    onToggleLibrary: () -> Unit,
    onToggleWatched: () -> Unit,
    onPlayRandomEpisode: (() -> Unit)? = null,
    onStartChannelShuffle: (() -> Unit)? = null,
    onStartChannelOrder: (() -> Unit)? = null,
    onChicReview: (() -> Unit)? = null,
    onStartThematicChannel: (() -> Unit)? = null
) {
    val primaryFocusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.home_poster_dialog_subtitle),
        width = 440.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = onDetails,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 38.dp)
                    .focusRequester(primaryFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(
                    text = stringResource(R.string.cw_action_go_to_details),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (onWatchlistClick != null) {
                Button(
                    onClick = onWatchlistClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 38.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(
                        text = "Watchlist (Trakt / Simkl)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (onPlayNextClick != null) {
                Button(
                    onClick = onPlayNextClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 38.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(
                        text = "Play Next",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (onAddToQueueClick != null) {
                Button(
                    onClick = onAddToQueueClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 38.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(
                        text = "Add to Queue",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (onQueueClick != null) {
                Button(
                    onClick = onQueueClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 38.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(
                        text = "Queue...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Button(
                onClick = onToggleLibrary,
                enabled = !isLibraryPending,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 38.dp),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(
                    text = if (showManageLists) {
                        stringResource(R.string.library_manage_lists)
                    } else {
                        if (isInLibrary) {
                            stringResource(R.string.hero_remove_from_library)
                        } else {
                            stringResource(R.string.hero_add_to_library)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (isMovie || isSeries) {
                Button(
                    onClick = onToggleWatched,
                    enabled = !isWatchedPending,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 38.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(
                        text = if (isWatched) {
                            stringResource(R.string.hero_mark_unwatched)
                        } else {
                            stringResource(R.string.hero_mark_watched)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (isSeries && onPlayRandomEpisode != null) {
                Button(
                    onClick = onPlayRandomEpisode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 38.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(
                        text = "🎲 Play Random Episode",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterListPickerDialog(
    title: String,
    tabs: List<LibraryListTab>,
    membership: Map<String, Boolean>,
    isPending: Boolean,
    error: String?,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.detail_lists_subtitle),
        width = 500.dp
    ) {
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFB6B6)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(tabs, key = { it.key }) { tab ->
                val selected = membership[tab.key] == true
                val titleText = if (selected) "✓ ${tab.localizedTitle()}" else tab.localizedTitle()
                Button(
                    onClick = { onToggle(tab.key) },
                    enabled = !isPending,
                    modifier = if (tab.key == tabs.firstOrNull()?.key) {
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(primaryFocusRequester)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(
                        text = titleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Divider(color = NuvioTheme.colors.Border, thickness = NuvioTheme.spacing.hairline)

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Button(
                onClick = onSave,
                enabled = !isPending,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(if (isPending) stringResource(R.string.action_saving) else stringResource(R.string.action_save))
            }
        }
    }
}

@Composable
fun PosterOptionsHost(
    state: PosterOptionsState,
    controller: PosterOptionsController,
    onNavigateToDetail: (id: String, type: String, addonBaseUrl: String) -> Unit,
    onPlaySeriesEpisode: ((id: String, type: String, addonBaseUrl: String, season: Int, episode: Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val watchlistTarget = controller.watchlistDialogTarget
    if (watchlistTarget != null) {
        TraktSimklWatchlistDialog(
            title = watchlistTarget.title,
            isTraktConnected = controller.isTraktConnected,
            isSimklConnected = controller.isSimklConnected,
            isInTrakt = controller.isInTraktWatchlist,
            isInSimkl = controller.isInSimklWatchlist,
            isPending = controller.isWatchlistPending,
            onToggleTrakt = { controller.toggleTraktWatchlist() },
            onToggleSimkl = { controller.toggleSimklWatchlist() },
            onAddBoth = { controller.addBothWatchlists() },
            onRemoveBoth = { controller.removeBothWatchlists() },
            onDismiss = { controller.dismissWatchlistDialog() }
        )
    }

    var posterQueueTarget by remember { mutableStateOf<MetaPreview?>(null) }
    val pQueue = posterQueueTarget
    if (pQueue != null) {
        val isMovie = pQueue.apiType.equals("movie", ignoreCase = true)
        val playlistItem = com.nuvio.tv.core.playlist.PlaylistItem(
            contentId = pQueue.id,
            videoId = if (isMovie) pQueue.id else null,
            title = pQueue.name,
            seriesTitle = if (isMovie) null else pQueue.name,
            season = if (isMovie) null else 1,
            episode = if (isMovie) null else 1,
            thumbnail = pQueue.poster,
            mediaType = pQueue.apiType
        )
        val currentQueue by com.nuvio.tv.core.playlist.PlaylistManager.queue.collectAsState()
        QueueSubmenuDialog(
            title = pQueue.name,
            queueSize = currentQueue.size,
            onPlayNext = {
                com.nuvio.tv.core.playlist.PlaylistManager.playNext(playlistItem)
                posterQueueTarget = null
            },
            onAddToQueue = {
                com.nuvio.tv.core.playlist.PlaylistManager.addToQueue(playlistItem)
                posterQueueTarget = null
            },
            onClearQueue = {
                com.nuvio.tv.core.playlist.PlaylistManager.clearQueue()
                posterQueueTarget = null
            },
            onDismiss = { posterQueueTarget = null }
        )
    }

    val target = state.target
    if (target != null) {
        val isMovie = target.apiType.equals("movie", ignoreCase = true)
        val isSeries = target.apiType.equals("series", ignoreCase = true) ||
            target.apiType.equals("tv", ignoreCase = true) ||
            target.apiType.equals("anime", ignoreCase = true)
        PosterOptionsDialog(
            title = target.name,
            isInLibrary = state.isInLibrary,
            isLibraryPending = state.isLibraryPending,
            showManageLists = state.librarySourceMode != LibrarySourceMode.LOCAL,
            isMovie = isMovie,
            isSeries = isSeries,
            isWatched = state.isWatched,
            isWatchedPending = state.isWatchedPending,
            onDismiss = { controller.dismiss() },
            onDetails = {
                onNavigateToDetail(target.id, target.apiType, state.addonBaseUrl)
                controller.dismiss()
            },
            onPlayNextClick = {
                val isMovie = target.apiType.equals("movie", ignoreCase = true)
                val playlistItem = com.nuvio.tv.core.playlist.PlaylistItem(
                    contentId = target.id,
                    videoId = if (isMovie) target.id else null,
                    title = target.name,
                    seriesTitle = if (isMovie) null else target.name,
                    season = if (isMovie) null else 1,
                    episode = if (isMovie) null else 1,
                    thumbnail = target.poster,
                    mediaType = target.apiType
                )
                com.nuvio.tv.core.playlist.PlaylistManager.playNext(playlistItem)
                android.widget.Toast.makeText(context, "Set to Play Next: ${target.name}", android.widget.Toast.LENGTH_SHORT).show()
                controller.dismiss()
            },
            onAddToQueueClick = {
                val isMovie = target.apiType.equals("movie", ignoreCase = true)
                val playlistItem = com.nuvio.tv.core.playlist.PlaylistItem(
                    contentId = target.id,
                    videoId = if (isMovie) target.id else null,
                    title = target.name,
                    seriesTitle = if (isMovie) null else target.name,
                    season = if (isMovie) null else 1,
                    episode = if (isMovie) null else 1,
                    thumbnail = target.poster,
                    mediaType = target.apiType
                )
                com.nuvio.tv.core.playlist.PlaylistManager.addToQueue(playlistItem)
                android.widget.Toast.makeText(context, "Added to Queue: ${target.name}", android.widget.Toast.LENGTH_SHORT).show()
                controller.dismiss()
            },
            onWatchlistClick = {
                val parsedIds = parseContentIds(target.id)
                val input = LibraryEntryInput(
                    itemId = target.id,
                    itemType = target.apiType,
                    title = target.name,
                    traktId = parsedIds.trakt,
                    simklId = parsedIds.simkl,
                    imdbId = parsedIds.imdb,
                    tmdbId = parsedIds.tmdb,
                    poster = target.poster
                )
                controller.openWatchlistDialog(input)
                controller.dismiss()
            },
            onQueueClick = {
                posterQueueTarget = target
                controller.dismiss()
            },
            onToggleLibrary = {
                if (state.librarySourceMode != LibrarySourceMode.LOCAL) {
                    controller.openListPicker()
                } else {
                    controller.toggleLibrary()
                    controller.dismiss()
                }
            },
            onToggleWatched = {
                if (isMovie) {
                    controller.toggleMovieWatched()
                } else {
                    controller.toggleSeriesWatched()
                }
                controller.dismiss()
            },
            onPlayRandomEpisode = {
                controller.playRandomEpisode { id, type, addon, season, episode ->
                    if (onPlaySeriesEpisode != null) {
                        onPlaySeriesEpisode(id, type, addon, season, episode)
                    } else {
                        onNavigateToDetail(id, type, addon)
                    }
                }
            },
            onStartChannelShuffle = {
                controller.startChannel(shuffle = true) { id, type, addon, season, episode ->
                    if (onPlaySeriesEpisode != null) {
                        onPlaySeriesEpisode(id, type, addon, season, episode)
                    } else {
                        onNavigateToDetail(id, type, addon)
                    }
                }
            },
            onStartChannelOrder = {
                controller.startChannel(shuffle = false) { id, type, addon, season, episode ->
                    if (onPlaySeriesEpisode != null) {
                        onPlaySeriesEpisode(id, type, addon, season, episode)
                    } else {
                        onNavigateToDetail(id, type, addon)
                    }
                }
            }
        )
    }

    if (state.listPickerActive) {
        val localTab = LibraryListTab(
            key = LOCAL_LIBRARY_LIST_KEY,
            title = stringResource(R.string.trakt_library_source_nuvio),
            type = LibraryListTab.Type.WATCHLIST
        )
        val contentType = state.listPickerContentType.orEmpty()
        val destinationTabs = state.libraryListTabs.filter { tab ->
            tab.supportsMembershipFor(contentType)
        }
        PosterListPickerDialog(
            title = state.listPickerTitle.orEmpty(),
            tabs = listOf(localTab) + destinationTabs,
            membership = state.listPickerMembership,
            isPending = state.listPickerPending,
            error = state.listPickerError,
            onToggle = { key -> controller.toggleListMembership(key) },
            onSave = { controller.saveListPicker() },
            onDismiss = { controller.dismissListPicker() }
        )
    }

    val confirmations = state.removalConfirmations
    if (confirmations.isNotEmpty()) {
        TrackingRemovalConfirmationDialog(
            itemTitle = state.listPickerTitle.orEmpty(),
            confirmations = confirmations,
            isPending = state.listPickerPending,
            onConfirm = controller::confirmDestructiveRemoval,
            onDismiss = controller::cancelDestructiveRemoval
        )
    }
}

@Composable
fun TrackingRemovalConfirmationDialog(
    itemTitle: String,
    confirmations: List<TrackingMembershipRemovalConfirmation>,
    isPending: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val providers = confirmations.joinToString { confirmation ->
        confirmation.providerId.storageId.replaceFirstChar(Char::uppercase)
    }
    val impacts = confirmations.flatMapTo(linkedSetOf()) { confirmation -> confirmation.impacts }
    val impact = when (impacts) {
        setOf(TrackingMembershipRemovalImpact.WATCHED_HISTORY) ->
            stringResource(R.string.tracking_removal_impact_history)
        setOf(TrackingMembershipRemovalImpact.RATING) ->
            stringResource(R.string.tracking_removal_impact_rating)
        else -> stringResource(R.string.tracking_removal_impact_history_rating)
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.tracking_removal_title, providers),
        subtitle = stringResource(R.string.tracking_removal_message, itemTitle, providers, impact),
        width = 560.dp,
        suppressFirstKeyUp = false
    ) {
        Button(
            onClick = onConfirm,
            enabled = !isPending,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_remove_anyway))
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}
