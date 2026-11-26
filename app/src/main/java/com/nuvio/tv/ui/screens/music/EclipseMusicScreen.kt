@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.music

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
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
 * 10-foot TV browse screen for Eclipse Music: Daily Mixes, Playlists with artwork,
 * inline seamless playlist/album/artist collection view, Search, Umbra AI DJ (with prompt & cancel),
 * AI Mix generator, and Shield TV remote Voice Control.
 */
@Composable
fun EclipseMusicScreen(
    showBuiltInHeader: Boolean = true,
    onOpenNowPlaying: () -> Unit,
    onOpenDjMixer: () -> Unit = {},
    onOpenSettings: () -> Unit,
    viewModel: EclipseMusicViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val telemetry by viewModel.audioTelemetry.collectAsStateWithLifecycle()
    val djTelemetry by viewModel.djTelemetry.collectAsStateWithLifecycle()

    val isConfigured = settings.enabled && settings.mcpBearerToken.isNotBlank()
    val context = LocalContext.current

    var showSearchDialog by remember { mutableStateOf(false) }
    var showUmbraDialog by remember { mutableStateOf(false) }
    var showMixDialog by remember { mutableStateOf(false) }

    // Voice recognition (Shield remote / Android TV SpeechRecognizer)
    var isVoiceListening by remember { mutableStateOf(false) }
    var voiceRmsLevel by remember { mutableFloatStateOf(0f) }
    var voiceTargetCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val isVoiceAvailable = remember(context) { SpeechRecognizer.isRecognitionAvailable(context) }
    val speechRecognizer = remember(context, isVoiceAvailable) {
        if (isVoiceAvailable) runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() else null
    }
    var recordAudioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val startVoiceCapture: ((String) -> Unit) -> Unit = remember(context, speechRecognizer) {
        { onText ->
            voiceTargetCallback = onText
            if (speechRecognizer == null) {
                Toast.makeText(context, "Voice recognition unavailable on this device", Toast.LENGTH_SHORT).show()
            } else {
                isVoiceListening = true
                runCatching {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    }
                    speechRecognizer.cancel()
                    speechRecognizer.startListening(intent)
                }.onFailure {
                    isVoiceListening = false
                    Toast.makeText(context, "Unable to start voice recognition", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        recordAudioGranted = granted
        if (granted) {
            voiceTargetCallback?.let { startVoiceCapture(it) }
        } else {
            Toast.makeText(context, "Microphone permission is required for voice commands", Toast.LENGTH_SHORT).show()
        }
    }

    val launchVoice: ((String) -> Unit) -> Unit = { callback ->
        if (!recordAudioGranted) {
            voiceTargetCallback = callback
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startVoiceCapture(callback)
        }
    }

    DisposableEffect(speechRecognizer) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                voiceRmsLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) {
                isVoiceListening = false
                voiceRmsLevel = 0f
                if (error != SpeechRecognizer.ERROR_CLIENT && error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    Toast.makeText(context, "Voice input cancelled or did not match", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResults(results: Bundle?) {
                isVoiceListening = false
                voiceRmsLevel = 0f
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                if (!spoken.isNullOrBlank()) {
                    voiceTargetCallback?.invoke(spoken)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
        }
        speechRecognizer?.setRecognitionListener(listener)
        onDispose {
            runCatching { speechRecognizer?.destroy() }
        }
    }

    if (!isConfigured) {
        MusicNotConfiguredState(
            showBuiltInHeader = showBuiltInHeader,
            onOpenSettings = onOpenSettings,
            onRetry = { viewModel.refreshHome() },
        )
        return
    }

    // Reserve clean top padding when floating sidebar pill is present (showBuiltInHeader == false)
    val topScreenPadding = if (showBuiltInHeader) 24.dp else 78.dp

    val selectedPlaylist = uiState.selectedPlaylist
    val selectedAlbum = uiState.selectedAlbum
    val selectedArtist = uiState.selectedArtist
    val isInDetail = uiState.isLoadingDetail || selectedPlaylist != null || selectedAlbum != null || selectedArtist != null

    if (isInDetail) {
        BackHandler { viewModel.closeDetail() }

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

        MusicCollectionDetailView(
            topPadding = topScreenPadding,
            title = detailTitle,
            subtitle = detailSubtitle,
            coverUrl = detailCover,
            tracks = detailTracks,
            isLoading = uiState.isLoadingDetail || uiState.isResolvingQueue || uiState.isDjBusy,
            isDjBusy = uiState.isDjBusy,
            error = uiState.error,
            includeSurprises = uiState.includeDjSurprises,
            onBack = { viewModel.closeDetail() },
            onDismissError = { viewModel.clearError() },
            onPlayAll = {
                viewModel.playTracks(detailTracks, startIndex = 0, source = detailSource) {
                    onOpenNowPlaying()
                }
            },
            onProDjMix = {
                val pid = selectedPlaylist?.playlist?.id
                viewModel.startProDjSet(
                    playlistId = pid,
                    tracks = if (pid == null) detailTracks else null,
                    includeSurprises = uiState.includeDjSurprises,
                    fallbackTracks = detailTracks,
                ) {
                    onOpenDjMixer()
                }
            },
            onToggleSurprises = { viewModel.toggleDjSurprises() },
            onQueueNext = {
                viewModel.queueNext(detailTracks)
                Toast.makeText(context, "Queued ${detailTracks.size} tracks up next", Toast.LENGTH_SHORT).show()
            },
            onPlayTrack = { idx ->
                viewModel.playTracks(detailTracks, startIndex = idx, source = detailSource) {
                    onOpenNowPlaying()
                }
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(top = topScreenPadding, bottom = 16.dp)
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ASSIST ||
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST)
                ) {
                    launchVoice { spoken -> viewModel.handleVoiceCommand(spoken, onOpenNowPlaying, onOpenDjMixer) }
                    true
                } else {
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (showBuiltInHeader) {
            Text(
                text = stringResource(R.string.nav_music),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 48.dp),
            )
        }

        MusicActionRow(
            isDjActive = djTelemetry.isActive || telemetry.djSessionId != null,
            isProDjBoothActive = djTelemetry.isActive,
            hasActiveAudio = telemetry.isActive,
            isDjBusy = uiState.isDjBusy,
            isVoiceListening = isVoiceListening,
            isVoiceAvailable = isVoiceAvailable,
            onVoice = { launchVoice { spoken -> viewModel.handleVoiceCommand(spoken, onOpenNowPlaying, onOpenDjMixer) } },
            onSearch = { showSearchDialog = true },
            onUmbraDj = { showUmbraDialog = true },
            onOpenDjBooth = onOpenDjMixer,
            onCancelUmbraDj = { viewModel.cancelUmbraDj() },
            onStopUmbraDj = { viewModel.stopUmbraDj() },
            onGenerateMix = { showMixDialog = true },
            onRefresh = { viewModel.refreshHome() },
            onNowPlaying = onOpenNowPlaying,
        )

        if (isVoiceListening || !uiState.lastVoiceStatus.isNullOrBlank()) {
            val statusMsg = if (isVoiceListening) "Listening… speak a song, artist, 'Umbra …', or playback command" else uiState.lastVoiceStatus.orEmpty()
            Text(
                text = statusMsg,
                style = MaterialTheme.typography.bodySmall,
                color = if (isVoiceListening) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(horizontal = 48.dp),
            )
        }

        if (!uiState.error.isNullOrBlank()) {
            Card(
                onClick = { viewModel.clearError() },
                colors = CardDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                ),
                shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                modifier = Modifier.padding(horizontal = 48.dp),
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
                contentPadding = PaddingValues(bottom = 110.dp),
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

    if (showUmbraDialog) {
        UmbraDjLaunchDialog(
            isBusy = uiState.isDjBusy,
            statusMessage = uiState.djStatusMessage,
            error = uiState.error,
            includeSurprises = uiState.includeDjSurprises,
            isVoiceAvailable = isVoiceAvailable,
            isVoiceListening = isVoiceListening,
            onToggleSurprises = { viewModel.toggleDjSurprises() },
            onVoicePrompt = { fill -> launchVoice(fill) },
            onStart = { prompt ->
                viewModel.startProDjSet(
                    prompt = prompt,
                    includeSurprises = uiState.includeDjSurprises,
                ) {
                    showUmbraDialog = false
                    onOpenDjMixer()
                }
            },
            onCancelStartup = {
                viewModel.cancelUmbraDj()
                showUmbraDialog = false
            },
            onDismiss = {
                if (uiState.isDjBusy) viewModel.cancelUmbraDj()
                showUmbraDialog = false
            },
        )
    }

    if (showSearchDialog) {
        MusicTextInputDialog(
            title = "Search Eclipse Music",
            subtitle = stringResource(R.string.music_search_placeholder),
            initialValue = uiState.searchQuery,
            confirmLabel = "Search",
            isVoiceAvailable = isVoiceAvailable,
            onVoiceInput = { fill -> launchVoice(fill) },
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
            isVoiceAvailable = isVoiceAvailable,
            onVoiceInput = { fill -> launchVoice(fill) },
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
private fun MusicCollectionDetailView(
    topPadding: androidx.compose.ui.unit.Dp,
    title: String,
    subtitle: String?,
    coverUrl: String?,
    tracks: List<EclipseTrack>,
    isLoading: Boolean,
    isDjBusy: Boolean = false,
    error: String? = null,
    includeSurprises: Boolean,
    onBack: () -> Unit,
    onDismissError: () -> Unit = {},
    onPlayAll: () -> Unit,
    onProDjMix: () -> Unit,
    onToggleSurprises: () -> Unit,
    onQueueNext: () -> Unit,
    onPlayTrack: (Int) -> Unit,
) {
    val colors = NuvioTheme.colors
    val playAllFocus = remember { FocusRequester() }

    LaunchedEffect(tracks.size, isLoading) {
        if (!isLoading && tracks.isNotEmpty()) {
            runCatching { playAllFocus.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.Background)
            .padding(top = topPadding, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Seamless Hero Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.colors(
                    containerColor = colors.BackgroundCard,
                    contentColor = colors.TextPrimary,
                    focusedContainerColor = colors.FocusBackground,
                ),
            ) {
                Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back to Music")
            }

            Box(
                modifier = Modifier
                    .size(132.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.BackgroundCard),
                contentAlignment = Alignment.Center,
            ) {
                val resolvedCover = coverUrl?.takeIf { it.isNotBlank() }
                    ?: tracks.firstOrNull { it.displayArtUrl().isNotBlank() }?.displayArtUrl()
                if (!resolvedCover.isNullOrBlank()) {
                    AsyncImage(
                        model = resolvedCover,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = colors.TextSecondary,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!error.isNullOrBlank()) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.Error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onPlayAll,
                        enabled = tracks.isNotEmpty() && !isLoading,
                        modifier = Modifier.focusRequester(playAllFocus),
                        colors = ButtonDefaults.colors(
                            containerColor = colors.Primary,
                            contentColor = colors.OnPrimary,
                            focusedContainerColor = colors.FocusBackground,
                            focusedContentColor = colors.TextPrimary,
                        ),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isLoading) "Loading…" else "Play All (${tracks.size})")
                    }

                    Button(
                        onClick = onProDjMix,
                        enabled = tracks.isNotEmpty() && !isLoading,
                        colors = ButtonDefaults.colors(
                            containerColor = colors.BackgroundCard,
                            contentColor = colors.Primary,
                            focusedContainerColor = colors.Primary,
                            focusedContentColor = colors.OnPrimary,
                        ),
                    ) {
                        Icon(Icons.Default.CompareArrows, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isDjBusy) "Curating…" else "Pro AI DJ Mix")
                    }

                    Button(
                        onClick = onToggleSurprises,
                        colors = ButtonDefaults.colors(
                            containerColor = if (includeSurprises) colors.Primary.copy(alpha = 0.2f) else colors.BackgroundCard,
                            contentColor = if (includeSurprises) colors.Primary else colors.TextSecondary,
                            focusedContainerColor = colors.FocusBackground,
                        ),
                    ) {
                        Icon(Icons.Default.Casino, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (includeSurprises) "Surprises: ON" else "Surprises: OFF")
                    }

                    Button(
                        onClick = onQueueNext,
                        enabled = tracks.isNotEmpty() && !isLoading,
                        colors = ButtonDefaults.colors(
                            containerColor = colors.BackgroundCard,
                            contentColor = colors.TextPrimary,
                            focusedContainerColor = colors.FocusBackground,
                        ),
                    ) {
                        Icon(Icons.Default.QueueMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Queue Next")
                    }
                }
            }
        }

        if (isLoading && tracks.isEmpty()) {
            Text(
                text = "Loading tracks & artwork…",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.TextSecondary,
                modifier = Modifier.padding(horizontal = 48.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp).let {
                    PaddingValues(start = 48.dp, end = 48.dp, top = 10.dp, bottom = 110.dp)
                },
            ) {
                itemsIndexed(tracks, key = { idx, t -> "${t.id}_$idx" }) { index, track ->
                    Card(
                        onClick = { onPlayTrack(index) },
                        colors = CardDefaults.colors(
                            containerColor = colors.BackgroundCard,
                            focusedContainerColor = colors.FocusBackground,
                        ),
                        border = CardDefaults.border(
                            border = Border(BorderStroke(1.dp, Color.Transparent), shape = RoundedCornerShape(12.dp)),
                            focusedBorder = Border(BorderStroke(2.dp, colors.BorderFocused), shape = RoundedCornerShape(12.dp)),
                        ),
                        scale = CardDefaults.scale(focusedScale = 1.02f),
                        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.TextTertiary,
                                modifier = Modifier.width(26.dp),
                            )
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.Surface),
                                contentAlignment = Alignment.Center,
                            ) {
                                val art = track.displayArtUrl()
                                if (art.isNotBlank()) {
                                    AsyncImage(
                                        model = art,
                                        contentDescription = track.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.matchParentSize(),
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = colors.TextSecondary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = buildString {
                                        append(track.artist)
                                        if (track.album.isNotBlank()) {
                                            append(" • ")
                                            append(track.album)
                                        }
                                    },
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

@Composable
private fun UmbraDjLaunchDialog(
    isBusy: Boolean,
    statusMessage: String? = null,
    error: String? = null,
    includeSurprises: Boolean,
    isVoiceAvailable: Boolean,
    isVoiceListening: Boolean = false,
    onToggleSurprises: () -> Unit,
    onVoicePrompt: ((String) -> Unit) -> Unit,
    onStart: (String) -> Unit,
    onCancelStartup: () -> Unit,
    onDismiss: () -> Unit,
) {
    var promptText by remember { mutableStateOf("") }
    val inputFocus = remember { FocusRequester() }
    val startFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isBusy) {
        runCatching {
            if (isBusy) cancelFocus.requestFocus() else inputFocus.requestFocus()
        }
    }

    NuvioDialog(
        title = stringResource(R.string.music_umbra_dj),
        subtitle = "Optional vibe or prompt (leave blank for Umbra's curated mix).",
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
                    value = promptText,
                    onValueChange = { promptText = it },
                    singleLine = true,
                    enabled = !isBusy,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = NuvioTheme.colors.TextPrimary,
                    ),
                    cursorBrush = SolidColor(NuvioTheme.colors.Primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            if (!isBusy) onStart(promptText.trim())
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .focusRequester(inputFocus)
                        .onKeyEvent { event ->
                            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER &&
                                !isBusy
                            ) {
                                keyboardController?.hide()
                                onStart(promptText.trim())
                                true
                            } else {
                                false
                            }
                        },
                )
            }

            if (isBusy) {
                Text(
                    text = statusMessage ?: "Curating Pro AI DJ beatmix…",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.Primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (!error.isNullOrBlank()) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.Error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isVoiceAvailable && !isBusy) {
                        Button(
                            onClick = {
                                onVoicePrompt { spoken ->
                                    promptText = spoken
                                    runCatching { startFocus.requestFocus() }
                                }
                            },
                            colors = ButtonDefaults.colors(
                                containerColor = if (isVoiceListening) NuvioTheme.colors.Primary.copy(alpha = 0.25f) else NuvioTheme.colors.BackgroundCard,
                                contentColor = if (isVoiceListening) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary,
                            ),
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isVoiceListening) "Listening…" else "Voice")
                        }
                    }
                    if (!isBusy) {
                        Button(
                            onClick = onToggleSurprises,
                            colors = ButtonDefaults.colors(
                                containerColor = if (includeSurprises) NuvioTheme.colors.Primary.copy(alpha = 0.2f) else NuvioTheme.colors.BackgroundCard,
                                contentColor = if (includeSurprises) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary,
                            ),
                        ) {
                            Icon(Icons.Default.Casino, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(if (includeSurprises) "Surprises: ON" else "Surprises: OFF")
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { if (isBusy) onCancelStartup() else onDismiss() },
                        modifier = Modifier.focusRequester(cancelFocus),
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = if (isBusy) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                        ),
                    ) {
                        Text(if (isBusy) "Cancel Startup" else "Cancel")
                    }

                    if (!isBusy) {
                        Button(
                            onClick = { onStart(promptText.trim()) },
                            modifier = Modifier.focusRequester(startFocus),
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.Primary,
                                contentColor = NuvioTheme.colors.OnPrimary,
                            ),
                        ) {
                            Text(if (promptText.isBlank()) "Start Umbra DJ" else "Start with Prompt")
                        }
                    }
                }
            }
        }
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
    isProDjBoothActive: Boolean,
    hasActiveAudio: Boolean,
    isDjBusy: Boolean,
    isVoiceListening: Boolean,
    isVoiceAvailable: Boolean,
    onVoice: () -> Unit,
    onSearch: () -> Unit,
    onUmbraDj: () -> Unit,
    onOpenDjBooth: () -> Unit,
    onCancelUmbraDj: () -> Unit,
    onStopUmbraDj: () -> Unit,
    onGenerateMix: () -> Unit,
    onRefresh: () -> Unit,
    onNowPlaying: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
    ) {
        if (isVoiceAvailable) {
            item(key = "btn_voice") {
                MusicChipButton(
                    label = if (isVoiceListening) "Listening…" else "Voice",
                    icon = Icons.Default.Mic,
                    highlighted = isVoiceListening,
                    onClick = onVoice,
                )
            }
        }
        item(key = "btn_search") {
            MusicChipButton(
                label = "Search",
                icon = Icons.Default.Search,
                onClick = onSearch,
            )
        }
        if (isDjBusy) {
            item(key = "btn_cancel_dj") {
                MusicChipButton(
                    label = "Cancel DJ",
                    icon = Icons.Default.Close,
                    highlighted = true,
                    onClick = onCancelUmbraDj,
                )
            }
        } else {
            item(key = "btn_umbra_dj") {
                MusicChipButton(
                    label = stringResource(R.string.music_umbra_dj),
                    icon = Icons.Default.GraphicEq,
                    highlighted = isDjActive,
                    onClick = onUmbraDj,
                )
            }
        }
        if (isProDjBoothActive) {
            item(key = "btn_dj_booth") {
                MusicChipButton(
                    label = "DJ Booth",
                    icon = Icons.Default.CompareArrows,
                    highlighted = true,
                    onClick = onOpenDjBooth,
                )
            }
        }
        if (isDjActive && !isDjBusy) {
            item(key = "btn_stop_dj") {
                MusicChipButton(
                    label = "Stop DJ",
                    icon = Icons.Default.Stop,
                    onClick = onStopUmbraDj,
                )
            }
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
        contentPadding = PaddingValues(bottom = 110.dp),
    ) {
        item(key = "search_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 48.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(start = 48.dp, end = 96.dp, top = 12.dp, bottom = 14.dp),
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
    val cardShape = RoundedCornerShape(14.dp)
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = colors.BackgroundCard,
            focusedContainerColor = colors.FocusBackground,
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, colors.BorderMuted), shape = cardShape),
            focusedBorder = Border(BorderStroke(2.dp, colors.BorderFocused), shape = cardShape),
        ),
        scale = CardDefaults.scale(focusedScale = 1.04f),
        shape = CardDefaults.shape(cardShape),
        modifier = Modifier.width(188.dp),
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
private fun MusicTextInputDialog(
    title: String,
    subtitle: String,
    initialValue: String,
    confirmLabel: String,
    isVoiceAvailable: Boolean = false,
    onVoiceInput: (((String) -> Unit) -> Unit)? = null,
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isVoiceAvailable && onVoiceInput != null) {
                    Button(
                        onClick = { onVoiceInput { spoken -> value = spoken } },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary,
                        ),
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Voice")
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                Row(horizontalArrangement = Arrangement.End) {
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
}

internal fun formatAudioTime(millis: Long): String {
    val totalSec = (millis / 1_000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}
