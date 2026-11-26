package com.nuvio.tv.ui.screens.music

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.music.DjConsoleTelemetry
import com.nuvio.tv.core.music.EclipseAudioPlayer
import com.nuvio.tv.core.music.EclipseAudioTelemetry
import com.nuvio.tv.core.music.EclipseDjStartResult
import com.nuvio.tv.core.music.EclipseDjTrackMeta
import com.nuvio.tv.core.music.EclipseMusicClient
import com.nuvio.tv.core.music.EclipseSearchResponse
import com.nuvio.tv.core.music.EclipseTrack
import com.nuvio.tv.data.local.EclipseSettings
import com.nuvio.tv.data.local.EclipseSettingsDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class EclipseMusicViewModelProDjTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val settingsDataStore: EclipseSettingsDataStore = mockk(relaxed = true)
    private val audioPlayer: EclipseAudioPlayer = mockk(relaxed = true)
    private val musicClient: EclipseMusicClient = mockk(relaxed = true)

    private fun stubDefaults() {
        every { settingsDataStore.settings } returns MutableStateFlow(EclipseSettings(enabled = false))
        every { audioPlayer.telemetry } returns MutableStateFlow(EclipseAudioTelemetry())
        every { audioPlayer.djTelemetry } returns MutableStateFlow(DjConsoleTelemetry())
        coEvery { audioPlayer.startDjMixSet(any(), any(), any(), any()) } returns Unit
        coEvery { audioPlayer.injectDjSurprises(any(), any()) } returns Unit
    }

    private fun track(
        id: String,
        streamUrl: String = "",
        isWildcard: Boolean = false,
        title: String = "Title-$id",
        artist: String = "Artist-$id",
    ): EclipseTrack = EclipseTrack(
        id = id,
        title = title,
        artist = artist,
        streamUrl = streamUrl,
        djMeta = EclipseDjTrackMeta(
            bpm = 126f,
            musicalKey = "9A",
            isWildcard = isWildcard,
            djWhy = "why-$id",
        ),
    )

    private fun startResult(
        sessionId: String = "s1",
        tracks: List<EclipseTrack> = emptyList(),
    ): EclipseDjStartResult = EclipseDjStartResult(
        sessionId = sessionId,
        conversationId = null,
        message = "msg",
        commentary = "commentary",
        mood = "mood",
        tracks = tracks,
    )

    @Test
    fun startProDjSet_resolvesBlankStreamUrls_preservesDjMeta_andStarts() = runTest {
        stubDefaults()

        val proTrack1 = track("pro1", streamUrl = "")
        val proTrack2 = track("pro2", streamUrl = "")
        val resolved1 = EclipseTrack(id = "pro1", title = "Title-pro1", artist = "Artist-pro1", streamUrl = "http://resolved/pro1")
        val resolved2 = EclipseTrack(id = "pro2", title = "Title-pro2", artist = "Artist-pro2", streamUrl = "http://resolved/pro2")

        coEvery { musicClient.startProDjSet(any(), any(), any(), any(), any()) } returns
            Result.success(startResult(tracks = listOf(proTrack1, proTrack2)))
        coEvery { musicClient.resolveQueue(any(), any()) } returns Result.success(listOf(resolved1, resolved2))

        val viewModel = EclipseMusicViewModel(musicClient, audioPlayer, settingsDataStore)
        runCurrent()

        var started = false
        viewModel.startProDjSet(
            prompt = "deep house",
            onStarted = { started = true },
        )
        runCurrent()

        coVerify(exactly = 1) { musicClient.resolveQueue(any(), false) }
        coVerify(exactly = 1) {
            audioPlayer.startDjMixSet(
                match { tracks ->
                    tracks.size == 2 &&
                        tracks[0].streamUrl == "http://resolved/pro1" &&
                        tracks[1].streamUrl == "http://resolved/pro2" &&
                        tracks.all { it.djMeta?.bpm == 126f && it.djMeta?.musicalKey == "9A" }
                },
                "s1",
                "commentary",
                "mood",
            )
        }
        assertTrue(started)
        assertFalse(viewModel.uiState.value.isDjBusy)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun startProDjSet_fallsBackToFallbackTracks_whenProSetFails() = runTest {
        stubDefaults()

        val fallback = track("fb1", streamUrl = "")
        val resolvedFallback = fallback.copy(streamUrl = "http://resolved/fb1")

        coEvery { musicClient.startProDjSet(any(), any(), any(), any(), any()) } returns
            Result.failure(IOException("boom"))
        coEvery { musicClient.resolveQueue(any(), any()) } returns Result.success(listOf(resolvedFallback))

        val viewModel = EclipseMusicViewModel(musicClient, audioPlayer, settingsDataStore)
        runCurrent()

        var started = false
        viewModel.startProDjSet(
            playlistId = "pl_1",
            fallbackTracks = listOf(fallback),
            onStarted = { started = true },
        )
        runCurrent()

        coVerify(exactly = 1) {
            audioPlayer.startDjMixSet(
                match { tracks -> tracks.size == 1 && tracks.first().streamUrl == "http://resolved/fb1" },
                any(),
                any(),
                any(),
            )
        }
        assertTrue(started)
        assertFalse(viewModel.uiState.value.isDjBusy)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun startProDjSet_fallsBackToUmbraDj_whenProSetFailsAndNoSeedTracksExist() = runTest {
        stubDefaults()

        val umbraTrack = track("umbra1", streamUrl = "")
        val resolvedUmbra = umbraTrack.copy(streamUrl = "http://resolved/umbra1")

        coEvery { musicClient.startProDjSet(any(), any(), any(), any(), any()) } returns
            Result.failure(IOException("pro-set unavailable"))
        coEvery { musicClient.startUmbraDj(any(), any()) } returns
            Result.success(startResult(sessionId = "umbra-s1", tracks = listOf(umbraTrack)))
        coEvery { musicClient.resolveQueue(any(), any()) } returns Result.success(listOf(resolvedUmbra))

        val viewModel = EclipseMusicViewModel(musicClient, audioPlayer, settingsDataStore)
        runCurrent()

        var started = false
        viewModel.startProDjSet(
            prompt = "late night chill",
            onStarted = { started = true },
        )
        runCurrent()

        coVerify(exactly = 1) { musicClient.startUmbraDj("late night chill", true) }
        coVerify(exactly = 1) {
            audioPlayer.startDjMixSet(
                match { tracks -> tracks.size == 1 && tracks.first().streamUrl == "http://resolved/umbra1" },
                "umbra-s1",
                any(),
                any(),
            )
        }
        assertTrue(started)
        assertFalse(viewModel.uiState.value.isDjBusy)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun startProDjSet_fallsBackToGeneratePlaylist_whenDjCuratorsTimeOut() = runTest {
        stubDefaults()

        val genTrack = track("gen1", streamUrl = "")
        val resolvedGen = genTrack.copy(streamUrl = "http://resolved/gen1")

        coEvery { musicClient.startProDjSet(any(), any(), any(), any(), any()) } returns
            Result.failure(java.net.SocketTimeoutException("timeout"))
        coEvery { musicClient.startUmbraDj(any(), any()) } returns
            Result.failure(java.net.SocketTimeoutException("timeout"))
        coEvery { musicClient.generatePlaylist("90s hip hop", any(), false) } returns
            Result.success(listOf(genTrack))
        coEvery { musicClient.resolveQueue(any(), false) } returns
            Result.success(listOf(resolvedGen))

        val viewModel = EclipseMusicViewModel(musicClient, audioPlayer, settingsDataStore)
        runCurrent()

        var started = false
        viewModel.startProDjSet(
            prompt = "90s hip hop",
            onStarted = { started = true },
        )
        runCurrent()

        assertTrue(started)
        assertFalse(viewModel.uiState.value.isDjBusy)
        assertNull(viewModel.uiState.value.error)
        coVerify(exactly = 1) {
            audioPlayer.startDjMixSet(
                match { it.size == 1 && it.first().streamUrl == "http://resolved/gen1" },
                any(),
                any(),
                "90s hip hop",
            )
        }
    }

    @Test
    fun startProDjSet_fallsBackToSearch_whenAiEndpointsTimeOutOnVoiceTopic() = runTest {
        stubDefaults()

        val searchTrack = track("srch1", streamUrl = "")
        val resolvedSearch = searchTrack.copy(streamUrl = "http://resolved/srch1")

        coEvery { musicClient.startProDjSet(any(), any(), any(), any(), any()) } returns
            Result.failure(java.net.SocketTimeoutException("timeout"))
        coEvery { musicClient.startUmbraDj(any(), any()) } returns
            Result.failure(java.net.SocketTimeoutException("timeout"))
        coEvery { musicClient.generatePlaylist(any(), any(), any()) } returns
            Result.failure(java.net.SocketTimeoutException("timeout"))
        coEvery { musicClient.search("synthwave workout", any(), any()) } returns
            Result.success(EclipseSearchResponse(tracks = listOf(searchTrack)))
        coEvery { musicClient.resolveQueue(any(), false) } returns
            Result.success(listOf(resolvedSearch))

        val viewModel = EclipseMusicViewModel(musicClient, audioPlayer, settingsDataStore)
        runCurrent()

        var started = false
        viewModel.startProDjSet(
            prompt = "synthwave workout",
            onStarted = { started = true },
        )
        runCurrent()

        assertTrue(started)
        assertFalse(viewModel.uiState.value.isDjBusy)
        assertNull(viewModel.uiState.value.error)
        coVerify(exactly = 1) { musicClient.search("synthwave workout", "auto", 20) }
        coVerify(exactly = 1) {
            audioPlayer.startDjMixSet(
                match { it.size == 1 && it.first().streamUrl == "http://resolved/srch1" },
                any(),
                any(),
                "synthwave workout",
            )
        }
    }

    @Test
    fun generateAiMix_fallsBackToKeywordSearch_whenGeneratePlaylistReturnsNetworkError() = runTest {
        stubDefaults()
        coEvery { audioPlayer.playQueue(any(), any(), any(), any(), any()) } returns Unit

        val foundTrack = track("mix1", streamUrl = "")
        val resolvedFound = foundTrack.copy(streamUrl = "http://resolved/mix1")

        coEvery { musicClient.generatePlaylist(any(), any(), any()) } returns
            Result.failure(IOException("Network error contacting Eclipse"))
        coEvery { musicClient.startUmbraDj(any(), any()) } returns
            Result.failure(IOException("Network error contacting Eclipse"))
        coEvery { musicClient.search("play some chill jazz music", any(), any()) } returns
            Result.success(EclipseSearchResponse(tracks = emptyList()))
        coEvery { musicClient.search("chill jazz", any(), any()) } returns
            Result.success(EclipseSearchResponse(tracks = listOf(foundTrack)))
        coEvery { musicClient.resolveQueue(any(), false) } returns
            Result.success(listOf(resolvedFound))

        val viewModel = EclipseMusicViewModel(musicClient, audioPlayer, settingsDataStore)
        runCurrent()

        var started = false
        viewModel.generateAiMix("play some chill jazz music", onStarted = { started = true })
        runCurrent()

        assertTrue(started)
        assertFalse(viewModel.uiState.value.isDjBusy)
        assertNull(viewModel.uiState.value.error)
        coVerify(exactly = 1) {
            audioPlayer.playQueue(
                match { it.size == 1 && it.first().streamUrl == "http://resolved/mix1" },
                0,
                true,
                "eclipse_ai_mix",
                null,
            )
        }
    }

    @Test
    fun startProDjSet_surfacesError_whenAllTiersFail() = runTest {
        stubDefaults()

        coEvery { musicClient.startProDjSet(any(), any(), any(), any(), any()) } returns
            Result.failure(IOException("Unauthorized"))
        coEvery { musicClient.startUmbraDj(any(), any()) } returns
            Result.failure(IOException("Unauthorized"))
        coEvery { musicClient.generatePlaylist(any(), any(), any()) } returns
            Result.failure(IOException("Unauthorized"))
        coEvery { musicClient.search(any(), any(), any()) } returns
            Result.failure(IOException("Unauthorized"))
        coEvery { musicClient.playRadio(any(), any(), any()) } returns
            Result.failure(IOException("Unauthorized"))

        val viewModel = EclipseMusicViewModel(musicClient, audioPlayer, settingsDataStore)
        runCurrent()

        var started = false
        viewModel.startProDjSet(
            prompt = "test",
            onStarted = { started = true },
        )
        runCurrent()

        assertFalse(started)
        assertFalse(viewModel.uiState.value.isDjBusy)
        assertEquals("Unauthorized", viewModel.uiState.value.error)
        coVerify(exactly = 0) { audioPlayer.startDjMixSet(any(), any(), any(), any()) }
    }

    @Test
    fun injectDjWildcard_resolvesBlankStreamUrl_andInjects() = runTest {
        stubDefaults()

        val wildcard = track("wild1", streamUrl = "", isWildcard = true)
        val resolvedWildcard = wildcard.copy(streamUrl = "http://resolved/wild1")

        coEvery { musicClient.startProDjSet(any(), any(), any(), any(), any()) } returns
            Result.success(startResult(tracks = listOf(wildcard)))
        coEvery { musicClient.resolveQueue(any(), any()) } returns Result.success(listOf(resolvedWildcard))

        val viewModel = EclipseMusicViewModel(musicClient, audioPlayer, settingsDataStore)
        runCurrent()

        viewModel.injectDjWildcard("disco surprise")
        runCurrent()

        coVerify(exactly = 1) { musicClient.resolveQueue(any(), false) }
        coVerify(exactly = 1) {
            audioPlayer.injectDjSurprises(
                match { tracks -> tracks.size == 1 && tracks.first().streamUrl == "http://resolved/wild1" },
                any(),
            )
        }
        assertFalse(viewModel.uiState.value.isDjBusy)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun eclipseMusicClient_handlesNullTracks_andSurfacesFastApiDetailError() = runBlocking {
        val nullTracksClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"session_id":"s1","tracks":null}""".toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()

        val settingsStore = mockk<EclipseSettingsDataStore>(relaxed = true)
        coEvery { settingsStore.getSettings() } returns EclipseSettings(
            enabled = true,
            bridgeBaseUrl = "http://localhost:8771",
            mcpBearerToken = "tok",
        )

        val clientOk = EclipseMusicClient(nullTracksClient, settingsStore)
        val okRes = clientOk.startProDjSet(prompt = "chill")
        assertTrue("Expected success result, got: $okRes", okRes.isSuccess)
        assertEquals(0, okRes.getOrNull()?.tracks?.size)

        val unauthorizedHttpClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(401)
                        .message("Unauthorized")
                        .body("""{"detail":"Unauthorized"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()

        val clientErr = EclipseMusicClient(unauthorizedHttpClient, settingsStore)
        val errRes = clientErr.startProDjSet(prompt = "chill")
        assertTrue("Expected failure result", errRes.isFailure)
        val msg = errRes.exceptionOrNull()?.message
        if (msg == null) {
            fail("Expected exception message for 401 response")
        } else {
            assertTrue("Expected 'Unauthorized' in '$msg'", msg.contains("Unauthorized"))
        }
    }
}
