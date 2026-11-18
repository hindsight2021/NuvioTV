package com.nuvio.tv.core.music

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nuvio.tv.core.ai.ThematicChannelGenerator
import com.nuvio.tv.core.control.AppCommand
import com.nuvio.tv.core.control.AppCommandBus
import com.nuvio.tv.core.control.CommandResult
import com.nuvio.tv.core.control.PlaybackTelemetrySnapshot
import com.nuvio.tv.core.control.PlayerPlaybackBridge
import com.nuvio.tv.core.control.RemoteControlSettings
import com.nuvio.tv.core.control.RemoteControlSettingsDataStore
import com.nuvio.tv.core.server.NuvioControlServer
import com.nuvio.tv.data.simkl.calendar.SimklCalendarRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import fi.iki.elonen.NanoHTTPD
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

class EclipseMusicIntegrationTest {

    private val gson = Gson()

    @Test
    fun `parseLrcLyrics parses synced timestamps in order and handles plain lyrics`() {
        val rawSynced = """
            [01:05.25]Second line
            [00:12.5]First line
            [01:05.250][02:00.00]Chorus line
        """.trimIndent()

        val parsed = parseLrcLyrics(rawSynced)
        assertEquals(4, parsed.size)
        assertEquals(12_500L, parsed[0].timestampMs)
        assertEquals("First line", parsed[0].text)
        assertEquals(65_250L, parsed[1].timestampMs)
        assertEquals(120_000L, parsed[3].timestampMs)
        assertEquals("Chorus line", parsed[3].text)

        val plain = parseLrcLyrics("Line A\n\nLine B")
        assertEquals(2, plain.size)
        assertEquals(-1L, plain[0].timestampMs)
        assertEquals("Line B", plain[1].text)
    }

    @Test
    fun `EclipseAudioModels deserializes snake_case and camelCase fields`() {
        val snakeJson = """
            {
              "id": "trk_1",
              "title": "Nights",
              "artist": "Frank Ocean",
              "album": "Blonde",
              "duration_sec": 307,
              "stream_url": "http://192.168.1.250:8771/audio/track/abc.flac",
              "artwork_url": "https://cdn.example/1200x1200bb.jpg",
              "backdrop_url": "https://cdn.example/hero.jpg",
              "lyrics_lrc": "[00:01.00]Round the city",
              "enrichment": {
                "genre": "R&B",
                "year": 2016,
                "quality": "LOSSLESS",
                "editorial_notes": "Classic track"
              }
            }
        """.trimIndent()

        val track = gson.fromJson(snakeJson, EclipseTrack::class.java)
        assertEquals("trk_1", track.id)
        assertEquals(307, track.durationSec)
        assertEquals("http://192.168.1.250:8771/audio/track/abc.flac", track.streamUrl)
        assertEquals("https://cdn.example/1200x1200bb.jpg", track.displayArtUrl())
        assertEquals("R&B", track.enrichment?.genre)
        assertEquals(2016, track.enrichment?.year)
        assertEquals("LOSSLESS", track.enrichment?.quality)

        val camelJson = """
            {
              "id": "trk_2",
              "name": "Ivy",
              "artistName": "Frank Ocean",
              "albumName": "Blonde",
              "durationSec": 249,
              "streamUrl": "http://192.168.1.250:8771/audio/track/def.m4a",
              "coverUrl": "https://cdn.example/ivy.jpg"
            }
        """.trimIndent()
        val camelTrack = gson.fromJson(camelJson, EclipseTrack::class.java)
        assertEquals("Ivy", camelTrack.title)
        assertEquals(249, camelTrack.durationSec)
        assertEquals("https://cdn.example/ivy.jpg", camelTrack.displayArtUrl())
    }

