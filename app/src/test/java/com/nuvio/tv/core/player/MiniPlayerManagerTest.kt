package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Unit tests for disabled [MiniPlayerManager].
 */
class MiniPlayerManagerTest {

    @Test
    fun `mini player remains disabled`() {
        assertFalse(MiniPlayerManager.isMiniPlayerActive.value)
        assertFalse(MiniPlayerManager.isPlaybackRunning())
    }
}
