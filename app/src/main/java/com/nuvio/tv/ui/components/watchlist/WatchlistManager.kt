package com.nuvio.tv.ui.components.watchlist

import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.ListMembershipChanges
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchlistManager @Inject constructor(
    private val trackingProviders: TrackingLibraryProviderRegistry?
) {
    suspend fun isTraktConnected(): Boolean {
        return trackingProviders?.provider(TrackingProviderId.TRAKT)?.isAuthenticated?.first() == true
    }

    suspend fun isSimklConnected(): Boolean {
        return trackingProviders?.provider(TrackingProviderId.SIMKL)?.isAuthenticated?.first() == true
    }

    suspend fun isInTraktWatchlist(itemId: String, itemType: String): Boolean {
        val provider = trackingProviders?.provider(TrackingProviderId.TRAKT) ?: return false
        val watchlistKey = provider.tabs.first().firstOrNull { it.type == LibraryListTab.Type.WATCHLIST }?.key ?: return false
        return provider.observeMembership(itemId, itemType).first().contains(watchlistKey)
    }

    suspend fun isInSimklWatchlist(itemId: String, itemType: String): Boolean {
        val provider = trackingProviders?.provider(TrackingProviderId.SIMKL) ?: return false
        val watchlistKey = provider.tabs.first().firstOrNull { it.type == LibraryListTab.Type.WATCHLIST }?.key ?: return false
        return provider.observeMembership(itemId, itemType).first().contains(watchlistKey)
    }

    suspend fun setTraktWatchlist(item: LibraryEntryInput, inWatchlist: Boolean) {
        val provider = trackingProviders?.provider(TrackingProviderId.TRAKT) ?: return
        val watchlistKey = provider.tabs.first().firstOrNull { it.type == LibraryListTab.Type.WATCHLIST }?.key ?: return
        provider.applyMembershipChanges(
            item = item,
            changes = ListMembershipChanges(desiredMembership = mapOf(watchlistKey to inWatchlist)),
            destructiveRemovalConfirmed = true
        )
    }

    suspend fun setSimklWatchlist(item: LibraryEntryInput, inWatchlist: Boolean) {
        val provider = trackingProviders?.provider(TrackingProviderId.SIMKL) ?: return
        val watchlistKey = provider.tabs.first().firstOrNull { it.type == LibraryListTab.Type.WATCHLIST }?.key ?: return
        provider.applyMembershipChanges(
            item = item,
            changes = ListMembershipChanges(desiredMembership = mapOf(watchlistKey to inWatchlist)),
            destructiveRemovalConfirmed = true
        )
    }

    suspend fun setBothWatchlists(item: LibraryEntryInput, inWatchlist: Boolean) {
        setTraktWatchlist(item, inWatchlist)
        setSimklWatchlist(item, inWatchlist)
    }
}
