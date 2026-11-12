package com.nuvio.tv.core.server

import android.content.Context
import com.google.gson.Gson
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.ai.ThematicChannelGenerator
import com.nuvio.tv.core.control.AppCommand
import com.nuvio.tv.core.control.AppCommandBus
import com.nuvio.tv.core.control.CommandResult
import com.nuvio.tv.core.control.DpadKey
import com.nuvio.tv.core.control.NuvioStatusResponse
import com.nuvio.tv.core.control.PlayerPlaybackBridge
import com.nuvio.tv.core.control.QueueItemSnapshot
import com.nuvio.tv.core.control.QueueSnapshot
import com.nuvio.tv.core.control.RemoteControlSettingsDataStore
import com.nuvio.tv.core.livetv.AiBroadcastNetwork
import com.nuvio.tv.core.livetv.LiveTvManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.playlist.PlaylistItem
import com.nuvio.tv.core.playlist.PlaylistManager
import com.nuvio.tv.data.simkl.calendar.CalendarItemType
import com.nuvio.tv.data.simkl.calendar.SimklCalendarRepository
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.time.LocalDate

/**
 * Embedded HTTP server that exposes a REST API for controlling Nuvio TV.
 *
 * Endpoints under /api/v1/ require authentication via Bearer token or X-Nuvio-Key header,
 * except for /api/v1/pair, /api/v1/qr, and the web remote UI at /.
 */
