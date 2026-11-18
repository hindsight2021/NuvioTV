package com.nuvio.tv.core.player

import android.app.ActivityManager
import android.content.Context
import androidx.media3.datasource.DataSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [NextEpisodePreWarmer].
 *
 * Covers URL pre-warmability filtering, RAM-adaptive byte budget selection,
 * and early abort/skip handling on the opening-segment pre-warm entrypoint.
 */
class NextEpisodePreWarmerTest {

    private lateinit var context: Context
    private lateinit var activityManager: ActivityManager
    private lateinit var upstreamFactory: DataSource.Factory

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        activityManager = mockk(relaxed = true)
        upstreamFactory = mockk(relaxed = true)
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns activityManager
        NextEpisodePreWarmer.resetStatus()
    }

    @After
    fun tearDown() {
        NextEpisodePreWarmer.resetStatus()
    }

    @Test
    fun `isPreWarmableUrl accepts progressive http and https URLs`() {
        assertTrue(NextEpisodePreWarmer.isPreWarmableUrl("https://cdn.example.com/video.mp4"))
        assertTrue(NextEpisodePreWarmer.isPreWarmableUrl("http://cdn.example.com/movie.mkv"))
    }

    @Test
    fun `isPreWarmableUrl rejects blank, manifests, magnet, and loopback URLs`() {
        assertFalse(NextEpisodePreWarmer.isPreWarmableUrl(""))
        assertFalse(NextEpisodePreWarmer.isPreWarmableUrl("   "))
        assertFalse(NextEpisodePreWarmer.isPreWarmableUrl("magnet:?xt=urn:btih:0123456789abcdef"))
        assertFalse(NextEpisodePreWarmer.isPreWarmableUrl("https://cdn.example.com/stream.m3u8"))
        assertFalse(NextEpisodePreWarmer.isPreWarmableUrl("https://cdn.example.com/stream.mpd"))
        assertFalse(NextEpisodePreWarmer.isPreWarmableUrl("https://cdn.example.com/stream.ism"))
        assertFalse(NextEpisodePreWarmer.isPreWarmableUrl("http://localhost:8080/video.mp4"))
        assertFalse(NextEpisodePreWarmer.isPreWarmableUrl("http://127.0.0.1:8080/video.mp4"))
    }

    @Test
    fun `computeTargetPreWarmBytes scales down to 2MB on low-RAM or 256MB devices`() {
        every { activityManager.isLowRamDevice } returns true
        every { activityManager.memoryClass } returns 512
        assertEquals(2L * 1024L * 1024L, NextEpisodePreWarmer.computeTargetPreWarmBytes(context))

        every { activityManager.isLowRamDevice } returns false
        every { activityManager.memoryClass } returns 256
        assertEquals(2L * 1024L * 1024L, NextEpisodePreWarmer.computeTargetPreWarmBytes(context))
    }

    @Test
    fun `computeTargetPreWarmBytes uses 4MB on higher-memory devices`() {
        every { activityManager.isLowRamDevice } returns false
        every { activityManager.memoryClass } returns 512

        assertEquals(4L * 1024L * 1024L, NextEpisodePreWarmer.computeTargetPreWarmBytes(context))
    }

    @Test
    fun `preWarmOpeningSegment aborts immediately when shouldAbort returns true`() = runTest {
        val result = NextEpisodePreWarmer.preWarmOpeningSegment(
            context = context,
            targetKey = "tt11280740:1:2",
            targetLabel = "S1E2",
            url = "https://cdn.example.com/video.mp4",
            upstreamFactory = upstreamFactory,
            shouldAbort = { true }
        )

        assertFalse(result.success)
        assertEquals("aborted_before_start", result.reason)
        assertEquals(PreWarmState.ABORTED, NextEpisodePreWarmer.status.value.state)
    }

    @Test
    fun `preWarmOpeningSegment skips HLS manifest URLs without touching cache`() = runTest {
        val result = NextEpisodePreWarmer.preWarmOpeningSegment(
            context = context,
            targetKey = "tt11280740:1:2",
            targetLabel = "S1E2",
            url = "https://cdn.example.com/master.m3u8",
            upstreamFactory = upstreamFactory,
            shouldAbort = { false }
        )

        assertFalse(result.success)
        assertEquals("not_prewarmable_url", result.reason)
        assertEquals(PreWarmState.SKIPPED, NextEpisodePreWarmer.status.value.state)
    }
}
