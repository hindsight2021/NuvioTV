package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.core.ai.ThematicChannelGenerator
import com.nuvio.tv.core.control.AppCommand
import com.nuvio.tv.core.control.AppCommandBus
import com.nuvio.tv.core.control.CommandResult
import com.nuvio.tv.core.control.PlaybackTelemetrySnapshot
import com.nuvio.tv.core.control.PlayerPlaybackBridge
import com.nuvio.tv.core.control.RemoteControlSettings
import com.nuvio.tv.core.control.RemoteControlSettingsDataStore
import com.nuvio.tv.data.simkl.calendar.SimklCalendarRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import fi.iki.elonen.NanoHTTPD
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Unit tests for [NuvioControlServer] PWA asset endpoints, authentication, telemetry,
 * queue reordering, Live TV tuning, stream launch, and playback hot-swap command dispatch.
 */
class NuvioControlServerFeatureTest {

    private lateinit var context: Context
    private lateinit var appCommandBus: AppCommandBus
    private lateinit var playbackBridge: PlayerPlaybackBridge
    private lateinit var settingsDataStore: RemoteControlSettingsDataStore
    private lateinit var thematicChannelGenerator: ThematicChannelGenerator
    private lateinit var watchProgressRepository: WatchProgressRepository
    private lateinit var simklCalendarRepository: SimklCalendarRepository
    private lateinit var server: NuvioControlServer

    private val testSettings = RemoteControlSettings(
        serverEnabled = true,
        serverPort = 8910,
        apiToken = "tok123",
        pairingPin = "4321"
    )

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        appCommandBus = mockk(relaxed = true)
        playbackBridge = mockk(relaxed = true)
        settingsDataStore = mockk(relaxed = true)
        thematicChannelGenerator = mockk(relaxed = true)
        watchProgressRepository = mockk(relaxed = true)
        simklCalendarRepository = mockk(relaxed = true)

        coEvery { settingsDataStore.getSettings() } returns testSettings
        coEvery { appCommandBus.dispatch(any()) } returns CommandResult.Success("OK")
        coEvery { playbackBridge.sampleTelemetry() } returns PlaybackTelemetrySnapshot(
            active = true,
            videoCodec = "hevc",
            width = 3840,
            height = 2160
        )

