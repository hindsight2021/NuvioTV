package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Meta
import kotlinx.coroutines.flow.Flow

interface MetaRepository {
    fun getMeta(
        addonBaseUrl: String,
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>>
    
    fun getMetaFromAllAddons(
        type: String,
        id: String,
        sourceAddonBaseUrl: String? = null
    ): Flow<NetworkResult<Meta>>

    fun getMetaFromPrimaryAddon(
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>>

    suspend fun getCandidateMetaAddons(
        type: String,
        id: String
    ): List<Pair<com.nuvio.tv.domain.model.Addon, String>> = emptyList()

    /**
     * Returns cached meta if available (no network call). Useful for
     * reading backdrop URLs synchronously before navigation.
     */
    fun getCachedMeta(type: String, id: String): Meta?
    
    fun clearCache()

    fun clearCacheForId(id: String)
}
