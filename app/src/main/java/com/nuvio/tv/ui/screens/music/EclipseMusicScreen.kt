@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.music

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.music.EclipseAlbumSummary
import com.nuvio.tv.core.music.EclipseArtistSummary
import com.nuvio.tv.core.music.EclipsePlaylistSummary
import com.nuvio.tv.core.music.EclipseSearchResponse
import com.nuvio.tv.core.music.EclipseTrack
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * 10-foot TV browse screen for Eclipse Music: Daily Mixes, Playlists, Search (Songs/Albums/Artists/Playlists),
 * Umbra AI DJ launcher, and prompt-driven AI Mix generator.
 */
@Composable
fun EclipseMusicScreen(
    showBuiltInHeader: Boolean = true,
    onOpenNowPlaying: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: EclipseMusicViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val telemetry by viewModel.audioTelemetry.collectAsStateWithLifecycle()

    val isConfigured = settings.enabled && settings.mcpBearerToken.isNotBlank()

    var showSearchDialog by remember { mutableStateOf(false) }
    var showMixDialog by remember { mutableStateOf(false) }

    if (!isConfigured) {
        MusicNotConfiguredState(
            showBuiltInHeader = showBuiltInHeader,
            onOpenSettings = onOpenSettings,
            onRetry = { viewModel.refreshHome() },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (showBuiltInHeader) {
            Text(
                text = stringResource(R.string.nav_music),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
        }

        MusicActionRow(
            isDjActive = telemetry.djSessionId != null,
            hasActiveAudio = telemetry.isActive,
            isDjBusy = uiState.isDjBusy,
            onSearch = { showSearchDialog = true },
            onUmbraDj = {
                viewModel.startUmbraDj(prompt = null) {
                    onOpenNowPlaying()
                }
            },
            onGenerateMix = { showMixDialog = true },
            onRefresh = { viewModel.refreshHome() },
            onNowPlaying = onOpenNowPlaying,
        )

        if (!uiState.error.isNullOrBlank()) {
            Card(
                onClick = { viewModel.clearError() },
                colors = CardDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                ),
                shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            ) {
                Text(
                    text = "${uiState.error} (click to dismiss)",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.Error,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        val searchResults = uiState.searchResults
        if (searchResults != null) {
            MusicSearchResultsBody(
                query = uiState.searchQuery,
                results = searchResults,
                isSearching = uiState.isSearching,
                onClearSearch = { viewModel.clearSearch() },
                onPlayTrack = { index ->
                    viewModel.playTracks(searchResults.tracks, startIndex = index, source = "eclipse_search") {
                        onOpenNowPlaying()
                    }
                },
                onOpenAlbum = { album -> viewModel.openAlbum(album.albumId.ifBlank { album.id }) },
                onOpenArtist = { artist -> viewModel.openArtist(artist.artistId.ifBlank { artist.id }) },
                onOpenPlaylist = { pl -> viewModel.openPlaylist(pl.id) },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(26.dp),
                contentPadding = PaddingValues(bottom = 84.dp),
            ) {
                if (uiState.dailyMixes.isNotEmpty()) {
                    item(key = "daily_mixes") {
                        MusicHorizontalSection(title = "Daily Mixes & Radio") {
                            itemsIndexed(
                                uiState.dailyMixes,
                                key = { index, mix -> mix.id ?: "mix_$index" },
                            ) { index, mix ->
                                MusicMediaCard(
                                    title = mix.displayTitle(),
                                    subtitle = mix.description ?: "${mix.tracks.size} tracks",
                                    imageUrl = mix.displayArtUrl(),
                                    fallbackIcon = Icons.Default.GraphicEq,
                                    onClick = {
                                        viewModel.playMix(mix, index) {
                                            onOpenNowPlaying()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                if (uiState.playlists.isNotEmpty()) {
                    item(key = "library_playlists") {
                        MusicHorizontalSection(title = "Playlists") {
                            items(uiState.playlists, key = { it.id }) { playlist ->
                                MusicMediaCard(
                                    title = playlist.name,
                                    subtitle = playlist.description ?: "${playlist.trackCount} tracks",
                                    imageUrl = playlist.artworkUrl,
                                    fallbackIcon = Icons.Default.MusicNote,
                                    onClick = { viewModel.openPlaylist(playlist.id) },
                                )
                            }
                        }
                    }
                }

                if (uiState.isLoading && uiState.playlists.isEmpty() && uiState.dailyMixes.isEmpty()) {
                    item(key = "loading_home") {
                        Text(
                            text = "Loading Eclipse Music library…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextSecondary,
                        )
                    }
                }
            }
        }
    }

    val selectedPlaylist = uiState.selectedPlaylist
    val selectedAlbum = uiState.selectedAlbum
    val selectedArtist = uiState.selectedArtist

    if (uiState.isLoadingDetail || selectedPlaylist != null || selectedAlbum != null || selectedArtist != null) {
        val detailTitle = selectedPlaylist?.playlist?.name
            ?: selectedAlbum?.album?.title
            ?: selectedArtist?.artist?.name
            ?: "Loading…"
        val detailCover = selectedPlaylist?.playlist?.artworkUrl
            ?: selectedAlbum?.album?.bestArtwork()
            ?: selectedArtist?.artist?.bestArtwork()
        val detailSubtitle = selectedPlaylist?.playlist?.description
            ?: selectedAlbum?.album?.let { "${it.artist} ${it.year ?: ""}".trim() }
            ?: selectedArtist?.artist?.genre
        val detailTracks: List<EclipseTrack> = selectedPlaylist?.tracks
            ?: selectedAlbum?.tracks
            ?: selectedArtist?.topSongs
            ?: emptyList()
        val detailSource = when {
            selectedAlbum != null -> "eclipse_album"
            selectedArtist != null -> "eclipse_artist"
            else -> "eclipse_playlist"
        }

        MusicCollectionDialog(
            title = detailTitle,
            subtitle = detailSubtitle,
            coverUrl = detailCover,
            tracks = detailTracks,
            isLoading = uiState.isLoadingDetail || uiState.isResolvingQueue,
            onPlayAll = {
                viewModel.playTracks(detailTracks, startIndex = 0, source = detailSource) {
                    viewModel.closeDetail()
                    onOpenNowPlaying()
                }
            },
            onPlayTrack = { idx ->
                viewModel.playTracks(detailTracks, startIndex = idx, source = detailSource) {
                    viewModel.closeDetail()
                    onOpenNowPlaying()
                }
            },
            onDismiss = { viewModel.closeDetail() },
        )
    }

    if (showSearchDialog) {
        MusicTextInputDialog(
            title = "Search Eclipse Music",
            subtitle = stringResource(R.string.music_search_placeholder),
            initialValue = uiState.searchQuery,
            confirmLabel = "Search",
            onDismiss = { showSearchDialog = false },
            onConfirm = { query ->
                showSearchDialog = false
                viewModel.search(query)
            },
        )
    }

    if (showMixDialog) {
        MusicTextInputDialog(
            title = stringResource(R.string.music_generate_mix),
            subtitle = "Describe a mood, genre, or artists (e.g. 'Late night synthwave & Arctic Monkeys')",
            initialValue = "",
            confirmLabel = "Generate & Play",
            onDismiss = { showMixDialog = false },
            onConfirm = { prompt ->
                showMixDialog = false
                viewModel.generateAiMix(prompt) {
                    onOpenNowPlaying()
                }
            },
        )
    }
}

@Composable
private fun MusicNotConfiguredState(
    showBuiltInHeader: Boolean,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = NuvioTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.Background)
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showBuiltInHeader) {
            Text(
                text = stringResource(R.string.nav_music),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(24.dp))
        }
        Box(
            modifier = Modifier
                .width(680.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.BackgroundCard)
                .padding(32.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(R.string.music_not_configured_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.music_not_configured_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.TextSecondary,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.colors(
                            containerColor = colors.Primary,
                            contentColor = colors.OnPrimary,
                        ),
                    ) {
                        Text(stringResource(R.string.music_open_settings))
                    }
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.colors(
                            containerColor = colors.Surface,
                            contentColor = colors.TextPrimary,
                        ),
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicActionRow(
    isDjActive: Boolean,
    hasActiveAudio: Boolean,
    isDjBusy: Boolean,
    onSearch: () -> Unit,
    onUmbraDj: () -> Unit,
    onGenerateMix: () -> Unit,
    onRefresh: () -> Unit,
    onNowPlaying: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        item(key = "btn_search") {
            MusicChipButton(
                label = "Search",
                icon = Icons.Default.Search,
                onClick = onSearch,
            )
        }
        item(key = "btn_umbra_dj") {
            MusicChipButton(
                label = if (isDjBusy) "Starting DJ…" else stringResource(R.string.music_umbra_dj),
                icon = Icons.Default.GraphicEq,
                highlighted = isDjActive,
                onClick = onUmbraDj,
            )
        }
        item(key = "btn_ai_mix") {
            MusicChipButton(
                label = stringResource(R.string.music_generate_mix),
                icon = Icons.Default.AutoAwesome,
                onClick = onGenerateMix,
            )
        }
        item(key = "btn_refresh") {
            MusicChipButton(
                label = "Refresh",
                icon = Icons.Default.Refresh,
                onClick = onRefresh,
            )
        }
        if (hasActiveAudio) {
            item(key = "btn_now_playing") {
                MusicChipButton(
                    label = stringResource(R.string.music_now_playing),
                    icon = Icons.Default.PlayArrow,
                    highlighted = true,
                    onClick = onNowPlaying,
                )
            }
        }
    }
}

@Composable
private fun MusicChipButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    val colors = NuvioTheme.colors
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = if (highlighted) colors.FocusBackground else colors.BackgroundCard,
            contentColor = colors.TextPrimary,
            focusedContainerColor = colors.FocusBackground,
            focusedContentColor = colors.TextPrimary,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun MusicSearchResultsBody(
    query: String,
    results: EclipseSearchResponse,
    isSearching: Boolean,
    onClearSearch: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onOpenAlbum: (EclipseAlbumSummary) -> Unit,
    onOpenArtist: (EclipseArtistSummary) -> Unit,
    onOpenPlaylist: (EclipsePlaylistSummary) -> Unit,
) {
    val colors = NuvioTheme.colors
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(bottom = 84.dp),
    ) {
        item(key = "search_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isSearching) "Searching for \"$query\"…" else "Results for \"$query\"",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Button(
                    onClick = onClearSearch,
                    colors = ButtonDefaults.colors(
                        containerColor = colors.BackgroundCard,
                        contentColor = colors.TextPrimary,
                    ),
                ) {
                    Text("Clear Search")
                }
            }
        }

        if (results.tracks.isNotEmpty()) {
            item(key = "search_tracks") {
                MusicHorizontalSection(title = "Tracks") {
                    itemsIndexed(results.tracks, key = { idx, t -> "${t.id}_$idx" }) { index, track ->
                        MusicMediaCard(
                            title = track.title,
                            subtitle = track.artist,
                            imageUrl = track.displayArtUrl(),
                            fallbackIcon = Icons.Default.MusicNote,
                            onClick = { onPlayTrack(index) },
                        )
                    }
                }
            }
        }

        if (results.albums.isNotEmpty()) {
            item(key = "search_albums") {
                MusicHorizontalSection(title = "Albums") {
                    itemsIndexed(results.albums, key = { idx, a -> "${a.id}_$idx" }) { _, album ->
                        MusicMediaCard(
                            title = album.title,
                            subtitle = album.artist,
                            imageUrl = album.bestArtwork(),
                            fallbackIcon = Icons.Default.Album,
                            onClick = { onOpenAlbum(album) },
                        )
                    }
                }
            }
        }

        if (results.artists.isNotEmpty()) {
            item(key = "search_artists") {
                MusicHorizontalSection(title = "Artists") {
                    itemsIndexed(results.artists, key = { idx, a -> "${a.id}_$idx" }) { _, artist ->
                        MusicMediaCard(
                            title = artist.name,
                            subtitle = artist.genre,
                            imageUrl = artist.bestArtwork(),
                            fallbackIcon = Icons.Default.GraphicEq,
                            onClick = { onOpenArtist(artist) },
                        )
                    }
                }
            }
        }

        if (results.playlists.isNotEmpty()) {
            item(key = "search_playlists") {
                MusicHorizontalSection(title = "Playlists") {
                    itemsIndexed(results.playlists, key = { idx, p -> "${p.id}_$idx" }) { _, playlist ->
                        MusicMediaCard(
                            title = playlist.name,
                            subtitle = playlist.creator ?: playlist.description,
                            imageUrl = playlist.artworkUrl,
                            fallbackIcon = Icons.Default.MusicNote,
                            onClick = { onOpenPlaylist(playlist) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicHorizontalSection(
    title: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
private fun MusicMediaCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    fallbackIcon: ImageVector,
    onClick: () -> Unit,
) {
    val colors = NuvioTheme.colors
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = colors.BackgroundCard,
            focusedContainerColor = colors.FocusBackground,
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, colors.BorderMuted)),
            focusedBorder = Border(BorderStroke(2.dp, colors.BorderFocused)),
        ),
        shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
        modifier = Modifier.width(190.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.Surface),
                contentAlignment = Alignment.Center,
            ) {
                if (!imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                } else {
                    Icon(
                        imageVector = fallbackIcon,
                        contentDescription = null,
                        tint = colors.TextSecondary,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MusicCollectionDialog(
    title: String,
    subtitle: String?,
    coverUrl: String?,
    tracks: List<EclipseTrack>,
    isLoading: Boolean,
    onPlayAll: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = NuvioTheme.colors
    NuvioDialog(
        title = title,
        subtitle = subtitle,
        width = 760.dp,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.Surface),
                    )
                }
                Button(
                    onClick = onPlayAll,
                    enabled = tracks.isNotEmpty() && !isLoading,
                    colors = ButtonDefaults.colors(
                        containerColor = colors.Primary,
                        contentColor = colors.OnPrimary,
                    ),
                ) {
                    Text(if (isLoading) "Loading…" else "Play All (${tracks.size})")
                }
            }

            if (isLoading && tracks.isEmpty()) {
                Text(
                    text = "Loading tracks…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.TextSecondary,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(tracks, key = { idx, t -> "${t.id}_$idx" }) { index, track ->
                        Card(
                            onClick = { onPlayTrack(index) },
                            colors = CardDefaults.colors(
                                containerColor = colors.BackgroundCard,
                                focusedContainerColor = colors.FocusBackground,
                            ),
                            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${index + 1}. ${track.title}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = track.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (track.durationSec > 0) {
                                    Text(
                                        text = formatAudioTime(track.durationSec * 1_000L),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.TextTertiary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicTextInputDialog(
    title: String,
    subtitle: String,
    initialValue: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    NuvioDialog(
        title = title,
        subtitle = subtitle,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(
                onClick = {},
                colors = CardDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = NuvioTheme.colors.BackgroundCard,
                ),
                shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = NuvioTheme.colors.TextPrimary,
                    ),
                    cursorBrush = SolidColor(NuvioTheme.colors.Primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            onConfirm(value.trim())
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .focusRequester(focusRequester)
                        .onKeyEvent { event ->
                            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER
                            ) {
                                keyboardController?.hide()
                                onConfirm(value.trim())
                                true
                            } else {
                                false
                            }
                        },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextSecondary,
                    ),
                ) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { onConfirm(value.trim()) },
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.Primary,
                        contentColor = NuvioTheme.colors.OnPrimary,
                    ),
                ) {
                    Text(confirmLabel)
                }
            }
        }
    }
}

internal fun formatAudioTime(millis: Long): String {
    val totalSec = (millis / 1_000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}