class NuvioControlServer(
    private val context: Context,
    private val appCommandBus: AppCommandBus,
    private val playerPlaybackBridge: PlayerPlaybackBridge,
    private val remoteControlSettingsDataStore: RemoteControlSettingsDataStore,
    private val thematicChannelGenerator: ThematicChannelGenerator,
    private val watchProgressRepository: WatchProgressRepository,
    private val simklCalendarRepository: SimklCalendarRepository,
    private val addonRepository: AddonRepository? = null,
    private val catalogRepository: CatalogRepository? = null,
    private val streamRepository: StreamRepository? = null,
    port: Int = 8910
) : NanoHTTPD(port) {

    private val gson = Gson()

    companion object {
        private const val API_PREFIX = "/api/v1"
        private const val BEARER_PREFIX = "Bearer "
        private const val HEADER_AUTHORIZATION = "authorization"
        private const val HEADER_API_KEY = "x-nuvio-key"
        private const val PARAM_TOKEN = "token"
        private const val PARAM_KEY = "key"
        private const val PARAM_PIN = "pin"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        // Zero-install PWA static assets (unauthenticated; API calls require pairing token)
        when (uri) {
            "/", "/index.html" -> return serveText(NuvioWebRemoteHtml.HTML, "text/html; charset=utf-8")
            "/app.js" -> return serveText(NuvioWebRemoteScript.JS, "application/javascript; charset=utf-8")
            "/manifest.webmanifest", "/manifest.json" -> return serveText(
                NuvioWebRemoteHtml.MANIFEST_JSON,
                "application/manifest+json; charset=utf-8"
            )
            "/icon.svg" -> return serveText(NuvioWebRemoteHtml.ICON_SVG, "image/svg+xml; charset=utf-8")
        }

        // Pairing endpoints (unauthenticated)
        if (uri == "$API_PREFIX/pair" || uri == "$API_PREFIX/qr") {
            return when {
                uri == "$API_PREFIX/pair" && (method == Method.POST || method == Method.GET) -> handlePair(session)
                uri == "$API_PREFIX/qr" -> handleQr()
                else -> jsonError(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed")
            }
        }

        // All other /api/v1/* endpoints require authorization
        if (uri.startsWith(API_PREFIX)) {
            if (!isAuthorized(session)) {
                return jsonError(Response.Status.UNAUTHORIZED, "Unauthorized: Invalid or missing API key")
            }
        }

        return try {
            route(session, uri, method)
        } catch (t: Throwable) {
            jsonError(Response.Status.INTERNAL_ERROR, t.message ?: "Internal error")
        }
    }

    private fun route(session: IHTTPSession, uri: String, method: Method): Response {
        return when {
            uri == "$API_PREFIX/status" && method == Method.GET -> handleStatus()
            uri == "$API_PREFIX/telemetry" && method == Method.GET -> handleTelemetry()
            uri == "$API_PREFIX/streams/current" && method == Method.GET -> handleCurrentStreams()
            uri == "$API_PREFIX/streams/browse" && method == Method.GET -> handleBrowseStreams(session)
            uri == "$API_PREFIX/search" && method == Method.GET -> handleCatalogSearch(session)
            uri == "$API_PREFIX/continue-watching" && method == Method.GET -> handleContinueWatching()
            uri == "$API_PREFIX/calendar/tv" && method == Method.GET -> handleTvCalendar(session)

            uri == "$API_PREFIX/playback/play_pause" && method == Method.POST -> dispatch(AppCommand.PlayPause)
            uri == "$API_PREFIX/playback/play" && method == Method.POST -> dispatch(AppCommand.Play)
            uri == "$API_PREFIX/playback/pause" && method == Method.POST -> dispatch(AppCommand.Pause)
            uri == "$API_PREFIX/playback/stop" && method == Method.POST -> dispatch(AppCommand.Stop)
            uri == "$API_PREFIX/playback/seek" && method == Method.POST -> handleSeek(session)
            uri == "$API_PREFIX/playback/next" && method == Method.POST -> dispatch(AppCommand.Next)
            uri == "$API_PREFIX/playback/previous" && method == Method.POST -> dispatch(AppCommand.Previous)
            uri == "$API_PREFIX/playback/speed" && method == Method.POST -> handleSpeed(session)
            uri == "$API_PREFIX/playback/audio_track" && method == Method.POST -> handleAudioTrack(session)
            uri == "$API_PREFIX/playback/subtitle_track" && method == Method.POST -> handleSubtitleTrack(session)
            uri == "$API_PREFIX/playback/subtitles/disable" && method == Method.POST -> dispatch(AppCommand.DisableSubtitles)
            uri == "$API_PREFIX/playback/skip_intro" && method == Method.POST -> dispatch(AppCommand.SkipIntro)
            uri == "$API_PREFIX/playback/hotswap" && method == Method.POST -> handleHotSwap(session)

            uri == "$API_PREFIX/navigation/open" && method == Method.POST -> handleOpenScreen(session)
            uri == "$API_PREFIX/navigation/search" && method == Method.POST -> handleSearch(session)
            uri == "$API_PREFIX/navigation/dpad" && method == Method.POST -> handleDpad(session)

            uri == "$API_PREFIX/play" && method == Method.POST -> handlePlayMedia(session)
            uri == "$API_PREFIX/play/stream" && method == Method.POST -> handlePlayStream(session)
            uri.startsWith("$API_PREFIX/play/requests/") && (method == Method.GET || method == Method.DELETE) -> {
                val id = uri.removePrefix("$API_PREFIX/play/requests/")
                if (method == Method.DELETE) appCommandBus.cancelPlaybackRequest(id)
                val request = appCommandBus.playbackRequestStatus(id)
                if (request == null) jsonError(Response.Status.NOT_FOUND, "Unknown playback request")
                else jsonResponse(gson.toJson(request))
            }

            uri == "$API_PREFIX/channels/curated" && method == Method.GET -> handleCuratedList()
            uri == "$API_PREFIX/channels/curated" && method == Method.POST -> handleCuratedPlay(session)
            uri == "$API_PREFIX/channels/thematic" && method == Method.POST -> handleThematic(session)

            uri == "$API_PREFIX/livetv/channels" && method == Method.GET -> handleLiveTvChannels()
            uri == "$API_PREFIX/livetv/tune" && method == Method.POST -> handleLiveTvTune(session)

            uri == "$API_PREFIX/ai/command" && method == Method.POST -> handleAiCommand(session)

            uri == "$API_PREFIX/queue" && method == Method.GET -> handleQueue()
            uri == "$API_PREFIX/queue" && method == Method.DELETE -> dispatch(AppCommand.ClearQueue)
            uri == "$API_PREFIX/queue" && method == Method.POST -> handleQueueAdd(session, isPlayNext = false)
            uri == "$API_PREFIX/queue/play-next" && method == Method.POST -> handleQueueAdd(session, isPlayNext = true)
            uri == "$API_PREFIX/queue/advance" && method == Method.POST -> dispatch(AppCommand.AdvanceQueue)
            uri == "$API_PREFIX/queue/reorder" && method == Method.POST -> handleQueueReorder(session)
            uri == "$API_PREFIX/queue/remove" && method == Method.POST -> handleQueueRemove(session)

            else -> jsonError(Response.Status.NOT_FOUND, "Endpoint not found: $uri")
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val settings = runBlocking { remoteControlSettingsDataStore.getSettings() }
        val expected = settings.apiToken
        if (expected.isBlank()) return false

        val authHeader = session.headers[HEADER_AUTHORIZATION]
        val bearer = authHeader?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)?.trim()
        val apiKey = session.headers[HEADER_API_KEY]?.trim()
        val paramToken = session.parameters[PARAM_TOKEN]?.firstOrNull()?.trim()
        val paramKey = session.parameters[PARAM_KEY]?.firstOrNull()?.trim()

        return (bearer == expected) || (apiKey == expected) || (paramToken == expected) || (paramKey == expected)
    }

    private fun handleStatus(): Response {
        val playback = playerPlaybackBridge.playbackSnapshot.value
        val queueItems = PlaylistManager.queue.value.map { it.toSnapshot() }
        val currentItem = PlaylistManager.currentItem()?.toSnapshot()
        val queueSnapshot = QueueSnapshot(
            size = queueItems.size,
            currentIndex = PlaylistManager.currentIndex.value,
            channelMode = PlaylistManager.channelMode.value.name,
            currentItem = currentItem,
            items = queueItems
        )

        val response = NuvioStatusResponse(
            app = "Nuvio TV",
            version = BuildConfig.VERSION_NAME,
            deviceIp = DeviceIpAddress.get(context),
            activeScreen = if (playback?.isActive == true) "player" else "main",
            playback = playback,
            queue = queueSnapshot
        )
        return jsonResponse(gson.toJson(response))
    }

    private fun handleContinueWatching(): Response {
        val items = runBlocking { withTimeout(5_000) { watchProgressRepository.continueWatching.first() } }
        val nextUpSeeds = runBlocking { withTimeout(5_000) { watchProgressRepository.observeNextUpSeeds().first() } }
        val progressLoaded = runBlocking { withTimeout(5_000) { watchProgressRepository.observeRemoteProgressLoaded().first() } }
        return jsonResponse(gson.toJson(mapOf(
            "progressLoaded" to progressLoaded,
            "items" to items.take(100).map { progress -> mapOf(
                "contentId" to progress.contentId,
                "contentType" to progress.contentType,
                "title" to progress.name,
                "videoId" to progress.videoId,
                "season" to progress.season,
                "episode" to progress.episode,
                "episodeTitle" to progress.episodeTitle,
                "positionMs" to progress.position,
                "durationMs" to progress.duration,
                "progressPercent" to progress.progressPercent,
                "lastWatchedMs" to progress.lastWatched,
                "source" to progress.source
            ) },
            "nextUpSeeds" to nextUpSeeds.take(100).map { progress -> mapOf(
                "contentId" to progress.contentId,
                "title" to progress.name,
                "lastCompletedSeason" to progress.season,
                "lastCompletedEpisode" to progress.episode,
                "lastWatchedMs" to progress.lastWatched
            ) }
        )))
    }

    private fun handleTvCalendar(session: IHTTPSession): Response {
        val period = session.parameters["period"]?.firstOrNull() ?: "both"
        if (period !in setOf("upcoming", "previous", "both")) {
            return jsonError(Response.Status.BAD_REQUEST, "period must be upcoming, previous, or both")
        }
        val days = session.parameters["days"]?.firstOrNull()?.toIntOrNull() ?: 7
        if (days !in 1..7) return jsonError(Response.Status.BAD_REQUEST, "days must be 1-7")
        val result = runBlocking { withTimeout(20_000) { simklCalendarRepository.getCalendarData() } }
        val calendar = result.getOrElse {
            return jsonError(Response.Status.SERVICE_UNAVAILABLE, "Simkl calendar is unavailable")
        }
        val today = LocalDate.now()
        val items = calendar.allItems.asSequence()
            .filter { it.type == CalendarItemType.TV_EPISODE }
            .filter { item ->
                when (period) {
                    "upcoming" -> item.date >= today && item.date <= today.plusDays(days.toLong())
                    "previous" -> item.date < today && item.date >= today.minusDays(days.toLong())
                    else -> item.date >= today.minusDays(days.toLong()) && item.date <= today.plusDays(days.toLong())
                }
            }
            .sortedBy { it.date }
            .take(100)
            .map { item -> mapOf(
                "title" to item.title,
                "date" to item.date.toString(),
                "airTime" to item.airTimeString,
                "season" to item.season,
                "episode" to item.episode,
                "episodeTitle" to item.episodeTitle,
                "imdbId" to item.imdbId,
                "tmdbId" to item.tmdbId,
                "simklId" to item.simklId,
                "isActivelyWatching" to item.isActivelyWatching,
                "isWatchlist" to item.isWatchlist,
                "isNextUpForUser" to item.isNextUpForUser
            ) }
            .toList()
        return jsonResponse(gson.toJson(mapOf(
            "period" to period,
            "days" to days,
            "today" to today.toString(),
            "timezone" to java.time.ZoneId.systemDefault().id,
            "items" to items
        )))
    }

    private fun dispatch(command: AppCommand): Response {
        val result = runBlocking { appCommandBus.dispatch(command) }
        return when (result) {
            is CommandResult.Success -> jsonOk(result.message, result.data)
            is CommandResult.Unavailable -> jsonError(Response.Status.CONFLICT, result.reason)
            is CommandResult.Error -> jsonError(Response.Status.INTERNAL_ERROR, result.message)
        }
    }

    private fun handleSeek(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val pos = body.get("positionMs")?.asLong
        val delta = body.get("deltaMs")?.asLong
        return when {
            pos != null -> dispatch(AppCommand.SeekTo(pos))
            delta != null -> dispatch(AppCommand.SeekBy(delta))
            else -> jsonError(Response.Status.BAD_REQUEST, "positionMs or deltaMs required")
        }
    }

    private fun handleSpeed(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val speed = body.get("speed")?.asFloat
            ?: return jsonError(Response.Status.BAD_REQUEST, "speed required")
        return dispatch(AppCommand.SetSpeed(speed))
    }

    private fun handleAudioTrack(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val index = body.get("index")?.asInt
            ?: return jsonError(Response.Status.BAD_REQUEST, "index required")
        return dispatch(AppCommand.SelectAudioTrack(index))
    }

    private fun handleSubtitleTrack(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val index = body.get("index")?.asInt
            ?: return jsonError(Response.Status.BAD_REQUEST, "index required")
        return dispatch(AppCommand.SelectSubtitleTrack(index))
    }

    private fun handleOpenScreen(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val screen = body.get("screen")?.asString
            ?: return jsonError(Response.Status.BAD_REQUEST, "screen required")
        return dispatch(AppCommand.OpenScreen(screen))
    }

    private fun handleSearch(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val query = body.get("query")?.asString
            ?: return jsonError(Response.Status.BAD_REQUEST, "query required")
        return dispatch(AppCommand.Search(query))
    }

    private fun handleDpad(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val key = body.get("key")?.asString?.lowercase()?.trim()
            ?: return jsonError(Response.Status.BAD_REQUEST, "key required")
        val dpadKey = when (key) {
            "up" -> DpadKey.UP
            "down" -> DpadKey.DOWN
            "left" -> DpadKey.LEFT
            "right" -> DpadKey.RIGHT
            "select", "enter", "ok", "center" -> DpadKey.SELECT
            "back" -> DpadKey.BACK
            "menu" -> DpadKey.MENU
            else -> return jsonError(Response.Status.BAD_REQUEST, "Unknown key: $key")
        }
        return dispatch(AppCommand.SendDpad(dpadKey))
    }

    private fun handlePlayMedia(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val contentId = body.get("contentId")?.asString
            ?: return jsonError(Response.Status.BAD_REQUEST, "contentId required")
        val type = body.get("type")?.asString ?: "movie"
        val season = body.get("season")?.asInt
        val episode = body.get("episode")?.asInt
        val title = body.get("title")?.asString
        return dispatch(
            AppCommand.PlayMedia(
                contentId = contentId,
                contentType = type,
                season = season,
                episode = episode,
                title = title
            )
        )
    }

    private fun handleCuratedList(): Response {
        val moods = thematicChannelGenerator.curatedMoods
        return jsonResponse(gson.toJson(moods))
    }

    private fun handleCuratedPlay(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val moodId = body.get("moodId")?.asString
            ?: return jsonError(Response.Status.BAD_REQUEST, "moodId required")
        return dispatch(AppCommand.PlayCuratedMood(moodId))
    }

    private fun handleThematic(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val prompt = body.get("prompt")?.asString
            ?: return jsonError(Response.Status.BAD_REQUEST, "prompt required")
        return dispatch(AppCommand.PlayThematicChannel(prompt))
    }

    private fun handleAiCommand(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val prompt = body.get("prompt")?.asString
            ?: return jsonError(Response.Status.BAD_REQUEST, "prompt required")
        return dispatch(AppCommand.ExecuteAiPrompt(prompt))
    }

    private fun handleQueueAdd(session: IHTTPSession, isPlayNext: Boolean = false): Response {
        val body = parseJsonBody(session)
        val contentId = body.get("contentId")?.asString.orEmpty()
        val title = body.get("title")?.asString.orEmpty()
        if (contentId.isBlank() && title.isBlank()) {
            return jsonError(Response.Status.BAD_REQUEST, "contentId or title required")
        }
        val videoId = body.get("videoId")?.asString
        val seriesTitle = body.get("seriesTitle")?.asString
        val season = body.get("season")?.asInt
        val episode = body.get("episode")?.asInt
        val thumbnail = body.get("thumbnail")?.asString
        val mediaType = body.get("mediaType")?.asString ?: if (season != null || episode != null) "series" else "movie"
        val playNextFlag = isPlayNext || (body.get("playNext")?.asBoolean == true)

        val item = PlaylistItem(
            contentId = contentId.ifBlank { videoId ?: "queue_${System.currentTimeMillis()}" },
            videoId = videoId,
            title = title.ifBlank { "Queued Item" },
            seriesTitle = seriesTitle,
            season = season,
            episode = episode,
            thumbnail = thumbnail,
            mediaType = mediaType
        )
        val command = if (playNextFlag) AppCommand.PlayNextItem(item) else AppCommand.QueueAdd(item)
        return dispatch(command)
    }

    private fun handleQueue(): Response {
        val queueItems = PlaylistManager.queue.value.map { it.toSnapshot() }
        val currentItem = PlaylistManager.currentItem()?.toSnapshot()
        val snapshot = QueueSnapshot(
            size = queueItems.size,
            currentIndex = PlaylistManager.currentIndex.value,
            channelMode = PlaylistManager.channelMode.value.name,
            currentItem = currentItem,
            items = queueItems
        )
        return jsonResponse(gson.toJson(snapshot))
    }

    private fun handlePair(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val pin = body.get(PARAM_PIN)?.asString
            ?: session.parameters[PARAM_PIN]?.firstOrNull()?.trim()
            ?: return jsonError(Response.Status.BAD_REQUEST, "pin parameter or body required")

        val settings = runBlocking { remoteControlSettingsDataStore.getSettings() }
        if (settings.pairingPin.isBlank() || pin != settings.pairingPin) {
            return jsonError(Response.Status.BAD_REQUEST, "Invalid PIN")
        }

        val payload = mapOf(
            "status" to "paired",
            "token" to settings.apiToken,
            "device" to "Nuvio TV",
            "ip" to DeviceIpAddress.get(context)
        )
        return jsonResponse(gson.toJson(payload))
    }

    private fun handleQr(): Response {
        val settings = runBlocking { remoteControlSettingsDataStore.getSettings() }
        val ip = DeviceIpAddress.get(context)
        val payload = mapOf(
            "device" to "Nuvio TV",
            "ip" to ip,
            "port" to listeningPort,
            "pin" to settings.pairingPin,
            "pairingUrl" to "http://$ip:$listeningPort/?pin=${settings.pairingPin}"
        )
        return jsonResponse(gson.toJson(payload))
    }

    private fun parseJsonBody(session: IHTTPSession): com.google.gson.JsonObject {
        val result = com.google.gson.JsonObject()
        try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val raw = files["postData"]
                ?: files["content"]?.let { contentPath ->
                    val file = java.io.File(contentPath)
                    if (file.exists()) runCatching { file.readText() }.getOrNull() else contentPath
                }
            if (!raw.isNullOrBlank()) {
                val parsed = gson.fromJson(raw, com.google.gson.JsonObject::class.java)
                if (parsed != null) {
                    for ((k, v) in parsed.entrySet()) result.add(k, v)
                }
            }
        } catch (_: Exception) {
            // Non-JSON or empty body
        }
        for ((k, values) in session.parameters) {
            if (!result.has(k) && values.isNotEmpty()) {
                result.addProperty(k, values.first())
            }
        }
        return result
    }

    private fun jsonOk(message: String = "OK", data: Any? = null): Response {
        val payload = mutableMapOf<String, Any?>("status" to "ok", "message" to message)
        if (data != null) payload["data"] = data
        return jsonResponse(gson.toJson(payload))
    }

    private fun jsonResponse(json: String): Response =
        newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            json
        )

    private fun jsonError(status: Response.Status, message: String): Response {
        val payload = mapOf("status" to "error", "error" to message)
        return newFixedLengthResponse(
            status,
            "application/json; charset=utf-8",
            gson.toJson(payload)
        )
    }

    private fun PlaylistItem.toSnapshot(): QueueItemSnapshot {
        return QueueItemSnapshot(
            contentId = contentId,
            videoId = videoId,
            title = title,
            seriesTitle = seriesTitle,
            season = season,
            episode = episode,
            thumbnail = thumbnail,
            mediaType = mediaType
        )
    }

    private fun handleTelemetry(): Response {
        val telemetry = runBlocking { playerPlaybackBridge.sampleTelemetry() }
        return jsonResponse(gson.toJson(telemetry))
    }

    private fun handleCurrentStreams(): Response {
        val streams = playerPlaybackBridge.getCurrentStreams()
        return jsonResponse(gson.toJson(mapOf("streams" to streams)))
    }

    private fun handleHotSwap(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val url = body.get("streamUrl")?.asString?.takeIf { it.isNotBlank() }
            ?: return jsonError(Response.Status.BAD_REQUEST, "streamUrl required")
        val title = body.get("title")?.asString ?: "Stream"
        val headersObj = body.getAsJsonObject("headers")
        val headers = headersObj?.entrySet()
            ?.mapNotNull { (k, v) -> v.takeIf { it.isJsonPrimitive }?.asString?.let { k to it } }
            ?.toMap()
            ?: emptyMap()
        return dispatch(AppCommand.HotSwapStream(streamUrl = url, title = title, headers = headers))
    }

    private fun handlePlayStream(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val url = body.get("streamUrl")?.asString?.takeIf { it.isNotBlank() }
            ?: return jsonError(Response.Status.BAD_REQUEST, "streamUrl required")
        val title = body.get("title")?.asString ?: "Stream"
        val headersObj = body.getAsJsonObject("headers")
        val headers = headersObj?.entrySet()
            ?.mapNotNull { (k, v) -> v.takeIf { it.isJsonPrimitive }?.asString?.let { k to it } }
            ?.toMap()
            ?: emptyMap()
        return dispatch(AppCommand.PlayStream(streamUrl = url, title = title, headers = headers))
    }

    private fun handleCatalogSearch(session: IHTTPSession): Response {
        val query = session.parameters["q"]?.firstOrNull()?.trim().orEmpty()
        if (query.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "q required")
        val addonRepo = addonRepository ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "Search repository unavailable")
        val catalogRepo = catalogRepository ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "Catalog repository unavailable")

        val items = runBlocking {
            val addons = withTimeoutOrNull(2_000) { addonRepo.getInstalledAddons().first() }.orEmpty()
            val searchCatalogs = addons
                .filter { it.enabled }
                .flatMap { addon ->
                    addon.catalogs
                        .filter { cat ->
                            cat.extra.any { it.name.equals("search", ignoreCase = true) } ||
                                cat.extraSupported.any { it.equals("search", ignoreCase = true) }
                        }
                        .map { cat -> addon to cat }
                }
                .take(8)

            coroutineScope {
                searchCatalogs.map { (addon, cat) ->
                    async {
                        withTimeoutOrNull(6_000) {
                            catalogRepo.getCatalog(
                                addonBaseUrl = addon.baseUrl,
                                addonId = addon.id,
                                addonName = addon.displayName,
                                catalogId = cat.id,
                                catalogName = cat.name,
                                type = cat.apiType,
                                skip = 0,
                                skipStep = cat.pageSize ?: 100,
                                extraArgs = mapOf("search" to query),
                                supportsSkip = false
                            ).firstOrNull { it !is NetworkResult.Loading }
                        }
                    }
                }.awaitAll()
            }.flatMap { result ->
                val row = (result as? NetworkResult.Success)?.data ?: return@flatMap emptyList()
                row.items.map { meta ->
                    mapOf(
                        "id" to meta.id,
                        "name" to meta.name,
                        "type" to meta.apiType,
                        "poster" to meta.poster,
                        "releaseInfo" to meta.releaseInfo,
                        "catalogName" to row.catalogName
                    )
                }
            }.distinctBy { "${it["type"]}:${it["id"]}" }.take(60)
        }
        return jsonResponse(gson.toJson(mapOf("query" to query, "items" to items)))
    }

    private fun handleBrowseStreams(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()?.trim().orEmpty()
        if (id.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "id required")
        val type = session.parameters["type"]?.firstOrNull()?.trim().orEmpty().ifBlank { "movie" }
        val season = session.parameters["season"]?.firstOrNull()?.toIntOrNull()
        val episode = session.parameters["episode"]?.firstOrNull()?.toIntOrNull()
        val title = session.parameters["title"]?.firstOrNull()?.trim()
        val repo = streamRepository ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "Stream repository unavailable")

        val videoId = if (type.equals("series", ignoreCase = true) && season != null && episode != null && !id.contains(':')) {
            "$id:$season:$episode"
        } else {
            id
        }

        val streams = runBlocking {
            val res = withTimeoutOrNull(8_500) {
                repo.getStreamsFromAllAddons(
                    type = type,
                    videoId = videoId,
                    season = season,
                    episode = episode,
                    forceRefresh = false,
                    title = title
                ).firstOrNull { it is NetworkResult.Success || it is NetworkResult.Error }
            }
            val addons = (res as? NetworkResult.Success)?.data.orEmpty()
            addons.flatMap { group ->
                group.streams.mapNotNull { st ->
                    val url = st.getStreamUrl() ?: return@mapNotNull null
                    val label = listOfNotNull(st.name?.takeIf { it.isNotBlank() }, st.title?.takeIf { it.isNotBlank() })
                        .joinToString(" · ")
                        .ifBlank { group.addonName }
                    val headers = st.behaviorHints?.proxyHeaders?.request.orEmpty()
                    mapOf(
                        "url" to url,
                        "label" to label,
                        "title" to (st.title ?: st.name ?: label),
                        "description" to st.description,
                        "addonName" to group.addonName,
                        "quality" to st.quality,
                        "headers" to headers
                    )
                }
            }.take(60)
        }
        return jsonResponse(gson.toJson(mapOf("id" to id, "type" to type, "streams" to streams)))
    }

    private fun handleQueueReorder(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val fromIndex = body.get("fromIndex")?.asInt
            ?: return jsonError(Response.Status.BAD_REQUEST, "fromIndex required")
        val toIndex = body.get("toIndex")?.asInt
            ?: return jsonError(Response.Status.BAD_REQUEST, "toIndex required")
        return dispatch(AppCommand.ReorderQueue(fromIndex = fromIndex, toIndex = toIndex))
    }

    private fun handleQueueRemove(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val index = body.get("index")?.asInt
            ?: return jsonError(Response.Status.BAD_REQUEST, "index required")
        return dispatch(AppCommand.RemoveQueueItem(index = index))
    }

    private fun handleLiveTvChannels(): Response {
        LiveTvManager.refreshAiChannels(context)
        val nowMs = System.currentTimeMillis()
        val channels = LiveTvManager.channels.value.map { ch ->
            val nowProgTitle = ch.aiConfigId?.let { cfgId ->
                AiBroadcastNetwork.findChannelById(context, cfgId)?.let { cfg ->
                    AiBroadcastNetwork.buildScheduleForChannel(cfg, nowMs, slotCount = 1)
                        .firstOrNull { it.isOnAirNow }
                        ?.program?.title
                }
            } ?: ch.currentProgram.title
            mapOf(
                "id" to ch.id,
                "number" to ch.number,
                "name" to ch.name,
                "callSign" to (ch.callSign ?: ""),
                "category" to ch.category.name,
                "isAiNetwork" to ch.isAiNetwork,
                "djHostName" to ch.djHostName,
                "nowPlaying" to (nowProgTitle.takeIf { it.isNotBlank() } ?: ch.tagline)
            )
        }
        return jsonResponse(gson.toJson(mapOf("channels" to channels)))
    }

    private fun handleLiveTvTune(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val channelId = body.get("channelId")?.asString?.takeIf { it.isNotBlank() }
            ?: return jsonError(Response.Status.BAD_REQUEST, "channelId required")
        val startFromBeginning = body.get("startFromBeginning")?.asBoolean ?: false
        return dispatch(AppCommand.TuneLiveChannel(channelId = channelId, startFromBeginning = startFromBeginning))
    }

    private fun serveText(content: String, contentType: String): Response {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return newFixedLengthResponse(
            Response.Status.OK,
            contentType,
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        )
    }
}
