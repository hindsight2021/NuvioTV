package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [StartupErrorFailoverPolicy].
 */
class StartupErrorFailoverPolicyTest {

    private fun stream(
        name: String,
        url: String?,
        infoHash: String? = null,
        fileIdx: Int? = null,
        clientResolve: StreamClientResolve? = null
    ): Stream = Stream(
        name = name,
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = fileIdx,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Test",
        addonLogo = null,
        clientResolve = clientResolve
    )

    private fun clientResolveWithHash(infoHash: String) = StreamClientResolve(
        type = "torrent",
        infoHash = infoHash,
        fileIdx = 1,
        magnetUri = null,
        sources = null,
        torrentName = "Test.Torrent",
        filename = "ep.mkv",
        mediaType = "series",
        mediaId = "tt1234567:1:1",
        mediaOnlyId = "tt1234567",
        title = "Test",
        season = 1,
        episode = 1,
        service = "torbox",
        serviceIndex = 0,
        serviceExtension = null,
        isCached = true
    )

    private fun playbackExceptionWithCause(cause: Throwable): PlaybackException {
        val exception = mockk<PlaybackException>(relaxed = true)
        every { exception.cause } returns cause
        return exception
    }

    private fun invalidResponseCodeException(responseCode: Int): HttpDataSource.InvalidResponseCodeException {
        val exception = mockk<HttpDataSource.InvalidResponseCodeException>(relaxed = true)
        HttpDataSource.InvalidResponseCodeException::class.java
            .getField("responseCode")
            .apply { isAccessible = true }
            .setInt(exception, responseCode)
        return exception
    }

    @Test
    fun `extractHttpStatusCode parses HTTP prefix pattern`() {
        assertEquals(404, StartupErrorFailoverPolicy.extractHttpStatusCode("HTTP 404"))
        assertEquals(403, StartupErrorFailoverPolicy.extractHttpStatusCode("error: HTTP 403 occurred"))
    }

    @Test
    fun `extractHttpStatusCode parses Response code pattern`() {
        assertEquals(403, StartupErrorFailoverPolicy.extractHttpStatusCode("Response code: 403"))
        assertEquals(500, StartupErrorFailoverPolicy.extractHttpStatusCode("Response code: 500"))
    }

    @Test
    fun `extractHttpStatusCode parses status equals pattern`() {
        assertEquals(401, StartupErrorFailoverPolicy.extractHttpStatusCode("status=401"))
        assertEquals(410, StartupErrorFailoverPolicy.extractHttpStatusCode("status=410"))
    }

    @Test
    fun `extractHttpStatusCode parses status space pattern`() {
        assertEquals(410, StartupErrorFailoverPolicy.extractHttpStatusCode("status 410"))
        assertEquals(404, StartupErrorFailoverPolicy.extractHttpStatusCode("status 404"))
    }

    @Test
    fun `extractHttpStatusCode returns null for blank or unmatched strings`() {
        assertNull(StartupErrorFailoverPolicy.extractHttpStatusCode(""))
        assertNull(StartupErrorFailoverPolicy.extractHttpStatusCode("   "))
        assertNull(StartupErrorFailoverPolicy.extractHttpStatusCode("some random error"))
    }

    @Test
    fun `isNonRetryableHttpStatusCode returns true for non-retryable codes`() {
        assertTrue(StartupErrorFailoverPolicy.isNonRetryableHttpStatusCode(400))
        assertTrue(StartupErrorFailoverPolicy.isNonRetryableHttpStatusCode(401))
        assertTrue(StartupErrorFailoverPolicy.isNonRetryableHttpStatusCode(403))
        assertTrue(StartupErrorFailoverPolicy.isNonRetryableHttpStatusCode(404))
        assertTrue(StartupErrorFailoverPolicy.isNonRetryableHttpStatusCode(410))
    }

    @Test
    fun `isNonRetryableHttpStatusCode returns false for retryable or null codes`() {
        assertFalse(StartupErrorFailoverPolicy.isNonRetryableHttpStatusCode(null))
        assertFalse(StartupErrorFailoverPolicy.isNonRetryableHttpStatusCode(500))
        assertFalse(StartupErrorFailoverPolicy.isNonRetryableHttpStatusCode(502))
        assertFalse(StartupErrorFailoverPolicy.isNonRetryableHttpStatusCode(503))
        assertFalse(StartupErrorFailoverPolicy.isNonRetryableHttpStatusCode(200))
    }

    @Test
    fun `isNonRetryableHttpError detects InvalidResponseCodeException in cause chain`() {
        val cause = invalidResponseCodeException(404)
        val error = playbackExceptionWithCause(cause)
        assertTrue(StartupErrorFailoverPolicy.isNonRetryableHttpError(error, ""))
    }

    @Test
    fun `isNonRetryableHttpError detects nested InvalidResponseCodeException in cause chain`() {
        val cause = invalidResponseCodeException(403)
        val wrapped = IOException("wrapped", cause)
        val error = playbackExceptionWithCause(wrapped)
        assertTrue(StartupErrorFailoverPolicy.isNonRetryableHttpError(error, ""))
    }

    @Test
    fun `isNonRetryableHttpError returns false for retryable InvalidResponseCodeException`() {
        val cause = invalidResponseCodeException(503)
        val error = playbackExceptionWithCause(cause)
        assertFalse(StartupErrorFailoverPolicy.isNonRetryableHttpError(error, ""))
    }

