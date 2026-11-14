package com.nuvio.tv.core.control

import com.google.gson.annotations.SerializedName

/**
 * Snapshot of the current device and player status.
 *
 * This model is designed to be serialized (e.g. with Gson)
 * and returned by the remote control / REST API endpoints.
 */
data class NuvioStatusResponse(
    @SerializedName("app")
    val app: String = "Nuvio TV",

    @SerializedName("version")
    val version: String,

    @SerializedName("deviceIp")
    val deviceIp: String? = null,

    @SerializedName("activeScreen")
    val activeScreen: String? = null,

    @SerializedName("playback")
    val playback: PlaybackSnapshot? = null,

    @SerializedName("queue")
    val queue: QueueSnapshot? = null
)

/**
 * Snapshot of the current playback state, including position, track selection
 * and metadata about the currently playing content.
 */
data class PlaybackSnapshot(
    @SerializedName("isActive")
    val isActive: Boolean = false,

    @SerializedName("isPlaying")
    val isPlaying: Boolean = false,

    @SerializedName("isBuffering")
    val isBuffering: Boolean = false,

    @SerializedName("playbackEnded")
    val playbackEnded: Boolean = false,

    @SerializedName("positionMs")
    val positionMs: Long = 0L,

    @SerializedName("durationMs")
    val durationMs: Long = 0L,

    @SerializedName("bufferedPositionMs")
    val bufferedPositionMs: Long = 0L,

    @SerializedName("title")
    val title: String = "",

    @SerializedName("contentName")
    val contentName: String? = null,

    @SerializedName("contentType")
    val contentType: String? = null,

    @SerializedName("season")
    val season: Int? = null,

    @SerializedName("episode")
    val episode: Int? = null,

    @SerializedName("episodeTitle")
    val episodeTitle: String? = null,

    @SerializedName("streamName")
    val streamName: String? = null,

    @SerializedName("playbackSpeed")
    val playbackSpeed: Float = 1.0f,

    @SerializedName("audioTracks")
    val audioTracks: List<TrackSnapshot> = emptyList(),

    @SerializedName("subtitleTracks")
    val subtitleTracks: List<TrackSnapshot> = emptyList(),

    @SerializedName("selectedAudioIndex")
    val selectedAudioIndex: Int = -1,

    @SerializedName("selectedSubtitleIndex")
    val selectedSubtitleIndex: Int = -1,
    val contentId: String? = null,
    val videoId: String? = null,
    val profileId: Int? = null,
    val sessionId: String? = null
)

/**
 * Snapshot of a single media track (audio or subtitle).
 */
data class TrackSnapshot(
    @SerializedName("index")
    val index: Int,

    @SerializedName("name")
    val name: String,

    @SerializedName("language")
    val language: String? = null,

    @SerializedName("codec")
    val codec: String? = null,

    @SerializedName("channelCount")
    val channelCount: Int? = null,

    @SerializedName("isSelected")
    val isSelected: Boolean = false
)

/**
 * Snapshot of the current playback queue.
 */
data class QueueSnapshot(
    @SerializedName("size")
    val size: Int = 0,

    @SerializedName("currentIndex")
    val currentIndex: Int = -1,

    @SerializedName("channelMode")
    val channelMode: String = "NONE",

    @SerializedName("currentItem")
    val currentItem: QueueItemSnapshot? = null,

    @SerializedName("items")
    val items: List<QueueItemSnapshot> = emptyList()
)

/**
 * Snapshot of a single item within the playback queue.
 */
data class QueueItemSnapshot(
    @SerializedName("contentId")
    val contentId: String,

    @SerializedName("videoId")
    val videoId: String? = null,

    @SerializedName("title")
    val title: String,

    @SerializedName("seriesTitle")
    val seriesTitle: String? = null,

    @SerializedName("season")
    val season: Int? = null,

    @SerializedName("episode")
    val episode: Int? = null,

    @SerializedName("thumbnail")
    val thumbnail: String? = null,

    @SerializedName("mediaType")
    val mediaType: String = "series"
)

data class PreWarmTelemetrySnapshot(
    @SerializedName("state")
    val state: String = "IDLE",
    @SerializedName("targetLabel")
    val targetLabel: String? = null,
    @SerializedName("cachedBytes")
    val cachedBytes: Long = 0L,
    @SerializedName("targetBytes")
    val targetBytes: Long = 0L
)

data class PlaybackTelemetrySnapshot(
    @SerializedName("active")
    val active: Boolean = false,
    @SerializedName("engine")
    val engine: String? = null,
    @SerializedName("videoCodec")
    val videoCodec: String? = null,
    @SerializedName("width")
    val width: Int = 0,
    @SerializedName("height")
    val height: Int = 0,
    @SerializedName("frameRateFps")
    val frameRateFps: Float = 0f,
    @SerializedName("droppedFrames")
    val droppedFrames: Int = 0,
    @SerializedName("videoBitrateBps")
    val videoBitrateBps: Int = 0,
    @SerializedName("audioCodec")
    val audioCodec: String? = null,
    @SerializedName("audioChannels")
    val audioChannels: Int = 0,
    @SerializedName("bufferedDurationMs")
    val bufferedDurationMs: Long = 0L,
    @SerializedName("totalBufferAheadMs")
    val totalBufferAheadMs: Long = 0L,
    @SerializedName("networkThroughputMbps")
    val networkThroughputMbps: Double = 0.0,
    @SerializedName("vodCacheUsedMb")
    val vodCacheUsedMb: Int = 0,
    @SerializedName("vodCacheMaxMb")
    val vodCacheMaxMb: Int = 0,
    @SerializedName("speculativePreWarm")
    val speculativePreWarm: PreWarmTelemetrySnapshot = PreWarmTelemetrySnapshot()
)

data class StreamCandidateSnapshot(
    @SerializedName("url")
    val url: String,
    @SerializedName("title")
    val title: String,
    @SerializedName("label")
    val label: String? = null,
    @SerializedName("addonName")
    val addonName: String? = null,
    @SerializedName("description")
    val description: String? = null,
    @SerializedName("headers")
    val headers: Map<String, String> = emptyMap(),
    @SerializedName("isCurrent")
    val isCurrent: Boolean = false
)
