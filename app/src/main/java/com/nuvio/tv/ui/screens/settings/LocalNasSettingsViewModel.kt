package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.localmedia.LocalMediaScanner
import com.nuvio.tv.domain.model.LocalMediaItem
import com.nuvio.tv.domain.model.LocalMediaScanSummary
import com.nuvio.tv.domain.repository.LocalMediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class LocalNasSettingsUiState(
    val isEnabled: Boolean = true,
    val isScanning: Boolean = false,
    val detectedPaths: List<String> = emptyList(),
    val configuredPaths: List<String> = emptyList(),
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val totalItems: Int = 0,
    val lastScanSummary: String? = null,
    val lastScanTimeFormatted: String? = null,
    val statusMessage: String? = null
)

@HiltViewModel
class LocalNasSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMediaRepository: LocalMediaRepository,
    private val scanner: LocalMediaScanner
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocalNasSettingsUiState())
    val uiState: StateFlow<LocalNasSettingsUiState> = _uiState.asStateFlow()

    init {
        observeSettings()
        refreshDetectedPaths()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            localMediaRepository.settings.collect { settings ->
                val movies = settings.cachedItems.count { it.type == LocalMediaItem.TYPE_MOVIE }
                val episodes = settings.cachedItems.count { it.type == LocalMediaItem.TYPE_SERIES }
                val dateStr = if (settings.lastScanTimestamp > 0L) {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(settings.lastScanTimestamp))
                } else null

                _uiState.update { current ->
                    current.copy(
                        isEnabled = settings.enabled,
                        configuredPaths = settings.scanPaths,
                        movieCount = movies,
                        seriesCount = episodes,
                        totalItems = settings.cachedItems.size,
                        lastScanTimeFormatted = dateStr,
                        lastScanSummary = if (settings.cachedItems.isNotEmpty()) {
                            "$movies movies, $episodes episodes discovered"
                        } else null
                    )
                }
            }
        }
    }

    fun toggleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            localMediaRepository.setEnabled(enabled)
        }
    }

    fun scanNow() {
        if (_uiState.value.isScanning) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isScanning = true, statusMessage = "Scanning storage mounts...") }
            try {
                val summary = localMediaRepository.scanNow()
                val summaryText = "Scan completed in ${summary.displayScanDuration}: ${summary.totalMovies} movies, ${summary.totalEpisodes} episodes across ${summary.scannedPaths.size} paths"
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        statusMessage = summaryText
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        statusMessage = "Scan failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            localMediaRepository.clearCache()
            _uiState.update {
                it.copy(statusMessage = "Cache cleared")
            }
        }
    }

    fun refreshDetectedPaths() {
        viewModelScope.launch(Dispatchers.IO) {
            val roots = scanner.detectStorageRoots().map { it.absolutePath }
            _uiState.update { it.copy(detectedPaths = roots) }
        }
    }
}
