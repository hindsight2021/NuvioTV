package com.nuvio.tv.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for debrid expiration and refreshability rules on [CachedStreamLink].
 */
class StreamLinkCacheDebridPolicyTest {

    private val maxAgeMs: Long = CachedStreamLink.DEBRID_CDN_MAX_AGE_MS

    @Test
    fun `isLikelyDebridCdnUrl recognizes known debrid hosts`() {
        val knownUrls = listOf(
            "https://tb-cdn.io/stream/abc.mkv",
            "https://store.torbox.app/stream/abc.mkv",
            "https://torbox.app/stream/abc.mkv",
            "https://premiumize.me/stream/abc.mkv",
            "https://www.premiumize.me/stream/abc.mkv",
            "https://real-debrid.com/d/abc.mkv",
            "https://rdeb.io/d/abc.mkv"
        )
        for (url in knownUrls) {
            assertTrue("Expected debrid CDN host for: $url", CachedStreamLink.isLikelyDebridCdnUrl(url))
        }
    }

    @Test
    fun `isLikelyDebridCdnUrl rejects generic cdn url`() {
        assertFalse(CachedStreamLink.isLikelyDebridCdnUrl("https://cdn.example.com/video.mkv"))
    }

    @Test
    fun `isLikelyDebridCdnUrl rejects blank strings`() {
        assertFalse(CachedStreamLink.isLikelyDebridCdnUrl(""))
        assertFalse(CachedStreamLink.isLikelyDebridCdnUrl("   "))
    }

    @Test
    fun `isDebridUrlExpired false at exact boundary for debrid link flag`() {
        val link = baseLink(url = "https://cdn.example.com/video.mkv", isDebridLink = true)
        assertFalse(link.isDebridUrlExpired(link.cachedAtMs + maxAgeMs))
    }

    @Test
    fun `isDebridUrlExpired true just past boundary for debrid link flag`() {
        val link = baseLink(url = "https://cdn.example.com/video.mkv", isDebridLink = true)
        assertTrue(link.isDebridUrlExpired(link.cachedAtMs + maxAgeMs + 1L))
    }

    @Test
    fun `isDebridUrlExpired false at exact boundary for debrid cdn host`() {
        val link = baseLink(url = "https://tb-cdn.io/stream/abc.mkv", isDebridLink = false)
        assertFalse(link.isDebridUrlExpired(link.cachedAtMs + maxAgeMs))
    }

    @Test
    fun `isDebridUrlExpired true just past boundary for debrid cdn host`() {
        val link = baseLink(url = "https://tb-cdn.io/stream/abc.mkv", isDebridLink = false)
        assertTrue(link.isDebridUrlExpired(link.cachedAtMs + maxAgeMs + 1L))
    }

    @Test
    fun `isDebridUrlExpired false for non debrid cdn url after two hours`() {
        val link = baseLink(url = "https://cdn.example.com/video.mkv", isDebridLink = false)
        assertFalse(link.isDebridUrlExpired(link.cachedAtMs + 2L * 60L * 60L * 1000L))
    }

    @Test
    fun `canRefreshDebridLink false when clientResolveService blank`() {
        val link = baseLink(
            url = "https://tb-cdn.io/stream/abc.mkv",
            isDebridLink = true,
            clientResolveService = "   ",
            infoHash = "abc123"
        )
        assertFalse(link.canRefreshDebridLink())
    }

    @Test
    fun `canRefreshDebridLink false when clientResolveService null`() {
        val link = baseLink(
            url = "https://tb-cdn.io/stream/abc.mkv",
            isDebridLink = true,
            clientResolveService = null,
            infoHash = "abc123"
        )
        assertFalse(link.canRefreshDebridLink())
    }

    @Test
    fun `canRefreshDebridLink false when no refreshable identifier present`() {
        val link = baseLink(
            url = "https://tb-cdn.io/stream/abc.mkv",
            isDebridLink = true,
            clientResolveService = "torbox",
            infoHash = null,
            clientResolveMagnet = null,
            clientResolveTorrentName = null,
            filename = null
        )
        assertFalse(link.canRefreshDebridLink())
    }

    @Test
    fun `canRefreshDebridLink true with infoHash`() {
        val link = baseLink(
            url = "https://tb-cdn.io/stream/abc.mkv",
            isDebridLink = true,
            clientResolveService = "torbox",
            infoHash = "abc123"
        )
        assertTrue(link.canRefreshDebridLink())
    }

    @Test
    fun `canRefreshDebridLink true with clientResolveMagnet`() {
        val link = baseLink(
            url = "https://tb-cdn.io/stream/abc.mkv",
            isDebridLink = true,
            clientResolveService = "torbox",
            clientResolveMagnet = "magnet:?xt=urn:btih:abc123"
        )
        assertTrue(link.canRefreshDebridLink())
    }

    @Test
    fun `canRefreshDebridLink true with clientResolveTorrentName`() {
        val link = baseLink(
            url = "https://tb-cdn.io/stream/abc.mkv",
            isDebridLink = true,
            clientResolveService = "torbox",
            clientResolveTorrentName = "Some.Torrent.Name"
        )
        assertTrue(link.canRefreshDebridLink())
    }

    @Test
    fun `canRefreshDebridLink true with filename`() {
        val link = baseLink(
            url = "https://tb-cdn.io/stream/abc.mkv",
            isDebridLink = true,
            clientResolveService = "torbox",
            filename = "video.mkv"
        )
        assertTrue(link.canRefreshDebridLink())
    }

    private fun baseLink(
        url: String,
        isDebridLink: Boolean,
        cachedAtMs: Long = 1_000_000L,
        clientResolveService: String? = null,
        infoHash: String? = null,
        clientResolveMagnet: String? = null,
        clientResolveTorrentName: String? = null,
        filename: String? = null
    ): CachedStreamLink = CachedStreamLink(
        url = url,
        streamName = "Test Stream",
        headers = emptyMap(),
        cachedAtMs = cachedAtMs,
        filename = filename,
        infoHash = infoHash,
        isDebridLink = isDebridLink,
        clientResolveService = clientResolveService,
        clientResolveMagnet = clientResolveMagnet,
        clientResolveTorrentName = clientResolveTorrentName
    )
}