    @Test
    fun `isNonRetryableHttpError detects detailedError patterns`() {
        assertTrue(StartupErrorFailoverPolicy.isNonRetryableHttpError(null, "HTTP 404"))
        assertTrue(StartupErrorFailoverPolicy.isNonRetryableHttpError(null, "Response code: 403"))
        assertTrue(StartupErrorFailoverPolicy.isNonRetryableHttpError(null, "status=401"))
        assertTrue(StartupErrorFailoverPolicy.isNonRetryableHttpError(null, "status 410"))
    }

    @Test
    fun `isNonRetryableHttpError returns false for retryable detailedError`() {
        assertFalse(StartupErrorFailoverPolicy.isNonRetryableHttpError(null, "HTTP 503"))
        assertFalse(StartupErrorFailoverPolicy.isNonRetryableHttpError(null, "some random error"))
    }

    @Test
    fun `shouldAllowStartupEngineFailover returns false for non-retryable cause chain`() {
        val cause = invalidResponseCodeException(404)
        val error = playbackExceptionWithCause(cause)
        assertFalse(StartupErrorFailoverPolicy.shouldAllowStartupEngineFailover(error, ""))
    }

    @Test
    fun `shouldAllowStartupEngineFailover returns false for non-retryable detailedError`() {
        assertFalse(StartupErrorFailoverPolicy.shouldAllowStartupEngineFailover(null, "HTTP 403"))
    }

    @Test
    fun `shouldAllowStartupEngineFailover returns true for retryable HTTP status`() {
        assertTrue(StartupErrorFailoverPolicy.shouldAllowStartupEngineFailover(null, "HTTP 503"))
    }

    @Test
    fun `shouldAllowStartupEngineFailover returns true for generic IOException`() {
        val error = playbackExceptionWithCause(IOException("network down"))
        assertTrue(StartupErrorFailoverPolicy.shouldAllowStartupEngineFailover(error, ""))
    }

    @Test
    fun `shouldAllowStartupEngineFailover returns true for null error and blank detail`() {
        assertTrue(StartupErrorFailoverPolicy.shouldAllowStartupEngineFailover(null, ""))
    }

    @Test
    fun `selectNextLiveCandidate advances to next candidate`() {
        val streams = listOf(
            stream("A", "http://a"),
            stream("B", "http://b"),
            stream("C", "http://c")
        )
        val next = StartupErrorFailoverPolicy.selectNextLiveCandidate(
            streams = streams,
            failedUrls = emptySet(),
            failedHashes = emptySet(),
            currentUrl = "http://a",
            currentHash = null,
            currentName = "A"
        )
        assertEquals("http://b", next?.url)
    }

    @Test
    fun `selectNextLiveCandidate wraps around past currentIndex`() {
        val streams = listOf(
            stream("A", "http://a"),
            stream("B", "http://b"),
            stream("C", "http://c")
        )
        val next = StartupErrorFailoverPolicy.selectNextLiveCandidate(
            streams = streams,
            failedUrls = emptySet(),
            failedHashes = emptySet(),
            currentUrl = "http://c",
            currentHash = null,
            currentName = "C"
        )
        assertEquals("http://a", next?.url)
    }

    @Test
    fun `selectNextLiveCandidate skips failedUrls`() {
        val streams = listOf(
            stream("A", "http://a"),
            stream("B", "http://b"),
            stream("C", "http://c")
        )
        val next = StartupErrorFailoverPolicy.selectNextLiveCandidate(
            streams = streams,
            failedUrls = setOf("http://b"),
            failedHashes = emptySet(),
            currentUrl = "http://a",
            currentHash = null,
            currentName = "A"
        )
        assertEquals("http://c", next?.url)
    }

    @Test
    fun `selectNextLiveCandidate skips failedHashes case-insensitively`() {
        val streams = listOf(
            stream("A", "http://a", infoHash = "HASH_A"),
            stream("B", "http://b", infoHash = "HASH_B"),
            stream("C", "http://c", infoHash = "HASH_C")
        )
        val next = StartupErrorFailoverPolicy.selectNextLiveCandidate(
            streams = streams,
            failedUrls = emptySet(),
            failedHashes = setOf("hash_b"),
            currentUrl = "http://a",
            currentHash = "HASH_A",
            currentName = "A"
        )
        assertEquals("http://c", next?.url)
    }

    @Test
    fun `selectNextLiveCandidate skips candidate whose clientResolve infoHash matches failedHashes`() {
        val streams = listOf(
            stream("A", "http://a", infoHash = "HASH_A"),
            stream("B", null, clientResolve = clientResolveWithHash("HASH_B")),
            stream("C", "http://c", infoHash = "HASH_C")
        )
        val next = StartupErrorFailoverPolicy.selectNextLiveCandidate(
            streams = streams,
            failedUrls = emptySet(),
            failedHashes = setOf("hash_b"),
            currentUrl = "http://a",
            currentHash = "HASH_A",
            currentName = "A"
        )
        assertEquals("http://c", next?.url)
    }

    @Test
    fun `selectNextLiveCandidate returns null when all candidates failed`() {
        val streams = listOf(
            stream("A", "http://a"),
            stream("B", "http://b"),
            stream("C", "http://c")
        )
        val next = StartupErrorFailoverPolicy.selectNextLiveCandidate(
            streams = streams,
            failedUrls = setOf("http://a", "http://b", "http://c"),
            failedHashes = emptySet(),
            currentUrl = "http://a",
            currentHash = null,
            currentName = "A"
        )
        assertNull(next)
    }

    @Test
    fun `selectNextLiveCandidate returns null for empty stream list`() {
        val next = StartupErrorFailoverPolicy.selectNextLiveCandidate(
            streams = emptyList(),
            failedUrls = emptySet(),
            failedHashes = emptySet(),
            currentUrl = "http://a",
            currentHash = null,
            currentName = "A"
        )
        assertNull(next)
    }
}
