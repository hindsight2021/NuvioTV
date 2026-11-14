package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class DebridStreamPresentationTest {
    private val formatter = DebridStreamFormatter(DebridStreamTemplateEngine())
    private val presentation = DebridStreamPresentation(
        dataStore = mockk<DebridSettingsDataStore>(relaxed = true),
        streamBadgeSettingsDataStore = mockk<StreamBadgeSettingsDataStore>(relaxed = true),
        formatter = formatter
    )
    private val settings = DebridSettings(
        enabled = true,
        torboxApiKey = "key",
        streamPreferences = DebridStreamPreferences(excludedEncodes = listOf(DebridStreamEncode.AVC)),
        streamNameTemplate = "{stream.resolution} {stream.quality} {stream.encode}",
        streamDescriptionTemplate = "{stream.filename}"
    )

    @Test
    fun `formats filtered debrid streams with the facts computed while filtering`() {
        val passthrough = Stream(
            name = "Regular",
            title = "Movie",
            description = null,
            url = "https://example.com/movie.mkv",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "Addon",
            addonLogo = null
        )
        val debridStreams = listOf(
            debridStream("Movie.2023.1080p.WEB-DL.DDP5.1.x265-GRP.mkv"),
            debridStream("Movie.2023.2160p.BluRay.Remux.DV.HDR.HEVC.DTS-HD.MA-SGF.mkv"),
            debridStream("Movie.2023.720p.WEBRip.AAC.x264-GRP.mkv"),
            debridStream("Movie.2023.1440p.HDTV.HDR.AV1.mkv")
        )

        val result = presentation.apply(
            groups = listOf(AddonStreams(addonName = "Addon", addonLogo = null, streams = debridStreams + passthrough)),
            settings = settings,
            includeBadgeMatches = false
        ).single().streams

        val expectedDebrid = DirectDebridStreamFilter.applyPreferences(debridStreams, settings)
            .map { formatter.format(it, settings) }
        assertEquals(expectedDebrid + passthrough, result)
        assertEquals(
            setOf("1080p WEB-DL HEVC", "2160p BluRay REMUX HEVC", "1440p HDTV AV1"),
            result.dropLast(1).map { it.name }.toSet()
        )
    }

    @Test
    fun `preserves direct debrid streams for both torbox and premiumize when both are configured`() {
        val bothSettings = settings.copy(
            premiumizeApiKey = "pm-key",
            preferredResolverProviderId = DebridProviders.TORBOX_ID,
            fallbackResolverProviderId = DebridProviders.PREMIUMIZE_ID
        )
        val torboxStream = debridStream("Movie.2023.1080p.WEB-DL.x265-GRP.mkv", service = DebridProviders.TORBOX_ID)
        val premiumizeStream = debridStream("Movie.2023.2160p.BluRay.HEVC-SGF.mkv", service = DebridProviders.PREMIUMIZE_ID)

        val result = presentation.apply(
            groups = listOf(
                AddonStreams(
                    addonName = "Addon",
                    addonLogo = null,
                    streams = listOf(torboxStream, premiumizeStream)
                )
            ),
            settings = bothSettings,
            includeBadgeMatches = false
        ).single().streams

        assertEquals(
            listOf(
                formatter.format(torboxStream, bothSettings),
                formatter.format(premiumizeStream, bothSettings)
            ),
            result
        )
    }

    @Test
    fun `hides premiumize direct debrid streams when fallback is none while keeping torbox streams`() {
        val primaryOnlySettings = settings.copy(
            premiumizeApiKey = "pm-key",
            preferredResolverProviderId = DebridProviders.TORBOX_ID,
            fallbackResolverProviderId = DebridProviders.FALLBACK_NONE
        )
        val torboxStream = debridStream("Movie.2023.1080p.WEB-DL.x265-GRP.mkv", service = DebridProviders.TORBOX_ID)
        val premiumizeStream = debridStream("Movie.2023.2160p.BluRay.HEVC-SGF.mkv", service = DebridProviders.PREMIUMIZE_ID)

        val result = presentation.apply(
            groups = listOf(
                AddonStreams(
                    addonName = "Addon",
                    addonLogo = null,
                    streams = listOf(torboxStream, premiumizeStream)
                )
            ),
            settings = primaryOnlySettings,
            includeBadgeMatches = false
        ).single().streams

        assertEquals(
            listOf(formatter.format(torboxStream, primaryOnlySettings)),
            result
        )
    }

    private fun debridStream(filename: String, service: String = DebridProviders.TORBOX_ID): Stream = Stream(
        name = "TB cached",
        title = "Movie",
        description = filename,
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Addon",
        addonLogo = null,
        clientResolve = StreamClientResolve(
            type = "debrid",
            infoHash = filename.hashCode().toString(),
            fileIdx = 0,
            magnetUri = null,
            sources = null,
            torrentName = filename,
            filename = filename,
            mediaType = "movie",
            mediaId = "tt1",
            mediaOnlyId = "tt1",
            title = "Movie",
            season = null,
            episode = null,
            service = service,
            serviceIndex = 0,
            serviceExtension = null,
            isCached = true
        )
    )
}
