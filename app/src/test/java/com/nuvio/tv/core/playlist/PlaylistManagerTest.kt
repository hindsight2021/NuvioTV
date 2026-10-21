package com.nuvio.tv.core.playlist

import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PlaylistManager].
 *
 * Verifies channel activation logic, membership checks, and clearing behavior
 * across the different [ChannelMode] states, as well as temporary queue operations.
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

    @Test
    fun `startChannel with isRandomEpisode sets flag and clear resets it`() {
        PlaylistManager.startChannel(
            contentId = contentId,
            seriesTitle = seriesTitle,
            episodes = episodes,
            shuffle = true,
            isRandomEpisode = true
        )

        assertTrue(PlaylistManager.isRandomEpisode)

        PlaylistManager.clear()

        assertFalse(PlaylistManager.isRandomEpisode)
    }

    @Test
    fun `startChannel without isRandomEpisode defaults to false`() {
        PlaylistManager.startChannel(
            contentId = contentId,
            seriesTitle = seriesTitle,
            episodes = episodes
        )

        assertFalse(PlaylistManager.isRandomEpisode)
    }

    // ---------------------------------------------------------------------
    // Temporary queue tests
    // ---------------------------------------------------------------------

    @Test
    fun `playNext inserts at front when currentIndex is negative`() {
        PlaylistManager.playNext(playlistItem(id = "next-1", episode = 1))
        PlaylistManager.playNext(playlistItem(id = "next-2", episode = 2))

        val queue = PlaylistManager.queue.value
        assertEquals(2, queue.size)
        assertEquals("next-2", queue[0].videoId)
        assertEquals("next-1", queue[1].videoId)
    }

    @Test
    fun `addToQueue appends to back`() {
        PlaylistManager.addToQueue(playlistItem(id = "q-1", episode = 1))
        PlaylistManager.addToQueue(playlistItem(id = "q-2", episode = 2))

        val queue = PlaylistManager.queue.value
        assertEquals(2, queue.size)
        assertEquals("q-1", queue[0].videoId)
        assertEquals("q-2", queue[1].videoId)
    }

    @Test
    fun `playNext and addToQueue maintain correct ordering`() {
        PlaylistManager.addToQueue(playlistItem(id = "back-1", episode = 1))
        PlaylistManager.playNext(playlistItem(id = "front-1", episode = 2))
        PlaylistManager.addToQueue(playlistItem(id = "back-2", episode = 3))

        val queue = PlaylistManager.queue.value
        assertEquals(3, queue.size)
        assertEquals("front-1", queue[0].videoId)
        assertEquals("back-1", queue[1].videoId)
        assertEquals("back-2", queue[2].videoId)
    }

    @Test
    fun `hasQueuedItem returns true when items are queued in NONE mode`() {
        assertFalse(PlaylistManager.hasQueuedItem())

        PlaylistManager.addToQueue(playlistItem(id = "q-1", episode = 1))

        assertTrue(PlaylistManager.hasQueuedItem())
    }

    @Test
    fun `hasQueuedItem returns false when queue is empty`() {
        assertFalse(PlaylistManager.hasQueuedItem())
    }

    @Test
    fun `peekNext returns first item without removing it`() {
        PlaylistManager.addToQueue(playlistItem(id = "q-1", episode = 1))
        PlaylistManager.addToQueue(playlistItem(id = "q-2", episode = 2))

        val peeked = PlaylistManager.peekNext()

        assertEquals("q-1", peeked?.videoId)
        assertEquals(2, PlaylistManager.queue.value.size)
        assertEquals("q-1", PlaylistManager.queue.value[0].videoId)
    }

    @Test
    fun `peekNext returns null when queue is empty`() {
        assertNull(PlaylistManager.peekNext())
    }

    @Test
    fun `popNext pops items in FIFO order and shrinks queue`() {
        PlaylistManager.addToQueue(playlistItem(id = "q-1", episode = 1))
        PlaylistManager.addToQueue(playlistItem(id = "q-2", episode = 2))
        PlaylistManager.addToQueue(playlistItem(id = "q-3", episode = 3))

        assertEquals(3, PlaylistManager.queue.value.size)

        val first = PlaylistManager.popNext()
        assertEquals("q-1", first?.videoId)
        assertEquals(2, PlaylistManager.queue.value.size)

        val second = PlaylistManager.popNext()
        assertEquals("q-2", second?.videoId)
        assertEquals(1, PlaylistManager.queue.value.size)

        val third = PlaylistManager.popNext()
        assertEquals("q-3", third?.videoId)
        assertEquals(0, PlaylistManager.queue.value.size)

        assertNull(PlaylistManager.popNext())
    }

    @Test
    fun `clearQueue clears all queued items and resets state`() {
        PlaylistManager.addToQueue(playlistItem(id = "q-1", episode = 1))
        PlaylistManager.addToQueue(playlistItem(id = "q-2", episode = 2))

        assertTrue(PlaylistManager.hasQueuedItem())

        PlaylistManager.clearQueue()

        assertTrue(PlaylistManager.queue.value.isEmpty())
        assertFalse(PlaylistManager.hasQueuedItem())
        assertNull(PlaylistManager.peekNext())
    }

    private fun playlistItem(id: String, season: Int = 1, episode: Int = 1): PlaylistItem = PlaylistItem(
        contentId = contentId,
        videoId = id,
        title = "Episode $episode",
        seriesTitle = seriesTitle,
        season = season,
        episode = episode,
        thumbnail = null,
        mediaType = "series"
    )

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
