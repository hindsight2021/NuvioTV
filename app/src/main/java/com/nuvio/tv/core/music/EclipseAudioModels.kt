package com.nuvio.tv.core.music

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

/**
 * Additional metadata for an [EclipseTrack], enriched via EclipseMCP / Apple Music / lrclib.
 */
@Immutable
data class EclipseTrackEnrichment(
    @SerializedName("genre") val genre: String? = null,
    @SerializedName("year") val year: Int? = null,
    @SerializedName("quality") val quality: String? = null,
    @SerializedName("composer") val composer: String? = null,
    @SerializedName("explicit") val explicit: Boolean = false,
    @SerializedName("editorial_notes", alternate = ["editorialNotes"])
    val editorialNotes: String? = null,
    @SerializedName("audio_traits", alternate = ["audioTraits"])
    val audioTraits: List<String> = emptyList(),
    @SerializedName("provider") val provider: String? = null,
)

/**
 * Playable audio track normalized for Nuvio+ audio playback and EclipseMCP REST/Connect APIs.
 */
@Immutable
data class EclipseTrack(
    @SerializedName("id") val id: String = "",
    @SerializedName("title", alternate = ["trackTitle", "name"])
    val title: String = "Unknown",
    @SerializedName("artist", alternate = ["artistName"])
    val artist: String = "Unknown Artist",
    @SerializedName("album", alternate = ["albumName"])
    val album: String = "",
    @SerializedName("duration_sec", alternate = ["durationSec", "duration"])
    val durationSec: Int = 0,
    @SerializedName("stream_url", alternate = ["streamUrl", "streamURL"])
    val streamUrl: String = "",
    @SerializedName("artwork_url", alternate = ["artworkUrl", "artworkURL", "cover_url_hd", "cover_url", "coverUrl"])
    val artworkUrl: String = "",
    @SerializedName("backdrop_url", alternate = ["backdropUrl"])
    val backdropUrl: String = "",
    @SerializedName("lyrics_lrc", alternate = ["lyricsLrc"])
    val lyricsLrc: String? = null,
    @SerializedName("enrichment") val enrichment: EclipseTrackEnrichment? = null,
    @SerializedName("apple_music_track_id", alternate = ["appleMusicTrackId"])
    val appleMusicTrackId: String? = null,
    @SerializedName("tidal_track_id", alternate = ["tidalTrackId"])
    val tidalTrackId: String? = null,
    @SerializedName("addon_id", alternate = ["addonId"])
    val addonId: String? = null,
    @SerializedName("addon_track_id", alternate = ["addonTrackId"])
    val addonTrackId: String? = null,
    @SerializedName("manifest_url_base", alternate = ["manifestUrlBase"])
    val manifestUrlBase: String? = null,
    @SerializedName("provider") val provider: String? = null,
) {
    fun displayArtUrl(): String = artworkUrl.ifBlank { backdropUrl }
}

@Immutable
data class EclipsePlaylistSummary(
    @SerializedName("id") val id: String = "",
    @SerializedName("name", alternate = ["title"])
    val name: String = "",
    @SerializedName("description") val description: String? = null,
    @SerializedName("artwork_url", alternate = ["artworkUrl", "artworkURL", "cover_url_hd", "cover_url", "coverUrl"])
    val artworkUrl: String? = null,
    @SerializedName("track_count", alternate = ["trackCount"])
    val trackCount: Int = 0,
    @SerializedName("creator") val creator: String? = null,
    @SerializedName("tracks") val tracks: List<EclipseTrack> = emptyList(),
)

@Immutable
data class EclipseAlbumSummary(
    @SerializedName("id") val id: String = "",
    @SerializedName("album_id", alternate = ["albumId"])
    val albumId: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("artist") val artist: String = "",
    @SerializedName("cover_url", alternate = ["coverUrl"])
    val coverUrl: String = "",
    @SerializedName("cover_url_hd", alternate = ["coverUrlHd"])
    val coverUrlHd: String = "",
    @SerializedName("year") val year: Int? = null,
    @SerializedName("genre") val genre: String? = null,
    @SerializedName("track_count", alternate = ["trackCount"])
    val trackCount: Int = 0,
    @SerializedName("editorial_notes", alternate = ["editorialNotes"])
    val editorialNotes: String? = null,
) {
    fun bestArtwork(): String = coverUrlHd.ifBlank { coverUrl }
}

@Immutable
data class EclipseArtistSummary(
    @SerializedName("id") val id: String = "",
    @SerializedName("artist_id", alternate = ["artistId"])
    val artistId: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("cover_url", alternate = ["coverUrl"])
    val coverUrl: String = "",
    @SerializedName("cover_url_hd", alternate = ["coverUrlHd"])
    val coverUrlHd: String = "",
    @SerializedName("backdrop_url", alternate = ["backdropUrl"])
    val backdropUrl: String = "",
    @SerializedName("genre") val genre: String? = null,
    @SerializedName("editorial_bio", alternate = ["editorialBio"])
    val editorialBio: String? = null,
) {
    fun bestArtwork(): String = coverUrlHd.ifBlank { coverUrl }.ifBlank { backdropUrl }
}

