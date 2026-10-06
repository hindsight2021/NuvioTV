package com.nuvio.tv.core.control

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.navigation.Screen
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JustPlayCoordinatorTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var navigation: NavigationCommander
    private lateinit var bridge: PlayerPlaybackBridge
    private lateinit var profileManager: ProfileManager
    private lateinit var watchProgress: WatchProgressRepository
    private lateinit var coordinator: JustPlayCoordinator

    private lateinit var snapshotFlow: MutableStateFlow<PlaybackSnapshot?>
    private lateinit var profileFlow: MutableStateFlow<Int>

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        navigation = mockk(relaxed = true)
        bridge = mockk(relaxed = true)
        profileManager = mockk(relaxed = true)
        watchProgress = mockk(relaxed = true)

        snapshotFlow = MutableStateFlow(null)
        profileFlow = MutableStateFlow(1)

        every { bridge.playbackSnapshot } returns snapshotFlow
        every { profileManager.activeProfileId } returns profileFlow
        every { watchProgress.allProgress } returns emptyFlow()
        every { navigation.tryNavigateTo(any()) } returns true

        coordinator = JustPlayCoordinator(navigation, bridge, profileManager, watchProgress)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun snapshot(
        contentId: String,
        contentType: String,
        profileId: Int,
        season: Int?,
        episode: Int?,
        sessionId: String?,
        isPlaying: Boolean = true,
        isBuffering: Boolean = false,
    ) = PlaybackSnapshot(
        contentId = contentId,
        contentType = contentType,
        profileId = profileId,
        season = season,
        episode = episode,
        sessionId = sessionId,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
    )

    private fun playMedia(
        contentId: String = "c1",
        contentType: String = "movie",
        season: Int? = null,
        episode: Int? = null,
    ) = AppCommand.PlayMedia(
        contentId = contentId,
        contentType = contentType,
        season = season,
        episode = episode,
    )

    private fun requestId(result: CommandResult): String {
        val success = result as CommandResult.Success
        return (success.data as JustPlayCoordinator.Request).id
    }

    @Test
    fun validExactRequestRoutesPlayOnLoadExactCoordinates() = runTest(testDispatcher) {
        runCurrent()
        val result = coordinator.request(playMedia(contentId = "c1", contentType = "series", season = 2, episode = 4))
        runCurrent()

        assertTrue(result is CommandResult.Success)
        val req = (result as CommandResult.Success).data as JustPlayCoordinator.Request
        assertEquals("c1", req.contentId)
        assertEquals("series", req.contentType)
        assertEquals(1, req.profileId)
        assertEquals(JustPlayCoordinator.Status.RESOLVING, req.status)

        verify {
            navigation.tryNavigateTo(
                Screen.Detail.createRoute(
                    itemId = "c1",
                    itemType = "series",
                    returnFocusSeason = 2,
                    returnFocusEpisode = 4,
                    playOnLoad = true,
                    justPlayRequestId = req.id,
                    justPlayProfileId = 1,
                )
            )
        }
    }

    @Test
    fun duplicateSameIdDoesNotDuplicateNavigation() = runTest(testDispatcher) {
        runCurrent()
        val first = coordinator.request(playMedia(contentId = "c1", contentType = "movie"))
        runCurrent()
        val firstId = requestId(first)

        val second = coordinator.request(playMedia(contentId = "c1", contentType = "movie"))
        runCurrent()

        assertTrue(second is CommandResult.Success)
        val secondReq = (second as CommandResult.Success).data as JustPlayCoordinator.Request
        assertEquals(firstId, secondReq.id)

        verify(exactly = 1) { navigation.tryNavigateTo(any()) }
    }

    @Test
    fun incompleteCoordsFail() = runTest(testDispatcher) {
        runCurrent()
        val result = coordinator.request(
            playMedia(contentId = "c1", contentType = "series", season = 1, episode = null)
        )
        runCurrent()

        assertTrue(result is CommandResult.Unavailable)
        verify(exactly = 0) { navigation.tryNavigateTo(any()) }
    }

    @Test
    fun samePriorPlayerSessionCannotConfirmStarted() = runTest(testDispatcher) {
        runCurrent()
        snapshotFlow.value = snapshot(
            contentId = "c1",
            contentType = "movie",
            profileId = 1,
            season = null,
            episode = null,
            sessionId = "sess-1",
        )
        runCurrent()

        val result = coordinator.request(playMedia(contentId = "c1", contentType = "movie"))
        runCurrent()
        val id = requestId(result)

        // Re-emit same session id
        snapshotFlow.value = snapshot(
            contentId = "c1",
            contentType = "movie",
            profileId = 1,
            season = null,
            episode = null,
            sessionId = "sess-1",
        )
        runCurrent()

        val req = coordinator.status(id)
        assertNotNull(req)
        assertEquals(JustPlayCoordinator.Status.RESOLVING, req!!.status)
    }

    @Test
    fun newSessionMatchingCoordinatesConfirms() = runTest(testDispatcher) {
        runCurrent()
        snapshotFlow.value = snapshot(
            contentId = "c1",
            contentType = "movie",
            profileId = 1,
            season = null,
            episode = null,
            sessionId = "sess-1",
        )
        runCurrent()

        val result = coordinator.request(playMedia(contentId = "c1", contentType = "movie"))
        runCurrent()
        val id = requestId(result)

        snapshotFlow.value = snapshot(
            contentId = "c1",
            contentType = "movie",
            profileId = 1,
            season = null,
            episode = null,
            sessionId = "sess-2",
        )
        runCurrent()

        val req = coordinator.status(id)
        assertNotNull(req)
        assertEquals(JustPlayCoordinator.Status.STARTED, req!!.status)
    }

    @Test
    fun wrongEpisodeCannotConfirm() = runTest(testDispatcher) {
        runCurrent()
        val result = coordinator.request(
            playMedia(contentId = "c1", contentType = "series", season = 1, episode = 2)
        )
        runCurrent()
        val id = requestId(result)

        snapshotFlow.value = snapshot(
            contentId = "c1",
            contentType = "series",
            profileId = 1,
            season = 1,
            episode = 3,
            sessionId = "sess-1",
        )
        runCurrent()

        assertEquals(JustPlayCoordinator.Status.RESOLVING, coordinator.status(id)!!.status)
    }

    @Test
    fun wrongProfileCannotConfirm() = runTest(testDispatcher) {
        runCurrent()
        val result = coordinator.request(playMedia(contentId = "c1", contentType = "movie"))
        runCurrent()
        val id = requestId(result)

        snapshotFlow.value = snapshot(
            contentId = "c1",
            contentType = "movie",
            profileId = 2,
            season = null,
            episode = null,
            sessionId = "sess-1",
        )
        runCurrent()

        assertEquals(JustPlayCoordinator.Status.RESOLVING, coordinator.status(id)!!.status)
    }

    @Test
    fun cancelBlocksIsActive() = runTest(testDispatcher) {
        runCurrent()
        val result = coordinator.request(playMedia(contentId = "c1", contentType = "movie"))
        runCurrent()
        val id = requestId(result)

        assertTrue(coordinator.isActive(id, 1))
        coordinator.cancel(id)
        runCurrent()

        assertFalse(coordinator.isActive(id, 1))
        assertEquals(JustPlayCoordinator.Status.CANCELLED, coordinator.status(id)!!.status)
    }

    @Test
    fun profileChangeCancels() = runTest(testDispatcher) {
        runCurrent()
        val result = coordinator.request(playMedia(contentId = "c1", contentType = "movie"))
        runCurrent()
        val id = requestId(result)

        assertEquals(JustPlayCoordinator.Status.RESOLVING, coordinator.status(id)!!.status)

        profileFlow.value = 2
        runCurrent()

        assertEquals(JustPlayCoordinator.Status.CANCELLED, coordinator.status(id)!!.status)
    }

    @Test
    fun timeoutFails() = runTest(testDispatcher) {
        runCurrent()
        val result = coordinator.request(playMedia(contentId = "c1", contentType = "movie"))
        runCurrent()
        val id = requestId(result)

        assertEquals(JustPlayCoordinator.Status.RESOLVING, coordinator.status(id)!!.status)

        advanceTimeBy(120_001)
        runCurrent()

        val req = coordinator.status(id)
        assertNotNull(req)
        assertEquals(JustPlayCoordinator.Status.FAILED, req!!.status)
    }
}
