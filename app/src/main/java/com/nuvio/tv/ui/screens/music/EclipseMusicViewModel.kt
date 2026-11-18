package com.nuvio.tv.ui.screens.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val isDjBusy: Boolean = false,
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
        _uiState.update { it.copy(isDjBusy = false) }
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
            val sessionId = audioTelemetry.value.djSessionId
            musicClient.stopUmbraDj(sessionId = sessionId)
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
    fun handleVoiceCommand(spokenText: String, onOpenNowPlaying: () -> Unit) {
        val raw = spokenText.trim()
        if (raw.isEmpty()) return
        val lower = raw.lowercase()
        _uiState.update { it.copy(lastVoiceStatus = "Heard: \"$raw\"") }

        when {
            lower in setOf("pause", "pause music") -> control("pause")
            lower in setOf("play", "resume", "resume music", "unpause") -> control("play")
            lower in setOf("next", "next track", "next song", "skip") -> control("next")
            lower in setOf("previous", "prev", "previous track", "previous song", "go back") -> control("previous")
            lower in setOf("stop", "stop music", "stop dj", "close player") -> {
                if (audioTelemetry.value.djSessionId != null) stopUmbraDj() else control("stop")
            }
            lower.startsWith("umbra") || lower.startsWith("dj ") || lower == "dj" -> {
                val prompt = raw.replace(Regex("^(?:umbra(?:\\s+ai)?(?:\\s+dj)?|dj)\\s*", RegexOption.IGNORE_CASE), "").trim()
                startUmbraDj(prompt.ifBlank { null }, onStarted = onOpenNowPlaying)
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
