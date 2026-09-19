package com.nuvio.tv.domain.repository

import com.nuvio.tv.data.local.LocalMediaSettings
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.LocalMediaItem
import com.nuvio.tv.domain.model.LocalMediaScanSummary
import com.nuvio.tv.domain.model.LocalSeriesSummary
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.flow.Flow

interface LocalMediaRepository {
    val settings: Flow<LocalMediaSettings>

    suspend fun setEnabled(enabled: Boolean)
    suspend fun setScanPaths(paths: List<String>)
    suspend fun addScanPath(path: String)
    suspend fun removeScanPath(path: String)
    suspend fun clearCache()

    suspend fun scanNow(customPaths: List<String>? = null): LocalMediaScanSummary

    fun getLocalMovies(): Flow<List<LocalMediaItem>>
    fun getLocalSeries(): Flow<List<LocalSeriesSummary>>

    suspend fun findMatchingStreams(
        type: String,
        title: String?,
        season: Int?,
        episode: Int?,
        videoId: String? = null
    ): List<Stream>

    suspend fun searchLocal(query: String): List<LocalMediaItem>
    suspend fun getLocalCatalogRows(): List<CatalogRow>
    suspend fun getLocalMeta(id: String, type: String): Meta?
}
