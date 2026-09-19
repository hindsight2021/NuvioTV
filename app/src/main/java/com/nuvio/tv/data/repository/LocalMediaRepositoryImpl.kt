package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.localmedia.LocalMediaScanner
import com.nuvio.tv.data.local.LocalMediaSettings
import com.nuvio.tv.data.local.LocalMediaSettingsDataStore
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LocalMediaItem
import com.nuvio.tv.domain.model.LocalMediaScanSummary
import com.nuvio.tv.domain.model.LocalSeriesSummary
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.repository.LocalMediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMediaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: LocalMediaScanner,
    private val dataStore: LocalMediaSettingsDataStore
) : LocalMediaRepository {

    companion object {
        private const val TAG = "LocalMediaRepo"
        const val ADDON_ID_LOCAL_NAS = "local_nas"
        const val ADDON_NAME_LOCAL_NAS = "Local NAS"

        private val DIACRITICS_REGEX = Regex("\\p{Mn}+")
        private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val RELEASE_TAG_REGEX = Regex(
            "\\b(" +
                "19\\d{2}|20\\d{2}|" +
                "480p|576p|720p|1080p|1440p|2160p|4k|8k|" +
                "x264|x265|h264|h265|hevc|avc|av1|" +
                "aac|ac3|eac3|dts|dtshd|truehd|atmos|flac|mp3|" +
                "webrip|web-dl|webdl|bluray|blu-ray|brrip|bdrip|" +
                "hdtv|dvdrip|dvdscr|hdrip|cam|ts|remux|" +
                "proper|repack|extended|unrated|remastered|" +
                "multi|dual|dubbed|subbed|subs" +
                ")\\b"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _cachedItems = MutableStateFlow<List<LocalMediaItem>>(emptyList())

    override val settings: Flow<LocalMediaSettings> = dataStore.settings

    init {
        scope.launch {
            dataStore.settings.collect { s ->
                if (_cachedItems.value.isEmpty() && s.cachedItems.isNotEmpty()) {
                    _cachedItems.value = s.cachedItems
                }
            }
        }
    }

    override suspend fun setEnabled(enabled: Boolean) = dataStore.setEnabled(enabled)

    override suspend fun setScanPaths(paths: List<String>) = dataStore.setScanPaths(paths)

    override suspend fun addScanPath(path: String) = dataStore.addScanPath(path)

    override suspend fun removeScanPath(path: String) = dataStore.removeScanPath(path)

    override suspend fun clearCache() {
        _cachedItems.value = emptyList()
        dataStore.clearCache()
    }

    override suspend fun scanNow(customPaths: List<String>?): LocalMediaScanSummary {
        val currentSettings = dataStore.settings.first()
        val configuredPaths = customPaths ?: currentSettings.scanPaths.ifEmpty {
            scanner.detectStorageRoots().map { it.absolutePath }
        }

        Log.i(TAG, "Starting local media scan on: $configuredPaths")
        val (discoveredItems, summary) = scanner.scan(configuredPaths)

        _cachedItems.value = discoveredItems
        dataStore.saveScanResult(discoveredItems, summary.timestamp)
        if (currentSettings.scanPaths.isEmpty() && summary.scannedPaths.isNotEmpty()) {
            dataStore.setScanPaths(summary.scannedPaths)
        }

        return summary
    }

    override fun getLocalMovies(): Flow<List<LocalMediaItem>> =
        _cachedItems.asStateFlow().map { items ->
            items.filter { it.type == LocalMediaItem.TYPE_MOVIE }
                .sortedBy { it.title.lowercase(Locale.ROOT) }
        }

    override fun getLocalSeries(): Flow<List<LocalSeriesSummary>> =
        _cachedItems.asStateFlow().map { items ->
            items.filter { it.type == LocalMediaItem.TYPE_SERIES }
                .groupBy { it.title.lowercase(Locale.ROOT) }
                .map { (_, epList) ->
                    val first = epList.first()
                    LocalSeriesSummary(
                        title = first.title,
                        year = epList.mapNotNull { it.year }.firstOrNull(),
                        totalSeasons = epList.mapNotNull { it.season }.distinct().size,
                        totalEpisodes = epList.size,
                        episodes = epList.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
                    )
                }
                .sortedBy { it.title.lowercase(Locale.ROOT) }
        }

    override suspend fun findMatchingStreams(
        type: String,
        title: String?,
        season: Int?,
        episode: Int?,
        videoId: String?
    ): List<Stream> {
        val currentSettings = dataStore.settings.first()
        if (!currentSettings.enabled) return emptyList()

        val items = _cachedItems.value.ifEmpty {
            currentSettings.cachedItems
        }

        // Direct match by videoId when it is a local id.
        if (!videoId.isNullOrBlank() && videoId.startsWith("local:")) {
            val directMatches = items.filter { it.id == videoId }
            if (directMatches.isNotEmpty()) {
                return directMatches.map { it.toStream() }
            }

            // Fallback: match by prefix (series id) plus season/episode.
            val prefixMatches = items.filter { item ->
                videoId.startsWith(item.id) &&
                    (season == null || item.season == season) &&
                    (episode == null || item.episode == episode)
            }
            if (prefixMatches.isNotEmpty()) {
                return prefixMatches.map { it.toStream() }
            }
        }

        if (title.isNullOrBlank()) return emptyList()

        val normalizedTarget = normalizeTitle(title)
        if (normalizedTarget.isBlank()) return emptyList()

        val items = _cachedItems.value.ifEmpty {
            currentSettings.cachedItems
        }

        val normalizedType = type.trim().lowercase(Locale.ROOT)

        return when {
            normalizedType == "movie" || normalizedType == "film" -> {
                items.filter { it.type == LocalMediaItem.TYPE_MOVIE && titlesMatch(it.title, normalizedTarget) }
                    .map { it.toStream() }
            }
            normalizedType == "series" || normalizedType == "tv" -> {
                items.filter {
                    it.type == LocalMediaItem.TYPE_SERIES &&
                        titlesMatch(it.title, normalizedTarget) &&
                        (season == null || it.season == season) &&
                        (episode == null || it.episode == episode)
                }.map { it.toStream() }
            }
            else -> emptyList()
        }
    }

    override suspend fun searchLocal(query: String): List<LocalMediaItem> {
        val normalizedQuery = normalizeTitle(query)
        if (normalizedQuery.isBlank()) return emptyList()

        val items = _cachedItems.value.ifEmpty { dataStore.settings.first().cachedItems }
        return items.filter { item ->
            val nTitle = normalizeTitle(item.title)
            nTitle.contains(normalizedQuery) || normalizedQuery.contains(nTitle)
        }
    }

    override suspend fun getLocalCatalogRows(): List<CatalogRow> {
        val currentSettings = dataStore.settings.first()
        if (!currentSettings.enabled) return emptyList()

        val items = _cachedItems.value.ifEmpty { currentSettings.cachedItems }
        if (items.isEmpty()) return emptyList()

        val rows = mutableListOf<CatalogRow>()

        val movies = items.filter { it.type == LocalMediaItem.TYPE_MOVIE }
            .distinctBy { it.title.lowercase(Locale.ROOT) }
            .sortedBy { it.title.lowercase(Locale.ROOT) }

        if (movies.isNotEmpty()) {
            rows += CatalogRow(
                catalogId = "local_nas_movies",
                addonId = ADDON_ID_LOCAL_NAS,
                addonName = ADDON_NAME_LOCAL_NAS,
                catalogName = "NAS Movies",
                type = "movie",
                items = movies.map { it.toMetaPreview() }
            )
        }

        val seriesSummaries = items.filter { it.type == LocalMediaItem.TYPE_SERIES }
            .groupBy { it.title.lowercase(Locale.ROOT) }
            .map { (_, epList) ->
                val first = epList.first()
                LocalSeriesSummary(
                    title = first.title,
                    year = epList.mapNotNull { it.year }.firstOrNull(),
                    totalSeasons = epList.mapNotNull { it.season }.distinct().size,
                    totalEpisodes = epList.size,
                    episodes = epList.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
                )
            }
            .sortedBy { it.title.lowercase(Locale.ROOT) }

        if (seriesSummaries.isNotEmpty()) {
            rows += CatalogRow(
                catalogId = "local_nas_series",
                addonId = ADDON_ID_LOCAL_NAS,
                addonName = ADDON_NAME_LOCAL_NAS,
                catalogName = "NAS TV Shows",
                type = "series",
                items = seriesSummaries.map { it.toMetaPreview() }
            )
        }

        return rows
    }

    override suspend fun getLocalMeta(id: String, type: String): Meta? {
        val items = _cachedItems.value.ifEmpty { dataStore.settings.first().cachedItems }
        val item = items.firstOrNull { it.id == id } ?: return null

        return if (item.type == LocalMediaItem.TYPE_SERIES) {
            val seriesEpisodes = items.filter {
                it.type == LocalMediaItem.TYPE_SERIES &&
                    it.title.equals(item.title, ignoreCase = true)
            }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            Meta(
                id = item.id,
                type = ContentType.SERIES,
                name = item.title,
                poster = null,
                posterShape = PosterShape.POSTER,
                background = null,
                logo = null,
                description = "Locally stored TV show with ${seriesEpisodes.size} episodes available on Shield storage.",
                releaseInfo = item.year?.toString(),
                imdbRating = null,
                genres = listOf("Local NAS", item.displayQuality),
                runtime = null,
                director = emptyList(),
                cast = emptyList(),
                videos = seriesEpisodes.map { ep ->
                    Video(
                        id = "${item.id}:${ep.season}:${ep.episode}",
                        title = ep.episodeTitle ?: "Episode ${ep.episode}",
                        season = ep.season ?: 1,
                        episode = ep.episode ?: 1
                    )
                },
                country = null,
                awards = null,
                language = null,
                links = emptyList()
            )
        } else {
            Meta(
                id = item.id,
                type = ContentType.MOVIE,
                name = item.title,
                poster = null,
                posterShape = PosterShape.POSTER,
                background = null,
                logo = null,
                description = "Locally stored movie (${item.displayQuality}, ${item.displayFileSize}) at ${item.filePath}",
                releaseInfo = item.year?.toString(),
                imdbRating = null,
                genres = listOf("Local NAS", item.displayQuality),
                runtime = null,
                director = emptyList(),
                cast = emptyList(),
                videos = emptyList(),
                country = null,
                awards = null,
                language = null,
                links = emptyList()
            )
        }
    }

    private fun titlesMatch(localTitle: String, normalizedTarget: String): Boolean {
        val nLocal = normalizeTitle(localTitle)
        if (nLocal == normalizedTarget) return true
        if (nLocal.contains(normalizedTarget) || normalizedTarget.contains(nLocal)) {
            val shorterLen = minOf(nLocal.length, normalizedTarget.length)
            if (shorterLen >= 4) return true
        }
        return false
    }

    private fun normalizeTitle(raw: String): String {
        if (raw.isBlank()) return ""
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        val withoutDiacritics = decomposed.replace(DIACRITICS_REGEX, "")
        val lowered = withoutDiacritics.lowercase(Locale.ROOT)
        return lowered
            .replace(RELEASE_TAG_REGEX, " ")
            .replace(NON_ALNUM_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun LocalMediaItem.toStream(): Stream {
        val qualityTag = if (resolution.contains("4k", ignoreCase = true) || qualityValue >= 2160) "4K" else "1080p"
        val epSuffix = if (isEpisode) " S${season}E${episode}" else ""
        return Stream(
            name = "⚡ Local NAS Direct Play",
            title = "$title$epSuffix [$qualityTag]",
            description = "$filePath • $displayFileSize • Direct File Playback (No Debrid)",
            url = fileUri,
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = ADDON_NAME_LOCAL_NAS,
            addonLogo = null,
            quality = qualityTag,
            qualityValue = qualityValue
        )
    }
}
