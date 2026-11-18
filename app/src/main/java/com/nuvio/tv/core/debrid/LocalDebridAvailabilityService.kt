package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDebridAvailabilityService @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val localDebridService: LocalDebridService
) {
    suspend fun markChecking(groups: List<AddonStreams>): List<AddonStreams> {
        val account = cacheCheckAccounts().firstOrNull() ?: return groups
        return groups.updateAvailabilityStatus { stream ->
            if (stream.localAvailabilityHash() == null || stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED) {
                stream
            } else {
                stream.copy(
                    debridCacheStatus = StreamDebridCacheStatus(
                        providerId = account.provider.id,
                        providerName = account.provider.displayName,
                        state = StreamDebridCacheState.CHECKING
                    )
                )
            }
        }
    }

    suspend fun annotateCachedAvailability(groups: List<AddonStreams>): List<AddonStreams> {
        val accounts = cacheCheckAccounts()
        val primaryAccount = accounts.firstOrNull() ?: return groups
        val hashes = groups.flatMap { group ->
            group.streams.mapNotNull { stream ->
                stream.localAvailabilityHash()
                    ?.takeUnless { stream.debridCacheStatus?.state in FINAL_CACHE_STATES }
            }
        }.distinct()
        if (hashes.isEmpty()) return groups

        val cached = mutableMapOf<String, LocalDebridCachedItem>()
        val winningAccounts = mutableMapOf<String, DebridServiceCredential>()
        val remaining = hashes.toMutableSet()
        var anyAccountResponded = false

        for (account in accounts) {
            if (remaining.isEmpty()) break
            val result = localDebridService.checkCached(
                account = account,
                hashes = remaining.toList()
            ) ?: continue
            anyAccountResponded = true
            for ((hash, item) in result) {
                if (hash in remaining) {
                    cached[hash] = item
                    winningAccounts[hash] = account
                    remaining.remove(hash)
                }
            }
        }

        if (!anyAccountResponded) {
            return groups.updateAvailabilityStatus { stream ->
                val hash = stream.localAvailabilityHash()
                if (hash == null || stream.debridCacheStatus?.state in FINAL_CACHE_STATES) {
                    stream
                } else {
                    stream.copy(
                        debridCacheStatus = StreamDebridCacheStatus(
                            providerId = primaryAccount.provider.id,
                            providerName = primaryAccount.provider.displayName,
                            state = StreamDebridCacheState.UNKNOWN
                        )
                    )
                }
            }
        }

        return groups.updateAvailabilityStatus { stream ->
            val hash = stream.localAvailabilityHash() ?: return@updateAvailabilityStatus stream
            if (stream.debridCacheStatus?.state in FINAL_CACHE_STATES) return@updateAvailabilityStatus stream
            val cachedItem = cached[hash]
            val account = winningAccounts[hash] ?: primaryAccount
            stream.copy(
                debridCacheStatus = StreamDebridCacheStatus(
                    providerId = account.provider.id,
                    providerName = account.provider.displayName,
                    state = if (cachedItem == null) StreamDebridCacheState.NOT_CACHED else StreamDebridCacheState.CACHED,
                    cachedName = cachedItem?.name,
                    cachedSize = cachedItem?.size
                )
            )
        }
    }

    suspend fun isCached(hash: String): Boolean? {
        val accounts = cacheCheckAccounts()
        if (accounts.isEmpty()) return null
        var sawFalse = false
        for (account in accounts) {
            when (localDebridService.isCached(account, hash)) {
                true -> return true
                false -> sawFalse = true
                null -> Unit
            }
        }
        return if (sawFalse) false else null
    }

    private suspend fun cacheCheckAccounts(): List<DebridServiceCredential> {
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks) return emptyList()
        return settings.orderedResolverCredentials
            .filter { credential -> credential.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck) }
    }
}

private val FINAL_CACHE_STATES = setOf(
    StreamDebridCacheState.CACHED,
    StreamDebridCacheState.NOT_CACHED
)

fun Stream.localAvailabilityHash(): String? =
    getEffectiveInfoHash()
        ?.trim()
        ?.lowercase()
        ?.takeIf { needsLocalDebridResolve() && it.isNotBlank() }

private fun List<AddonStreams>.updateAvailabilityStatus(
    transform: (Stream) -> Stream
): List<AddonStreams> =
    map { group ->
        var changed = false
        val updatedStreams = group.streams.map { stream ->
            val updated = transform(stream)
            if (updated != stream) changed = true
            updated
        }
        if (changed) group.copy(streams = updatedStreams) else group
    }
