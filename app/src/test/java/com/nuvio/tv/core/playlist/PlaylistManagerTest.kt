package com.nuvio.tv.core.playlist

import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PlaylistManager].
 *
 * Verifies channel activation logic, membership checks, and clearing behavior
 * across the different [ChannelMode] states.
 */
class PlaylistManagerTest {

    private val contentId = "show-1"
    private val seriesTitle = "Test Show"

    private lateinit var episodes: List<Video>

    @Before
    fun setUp() {
        PlaylistManager.clear()

        episodes = listOf(
            video(id = "ep-1", season = 1, episode = 1),
            video(id = "ep-2", season = 1, episode = 2),
            video(id = "ep-3", season = 1, episode = 3)
        )
    }

    @Test
    fun `isChannelActiveFor returns false when channel mode is NONE`() {
        assertFalse(PlaylistManager.isChannelActiveFor(contentId, "ep-1"))
    }

    @Test
    fun `isChannelActiveFor returns false when queue is empty`() {
        PlaylistManager.startChannel(
            contentId = contentId,
            seriesTitle = seriesTitle,
            episodes = emptyList()
        )

        assertFalse(PlaylistManager.isChannelActiveFor(contentId, "ep-1"))
    }

    @Test
    fun `isChannelActiveFor returns true for matching contentId`() {
        PlaylistManager.startChannel(
            contentId = contentId,
            seriesTitle = seriesTitle,
            episodes = episodes
        )

        assertTrue(PlaylistManager.isChannelActiveFor(contentId, null))
    }

    @Test
    fun `isChannelActiveFor returns true for matching videoId`() {
        PlaylistManager.startChannel(
            contentId = contentId,
            seriesTitle = seriesTitle,
            episodes = episodes
        )

        assertTrue(PlaylistManager.isChannelActiveFor(null, "ep-2"))
    }

    @Test
    fun `isChannelActiveFor returns false for different show even with same season episode numbers`() {
        PlaylistManager.startChannel(
            contentId = contentId,
            seriesTitle = seriesTitle,
            episodes = episodes
        )

        assertFalse(PlaylistManager.isChannelActiveFor("show-2", "ep-2-diff"))
        assertFalse(PlaylistManager.isChannelActiveFor("show-2", null))
    }

    @Test
    fun `clearIfNotInChannel clears channel mode and queue when content does not belong`() {
        PlaylistManager.startChannel(
            contentId = contentId,
            seriesTitle = seriesTitle,
            episodes = episodes
        )

        PlaylistManager.clearIfNotInChannel("show-2", "ep-2-diff")

        assertFalse(PlaylistManager.isChannelActiveFor(contentId, "ep-1"))
        assertFalse(PlaylistManager.isChannelActiveFor("show-2", "ep-2-diff"))
        assertEquals(ChannelMode.NONE, PlaylistManager.channelMode.value)
        assertTrue(PlaylistManager.queue.value.isEmpty())
    }

    @Test
    fun `clearIfNotInChannel preserves channel when content belongs`() {
        PlaylistManager.startChannel(
            contentId = contentId,
            seriesTitle = seriesTitle,
            episodes = episodes
        )

        PlaylistManager.clearIfNotInChannel(contentId, "ep-2")

        assertTrue(PlaylistManager.isChannelActiveFor(contentId, "ep-2"))
        assertEquals(ChannelMode.BINGE_ORDER, PlaylistManager.channelMode.value)
        assertEquals(3, PlaylistManager.queue.value.size)
    }

    private fun video(id: String, season: Int, episode: Int): Video = Video(
        id = id,
        title = "Episode $episode",
        released = null,
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null
    )
}
