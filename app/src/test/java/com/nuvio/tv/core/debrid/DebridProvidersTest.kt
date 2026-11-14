package com.nuvio.tv.core.debrid

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.domain.model.DebridSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridProvidersTest {
    @Test
    fun `configured services only include visible providers`() {
        val settings = DebridSettings(
            enabled = true,
            torboxApiKey = "tb",
            realDebridApiKey = "rd"
        )

        val services = DebridProviders.configuredServices(settings)

        assertEquals(listOf(DebridProviders.Torbox), services.map { it.provider })
        assertTrue(DebridProviders.isVisible(DebridProviders.TORBOX_ID))
        assertFalse(DebridProviders.isVisible(DebridProviders.REAL_DEBRID_ID))
    }

    @Test
    fun `premiumize auth method is api key when client id is blank`() {
        if (BuildConfig.PREMIUMIZE_CLIENT_ID.isBlank()) {
            assertEquals(DebridProviderAuthMethod.ApiKey, DebridProviders.Premiumize.authMethod)
        }
    }

    @Test
    fun `ordered resolver services default to torbox then premiumize when fallback is blank`() {
        val settings = DebridSettings(
            enabled = true,
            torboxApiKey = "tb",
            premiumizeApiKey = "pm",
            fallbackResolverProviderId = ""
        )

        val services = DebridProviders.orderedResolverServices(settings)

        assertEquals(
            listOf(DebridProviders.Torbox, DebridProviders.Premiumize),
            services.map { it.provider }
        )
    }

    @Test
    fun `ordered resolver services honor preferred and fallback providers`() {
        val settings = DebridSettings(
            enabled = true,
            torboxApiKey = "tb",
            premiumizeApiKey = "pm",
            preferredResolverProviderId = DebridProviders.PREMIUMIZE_ID,
            fallbackResolverProviderId = DebridProviders.TORBOX_ID
        )

        val services = DebridProviders.orderedResolverServices(settings)

        assertEquals(
            listOf(DebridProviders.Premiumize, DebridProviders.Torbox),
            services.map { it.provider }
        )
    }

    @Test
    fun `fallback none disables fallback resolver service`() {
        val settings = DebridSettings(
            enabled = true,
            torboxApiKey = "tb",
            premiumizeApiKey = "pm",
            preferredResolverProviderId = DebridProviders.TORBOX_ID,
            fallbackResolverProviderId = DebridProviders.FALLBACK_NONE
        )

        assertNull(DebridProviders.fallbackResolverService(settings))

        val services = DebridProviders.orderedResolverServices(settings)
        assertEquals(listOf(DebridProviders.Torbox), services.map { it.provider })
    }
}
