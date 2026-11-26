package com.nuvio.tv.core.music

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nuvio.tv.data.local.EclipseSettings
import com.nuvio.tv.data.local.EclipseSettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class EclipsePlaylistDetail(
    val playlist: EclipsePlaylistSummary,
    val tracks: List<EclipseTrack>,
)

data class EclipseAlbumDetail(
    val album: EclipseAlbumSummary,
    val tracks: List<EclipseTrack>,
)

data class EclipseArtistDetail(
    val artist: EclipseArtistSummary,
    val topSongs: List<EclipseTrack>,
)

data class EclipseDjStartResult(
    val sessionId: String?,
    val conversationId: String?,
    val message: String?,
    val commentary: String? = null,
    val mood: String? = null,
    val tracks: List<EclipseTrack>,
)

/**
 * HTTP REST client for the LAN EclipseMCP bridge (/health and /api/eclipse endpoints).
 */
@Singleton
class EclipseMusicClient @Inject constructor(
    okHttpClient: OkHttpClient,
    private val settingsDataStore: EclipseSettingsDataStore,
) {
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val djCurateClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val longReadClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun health(): Result<EclipseHealthStatus> = withContext(Dispatchers.IO) {
        val settings = settingsDataStore.getSettings()
        val url = "${settings.bridgeBaseUrl}/health"
        runCatching {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code}: ${body.take(180)}")
                }
                gson.fromJson(body, EclipseHealthStatus::class.java)
            }
        }
    }

    suspend fun fetchHome(): Result<EclipseHomeResponse> = getJson("/api/eclipse/home", EclipseHomeResponse::class.java)

    suspend fun search(
        query: String,
        source: String = "auto",
        limit: Int = 15,
    ): Result<EclipseSearchResponse> = withContext(Dispatchers.IO) {
        val settings = settingsDataStore.getSettings()
        if (!settings.enabled) return@withContext Result.failure(IllegalStateException("Eclipse Music is disabled in Settings"))
        val httpUrl = "${settings.bridgeBaseUrl}/api/eclipse/search".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", query.trim())
            ?.addQueryParameter("source", source)
            ?.addQueryParameter("limit", limit.toString())
            ?.build()
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid Eclipse bridge URL"))
        executeJson(
            Request.Builder()
                .url(httpUrl)
                .applyAuth(settings)
                .get()
                .build(),
            EclipseSearchResponse::class.java,
        )
    }

    suspend fun fetchPlaylist(playlistId: String): Result<EclipsePlaylistDetail> = withContext(Dispatchers.IO) {
        val encoded = encodeSegment(playlistId)
        getJsonObject("/api/eclipse/playlists/$encoded").mapCatching { json ->
            val playlist = gson.fromJson(json.getAsJsonObject("playlist"), EclipsePlaylistSummary::class.java)
                ?: EclipsePlaylistSummary(id = playlistId, name = "Playlist")
            val tracks = gson.fromJson(json.getAsJsonArray("tracks"), Array<EclipseTrack>::class.java)?.toList().orEmpty()
            EclipsePlaylistDetail(playlist = playlist, tracks = tracks)
        }
    }

    suspend fun fetchAlbum(albumId: String): Result<EclipseAlbumDetail> = withContext(Dispatchers.IO) {
        val encoded = encodeSegment(albumId)
        getJsonObject("/api/eclipse/albums/$encoded").mapCatching { json ->
            val album = gson.fromJson(json.getAsJsonObject("album"), EclipseAlbumSummary::class.java)
                ?: EclipseAlbumSummary(id = albumId, title = "Album")
            val tracks = gson.fromJson(json.getAsJsonArray("tracks"), Array<EclipseTrack>::class.java)?.toList().orEmpty()
            EclipseAlbumDetail(album = album, tracks = tracks)
        }
    }

    suspend fun fetchArtist(artistId: String): Result<EclipseArtistDetail> = withContext(Dispatchers.IO) {
        val encoded = encodeSegment(artistId)
        getJsonObject("/api/eclipse/artists/$encoded").mapCatching { json ->
            val artist = gson.fromJson(json.getAsJsonObject("artist"), EclipseArtistSummary::class.java)
                ?: EclipseArtistSummary(id = artistId, name = "Artist")
            val topSongs = gson.fromJson(json.getAsJsonArray("top_songs"), Array<EclipseTrack>::class.java)?.toList().orEmpty()
            EclipseArtistDetail(artist = artist, topSongs = topSongs)
        }
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSec: Int = 0,
    ): String? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null
        val settings = settingsDataStore.getSettings()
        if (!settings.enabled || settings.mcpBearerToken.isBlank()) return@withContext null
        val httpUrl = "${settings.bridgeBaseUrl}/api/eclipse/lyrics".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("title", title)
            ?.addQueryParameter("artist", artist)
            ?.apply {
                if (!album.isNullOrBlank()) addQueryParameter("album", album)
                if (durationSec > 0) addQueryParameter("duration", durationSec.toString())
            }
            ?.build()
            ?: return@withContext null
        runCatching {
            val req = Request.Builder().url(httpUrl).applyAuth(settings).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val obj = gson.fromJson(resp.body?.string().orEmpty(), JsonObject::class.java)
                obj?.get("lyrics_lrc")?.takeIf { !it.isJsonNull }?.asString
            }
        }.getOrNull()
    }

    suspend fun startUmbraDj(prompt: String? = null, fresh: Boolean = true): Result<EclipseDjStartResult> =
        postJsonObject(
            "/api/eclipse/dj/start",
            mapOf("prompt" to (prompt?.trim() ?: ""), "fresh" to fresh),
            httpClient = djCurateClient,
        ).mapCatching { json ->
            val msg = json.get("message")?.takeIf { !it.isJsonNull }?.asString
            EclipseDjStartResult(
                sessionId = json.get("session_id")?.takeIf { !it.isJsonNull }?.asString,
                conversationId = json.get("conversation_id")?.takeIf { !it.isJsonNull }?.asString,
                message = msg,
                commentary = json.get("commentary")?.takeIf { !it.isJsonNull }?.asString ?: msg,
                mood = json.get("mood")?.takeIf { !it.isJsonNull }?.asString,
                tracks = parseTracks(json),
            )
        }

    suspend fun startProDjSet(
        playlistId: String? = null,
        tracks: List<EclipseTrack>? = null,
        prompt: String? = null,
        includeSurprises: Boolean = true,
        surpriseCount: Int = 2,
    ): Result<EclipseDjStartResult> = withContext(Dispatchers.IO) {
        val settings = settingsDataStore.getSettings()
        if (!settings.enabled) return@withContext Result.failure(IllegalStateException("Eclipse Music is disabled in Settings"))
        val payload = buildMap<String, Any?> {
            if (!playlistId.isNullOrBlank()) put("playlist_id", playlistId)
            if (!tracks.isNullOrEmpty()) put("tracks", tracks.take(30))
            if (!prompt.isNullOrBlank()) put("prompt", prompt.trim())
            put("include_surprises", includeSurprises)
            put("surprise_count", surpriseCount.coerceIn(0, 5))
        }
        val body = gson.toJson(payload).toRequestBody(jsonMediaType)
        val req = Request.Builder()
            .url("${settings.bridgeBaseUrl}/api/eclipse/dj/pro-set")
            .applyAuth(settings)
            .post(body)
            .build()
        executeJson(req, JsonObject::class.java, httpClient = djCurateClient).mapCatching { json ->
            val comm = json.get("commentary")?.takeIf { !it.isJsonNull }?.asString
            val parsedTracks = parseTracks(json)
            if (parsedTracks.isEmpty()) {
                Log.w(TAG, "startProDjSet returned empty tracks (playlistId=$playlistId, prompt=${prompt?.take(80)})")
            }
            EclipseDjStartResult(
                sessionId = json.get("session_id")?.takeIf { !it.isJsonNull }?.asString,
                conversationId = null,
                message = comm,
                commentary = comm,
                mood = json.get("mood")?.takeIf { !it.isJsonNull }?.asString,
                tracks = parsedTracks,
            )
        }.onFailure { e ->
            Log.w(TAG, "startProDjSet request failed (playlistId=$playlistId, prompt=${prompt?.take(80)})", e)
        }
    }

    suspend fun analyzeTrackAudio(track: EclipseTrack): DjAudioAnalysisResult? = withContext(Dispatchers.IO) {
        val settings = settingsDataStore.getSettings()
        if (!settings.enabled || settings.mcpBearerToken.isBlank()) return@withContext null
        val httpUrl = "${settings.bridgeBaseUrl}/api/eclipse/audio/analysis".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("url", track.streamUrl)
            ?.addQueryParameter("title", track.title)
            ?.addQueryParameter("artist", track.artist)
            ?.apply {
                val genre = track.enrichment?.genre
                if (!genre.isNullOrBlank()) addQueryParameter("genre", genre)
                if (track.durationSec > 0) addQueryParameter("duration", track.durationSec.toString())
            }
            ?.build()
            ?: return@withContext null
        runCatching {
            val req = Request.Builder().url(httpUrl).applyAuth(settings).get().build()
            longReadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                gson.fromJson(resp.body?.string().orEmpty(), DjAudioAnalysisResult::class.java)
            }
        }.getOrNull()
    }

    suspend fun nextUmbraDj(sessionId: String? = null): Result<EclipseDjStartResult> =
        postJsonObject(
            "/api/eclipse/dj/next",
            buildMap {
                if (!sessionId.isNullOrBlank()) put("session_id", sessionId)
            },
        ).mapCatching { json ->
            EclipseDjStartResult(
                sessionId = json.get("session_id")?.takeIf { !it.isJsonNull }?.asString ?: sessionId,
                conversationId = null,
                message = null,
                tracks = parseTracks(json),
            )
        }

    suspend fun stopUmbraDj(sessionId: String? = null): Result<Unit> =
        postJsonObject(
            "/api/eclipse/dj/stop",
            buildMap {
                if (!sessionId.isNullOrBlank()) put("session_id", sessionId)
            },
        ).map { Unit }

    suspend fun generatePlaylist(prompt: String, count: Int = 25, save: Boolean = true): Result<List<EclipseTrack>> =
        postJsonObject(
            "/api/eclipse/playlist/generate",
            mapOf("prompt" to prompt.trim(), "count" to count, "save" to save),
            httpClient = djCurateClient,
        ).mapCatching { json ->
            parseTracks(json)
        }

    suspend fun playRadio(kind: String, query: String = "", mixIndex: Int = 0): Result<List<EclipseTrack>> =
        postJsonObject(
            "/api/eclipse/radio",
            mapOf("kind" to kind, "query" to query, "mix_index" to mixIndex),
        ).mapCatching { json ->
            parseTracks(json)
        }

    suspend fun resolveQueue(
        tracks: List<EclipseTrack>,
        includeLyrics: Boolean = true,
    ): Result<List<EclipseTrack>> =
        postJsonObject(
            "/api/eclipse/resolve-queue",
            mapOf("tracks" to tracks, "include_lyrics" to includeLyrics),
            httpClient = longReadClient,
        ).mapCatching { json ->
            parseTracks(json)
        }

    private suspend fun <T> getJson(path: String, clazz: Class<T>): Result<T> = withContext(Dispatchers.IO) {
        val settings = settingsDataStore.getSettings()
        if (!settings.enabled) return@withContext Result.failure(IllegalStateException("Eclipse Music is disabled in Settings"))
        val req = Request.Builder()
            .url("${settings.bridgeBaseUrl}$path")
            .applyAuth(settings)
            .get()
            .build()
        executeJson(req, clazz)
    }

    private suspend fun getJsonObject(path: String): Result<JsonObject> = getJson(path, JsonObject::class.java)

    private suspend fun postJsonObject(
        path: String,
        bodyMap: Map<String, Any?>,
        httpClient: OkHttpClient = client,
    ): Result<JsonObject> =
        withContext(Dispatchers.IO) {
            val settings = settingsDataStore.getSettings()
            if (!settings.enabled) return@withContext Result.failure(IllegalStateException("Eclipse Music is disabled in Settings"))
            val body = gson.toJson(bodyMap).toRequestBody(jsonMediaType)
            val req = Request.Builder()
                .url("${settings.bridgeBaseUrl}$path")
                .applyAuth(settings)
                .post(body)
                .build()
            executeJson(req, JsonObject::class.java, httpClient = httpClient)
        }

    private fun <T> executeJson(
        request: Request,
        clazz: Class<T>,
        httpClient: OkHttpClient = client,
    ): Result<T> = runCatching {
        httpClient.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val errMsg = runCatching {
                    val obj = gson.fromJson(raw, JsonObject::class.java)
                    val error = obj?.get("error")?.takeIf { !it.isJsonNull }?.asString
                    val detail = obj?.get("detail")?.takeIf { !it.isJsonNull }?.asString
                    error ?: detail
                }.getOrNull()
                val msg = errMsg ?: "HTTP ${resp.code}: ${raw.take(200)}"
                Log.w(TAG, "Eclipse API ${request.method} ${request.url.encodedPath} failed (${resp.code}): $msg")
                throw IOException(msg)
            }
            gson.fromJson(raw, clazz) ?: throw IOException("Empty JSON response")
        }
    }

    private fun parseTracks(json: JsonObject): List<EclipseTrack> =
        json.get("tracks")
            ?.takeIf { it.isJsonArray }
            ?.let { gson.fromJson(it, Array<EclipseTrack>::class.java)?.toList() }
            .orEmpty()

    private fun Request.Builder.applyAuth(settings: EclipseSettings): Request.Builder {
        if (settings.mcpBearerToken.isNotBlank()) {
            header("Authorization", "Bearer ${settings.mcpBearerToken}")
        }
        return this
    }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        private const val TAG = "EclipseMusicClient"
    }
}
