package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebridSettingsDataStoreTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var preferencesStore: DataStore<Preferences>
    private lateinit var debridSettingsDataStore: DebridSettingsDataStore

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferencesFile = tempFolder.newFile("debrid_settings.preferences_pb")
        preferencesStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { preferencesFile }
        )
        val profileManager = mockk<ProfileManager>(relaxed = true) {
            every { activeProfileId } returns MutableStateFlow(1)
        }
        val profileDataStoreFactory = mockk<ProfileDataStoreFactory>(relaxed = true) {
            every { get(any(), any()) } returns preferencesStore
        }
        debridSettingsDataStore = DebridSettingsDataStore(
            factory = profileDataStoreFactory,
            profileManager = profileManager
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `setting torbox and premiumize keys auto-selects torbox primary and premiumize fallback`() = runTest {
        debridSettingsDataStore.setProviderApiKey(DebridProviders.TORBOX_ID, "torbox-key")
        debridSettingsDataStore.setProviderApiKey(DebridProviders.PREMIUMIZE_ID, "premiumize-key")

        val settings = debridSettingsDataStore.settings.first()

        assertEquals(DebridProviders.TORBOX_ID, settings.preferredResolverProviderId)
        assertEquals(DebridProviders.PREMIUMIZE_ID, settings.fallbackResolverProviderIdResolved)
    }

    @Test
    fun `setPreferredResolverProviderId premiumize swaps primary to premiumize and fallback to torbox`() = runTest {
        debridSettingsDataStore.setProviderApiKey(DebridProviders.TORBOX_ID, "torbox-key")
        debridSettingsDataStore.setProviderApiKey(DebridProviders.PREMIUMIZE_ID, "premiumize-key")

        debridSettingsDataStore.setPreferredResolverProviderId(DebridProviders.PREMIUMIZE_ID)

        val settings = debridSettingsDataStore.settings.first()

        assertEquals(DebridProviders.PREMIUMIZE_ID, settings.preferredResolverProviderId)
        assertEquals(DebridProviders.TORBOX_ID, settings.fallbackResolverProviderIdResolved)
    }

    @Test
    fun `setFallbackResolverProviderId none disables fallback and survives primary switch`() = runTest {
        debridSettingsDataStore.setProviderApiKey(DebridProviders.TORBOX_ID, "torbox-key")
        debridSettingsDataStore.setProviderApiKey(DebridProviders.PREMIUMIZE_ID, "premiumize-key")

        debridSettingsDataStore.setFallbackResolverProviderId(DebridProviders.FALLBACK_NONE)

        val afterNone = debridSettingsDataStore.settings.first()
        assertNull(afterNone.fallbackResolverProviderIdResolved)
        assertEquals(DebridProviders.FALLBACK_NONE, afterNone.fallbackResolverProviderId)

        debridSettingsDataStore.setPreferredResolverProviderId(DebridProviders.PREMIUMIZE_ID)

        val afterSwitch = debridSettingsDataStore.settings.first()
        assertEquals(DebridProviders.PREMIUMIZE_ID, afterSwitch.preferredResolverProviderId)
        assertNull(afterSwitch.fallbackResolverProviderIdResolved)
        assertEquals(DebridProviders.FALLBACK_NONE, afterSwitch.fallbackResolverProviderId)
    }

    @Test
    fun `clearing secondary key clears fallback`() = runTest {
        debridSettingsDataStore.setProviderApiKey(DebridProviders.TORBOX_ID, "torbox-key")
        debridSettingsDataStore.setProviderApiKey(DebridProviders.PREMIUMIZE_ID, "premiumize-key")

        val beforeClear = debridSettingsDataStore.settings.first()
        assertEquals(DebridProviders.PREMIUMIZE_ID, beforeClear.fallbackResolverProviderIdResolved)

        debridSettingsDataStore.setProviderApiKey(DebridProviders.PREMIUMIZE_ID, "")

        val afterClear = debridSettingsDataStore.settings.first()
        assertEquals(DebridProviders.TORBOX_ID, afterClear.preferredResolverProviderId)
        assertNull(afterClear.fallbackResolverProviderIdResolved)
        assertEquals("", afterClear.fallbackResolverProviderId)
    }
}