        server = NuvioControlServer(
            context = context,
            appCommandBus = appCommandBus,
            playerPlaybackBridge = playbackBridge,
            remoteControlSettingsDataStore = settingsDataStore,
            thematicChannelGenerator = thematicChannelGenerator,
            watchProgressRepository = watchProgressRepository,
            simklCalendarRepository = simklCalendarRepository
        )
    }

    @Test
    fun `unauthenticated PWA static routes serve html, js, manifest, and svg icon cleanly`() {
        val htmlRes = server.serve(FakeSession(NanoHTTPD.Method.GET, "/"))
        assertEquals(NanoHTTPD.Response.Status.OK, htmlRes.status)
        assertTrue(htmlRes.mimeType.contains("text/html"))

        val jsRes = server.serve(FakeSession(NanoHTTPD.Method.GET, "/app.js"))
        assertEquals(NanoHTTPD.Response.Status.OK, jsRes.status)
        assertTrue(jsRes.mimeType.contains("javascript"))
        assertFalse(NuvioWebRemoteScript.JS.contains("\${"))

        val manifestRes = server.serve(FakeSession(NanoHTTPD.Method.GET, "/manifest.webmanifest"))
        assertEquals(NanoHTTPD.Response.Status.OK, manifestRes.status)
        assertTrue(manifestRes.mimeType.contains("manifest"))

        val iconRes = server.serve(FakeSession(NanoHTTPD.Method.GET, "/icon.svg"))
        assertEquals(NanoHTTPD.Response.Status.OK, iconRes.status)
        assertTrue(iconRes.mimeType.contains("svg"))
    }

    @Test
    fun `telemetry endpoint requires auth and returns snapshot json when authorized`() {
        val unauth = server.serve(FakeSession(NanoHTTPD.Method.GET, "/api/v1/telemetry"))
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, unauth.status)

        val auth = server.serve(
            FakeSession(
                method = NanoHTTPD.Method.GET,
                uri = "/api/v1/telemetry",
                headers = mapOf("authorization" to "Bearer tok123")
            )
        )
        assertEquals(NanoHTTPD.Response.Status.OK, auth.status)
        val body = auth.data.bufferedReader().readText()
        assertTrue(body.contains("\"videoCodec\":\"hevc\""))
        assertTrue(body.contains("3840"))
    }

    @Test
    fun `POST hotswap dispatches HotSwapStream command`() = runTest {
        val response = server.serve(
            FakeSession(
                method = NanoHTTPD.Method.POST,
                uri = "/api/v1/playback/hotswap",
                headers = mapOf("authorization" to "Bearer tok123"),
                postData = """{"streamUrl":"https://cdn.example.com/alt.mkv","title":"Alt 4K"}"""
            )
        )
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)

        val cmd = slot<AppCommand>()
        coVerify { appCommandBus.dispatch(capture(cmd)) }
        assertTrue(cmd.captured is AppCommand.HotSwapStream)
        val hs = cmd.captured as AppCommand.HotSwapStream
        assertEquals("https://cdn.example.com/alt.mkv", hs.streamUrl)
        assertEquals("Alt 4K", hs.title)
    }

    @Test
    fun `POST queue reorder dispatches ReorderQueue command`() = runTest {
        val response = server.serve(
            FakeSession(
                method = NanoHTTPD.Method.POST,
                uri = "/api/v1/queue/reorder",
                headers = mapOf("authorization" to "Bearer tok123"),
                postData = """{"fromIndex":2,"toIndex":0}"""
            )
        )
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)

        val cmd = slot<AppCommand>()
        coVerify { appCommandBus.dispatch(capture(cmd)) }
        assertTrue(cmd.captured is AppCommand.ReorderQueue)
        val rq = cmd.captured as AppCommand.ReorderQueue
        assertEquals(2, rq.fromIndex)
        assertEquals(0, rq.toIndex)
    }

    @Test
    fun `POST livetv tune dispatches TuneLiveChannel command`() = runTest {
        val response = server.serve(
            FakeSession(
                method = NanoHTTPD.Method.POST,
                uri = "/api/v1/livetv/tune",
                headers = mapOf("authorization" to "Bearer tok123"),
                postData = """{"channelId":"ai-9000","startFromBeginning":true}"""
            )
        )
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)

        val cmd = slot<AppCommand>()
        coVerify { appCommandBus.dispatch(capture(cmd)) }
        assertTrue(cmd.captured is AppCommand.TuneLiveChannel)
        val tune = cmd.captured as AppCommand.TuneLiveChannel
        assertEquals("ai-9000", tune.channelId)
        assertTrue(tune.startFromBeginning)
    }

    @Test
    fun `POST play stream dispatches PlayStream command`() = runTest {
        val response = server.serve(
            FakeSession(
                method = NanoHTTPD.Method.POST,
                uri = "/api/v1/play/stream",
                headers = mapOf("authorization" to "Bearer tok123"),
                postData = """{"streamUrl":"https://cdn.example.com/play.mkv","title":"Play Me"}"""
            )
        )
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)

        val cmd = slot<AppCommand>()
        coVerify { appCommandBus.dispatch(capture(cmd)) }
        assertTrue(cmd.captured is AppCommand.PlayStream)
        val ps = cmd.captured as AppCommand.PlayStream
        assertEquals("https://cdn.example.com/play.mkv", ps.streamUrl)
        assertEquals("Play Me", ps.title)
    }

    private class FakeSession(
        private val method: NanoHTTPD.Method,
        private val uri: String,
        private val headers: Map<String, String> = emptyMap(),
        private val postData: String? = null
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
