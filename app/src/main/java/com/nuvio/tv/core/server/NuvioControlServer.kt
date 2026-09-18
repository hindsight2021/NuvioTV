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
import com.nuvio.tv.core.playlist.PlaylistItem
import com.nuvio.tv.core.playlist.PlaylistManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

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
    port: Int = 8910
) : NanoHTTPD(port) {

    private val gson = Gson()

    companion object {
        private const val API_PREFIX = "/api/v1"
        private const val BEARER_PREFIX = "Bearer "
        private const val HEADER_AUTHORIZATION = "authorization"
        private const val HEADER_API_KEY = "x-nuvio-key"
        private const val PARAM_TOKEN = "token"
        private const val PARAM_PIN = "pin"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        // Web remote interface
        if (uri == "/" || uri == "/index.html") {
            return serveWebRemote()
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

            uri == "$API_PREFIX/navigation/open" && method == Method.POST -> handleOpenScreen(session)
            uri == "$API_PREFIX/navigation/search" && method == Method.POST -> handleSearch(session)
            uri == "$API_PREFIX/navigation/dpad" && method == Method.POST -> handleDpad(session)

            uri == "$API_PREFIX/play" && method == Method.POST -> handlePlayMedia(session)

            uri == "$API_PREFIX/channels/curated" && method == Method.GET -> handleCuratedList()
            uri == "$API_PREFIX/channels/curated" && method == Method.POST -> handleCuratedPlay(session)
            uri == "$API_PREFIX/channels/thematic" && method == Method.POST -> handleThematic(session)

            uri == "$API_PREFIX/ai/command" && method == Method.POST -> handleAiCommand(session)

            uri == "$API_PREFIX/queue" && method == Method.GET -> handleQueue()
            uri == "$API_PREFIX/queue" && method == Method.DELETE -> dispatch(AppCommand.ClearQueue)

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

        return (bearer == expected) || (apiKey == expected) || (paramToken == expected)
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
            val raw = files["postData"] ?: files["content"]
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

    private fun serveWebRemote(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            ByteArrayInputStream(WEB_REMOTE_HTML.toByteArray(Charsets.UTF_8)),
            WEB_REMOTE_HTML.toByteArray(Charsets.UTF_8).size.toLong()
        )
    }

    private val WEB_REMOTE_HTML: String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <title>Nuvio TV Remote</title>
            <style>
                * { box-sizing: border-box; }
                body {
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                    background: #0f1115;
                    color: #eaeaea;
                    margin: 0;
                    padding: 20px;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                }
                .container { width: 100%; max-width: 400px; display: flex; flex-direction: column; gap: 16px; }
                .header { text-align: center; }
                .header h1 { font-size: 22px; margin: 0 0 4px; color: #fff; font-weight: 700; }
                .header p { font-size: 13px; color: #8a8f98; margin: 0; }
                .card {
                    background: #181b21;
                    border: 1px solid #272c35;
                    border-radius: 14px;
                    padding: 16px;
                }
                .status-title { font-size: 16px; font-weight: 600; margin: 0 0 4px; color: #fff; }
                .status-sub { font-size: 13px; color: #9da3af; margin: 0; }
                .grid-dpad {
                    display: grid;
                    grid-template-columns: repeat(3, 1fr);
                    gap: 10px;
                    width: 240px;
                    margin: 0 auto;
                }
                .grid-playback {
                    display: grid;
                    grid-template-columns: repeat(4, 1fr);
                    gap: 8px;
                }
                .grid-nav {
                    display: grid;
                    grid-template-columns: repeat(3, 1fr);
                    gap: 8px;
                }
                button {
                    background: #232730;
                    color: #fff;
                    border: 1px solid #333945;
                    border-radius: 12px;
                    padding: 14px 10px;
                    font-size: 14px;
                    font-weight: 500;
                    cursor: pointer;
                    transition: background 0.15s, transform 0.05s;
                    user-select: none;
                }
                button:active { background: #373f50; transform: scale(0.97); }
                .btn-accent { background: #e50914; border-color: #f40612; }
                .btn-accent:active { background: #b80710; }
                .input-group { display: flex; gap: 8px; }
                input[type="text"] {
                    flex: 1;
                    padding: 12px 14px;
                    border-radius: 10px;
                    border: 1px solid #333945;
                    background: #121419;
                    color: #fff;
                    font-size: 14px;
                    outline: none;
                }
                input[type="text"]:focus { border-color: #6366f1; }
                .pin-box {
                    display: flex;
                    flex-direction: column;
                    gap: 10px;
                    text-align: center;
                }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="header">
                    <h1>Nuvio TV Remote</h1>
                    <p id="connStatus">Connecting...</p>
                </div>

                <div class="card" id="pairCard" style="display:none;">
                    <div class="pin-box">
                        <div style="font-weight: 600;">Pair with TV</div>
                        <p style="font-size: 12px; color: #8a8f98; margin:0;">Enter the 4-digit PIN displayed on your TV</p>
                        <div class="input-group">
                            <input type="text" id="pinInput" placeholder="4-digit PIN" maxlength="4" style="text-align:center; font-size:18px; letter-spacing:4px;" />
                            <button onclick="pairWithPin()" class="btn-accent">Pair</button>
                        </div>
                    </div>
                </div>

                <div class="card" id="nowPlayingCard">
                    <div class="status-title" id="trackTitle">Idle</div>
                    <p class="status-sub" id="trackSubtitle">Ready to play</p>
                </div>

                <div class="card">
                    <div class="grid-playback">
                        <button onclick="cmd('/playback/previous')">⏮</button>
                        <button onclick="cmd('/playback/play_pause')" class="btn-accent" id="btnPlayPause">⏯</button>
                        <button onclick="cmd('/playback/stop')">⏹</button>
                        <button onclick="cmd('/playback/next')">⏭</button>
                    </div>
                </div>

                <div class="card" style="display:flex; justify-content:center;">
                    <div class="grid-dpad">
                        <div></div>
                        <button onclick="dpad('up')">▲</button>
                        <div></div>
                        <button onclick="dpad('left')">◀</button>
                        <button onclick="dpad('select')" class="btn-accent" style="font-weight:700;">OK</button>
                        <button onclick="dpad('right')">▶</button>
                        <div></div>
                        <button onclick="dpad('down')">▼</button>
                        <div></div>
                    </div>
                </div>

                <div class="card">
                    <div class="grid-nav">
                        <button onclick="dpad('back')">↩ Back</button>
                        <button onclick="openScreen('home')">Home</button>
                        <button onclick="openScreen('search')">Search</button>
                    </div>
                </div>

                <div class="card">
                    <div style="font-size:12px; font-weight:600; margin-bottom:8px; color:#8a8f98;">AI TV OPERATOR</div>
                    <div class="input-group">
                        <input type="text" id="aiInput" placeholder="e.g. 'Play Below Deck S4E7'..." />
                        <button onclick="sendAi()" class="btn-accent">Ask</button>
                    </div>
                </div>
            </div>

            <script>
                let token = localStorage.getItem('nuvio_token') || '';
                const urlParams = new URLSearchParams(window.location.search);
                const pinFromUrl = urlParams.get('pin');

                async function init() {
                    if (pinFromUrl && !token) {
                        document.getElementById('pinInput').value = pinFromUrl;
                        await pairWithPin();
                        return;
                    }
                    if (!token) {
                        document.getElementById('pairCard').style.display = 'block';
                        document.getElementById('connStatus').innerText = 'Pairing required';
                    } else {
                        startPolling();
                    }
                }

                async function pairWithPin() {
                    const pin = document.getElementById('pinInput').value.trim();
                    if (!pin) return;
                    try {
                        const res = await fetch('/api/v1/pair', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ pin: pin })
                        });
                        const data = await res.json();
                        if (data.token) {
                            token = data.token;
                            localStorage.setItem('nuvio_token', token);
                            document.getElementById('pairCard').style.display = 'none';
                            startPolling();
                        } else {
                            alert('Pairing failed: ' + (data.error || 'Invalid PIN'));
                        }
                    } catch (e) {
                        alert('Pairing error: ' + e.message);
                    }
                }

                async function api(path, method = 'POST', body = null) {
                    if (!token) return;
                    const headers = { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' };
                    const opts = { method, headers };
                    if (body) opts.body = JSON.stringify(body);
                    const res = await fetch('/api/v1' + path, opts);
                    if (res.status === 401) {
                        localStorage.removeItem('nuvio_token');
                        token = '';
                        document.getElementById('pairCard').style.display = 'block';
                        document.getElementById('connStatus').innerText = 'Pairing expired';
                    }
                    return res.json();
                }

                function cmd(path) { api(path); }
                function dpad(k) { api('/navigation/dpad', 'POST', { key: k }); }
                function openScreen(s) { api('/navigation/open', 'POST', { screen: s }); }
                function sendAi() {
                    const val = document.getElementById('aiInput').value.trim();
                    if (val) {
                        api('/ai/command', 'POST', { prompt: val });
                        document.getElementById('aiInput').value = '';
                    }
                }

                async function refreshStatus() {
                    if (!token) return;
                    try {
                        const res = await fetch('/api/v1/status', {
                            headers: { 'Authorization': 'Bearer ' + token }
                        });
                        if (res.status === 401) {
                            token = '';
                            document.getElementById('pairCard').style.display = 'block';
                            return;
                        }
                        const data = await res.json();
                        document.getElementById('connStatus').innerText = 'Connected (' + (data.deviceIp || 'Local') + ')';
                        if (data.playback && data.playback.isActive) {
                            document.getElementById('trackTitle').innerText = data.playback.title || 'Playing';
                            const sub = data.playback.contentName ? (data.playback.contentName + (data.playback.season ? ' S' + data.playback.season + 'E' + data.playback.episode : '')) : (data.playback.streamName || '');
                            document.getElementById('trackSubtitle').innerText = sub || 'In playback';
                            document.getElementById('btnPlayPause').innerText = data.playback.isPlaying ? '⏸' : '▶';
                        } else {
                            document.getElementById('trackTitle').innerText = 'Idle';
                            document.getElementById('trackSubtitle').innerText = data.activeScreen ? ('Screen: ' + data.activeScreen) : 'Ready to play';
                            document.getElementById('btnPlayPause').innerText = '⏯';
                        }
                    } catch (e) {
                        document.getElementById('connStatus').innerText = 'Reconnecting...';
                    }
                }

                function startPolling() {
                    refreshStatus();
                    setInterval(refreshStatus, 2000);
                }

                init();
            </script>
        </body>
        </html>
    """.trimIndent()
}
