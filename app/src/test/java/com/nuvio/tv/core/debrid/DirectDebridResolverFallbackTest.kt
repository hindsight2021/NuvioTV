package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDebridResolverFallbackTest {

    private val settingsFlow = MutableStateFlow(
        DebridSettings(
            enabled = true,
            torboxApiKey = "tb-key",
            premiumizeApiKey = "pm-key",
            preferredResolverProviderId = DebridProviders.TORBOX_ID,
            fallbackResolverProviderId = DebridProviders.PREMIUMIZE_ID
        )
    )
    private val dataStore = mockk<DebridSettingsDataStore> {
        every { settings } returns settingsFlow
    }
    private val torboxResolver = mockk<TorboxDirectDebridResolver>()
    private val realDebridResolver = mockk<RealDebridDirectDebridResolver>()
    private val premiumizeResolver = mockk<PremiumizeDirectDebridResolver>()
    private val localDebridService = mockk<LocalDebridService> {
        coEvery { isCached(any(), any()) } returns true
    }

    private fun newResolver(): DirectDebridResolver = DirectDebridResolver(
        dataStore = dataStore,
        torboxResolver = torboxResolver,
        realDebridResolver = realDebridResolver,
        premiumizeResolver = premiumizeResolver,
        localDebridService = localDebridService
    )

    private fun torrentStream(
        infoHash: String = "0123456789abcdef0123456789abcdef01234567",
        cacheStatus: StreamDebridCacheStatus? = null
    ): Stream = Stream(
        name = "Torrent",
        title = "Movie 1080p",
        description = null,
        url = null,
        ytId = null,
        infoHash = infoHash,
        fileIdx = 0,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            filename = "Movie.mkv"
        ),
        addonName = "Torrentio",
        addonLogo = null,
        debridCacheStatus = cacheStatus
    )

    private fun directDebridStream(
        service: String = DebridProviders.TORBOX_ID,
        infoHash: String = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
    ): Stream = Stream(
        name = "TB Stream",
        title = "Movie",
        description = "Movie.mkv",
        url = null,
        ytId = null,
        infoHash = infoHash,
        fileIdx = 0,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            filename = "Movie.mkv"
        ),
        addonName = "Addon",
        addonLogo = null,
        clientResolve = StreamClientResolve(
            type = "debrid",
            infoHash = infoHash,
            fileIdx = 0,
            magnetUri = null,
            sources = null,
            torrentName = "Movie",
            filename = "Movie.mkv",
            mediaType = "movie",
            mediaId = "tt1",
            mediaOnlyId = "tt1",
            title = "Movie",
            season = null,
            episode = null,
            service = service,
            serviceIndex = 0,
            serviceExtension = null,
            isCached = true
        )
    )

    @Test
    fun `torrent stream falls back to premiumize when torbox returns error`() = runBlocking {
        val resolver = newResolver()
        val stream = torrentStream()

        coEvery { torboxResolver.resolve(any(), any(), any()) } returns DirectDebridResolveResult.Error
        coEvery { premiumizeResolver.resolve(any(), any(), any()) } returns DirectDebridResolveResult.Success(
            url = "https://premiumize.example/stream.mkv",
            filename = "Movie.mkv",
            videoSize = 1000L
        )

        val result = resolver.resolveToPlayableStream(stream, season = null, episode = null)

        assertTrue(result is DirectDebridPlayableResult.Success)
        val resolved = (result as DirectDebridPlayableResult.Success).stream
        assertEquals("https://premiumize.example/stream.mkv", resolved.url)
        assertEquals(DebridProviders.PREMIUMIZE_ID, resolved.debridCacheStatus?.providerId)
        assertEquals(StreamDebridCacheState.CACHED, resolved.debridCacheStatus?.state)
        coVerify(exactly = 1) { torboxResolver.resolve(any(), any(), any()) }
        coVerify(exactly = 1) { premiumizeResolver.resolve(any(), any(), any()) }
    }

    @Test
    fun `pre-annotated cached fallback tries premiumize first without calling torbox`() = runBlocking {
        val resolver = newResolver()
        val stream = torrentStream(
            cacheStatus = StreamDebridCacheStatus(
                providerId = DebridProviders.PREMIUMIZE_ID,
                providerName = DebridProviders.Premiumize.displayName,
                state = StreamDebridCacheState.CACHED
            )
        )

        coEvery { premiumizeResolver.resolve(any(), any(), any()) } returns DirectDebridResolveResult.Success(
            url = "https://premiumize.example/stream.mkv",
            filename = "Movie.mkv",
            videoSize = 1000L
        )

        val result = resolver.resolveToPlayableStream(stream, season = null, episode = null)

        assertTrue(result is DirectDebridPlayableResult.Success)
        val resolved = (result as DirectDebridPlayableResult.Success).stream
        assertEquals("https://premiumize.example/stream.mkv", resolved.url)
        assertEquals(DebridProviders.PREMIUMIZE_ID, resolved.debridCacheStatus?.providerId)
        coVerify(exactly = 0) { torboxResolver.resolve(any(), any(), any()) }
        coVerify(exactly = 1) { premiumizeResolver.resolve(any(), any(), any()) }
    }

    @Test
    fun `changing primary and fallback order invalidates resolve cache key`() = runBlocking {
        val resolver = newResolver()
        val stream = torrentStream()

        coEvery { torboxResolver.resolve(any(), any(), any()) } returns DirectDebridResolveResult.Success(
            url = "https://torbox.example/stream.mkv",
            filename = "Movie.mkv",
            videoSize = 1000L
        )
        coEvery { premiumizeResolver.resolve(any(), any(), any()) } returns DirectDebridResolveResult.Success(
            url = "https://premiumize.example/stream.mkv",
            filename = "Movie.mkv",
            videoSize = 1000L
        )

        val first = resolver.resolveToPlayableStream(stream, season = null, episode = null) as DirectDebridPlayableResult.Success
        assertEquals("https://torbox.example/stream.mkv", first.stream.url)

        settingsFlow.value = settingsFlow.value.copy(
            preferredResolverProviderId = DebridProviders.PREMIUMIZE_ID,
            fallbackResolverProviderId = DebridProviders.TORBOX_ID
        )

        val second = resolver.resolveToPlayableStream(stream, season = null, episode = null) as DirectDebridPlayableResult.Success
        assertEquals("https://premiumize.example/stream.mkv", second.stream.url)
        coVerify(exactly = 1) { torboxResolver.resolve(any(), any(), any()) }
        coVerify(exactly = 1) { premiumizeResolver.resolve(any(), any(), any()) }
    }

    @Test
    fun `direct clientResolve torbox failure fails over to premiumize torrent resolution`() = runBlocking {
        val resolver = newResolver()
        val stream = directDebridStream(service = DebridProviders.TORBOX_ID)

        coEvery { torboxResolver.resolve(any(), any(), any()) } returns DirectDebridResolveResult.Error
        coEvery { premiumizeResolver.resolve(any(), any(), any()) } returns DirectDebridResolveResult.Success(
            url = "https://premiumize.example/fallback.mkv",
            filename = "Movie.mkv",
            videoSize = 2000L
        )

        val result = resolver.resolveToPlayableStream(stream, season = null, episode = null)

        assertTrue(result is DirectDebridPlayableResult.Success)
        val resolved = (result as DirectDebridPlayableResult.Success).stream
        assertEquals("https://premiumize.example/fallback.mkv", resolved.url)
        assertEquals(DebridProviders.PREMIUMIZE_ID, resolved.clientResolve?.service)
        assertEquals(DebridProviders.PREMIUMIZE_ID, resolved.debridCacheStatus?.providerId)
        coVerify(exactly = 1) { torboxResolver.resolve(any(), any(), any()) }
        coVerify(exactly = 1) { premiumizeResolver.resolve(any(), any(), any()) }
    }
}
