package com.nuvio.tv.core.playlist

import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmartRandomEpisodePlannerTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun ep(
        season: Int,
        episode: Int,
        released: String? = "2020-01-01",
        available: Boolean? = true
    ): Video = Video(
        id = "s${season}e$episode",
        title = "S${season}E$episode",
        released = released,
        thumbnail = null,
        streams = emptyList(),
        season = season,
        episode = episode,
        overview = null,
        available = available
    )

    private fun signal(
        season: Int,
        episode: Int,
        completed: Boolean = false,
        inProgress: Boolean = false,
        lastWatchedMs: Long = 0L
    ) = EpisodeWatchSignal(season, episode, completed, inProgress, lastWatchedMs)

    @Test
    fun `never repeats immediate previous pick for two episode show`() {
        val episodes = listOf(ep(1, 1), ep(1, 2))
        val random = Random(42)
        var lastKey: Pair<Int, Int>? = null
        repeat(50) {
            val picks = if (lastKey != null) {
                listOf(RandomPickRecord(lastKey!!.first, lastKey!!.second, now - 1_000L))
            } else {
                emptyList()
            }
            val chosen = SmartRandomEpisodePlanner.pickEpisode(episodes, emptyMap(), picks, now, random)
            assertNotNull(chosen)
            val currentKey = chosen!!.season!! to chosen.episode!!
            if (lastKey != null) {
                assertNotEquals(lastKey, currentKey)
            }
            lastKey = currentKey
        }
    }

    @Test
    fun `never repeats even after exhausting 72h pick cooldown`() {
        val episodes = (1..5).map { ep(1, it) }
        val picks = episodes.mapIndexed { idx, video ->
            RandomPickRecord(video.season!!, video.episode!!, now - (idx + 1) * 60_000L)
        }
        val mostRecent = picks.first().season to picks.first().episode
        repeat(40) { seed ->
            val chosen = SmartRandomEpisodePlanner.pickEpisode(
                episodes = episodes,
                signalsByEpisode = emptyMap(),
                recentPicks = picks,
                nowMs = now,
                random = Random(seed)
            )
            assertNotNull(chosen)
            assertNotEquals(mostRecent, chosen!!.season!! to chosen.episode!!)
        }
    }

    @Test
    fun `prefers unwatched and stale episodes over recently watched ones`() {
        val episodes = (1..10).map { ep(1, it) }
        val signals = buildMap {
            for (i in 1..8) {
                put(1 to i, signal(1, i, completed = true, lastWatchedMs = now - 5L * day))
            }
            put(1 to 9, signal(1, 9, completed = true, lastWatchedMs = now - 60L * day))
            // S01E10 is unwatched
        }

        val chosenKeys = (1..50).map { seed ->
            SmartRandomEpisodePlanner.pickEpisode(episodes, signals, emptyList(), now, Random(seed))!!
                .let { it.season!! to it.episode!! }
        }.toSet()

        // S01E10 is unwatched (Tier 1), so Tier 1 is strictly preferred before Tier 2.
        assertEquals(setOf(1 to 10), chosenKeys)
    }

    @Test
    fun `rewatch mode skips recent watches within 30 days in favor of stale episodes`() {
        val episodes = (1..10).map { ep(1, it) }
        val signals = buildMap {
            for (i in 1..8) {
                put(1 to i, signal(1, i, completed = true, lastWatchedMs = now - 3L * day))
            }
            put(1 to 9, signal(1, 9, completed = true, lastWatchedMs = now - 45L * day))
            put(1 to 10, signal(1, 10, completed = true, lastWatchedMs = now - 180L * day))
        }

        val chosenEpisodes = (1..100).map { seed ->
            SmartRandomEpisodePlanner.pickEpisode(episodes, signals, emptyList(), now, Random(seed))!!.episode!!
        }

        assertTrue(chosenEpisodes.all { it == 9 || it == 10 })
        val olderCount = chosenEpisodes.count { it == 10 }
        assertTrue("Expected much older S01E10 to be weighted higher, got $olderCount/100", olderCount >= 70)
    }

    @Test
    fun `merged multi-source signals allow newer watch from Trakt or Simkl to block episode`() {
        val nuvioLegacy = EpisodeWatchSignal(1, 3, isCompleted = false, isInProgress = false, lastWatchedMs = 0L)
        val traktYesterday = EpisodeWatchSignal(1, 3, isCompleted = true, isInProgress = false, lastWatchedMs = now - day)
        val merged = SmartRandomEpisodePlanner.mergeWatchSignals(nuvioLegacy, traktYesterday)

        assertTrue(merged.isCompleted)
        assertEquals(now - day, merged.lastWatchedMs)

        val episodes = (1..3).map { ep(1, it) }
        val signals = mapOf(
            (1 to 1) to signal(1, 1, completed = true, lastWatchedMs = now - 90L * day),
            (1 to 2) to signal(1, 2, completed = true, lastWatchedMs = now - 10L * day),
            (1 to 3) to merged
        )
        repeat(30) { seed ->
            val chosen = SmartRandomEpisodePlanner.pickEpisode(episodes, signals, emptyList(), now, Random(seed))!!
            assertEquals(1, chosen.episode)
        }
    }

    @Test
    fun `falls back gracefully when all episodes were watched within 30 days`() {
        val episodes = (1..4).map { ep(1, it) }
        val signals = mapOf(
            (1 to 1) to signal(1, 1, completed = true, lastWatchedMs = now - 1L * day),
            (1 to 2) to signal(1, 2, completed = true, lastWatchedMs = now - 2L * day),
            (1 to 3) to signal(1, 3, completed = true, lastWatchedMs = now - 3L * day),
            (1 to 4) to signal(1, 4, completed = true, lastWatchedMs = now - 12L * day)
        )

        repeat(30) { seed ->
            val chosen = SmartRandomEpisodePlanner.pickEpisode(episodes, signals, emptyList(), now, Random(seed))!!
            assertEquals("Should pick S01E04 outside 7d window", 4, chosen.episode)
        }
    }

    @Test
    fun `encourages season variety across consecutive picks`() {
        val episodes = (1..3).flatMap { s -> (1..5).map { e -> ep(s, e) } }
        val recentPicks = listOf(RandomPickRecord(season = 1, episode = 2, pickedAtMs = now - 60_000L))

        var differentSeasonCount = 0
        repeat(100) { seed ->
            val chosen = SmartRandomEpisodePlanner.pickEpisode(
                episodes, emptyMap(), recentPicks, now, Random(seed)
            )!!
            if (chosen.season != 1) differentSeasonCount++
        }
        assertTrue("Expected different season to dominate, got $differentSeasonCount/100", differentSeasonCount >= 80)
    }

    @Test
    fun `buildSmartShuffleQueue keeps all eligible episodes without duplicates and prioritizes unwatched`() {
        val episodes = (1..2).flatMap { s -> (1..5).map { e -> ep(s, e) } }
        val signals = buildMap {
            for (e in 1..5) {
                put(1 to e, signal(1, e, completed = true, lastWatchedMs = now - 2L * day))
            }
            // Season 2 all unwatched except E1
            put(2 to 1, signal(2, 1, completed = true, lastWatchedMs = now - 10L * day))
        }

        val selection = SmartRandomEpisodePlanner.buildSmartShuffleQueue(
            episodes = episodes,
            signalsByEpisode = signals,
            recentPicks = emptyList(),
            nowMs = now,
            random = Random(99)
        )
        assertNotNull(selection)
        val queue = selection!!.orderedQueue
        assertEquals(episodes.size, queue.size)
        assertEquals(episodes.size, queue.map { it.season!! to it.episode!! }.toSet().size)
        assertEquals(selection.chosen.id, queue.first().id)

        // First 4 queued episodes must be the 4 unwatched Season 2 episodes (S02E02..S02E05)
        val firstFourKeys = queue.take(4).map { it.season!! to it.episode!! }.toSet()
        assertEquals(setOf(2 to 2, 2 to 3, 2 to 4, 2 to 5), firstFourKeys)
    }

    @Test
    fun `filters out unavailable and unaired future episodes when aired episodes exist`() {
        val episodes = listOf(
            ep(1, 1, released = "2020-01-01", available = true),
            ep(1, 2, released = "2099-01-01", available = true),
            ep(1, 3, released = "2020-01-08", available = false)
        )
        repeat(20) { seed ->
            val chosen = SmartRandomEpisodePlanner.pickEpisode(episodes, emptyMap(), emptyList(), now, Random(seed))!!
            assertEquals(1, chosen.episode)
        }
    }
}