    @Test
    fun `NuvioControlServer routes audio play queue control and exposes audio telemetry in status`() = runTest {
        val appCommandBus = mockk<AppCommandBus>(relaxed = true)
        val playbackBridge = mockk<PlayerPlaybackBridge>(relaxed = true)
        val settingsDataStore = mockk<RemoteControlSettingsDataStore>(relaxed = true)
        val eclipseAudioPlayer = mockk<EclipseAudioPlayer>(relaxed = true)

        coEvery { settingsDataStore.getSettings() } returns RemoteControlSettings(
            serverEnabled = true,
            serverPort = 8910,
            apiToken = "tok123",
            pairingPin = "4321",
        )
        coEvery { appCommandBus.dispatch(any()) } returns CommandResult.Success("OK")
        coEvery { playbackBridge.sampleTelemetry() } returns PlaybackTelemetrySnapshot(active = false)
        every { playbackBridge.playbackSnapshot } returns MutableStateFlow(null)

        val sampleTelemetry = EclipseAudioTelemetry(
            isActive = true,
            isPlaying = true,
            currentIndex = 0,
            currentTrack = EclipseTrack(id = "t1", title = "Song One", artist = "Artist One"),
            positionMs = 15_000L,
            durationMs = 200_000L,
            source = "eclipse_connect",
            queueSize = 1,
            queue = listOf(EclipseTrack(id = "t1", title = "Song One", artist = "Artist One")),
        )
        every { eclipseAudioPlayer.telemetry } returns MutableStateFlow(sampleTelemetry)

        val context = mockk<Context>(relaxed = true)
        val appContext = mockk<Context>(relaxed = true)
        val wifiManager = mockk<android.net.wifi.WifiManager>(relaxed = true)
        every { context.applicationContext } returns appContext
        every { appContext.getSystemService(Context.WIFI_SERVICE) } returns wifiManager
        every { context.getSystemService(Context.WIFI_SERVICE) } returns wifiManager

        val server = NuvioControlServer(
            context = context,
            appCommandBus = appCommandBus,
            playerPlaybackBridge = playbackBridge,
            remoteControlSettingsDataStore = settingsDataStore,
            thematicChannelGenerator = mockk<ThematicChannelGenerator>(relaxed = true),
            watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true),
            simklCalendarRepository = mockk<SimklCalendarRepository>(relaxed = true),
            eclipseAudioPlayer = eclipseAudioPlayer,
        )

        val authHeaders = mapOf("authorization" to "Bearer tok123")

        // Status includes activeScreen="music" and audio telemetry
        val statusRes = server.serve(FakeSession(NanoHTTPD.Method.GET, "/api/v1/status", authHeaders))
        assertEquals(NanoHTTPD.Response.Status.OK, statusRes.status)
        val statusJson = JsonParser.parseString(statusRes.data.bufferedReader().readText()).asJsonObject
        assertEquals("music", statusJson.get("activeScreen").asString)
        val audioJson = statusJson.getAsJsonObject("audio")
        assertNotNull(audioJson)
        assertTrue(audioJson.get("isActive").asBoolean)
        assertTrue(audioJson.get("isPlaying").asBoolean)
        assertEquals(15_000L, audioJson.get("positionMs").asLong)

        // /api/v1/audio/play
        val playRes = server.serve(
            FakeSession(
                NanoHTTPD.Method.POST,
                "/api/v1/audio/play",
                authHeaders,
                """{"start_index":0,"autoplay":true,"source":"eclipse_connect","tracks":[{"id":"t1","title":"Song One","artist":"Artist One","stream_url":"http://192.168.1.250:8771/audio/track/x.m4a"}]}""",
            ),
        )
        assertEquals(NanoHTTPD.Response.Status.OK, playRes.status)
        val playSlot = slot<AppCommand>()
        coVerify { appCommandBus.dispatch(capture(playSlot)) }
        assertTrue(playSlot.captured is AppCommand.PlayAudioQueue)
        assertEquals("Song One", (playSlot.captured as AppCommand.PlayAudioQueue).tracks.first().title)

        // /api/v1/audio/queue
        val queueRes = server.serve(
            FakeSession(
                NanoHTTPD.Method.POST,
                "/api/v1/audio/queue",
                authHeaders,
                """{"mode":"next","tracks":[{"id":"t2","title":"Song Two","artist":"Artist Two","stream_url":"http://192.168.1.250:8771/audio/track/y.m4a"}]}""",
            ),
        )
        assertEquals(NanoHTTPD.Response.Status.OK, queueRes.status)
        coVerify { appCommandBus.dispatch(match { it is AppCommand.QueueAudioTracks && it.mode == "next" }) }

