package com.nuvio.tv.ambient.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.ambient.AmbientCandidate
import com.nuvio.tv.ambient.settings.AmbientSettings
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests asserting zero-audio enforcement invariants across Ambient settings,
 * track selection, and player lifecycle management.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AmbientMuteEnforcementTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `ambient settings has videoMuteLockedOn defaulting to true`() {
        val settings = AmbientSettings()
        assertTrue("videoMuteLockedOn must default to true", settings.videoMuteLockedOn)
    }

    @Test
    fun `ambient settings videoMuteLockedOn is an immutable val`() {
        val field = AmbientSettings::class.java.getDeclaredField("videoMuteLockedOn")
        assertTrue(
            "videoMuteLockedOn must be final (val)",
            java.lang.reflect.Modifier.isFinal(field.modifiers)
        )
    }

    @Test
    fun `track selection parameters disable audio track type`() {
        val params = TrackSelectionParameters.Builder()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()

        assertTrue(
            "Audio track type must be disabled in track selection parameters",
            params.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)
        )
    }

    @Test
    fun `player volume reset to 0f when volume changed listener receives non-zero volume`() = runTest(testDispatcher) {
        val playerPool = mockk<AmbientPlayerPool>(relaxed = true)
        val preloader = mockk<AmbientPreloader>(relaxed = true)
        val transitionController = mockk<AmbientTransitionController>(relaxed = true)

        val player = mockk<ExoPlayer>(relaxed = true)
        val listenerSlot = slot<Player.Listener>()
        val volumeSlot = mutableListOf<Float>()

        every { player.addListener(capture(listenerSlot)) } just Runs
        every { player.volume = capture(volumeSlot) } answers {
            every { player.volume } returns volumeSlot.last()
        }
        every { player.volume } returns 0.5f // Simulating external volume modification
        every { playerPool.getPlayer(any()) } returns player
        val candidate = AmbientCandidate(
            id = "yt_test_mute",
            title = "Test Mute",
            category = "aerial"
        )

        coEvery { preloader.preload(any(), any()) } returns PreloadResult.Success(candidate, "https://example.com/test.mp4")
        every { transitionController.state } returns MutableStateFlow(TransitionState())

        val controller = AmbientPlaybackController(
            playerPool = playerPool,
            preloader = preloader,
            transitionController = transitionController
        )

        controller.playCandidate(candidate, immediate = true)
        advanceUntilIdle()

        assertTrue("Player listener should be attached", listenerSlot.isCaptured)

        // Trigger volume change event to non-zero (e.g. system or CEC volume surge)
        listenerSlot.captured.onVolumeChanged(0.8f)

        // Verify volume was immediately forced back to 0f
        verify(atLeast = 1) { player.volume = 0f }
        assertEquals(0f, volumeSlot.last(), 0.0f)
    }
}
