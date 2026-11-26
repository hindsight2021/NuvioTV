package com.nuvio.tv.ui.screens.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.music.DjConsoleTelemetry
import com.nuvio.tv.core.music.EclipseAlbumDetail
import com.nuvio.tv.core.music.EclipseArtistDetail
import com.nuvio.tv.core.music.EclipseAudioMix
import com.nuvio.tv.core.music.EclipseAudioPlayer
import com.nuvio.tv.core.music.EclipseAudioTelemetry
import com.nuvio.tv.core.music.EclipseMusicClient
import com.nuvio.tv.core.music.EclipsePlaylistDetail
import com.nuvio.tv.core.music.EclipsePlaylistSummary
import com.nuvio.tv.core.music.EclipseSearchResponse
import com.nuvio.tv.core.music.EclipseTrack
import com.nuvio.tv.data.local.EclipseSettings
import com.nuvio.tv.data.local.EclipseSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Eclipse Music browse, inline collection detail, and Now Playing TV experience.
 */
data class EclipseMusicUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val playlists: List<EclipsePlaylistSummary> = emptyList(),
    val dailyMixes: List<EclipseAudioMix> = emptyList(),
    val searchQuery: String = "",
    val searchResults: EclipseSearchResponse? = null,
    val isSearching: Boolean = false,
    val selectedPlaylist: EclipsePlaylistDetail? = null,
    val selectedAlbum: EclipseAlbumDetail? = null,
    val selectedArtist: EclipseArtistDetail? = null,
    val isLoadingDetail: Boolean = false,
    val isResolvingQueue: Boolean = false,
    val djBannerMessage: String? = null,
    val djStatusMessage: String? = null,
    val isDjBusy: Boolean = false,
    val includeDjSurprises: Boolean = true,
    val lastVoiceStatus: String? = null,
)

/**
 * ViewModel backing [EclipseMusicScreen] and [NowPlayingScreen].
 */
