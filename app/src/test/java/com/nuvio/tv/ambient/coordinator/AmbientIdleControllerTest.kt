package com.nuvio.tv.ambient.coordinator

import com.nuvio.tv.ambient.settings.AmbientSettings
import com.nuvio.tv.ambient.settings.AmbientSettingsDataStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AmbientIdleController].
 */
class AmbientIdleControllerTest {

    private lateinit var settingsDataStore: AmbientSettingsDataStore
    private lateinit var controller: AmbientIdleController

    @Before
    fun setUp() {
        settingsDataStore = mockk(relaxed = true)
        every { settingsDataStore.settings } returns flowOf(AmbientSettings())
        controller = AmbientIdleController(settingsDataStore)
    }

    @Test
    fun `initial isIdle state is false`() = runTest {
        assertFalse(controller.isIdle.value)
    }

    @Test
    fun `notifyUserActivity ensures non-idle state`() = runTest {
        controller.setPlaybackActiveProvider { false }
        controller.notifyUserActivity()
        assertFalse("notifyUserActivity() should keep or reset to non-idle", controller.isIdle.value)
    }

    @Test
    fun `playback active provider suppresses idle state`() = runTest {
        var playbackActive = true
        controller.setPlaybackActiveProvider { playbackActive }

        controller.start { /* no-op idle callback */ }
        assertFalse(
            "Controller must not be idle while playback is active",
            controller.isIdle.value
        )

        playbackActive = false
        controller.notifyUserActivity()
        assertFalse(controller.isIdle.value)

        controller.stop()
    }

    @Test
    fun `stop cancels monitoring job and is idempotent`() = runTest {
        controller.setPlaybackActiveProvider { false }
        controller.start { /* idle callback */ }

        controller.stop()
        // Calling stop again must be safe and idempotent
        controller.stop()

        assertFalse(controller.isIdle.value)
    }
}