@Immutable
data class EclipseAudioMix(
    @SerializedName("id") val id: String? = null,
    @SerializedName("title") val title: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("description") val description: String? = null,
    @SerializedName("artwork_url", alternate = ["artworkUrl", "artworkURL", "cover_url_hd", "cover_url", "coverUrl"])
    val artworkUrl: String? = null,
    @SerializedName("tracks") val tracks: List<EclipseTrack> = emptyList(),
) {
    fun displayTitle(): String = title.ifBlank { name }.ifBlank { "Daily Mix" }
    fun displayArtUrl(): String? = artworkUrl?.takeIf { it.isNotBlank() }
        ?: tracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl
}

@Immutable
data class EclipseAddonInfo(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String? = null,
    @SerializedName("enabled") val enabled: Boolean = true,
)

@Immutable
data class EclipseHomeResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("playlists") val playlists: List<EclipsePlaylistSummary> = emptyList(),
    @SerializedName("daily_mixes") val dailyMixes: List<EclipseAudioMix> = emptyList(),
    @SerializedName("addons") val addons: List<EclipseAddonInfo> = emptyList(),
    @SerializedName("error") val error: String? = null,
)

@Immutable
data class EclipseSearchResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("tracks") val tracks: List<EclipseTrack> = emptyList(),
    @SerializedName("albums") val albums: List<EclipseAlbumSummary> = emptyList(),
    @SerializedName("artists") val artists: List<EclipseArtistSummary> = emptyList(),
    @SerializedName("playlists") val playlists: List<EclipsePlaylistSummary> = emptyList(),
    @SerializedName("error") val error: String? = null,
)

@Immutable
data class EclipseHealthStatus(
    @SerializedName("status") val status: String = "",
    @SerializedName("eclipse_authenticated") val eclipseAuthenticated: Boolean = false,
    @SerializedName("connect_ws") val connectWs: Boolean = false,
    @SerializedName("devices") val devices: Int = 0,
    @SerializedName("addons") val addons: Int = 0,
    @SerializedName("ha_configured") val haConfigured: Boolean = false,
    @SerializedName("nuvio_speaker_configured") val nuvioSpeakerConfigured: Boolean = false,
    @SerializedName("nuvio_speaker_connected") val nuvioSpeakerConnected: Boolean = false,
    @SerializedName("nuvio_speaker_active") val nuvioSpeakerActive: Boolean = false,
)

enum class EclipseRepeatMode(val wireValue: String) {
    OFF("off"),
    ONE("one"),
    ALL("all");

    companion object {
        fun fromWire(value: String?): EclipseRepeatMode = when (value?.trim()?.lowercase()) {
            "one" -> ONE
            "all" -> ALL
            else -> OFF
        }
    }
}

/**
 * Audio playback telemetry exposed inside `GET /api/v1/status` (`audio` field) and UI state.
 */
@Immutable
data class EclipseAudioTelemetry(
    @SerializedName("isActive") val isActive: Boolean = false,
    @SerializedName("isPlaying") val isPlaying: Boolean = false,
    @SerializedName("isBuffering") val isBuffering: Boolean = false,
    @SerializedName("currentIndex") val currentIndex: Int = -1,
    @SerializedName("currentTrack") val currentTrack: EclipseTrack? = null,
    @SerializedName("positionMs") val positionMs: Long = 0L,
    @SerializedName("durationMs") val durationMs: Long = 0L,
    @SerializedName("shuffle") val shuffle: Boolean = false,
    @SerializedName("repeat") val repeat: String = "off",
    @SerializedName("volume") val volume: Int = 100,
    @SerializedName("source") val source: String = "eclipse",
    @SerializedName("queueSize") val queueSize: Int = 0,
    @SerializedName("queue") val queue: List<EclipseTrack> = emptyList(),
    @SerializedName("djSessionId") val djSessionId: String? = null,
    @SerializedName("error") val error: String? = null,
)

@Immutable
data class LrcLine(
    val timestampMs: Long,
    val text: String,
)

private val LRC_TIMESTAMP_REGEX = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

/**
 * Parses LRC-formatted lyrics into a sorted list of [LrcLine], or plain lines with
 * `timestampMs = -1L` when no timestamps are present.
 */
fun parseLrcLyrics(raw: String?): List<LrcLine> {
    if (raw.isNullOrBlank()) return emptyList()

    val synced = mutableListOf<LrcLine>()
    val plain = mutableListOf<LrcLine>()
    var sawTimestamp = false

    for (line in raw.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue

        val matches = LRC_TIMESTAMP_REGEX.findAll(trimmed).toList()
        if (matches.isEmpty()) {
            plain += LrcLine(timestampMs = -1L, text = trimmed)
            continue
        }

        sawTimestamp = true
        val text = trimmed.substring(matches.last().range.last + 1).trim()
        if (text.isEmpty()) continue
        for (match in matches) {
            val minutes = match.groupValues[1].toLongOrNull() ?: continue
            val seconds = match.groupValues[2].toLongOrNull() ?: continue
            val fractionRaw = match.groupValues[3]
            val millis = when (fractionRaw.length) {
                0 -> 0L
                1 -> fractionRaw.toLongOrNull()?.times(100L) ?: 0L
                2 -> fractionRaw.toLongOrNull()?.times(10L) ?: 0L
                else -> fractionRaw.take(3).toLongOrNull() ?: 0L
            }
            synced += LrcLine(
                timestampMs = minutes * 60_000L + seconds * 1_000L + millis,
                text = text,
            )
        }
    }

    return if (sawTimestamp) synced.sortedBy { it.timestampMs } else plain
}