@HiltViewModel
class EclipseMusicViewModel @Inject constructor(
    private val musicClient: EclipseMusicClient,
    private val audioPlayer: EclipseAudioPlayer,
    settingsDataStore: EclipseSettingsDataStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EclipseMusicUiState())
    val uiState: StateFlow<EclipseMusicUiState> = _uiState.asStateFlow()

    val settings: StateFlow<EclipseSettings> = settingsDataStore.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = EclipseSettings(),
    )

    val audioTelemetry: StateFlow<EclipseAudioTelemetry> = audioPlayer.telemetry
    val djTelemetry: StateFlow<DjConsoleTelemetry> = audioPlayer.djTelemetry

    private var djJob: Job? = null

    init {
        viewModelScope.launch {
            settingsDataStore.settings.collect { current ->
                if (current.enabled && current.mcpBearerToken.isNotBlank() &&
                    _uiState.value.playlists.isEmpty() && _uiState.value.dailyMixes.isEmpty() &&
                    !_uiState.value.isLoading
                ) {
                    refreshHome()
                }
            }
        }
    }

    fun refreshHome() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            musicClient.fetchHome().fold(
                onSuccess = { home ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            playlists = home.playlists,
                            dailyMixes = home.dailyMixes,
                            error = home.error,
                        )
                    }
                },
                onFailure = { t ->
                    _uiState.update {
                        it.copy(isLoading = false, error = t.message ?: "Failed to load Eclipse home")
                    }
                },
            )
        }
    }

    fun search(query: String) {
        val trimmed = query.trim()
        _uiState.update { it.copy(searchQuery = query) }
        if (trimmed.isEmpty()) {
            clearSearch()
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSearching = true,
                    selectedPlaylist = null,
                    selectedAlbum = null,
                    selectedArtist = null,
                    error = null,
                )
            }
            musicClient.search(trimmed, source = "auto", limit = 20).fold(
                onSuccess = { resp ->
                    _uiState.update {
                        it.copy(isSearching = false, searchResults = resp, error = resp.error)
                    }
                },
                onFailure = { t ->
                    _uiState.update {
                        it.copy(isSearching = false, error = t.message ?: "Music search failed")
                    }
                },
            )
        }
    }

    fun clearSearch() {
        _uiState.update { it.copy(searchQuery = "", searchResults = null, isSearching = false) }
    }

    fun openPlaylist(playlistId: String) {
        if (playlistId.isBlank()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingDetail = true,
                    selectedPlaylist = null,
                    selectedAlbum = null,
                    selectedArtist = null,
                    error = null,
                )
            }
            musicClient.fetchPlaylist(playlistId).fold(
                onSuccess = { detail ->
                    _uiState.update { it.copy(isLoadingDetail = false, selectedPlaylist = detail) }
                },
                onFailure = { t ->
                    _uiState.update {
                        it.copy(isLoadingDetail = false, error = t.message ?: "Failed to load playlist")
                    }
                },
            )
        }
    }

    fun openAlbum(albumId: String) {
        if (albumId.isBlank()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingDetail = true,
                    selectedPlaylist = null,
                    selectedAlbum = null,
                    selectedArtist = null,
                    error = null,
                )
            }
            musicClient.fetchAlbum(albumId).fold(
                onSuccess = { detail ->
                    _uiState.update { it.copy(isLoadingDetail = false, selectedAlbum = detail) }
                },
                onFailure = { t ->
                    _uiState.update {
                        it.copy(isLoadingDetail = false, error = t.message ?: "Failed to load album")
                    }
                },
            )
        }
    }

    fun openArtist(artistId: String) {
        if (artistId.isBlank()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingDetail = true,
                    selectedPlaylist = null,
                    selectedAlbum = null,
                    selectedArtist = null,
                    error = null,
                )
            }
            musicClient.fetchArtist(artistId).fold(
                onSuccess = { detail ->
                    _uiState.update { it.copy(isLoadingDetail = false, selectedArtist = detail) }
                },
                onFailure = { t ->
                    _uiState.update {
                        it.copy(isLoadingDetail = false, error = t.message ?: "Failed to load artist")
                    }
                },
            )
        }
    }

    fun closeDetail() {
        _uiState.update {
            it.copy(
                selectedPlaylist = null,
                selectedAlbum = null,
                selectedArtist = null,
                isLoadingDetail = false,
            )
        }
    }

    fun playMix(mix: EclipseAudioMix, mixIndex: Int, onStarted: (() -> Unit)? = null) {
        if (mix.tracks.isNotEmpty()) {
            playTracks(mix.tracks, startIndex = 0, source = "eclipse_mix", onStarted = onStarted)
            return
        }
        playRadio(kind = "daily_mix", query = mix.displayTitle(), mixIndex = mixIndex, onStarted = onStarted)
    }

    fun playTracks(
        tracks: List<EclipseTrack>,
        startIndex: Int = 0,
        source: String = "eclipse",
        djSessionId: String? = null,
        onStarted: (() -> Unit)? = null,
    ) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isResolvingQueue = true, error = null) }
            val playable = if (tracks.any { it.streamUrl.isBlank() }) {
                musicClient.resolveQueue(tracks).getOrElse { tracks }
            } else {
                tracks
            }
            val ready = playable.filter { it.streamUrl.isNotBlank() }
            if (ready.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isResolvingQueue = false,
                        error = "No playable audio stream returned for selected tracks.",
                    )
                }
                return@launch
            }
            val safeIndex = startIndex.coerceIn(0, ready.lastIndex)
            audioPlayer.playQueue(
                tracks = ready,
                startIndex = safeIndex,
                autoplay = true,
                source = source,
                djSessionId = djSessionId,
            )
            _uiState.update { it.copy(isResolvingQueue = false) }
            onStarted?.invoke()
        }
    }

    fun queueNext(tracks: List<EclipseTrack>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            val playable = if (tracks.any { it.streamUrl.isBlank() }) {
                musicClient.resolveQueue(tracks).getOrElse { tracks }
            } else {
                tracks
            }
            val ready = playable.filter { it.streamUrl.isNotBlank() }
            if (ready.isNotEmpty()) {
                audioPlayer.queueTracks(ready, mode = "play_next")
            }
        }
    }

    fun startUmbraDj(prompt: String? = null, onStarted: (() -> Unit)? = null) {
        djJob?.cancel()
        djJob = viewModelScope.launch {
            _uiState.update { it.copy(isDjBusy = true, error = null) }
            try {
                musicClient.startUmbraDj(prompt = prompt?.takeIf { it.isNotBlank() }, fresh = true).fold(
                    onSuccess = { result ->
                        val tracks = result.tracks.filter { it.streamUrl.isNotBlank() }
                        _uiState.update {
                            it.copy(
                                isDjBusy = false,
                                djBannerMessage = result.message ?: "Umbra AI DJ is on air",
                            )
                        }
                        if (tracks.isNotEmpty()) {
                            audioPlayer.playQueue(
                                tracks = tracks,
                                startIndex = 0,
                                autoplay = true,
                                source = "umbra_dj",
                                djSessionId = result.sessionId,
                            )
                            onStarted?.invoke()
                        } else {
                            _uiState.update { it.copy(error = "Umbra DJ did not return any playable tracks.") }
                        }
                    },
                    onFailure = { t ->
                        if (t is CancellationException) throw t
                        _uiState.update {
                            it.copy(isDjBusy = false, error = t.message ?: "Umbra DJ failed to start")
                        }
                    },
                )
            } catch (_: CancellationException) {
                _uiState.update { it.copy(isDjBusy = false) }
            }
        }
    }

    fun cancelUmbraDj() {
        djJob?.cancel()
        djJob = null
        _uiState.update { it.copy(isDjBusy = false, djStatusMessage = null) }
    }

    fun toggleDjSurprises() {
        _uiState.update { it.copy(includeDjSurprises = !it.includeDjSurprises) }
    }

    private suspend fun resolvePlayableDjTracks(rawTracks: List<EclipseTrack>): List<EclipseTrack> {
        if (rawTracks.isEmpty()) return emptyList()
        val subset = rawTracks.take(12)
        val resolved = if (subset.any { it.streamUrl.isBlank() }) {
            // Skip slow bulk lyrics lookup during DJ startup (lyrics load lazily per playing track).
            musicClient.resolveQueue(subset, includeLyrics = false).getOrElse {
                // Retry a smaller quick batch if resolving 12 tracks timed out so Deck A & B still launch fast.
                val quickBatch = subset.take(6)
                musicClient.resolveQueue(quickBatch, includeLyrics = false).getOrElse { subset }
            }
        } else {
            subset
        }
        val resolvedById = resolved.filter { it.id.isNotBlank() }.associateBy { it.id }
        return subset.mapIndexed { index, original ->
            val match = resolvedById[original.id] ?: resolved.getOrNull(index)
            if (match == null) {
                original
            } else {
                original.copy(
                    streamUrl = match.streamUrl.ifBlank { original.streamUrl },
                    artworkUrl = match.artworkUrl.ifBlank { original.artworkUrl },
                    backdropUrl = match.backdropUrl.ifBlank { original.backdropUrl },
                    durationSec = match.durationSec.takeIf { it > 0 } ?: original.durationSec,
                    lyricsLrc = match.lyricsLrc ?: original.lyricsLrc,
                )
            }
        }.filter { it.streamUrl.isNotBlank() }
    }

    fun startProDjSet(
        playlistId: String? = null,
        tracks: List<EclipseTrack>? = null,
        prompt: String? = null,
        includeSurprises: Boolean = _uiState.value.includeDjSurprises,
        fallbackTracks: List<EclipseTrack>? = null,
        onStarted: (() -> Unit)? = null,
    ) {
        val cleanPrompt = prompt?.trim()?.takeIf { it.isNotBlank() }
        djJob?.cancel()
        djJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDjBusy = true,
                    djStatusMessage = if (cleanPrompt != null) {
                        "Curating Pro AI DJ beatmix for \"$cleanPrompt\"…"
                    } else {
                        "Curating Pro AI DJ beatmix…"
                    },
                    error = null,
                )
            }
            try {
                var bannerMessage: String? = null
                var commentary: String? = null
                var mood: String? = null
                var sessionId: String? = null
                var lastFailure: String? = null
                var playable: List<EclipseTrack> = emptyList()

                // Tier 1: Pro AI DJ server curation & beatmix planning.
                musicClient.startProDjSet(
                    playlistId = playlistId,
                    tracks = tracks,
                    prompt = cleanPrompt,
                    includeSurprises = includeSurprises,
                    surpriseCount = if (includeSurprises) 2 else 0,
                ).fold(
                    onSuccess = { result ->
                        bannerMessage = result.commentary ?: result.message ?: "Pro AI DJ Beatmix Active"
                        commentary = result.commentary ?: result.message
                        mood = result.mood
                        sessionId = result.sessionId
                        playable = resolvePlayableDjTracks(result.tracks)
                    },
                    onFailure = { t ->
                        if (t is CancellationException) throw t
                        lastFailure = t.message
                    },
                )

                // Tier 2: Seed tracks / fallback playlist tracks / active playback queue.
                if (playable.isEmpty()) {
                    val seed = tracks?.takeIf { it.isNotEmpty() }
                        ?: fallbackTracks?.takeIf { it.isNotEmpty() }
                        ?: if (cleanPrompt == null) {
                            audioTelemetry.value.queue.takeIf { it.isNotEmpty() }
                                ?: djTelemetry.value.queue
                        } else {
                            emptyList()
                        }
                    if (seed.isNotEmpty()) {
                        _uiState.update { it.copy(djStatusMessage = "Resolving tracks for beatmix…") }
                        playable = resolvePlayableDjTracks(seed)
                        if (playable.isNotEmpty()) {
                            bannerMessage = bannerMessage ?: "Pro AI DJ Beatmix Active"
                            commentary = commentary ?: "Dual-deck beatmix engaged from your current set"
                        }
                    }
                }

                // Tier 3: Classic Umbra AI DJ endpoint.
                if (playable.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            djStatusMessage = if (cleanPrompt != null) {
                                "Switching to Umbra AI DJ for \"$cleanPrompt\"…"
                            } else {
                                "Switching to Umbra AI DJ…"
                            },
                        )
                    }
                    musicClient.startUmbraDj(
                        prompt = cleanPrompt,
                        fresh = true,
                    ).fold(
                        onSuccess = { result ->
                            playable = resolvePlayableDjTracks(result.tracks)
                            if (playable.isNotEmpty()) {
                                bannerMessage = result.commentary ?: result.message ?: "Umbra AI DJ is on air"
                                commentary = result.commentary ?: result.message
                                mood = result.mood
                                sessionId = result.sessionId
                            }
                        },
                        onFailure = { t ->
                            if (t is CancellationException) throw t
                            if (lastFailure.isNullOrBlank()) lastFailure = t.message
                        },
                    )
                }

                // Tier 4: AI Playlist Generator for spoken / custom topics when DJ curation endpoints time out.
                if (playable.isEmpty() && cleanPrompt != null) {
                    _uiState.update { it.copy(djStatusMessage = "Generating AI set for \"$cleanPrompt\"…") }
                    musicClient.generatePlaylist(
                        prompt = cleanPrompt,
                        count = 15,
                        save = false,
                    ).fold(
                        onSuccess = { generated ->
                            playable = resolvePlayableDjTracks(generated)
                            if (playable.isNotEmpty()) {
                                bannerMessage = "AI DJ Beatmix • $cleanPrompt"
                                commentary = "Dual-deck beatmix generated for \"$cleanPrompt\""
                                mood = cleanPrompt
                            }
                        },
                        onFailure = { t ->
                            if (t is CancellationException) throw t
                            if (lastFailure.isNullOrBlank()) lastFailure = t.message
                        },
                    )
                }

                // Tier 5: Instant Eclipse Search fallback for spoken topics (sub-3s) so voice topics never dead-end on timeout!
                if (playable.isEmpty() && cleanPrompt != null) {
                    _uiState.update { it.copy(djStatusMessage = "Building instant DJ mix for \"$cleanPrompt\"…") }
                    musicClient.search(
                        query = cleanPrompt,
                        source = "auto",
                        limit = 20,
                    ).fold(
                        onSuccess = { searchResp ->
                            var candidateTracks = searchResp.tracks
                            if (candidateTracks.isEmpty()) {
                                val topPlaylist = searchResp.playlists.firstOrNull()
                                val topArtist = searchResp.artists.firstOrNull()
                                val topAlbum = searchResp.albums.firstOrNull()
                                candidateTracks = when {
                                    topPlaylist != null -> musicClient.fetchPlaylist(topPlaylist.id).getOrNull()?.tracks.orEmpty()
                                    topArtist != null -> musicClient.fetchArtist(topArtist.artistId.ifBlank { topArtist.id }).getOrNull()?.topSongs.orEmpty()
                                    topAlbum != null -> musicClient.fetchAlbum(topAlbum.albumId.ifBlank { topAlbum.id }).getOrNull()?.tracks.orEmpty()
                                    else -> emptyList()
                                }
                            }
                            playable = resolvePlayableDjTracks(candidateTracks)
                            if (playable.isNotEmpty()) {
                                bannerMessage = "Instant AI DJ • $cleanPrompt"
                                commentary = "Live dual-deck beatmix spun from Eclipse tracks matching \"$cleanPrompt\""
                                mood = cleanPrompt
                            }
                        },
                        onFailure = { t ->
                            if (t is CancellationException) throw t
                            if (lastFailure.isNullOrBlank()) lastFailure = t.message
                        },
                    )
                }

                // Tier 6: Eclipse Radio mix fallback.
                if (playable.isEmpty()) {
                    _uiState.update { it.copy(djStatusMessage = "Loading Eclipse Radio beatmix…") }
                    musicClient.playRadio(
                        kind = if (cleanPrompt == null) "daily_mix" else "mix",
                        query = cleanPrompt ?: "",
                        mixIndex = 0,
                    ).fold(
                        onSuccess = { radioTracks ->
                            playable = resolvePlayableDjTracks(radioTracks)
                            if (playable.isNotEmpty()) {
                                bannerMessage = bannerMessage ?: "AI DJ Radio Beatmix Active"
                                commentary = commentary ?: "Dual-deck beatmix engaged from Eclipse Radio"
                            }
                        },
                        onFailure = { t ->
                            if (t is CancellationException) throw t
                            if (lastFailure.isNullOrBlank()) lastFailure = t.message
                        },
                    )
                }

                if (playable.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            isDjBusy = false,
                            djStatusMessage = null,
                            djBannerMessage = bannerMessage ?: "Pro AI DJ Beatmix Active",
                            error = null,
                        )
                    }
                    audioPlayer.startDjMixSet(
                        tracks = playable,
                        djSessionId = sessionId,
                        commentary = commentary,
                        mood = mood,
                    )
                    onStarted?.invoke()
                } else {
                    _uiState.update {
                        it.copy(
                            isDjBusy = false,
                            djStatusMessage = null,
                            error = lastFailure?.takeIf { m -> m.isNotBlank() }
                                ?: "Pro AI DJ returned no playable tracks.",
                        )
                    }
                }
            } catch (t: CancellationException) {
                _uiState.update { it.copy(isDjBusy = false, djStatusMessage = null) }
                throw t
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(isDjBusy = false, djStatusMessage = null, error = t.message ?: "Pro AI DJ set failed")
                }
            }
        }
    }

    fun triggerDjMixNow() {
        viewModelScope.launch {
            audioPlayer.triggerDjMixNow()
        }
    }

    fun injectDjWildcard(prompt: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDjBusy = true, error = null) }
            try {
                val currentSeed = djTelemetry.value.queue.take(5).ifEmpty {
                    audioTelemetry.value.queue.take(5)
                }
                val existingIds = djTelemetry.value.queue.map { it.id }.toSet()
                var picks: List<EclipseTrack> = emptyList()
                var commentary: String? = null
                var lastFailure: String? = null

                musicClient.startProDjSet(
                    tracks = currentSeed.ifEmpty { null },
                    prompt = prompt ?: "Pick an unexpected crate-digger wildcard track not in this list with infectious energy",
                    includeSurprises = true,
                    surpriseCount = 1,
                ).fold(
                    onSuccess = { result ->
                        val resolved = resolvePlayableDjTracks(result.tracks)
                        commentary = result.commentary
                        picks = resolved.filter { it.djMeta?.isWildcard == true && it.id !in existingIds }
                            .ifEmpty { resolved.filter { it.id !in existingIds }.take(1) }
                    },
                    onFailure = { t ->
                        if (t is CancellationException) throw t
                        lastFailure = t.message
                    },
                )

                if (picks.isEmpty()) {
                    musicClient.startUmbraDj(
                        prompt = prompt ?: "Unexpected crate-digger wildcard track",
                        fresh = true,
                    ).onSuccess { result ->
                        val resolved = resolvePlayableDjTracks(result.tracks)
                        commentary = commentary ?: result.commentary ?: result.message
                        picks = resolved.filter { it.id !in existingIds }.take(1)
                    }
                }

                _uiState.update {
                    it.copy(
                        isDjBusy = false,
                        error = if (picks.isEmpty()) (lastFailure ?: "Could not fetch wildcard surprise") else null,
                    )
                }
                if (picks.isNotEmpty()) {
                    audioPlayer.injectDjSurprises(picks, commentary)
                }
            } catch (t: CancellationException) {
                _uiState.update { it.copy(isDjBusy = false) }
                throw t
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(isDjBusy = false, error = t.message ?: "Could not fetch wildcard surprise")
                }
            }
        }
    }

    fun nextUmbraDj() {
        djJob?.cancel()
        djJob = viewModelScope.launch {
            val sessionId = audioTelemetry.value.djSessionId
            _uiState.update { it.copy(isDjBusy = true, error = null) }
            try {
                musicClient.nextUmbraDj(sessionId = sessionId).fold(
                    onSuccess = { result ->
                        val tracks = result.tracks.filter { it.streamUrl.isNotBlank() }
                        _uiState.update { it.copy(isDjBusy = false) }
                        if (tracks.isNotEmpty()) {
                            audioPlayer.playQueue(
                                tracks = tracks,
                                startIndex = 0,
                                autoplay = true,
                                source = "umbra_dj",
                                djSessionId = result.sessionId ?: sessionId,
                            )
                        }
                    },
                    onFailure = { t ->
                        if (t is CancellationException) throw t
                        _uiState.update {
                            it.copy(isDjBusy = false, error = t.message ?: "Umbra DJ next segment failed")
                        }
                    },
                )
            } catch (_: CancellationException) {
                _uiState.update { it.copy(isDjBusy = false) }
            }
        }
    }

    fun stopUmbraDj() {
        djJob?.cancel()
        djJob = null
        viewModelScope.launch {
            val sessionId = audioTelemetry.value.djSessionId ?: djTelemetry.value.djSessionId
            musicClient.stopUmbraDj(sessionId = sessionId)
            audioPlayer.stopDjMix()
            audioPlayer.control("stop")
            _uiState.update { it.copy(isDjBusy = false, djBannerMessage = null) }
        }
    }

    fun generateAiMix(prompt: String, onStarted: (() -> Unit)? = null) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return
        djJob?.cancel()
        djJob = viewModelScope.launch {
            _uiState.update { it.copy(isDjBusy = true, error = null) }
            try {
                musicClient.generatePlaylist(prompt = trimmed, count = 20, save = true).fold(
                    onSuccess = { tracks ->
                        _uiState.update { it.copy(isDjBusy = false) }
                        val ready = tracks.filter { it.streamUrl.isNotBlank() }
                        if (ready.isNotEmpty()) {
                            audioPlayer.playQueue(
                                tracks = ready,
                                startIndex = 0,
                                autoplay = true,
                                source = "eclipse_ai_mix",
                            )
                            onStarted?.invoke()
                        } else {
                            _uiState.update { it.copy(error = "AI Mix returned no playable tracks") }
                        }
                    },
                    onFailure = { t ->
                        if (t is CancellationException) throw t
                        _uiState.update {
                            it.copy(isDjBusy = false, error = t.message ?: "AI Mix generation failed")
                        }
                    },
                )
            } catch (_: CancellationException) {
                _uiState.update { it.copy(isDjBusy = false) }
            }
        }
    }

    fun playRadio(
        kind: String,
        query: String = "",
        mixIndex: Int = 0,
        onStarted: (() -> Unit)? = null,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isResolvingQueue = true, error = null) }
            musicClient.playRadio(kind = kind, query = query, mixIndex = mixIndex).fold(
                onSuccess = { tracks ->
                    _uiState.update { it.copy(isResolvingQueue = false) }
                    val ready = tracks.filter { it.streamUrl.isNotBlank() }
                    if (ready.isNotEmpty()) {
                        audioPlayer.playQueue(
                            tracks = ready,
                            startIndex = 0,
                            autoplay = true,
                            source = "eclipse_radio",
                        )
                        onStarted?.invoke()
                    } else {
                        _uiState.update { it.copy(error = "Radio mix returned no playable tracks") }
                    }
                },
                onFailure = { t ->
                    _uiState.update {
                        it.copy(isResolvingQueue = false, error = t.message ?: "Radio start failed")
                    }
                },
            )
        }
    }

    /**
     * Parses spoken Shield TV remote commands into playback control, Umbra DJ, AI Mix, or instant Search & Play.
     */
    fun handleVoiceCommand(
        spokenText: String,
        onOpenNowPlaying: () -> Unit,
        onOpenDjMixer: (() -> Unit)? = null,
    ) {
        val raw = spokenText.trim()
        if (raw.isEmpty()) return
        val lower = raw.lowercase()
        _uiState.update { it.copy(lastVoiceStatus = "Heard: \"$raw\"") }

        when {
            lower in setOf("pause", "pause music") -> control("pause")
            lower in setOf("play", "resume", "resume music", "unpause") -> control("play")
            lower in setOf("next", "next track", "next song", "skip") -> control("next")
            lower in setOf("mix now", "beatmix", "transition", "transition now", "crossfade") -> triggerDjMixNow()
            lower.contains("surprise") || lower.contains("wildcard") -> injectDjWildcard(raw)
            lower in setOf("previous", "prev", "previous track", "previous song", "go back") -> control("previous")
            lower in setOf("stop", "stop music", "stop dj", "close player") -> {
                if (audioTelemetry.value.djSessionId != null || djTelemetry.value.isActive) stopUmbraDj() else control("stop")
            }
            lower.startsWith("dj mix") || lower.startsWith("pro dj") || lower.contains("beatmix") -> {
                val prompt = raw.replace(Regex("^(?:pro\\s*dj(?:\\s+mix)?|dj\\s*mix)\\s*", RegexOption.IGNORE_CASE), "").trim()
                startProDjSet(
                    prompt = prompt.ifBlank { null },
                    includeSurprises = true,
                    onStarted = onOpenDjMixer ?: onOpenNowPlaying,
                )
            }
            lower.startsWith("umbra") || lower.startsWith("dj ") || lower == "dj" -> {
                val prompt = raw.replace(Regex("^(?:umbra(?:\\s+ai)?(?:\\s+dj)?|dj)\\s*", RegexOption.IGNORE_CASE), "").trim()
                startProDjSet(
                    prompt = prompt.ifBlank { null },
                    includeSurprises = _uiState.value.includeDjSurprises,
                    onStarted = onOpenDjMixer ?: onOpenNowPlaying,
                )
            }
            lower.startsWith("mix ") || lower.startsWith("ai mix ") || lower.startsWith("make a playlist ") -> {
                val prompt = raw.replace(Regex("^(?:ai\\s+mix|mix|make\\s+a\\s+playlist(?:\\s+of)?)\\s*", RegexOption.IGNORE_CASE), "").trim()
                if (prompt.isNotBlank()) generateAiMix(prompt, onStarted = onOpenNowPlaying) else search(raw)
            }
            lower.startsWith("play ") -> {
                val query = raw.substring(5).trim()
                if (query.isEmpty()) return
                viewModelScope.launch {
                    _uiState.update { it.copy(isSearching = true, searchQuery = query, error = null) }
                    musicClient.search(query, source = "auto", limit = 15).fold(
                        onSuccess = { resp ->
                            _uiState.update { it.copy(isSearching = false, searchResults = resp, error = resp.error) }
                            if (resp.tracks.isNotEmpty()) {
                                playTracks(resp.tracks, startIndex = 0, source = "eclipse_voice", onStarted = onOpenNowPlaying)
                            }
                        },
                        onFailure = { t ->
                            _uiState.update { it.copy(isSearching = false, error = t.message ?: "Voice search failed") }
                        },
                    )
                }
            }
            else -> search(raw)
        }
    }

    fun control(action: String, value: Any? = null) {
        viewModelScope.launch {
            audioPlayer.control(action, value)
        }
    }

    fun jumpTo(index: Int) {
        viewModelScope.launch {
            audioPlayer.jumpTo(index)
        }
    }

    fun removeAt(index: Int) {
        viewModelScope.launch {
            audioPlayer.removeAt(index)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
