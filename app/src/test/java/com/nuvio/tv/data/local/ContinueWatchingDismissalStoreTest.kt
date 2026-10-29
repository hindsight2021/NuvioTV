package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.nextUpEpisodeDismissKey
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingDismissalStoreTest {
    @Test
    fun `read and write preserve episode coordinates and legacy keys`() = runTest {
        val data = TestPreferencesStore()
        val key = stringSetPreferencesKey("dismissed_next_up_keys")
        data.edit { it[key] = setOf("legacy-show", "old-show|1|2") }
        val factory = mockk<ProfileDataStoreFactory> { every { get(any(), any()) } returns data }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        val settings = TraktSettingsDataStore(factory, profiles)
        val episodeKey = nextUpEpisodeDismissKey("show", 2, 6)
        settings.addDismissedNextUpKey(episodeKey)
        assertEquals(setOf("legacy-show", "old-show|1|2", episodeKey), settings.dismissedNextUpKeys.first())
    }

    @Test
    fun `playing content clears its legacy and scoped removals without touching other ids`() = runTest {
        val data = TestPreferencesStore()
        val key = stringSetPreferencesKey("dismissed_next_up_keys")
        val other = nextUpEpisodeDismissKey("show-other", 2, 6)
        data.edit { it[key] = setOf("show", "show|1|2", nextUpEpisodeDismissKey("show", 2, 6), other) }
        val factory = mockk<ProfileDataStoreFactory> { every { get(any(), any()) } returns data }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        val settings = TraktSettingsDataStore(factory, profiles)
        settings.removeDismissedNextUpKeysForContent("show", profileId = 1)
        assertEquals(setOf(other), settings.dismissedNextUpKeys.first())
    }
}