        // /api/v1/audio/control
        val ctrlRes = server.serve(
            FakeSession(
                NanoHTTPD.Method.POST,
                "/api/v1/audio/control",
                authHeaders,
                """{"action":"seek","value":42000}""",
            ),
        )
        assertEquals(NanoHTTPD.Response.Status.OK, ctrlRes.status)
        coVerify {
            appCommandBus.dispatch(
                match { it is AppCommand.AudioControl && it.action == "seek" && (it.value as? Number)?.toLong() == 42000L },
            )
        }
    }

    @Test
    fun `AppCommandBus pauses audio on PlayStream and routes audio commands`() = runTest {
        val playbackBridge = mockk<PlayerPlaybackBridge>(relaxed = true)
        every { playbackBridge.playbackSnapshot } returns MutableStateFlow(null)
        val navigationCommander = mockk<com.nuvio.tv.core.control.NavigationCommander>(relaxed = true)
        val eclipseAudioPlayer = mockk<EclipseAudioPlayer>(relaxed = true)
        val bus = AppCommandBus(
            context = mockk<Context>(relaxed = true),
            playerPlaybackBridge = playbackBridge,
            navigationCommander = navigationCommander,
            justPlayCoordinator = mockk(relaxed = true),
            aiOperatorEngine = mockk(relaxed = true),
            thematicChannelGenerator = mockk(relaxed = true),
            eclipseAudioPlayer = eclipseAudioPlayer,
        )

        val playAudio = AppCommand.PlayAudioQueue(
            tracks = listOf(EclipseTrack(id = "t1", title = "Track 1", artist = "Artist 1", streamUrl = "http://x/1.m4a")),
            startIndex = 0,
            autoplay = true,
            source = "eclipse_connect",
        )
        bus.dispatch(playAudio)
        coVerify { eclipseAudioPlayer.playQueue(playAudio.tracks, 0, true, "eclipse_connect", null) }

        bus.dispatch(AppCommand.PlayStream(streamUrl = "https://cdn.example/video.mkv", title = "Movie"))
        io.mockk.verify { eclipseAudioPlayer.pauseForVideo() }
    }

    @Test
    fun `Pro DJ metadata curve and 3-band audio processor operate accurately`() {
        val gson = com.google.gson.Gson()
        val djJson = """
            {
              "id": "dj_w_1",
              "title": "Acid Dawn",
              "artist": "DJ Test",
              "duration_sec": 210,
              "stream_url": "http://192.168.1.250:8771/audio/track/dj1.flac",
              "dj_meta": {
                "bpm": 126.4,
                "musical_key": "9A",
                "energy": 0.88,
                "is_wildcard": true,
                "dj_why": "Surprise harmonic peak bridge",
                "waveform": [0.2, 0.5, 0.9, 0.6],
                "transition_plan": {
                  "transition_start_ms": 182000,
                  "fade_ms": 15000,
                  "incoming_cue_ms": 12000,
                  "outgoing_bpm": 124.0,
                  "incoming_bpm": 126.4,
                  "target_tempo_ratio": 0.981,
                  "bass_swap": true,
                  "bass_swap_fraction": 0.6,
                  "filter_sweep": "lowpass",
                  "dj_note": "Bass Swap + Filter Sweep • 124->126 BPM"
                }
              }
            }
        """.trimIndent()
        val track = gson.fromJson(djJson, EclipseTrack::class.java)
        assertEquals(126.4f, track.resolvedBpm(), 0.01f)
        assertEquals("9A", track.resolvedKey())
        assertTrue(track.djMeta?.isWildcard == true)
        assertEquals(4, track.resolvedWaveform().size)
        assertEquals(15_000L, track.djMeta?.transitionPlan?.fadeMs)

        // Verify equal-power crossfade + Bass Swap curve at start, midpoint (swap), and end
        val plan = track.djMeta?.transitionPlan
        val early = DjTransitionCurve.evaluate(0.15f, plan)
        val late = DjTransitionCurve.evaluate(0.85f, plan)
        assertTrue(early.outgoingVolume > early.incomingVolume)
        assertTrue(early.outgoingLowGain > early.incomingLowGain)
        assertTrue(late.incomingVolume > late.outgoingVolume)
        assertTrue(late.incomingLowGain > late.outgoingLowGain)
        assertTrue(late.outgoingHighGain < 0.5f)

        // Verify DjDeckAudioProcessor processes PCM16 buffers & populates live meters
        val proc = DjDeckAudioProcessor(DjDeckId.A)
        val fmt = androidx.media3.common.audio.AudioProcessor.AudioFormat(44100, 2, androidx.media3.common.C.ENCODING_PCM_16BIT)
        proc.configure(fmt)
        proc.flush()
        proc.lowGain = 0.25f
        val samples = 1024
        val buf = java.nio.ByteBuffer.allocateDirect(samples * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val s = (kotlin.math.sin(2.0 * Math.PI * 80.0 * i / 44100.0) * 16000.0).toInt().toShort()
            buf.putShort(s)
            buf.putShort(s)
        }
        buf.flip()
        proc.queueInput(buf)
        val snap = proc.snapshot()
        assertTrue(snap.vuLevel > 0f)
        assertEquals(32, snap.liveWaveform.size)
    }

    private class FakeSession(
        private val method: NanoHTTPD.Method,
        private val uri: String,
        private val headers: Map<String, String> = emptyMap(),
        private val postData: String? = null,
    ) : NanoHTTPD.IHTTPSession {
        override fun execute() = Unit
        override fun getCookies(): NanoHTTPD.CookieHandler? = null
        override fun getHeaders(): Map<String, String> = headers.mapKeys { it.key.lowercase() }
        override fun getInputStream(): InputStream =
            ByteArrayInputStream(postData?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0))
        override fun getMethod(): NanoHTTPD.Method = method
        @Deprecated("Deprecated in NanoHTTPD")
        override fun getParms(): Map<String, String> = emptyMap()
        override fun getParameters(): Map<String, List<String>> = emptyMap()
        override fun getQueryParameterString(): String? = null
        override fun getUri(): String = uri
        override fun parseBody(files: MutableMap<String, String>) {
            postData?.let { files["postData"] = it }
        }
        override fun getRemoteIpAddress(): String = "127.0.0.1"
        override fun getRemoteHostName(): String = "localhost"
    }
}
