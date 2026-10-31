package com.nuvio.tv.core.player

import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MiniPlayerManager].
 */
class MiniPlayerManagerTest {

    private lateinit var controller: PlayerRuntimeController

    @Before
    fun setUp() {
        controller = mockk(relaxed = true)
        MiniPlayerManager.stopAndDismiss()
    }

    @Test
    fun `initial state is not active, controller null, route null`() {
        assertFalse(MiniPlayerManager.isMiniPlayerActive.value)
        assertNull(MiniPlayerManager.activeController.value)
        assertNull(MiniPlayerManager.fullPlayerRoute)
    }

    @Test
    fun `enterMiniPlayer sets isMiniPlayerActive to true, updates title and route`() {
        val title = "Test Title"
        val route = "test/route"

        MiniPlayerManager.enterMiniPlayer(controller = controller, title = title, route = route)

        assertTrue(MiniPlayerManager.isMiniPlayerActive.value)
        assertEquals(title, MiniPlayerManager.miniPlayerTitle.value)
        assertEquals(route, MiniPlayerManager.fullPlayerRoute)
        assertEquals(controller, MiniPlayerManager.activeController.value)
    }

    @Test
    fun `restoreFullScreen sets isMiniPlayerActive to false and calls navigation callback with route`() {
        val route = "test/route"
        MiniPlayerManager.enterMiniPlayer(controller = controller, title = "Title", route = route)

        var navigatedRoute: String? = null
        val result = MiniPlayerManager.restoreFullScreen { navigatedRoute = it }

        assertTrue(result)
        assertFalse(MiniPlayerManager.isMiniPlayerActive.value)
        assertEquals(route, navigatedRoute)
    }

    @Test
    fun `restoreFullScreen returns false if route is null`() {
        var navigatedRoute: String? = null
        val result = MiniPlayerManager.restoreFullScreen { navigatedRoute = it }

        assertFalse(result)
        assertNull(navigatedRoute)
    }

    @Test
    fun `exitMiniPlayer with stopPlayback=false leaves controller intact but deactivates`() {
        MiniPlayerManager.enterMiniPlayer(controller = controller, title = "Title", route = "route")

        MiniPlayerManager.exitMiniPlayer(stopPlayback = false)

        assertFalse(MiniPlayerManager.isMiniPlayerActive.value)
        assertEquals(controller, MiniPlayerManager.activeController.value)
        verify(exactly = 0) { controller.stopAndRelease() }
    }

    @Test
    fun `exitMiniPlayer with stopPlayback=true calls controller stopAndRelease and clears state`() {
        MiniPlayerManager.enterMiniPlayer(controller = controller, title = "Title", route = "route")

        MiniPlayerManager.exitMiniPlayer(stopPlayback = true)

        assertFalse(MiniPlayerManager.isMiniPlayerActive.value)
        assertNull(MiniPlayerManager.activeController.value)
        assertNull(MiniPlayerManager.fullPlayerRoute)
        assertEquals("", MiniPlayerManager.miniPlayerTitle.value)
        verify(exactly = 1) { controller.stopAndRelease() }
    }

    @Test
    fun `stopAndDismiss calls exitMiniPlayer with stopPlayback=true`() {
        MiniPlayerManager.enterMiniPlayer(controller = controller, title = "Title", route = "route")

        MiniPlayerManager.stopAndDismiss()

        assertFalse(MiniPlayerManager.isMiniPlayerActive.value)
        assertNull(MiniPlayerManager.activeController.value)
        verify(exactly = 1) { controller.stopAndRelease() }
    }
}
