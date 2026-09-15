package com.nuvio.tv.core.playlist

import android.content.Context
import android.util.Log
import com.nuvio.tv.domain.model.Video
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PlaylistItem(
    val contentId: String,
    val videoId: String?,
    val title: String,
    val seriesTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val thumbnail: String? = null,
    val mediaType: String = "series"
)

enum class ChannelMode {
    NONE,
    RANDOM_SHUFFLE,
    BINGE_ORDER
}

object PlaylistManager {
    private const val TAG = "PlaylistManager"
    private val json = Json { ignoreUnknownKeys = true }

    private val _queue = MutableStateFlow<List<PlaylistItem>>(emptyList())
    val queue: StateFlow<List<PlaylistItem>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow<Int>(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _channelMode = MutableStateFlow<ChannelMode>(ChannelMode.NONE)
    val channelMode: StateFlow<ChannelMode> = _channelMode.asStateFlow()

    fun currentItem(): PlaylistItem? {
        val idx = _currentIndex.value
        val items = _queue.value
        return if (idx in items.indices) items[idx] else null
    }

    fun playNext(item: PlaylistItem) {
        val current = _queue.value.toMutableList()
        val insertIndex = (_currentIndex.value + 1).coerceAtMost(current.size)
        current.add(insertIndex, item)
        _queue.value = current
        Log.d(TAG, "Added to Play Next: ${item.title} at index $insertIndex")
    }

    fun addToQueue(item: PlaylistItem) {
        val current = _queue.value.toMutableList()
        current.add(item)
        _queue.value = current
        Log.d(TAG, "Added to queue: ${item.title}")
    }

    fun startChannel(
        contentId: String,
        seriesTitle: String,
        episodes: List<Video>,
        startEpisode: Video? = null,
        shuffle: Boolean = false
    ): PlaylistItem? {
        val validEpisodes = episodes.filter { it.season != null && it.episode != null }
        if (validEpisodes.isEmpty()) return null

        val items = validEpisodes.map { ep ->
            PlaylistItem(
                contentId = contentId,
                videoId = ep.id,
                title = ep.title.ifBlank { "Episode ${ep.episode}" },
                seriesTitle = seriesTitle,
                season = ep.season,
                episode = ep.episode,
                thumbnail = ep.thumbnail,
                mediaType = "series"
            )
        }

        val finalQueue = if (shuffle) {
            val shuffled = items.shuffled().toMutableList()
            if (startEpisode != null) {
                // Ensure starting episode is first
                val startItem = items.firstOrNull {
                    (it.videoId != null && it.videoId == startEpisode.id) ||
                    (it.season == startEpisode.season && it.episode == startEpisode.episode)
                }
                if (startItem != null) {
                    shuffled.remove(startItem)
                    shuffled.add(0, startItem)
                }
            }
            shuffled
        } else {
            val sorted = items.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            if (startEpisode != null) {
                val startIndex = sorted.indexOfFirst {
                    (it.videoId != null && it.videoId == startEpisode.id) ||
                    (it.season == startEpisode.season && it.episode == startEpisode.episode)
                }
                if (startIndex > 0) {
                    sorted.drop(startIndex)
                } else {
                    sorted
                }
            } else {
                sorted
            }
        }

        _channelMode.value = if (shuffle) ChannelMode.RANDOM_SHUFFLE else ChannelMode.BINGE_ORDER
        _queue.value = finalQueue
        _currentIndex.value = 0
        Log.d(TAG, "Started channel mode ${_channelMode.value} with ${finalQueue.size} episodes")
        return finalQueue.firstOrNull()
    }

    fun startThematicChannel(items: List<PlaylistItem>): PlaylistItem? {
        if (items.isEmpty()) return null
        _channelMode.value = ChannelMode.BINGE_ORDER
        _queue.value = items
        _currentIndex.value = 0
        Log.d(TAG, "Started thematic channel with ${items.size} items")
        return items.firstOrNull()
    }

    fun next(): PlaylistItem? {
        val items = _queue.value
        if (items.isEmpty()) return null
        val nextIdx = _currentIndex.value + 1
        if (nextIdx in items.indices) {
            _currentIndex.value = nextIdx
            return items[nextIdx]
        }
        if (_channelMode.value == ChannelMode.RANDOM_SHUFFLE && items.isNotEmpty()) {
            val reshuffled = items.shuffled()
            _queue.value = reshuffled
            _currentIndex.value = 0
            return reshuffled.firstOrNull()
        }
        return null
    }

    fun hasNext(): Boolean {
        if (_channelMode.value == ChannelMode.RANDOM_SHUFFLE && _queue.value.isNotEmpty()) {
            return true
        }
        return (_currentIndex.value + 1) in _queue.value.indices
    }

    fun removeAt(index: Int) {
        val current = _queue.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _queue.value = current
            if (_currentIndex.value >= index && _currentIndex.value > 0) {
                _currentIndex.value -= 1
            }
        }
    }

    fun move(fromIndex: Int, toIndex: Int) {
        val current = _queue.value.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            _queue.value = current
        }
    }

    fun clear() {
        _queue.value = emptyList()
        _currentIndex.value = -1
        _channelMode.value = ChannelMode.NONE
    }

    fun saveNamedPlaylist(context: Context, name: String) {
        try {
            val dir = File(context.filesDir, "playlists").apply { mkdirs() }
            val file = File(dir, "$name.json")
            val data = json.encodeToString(_queue.value)
            file.writeText(data)
            Log.d(TAG, "Saved playlist '$name' with ${_queue.value.size} items")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save playlist", e)
        }
    }

    fun loadNamedPlaylist(context: Context, name: String): Boolean {
        return try {
            val file = File(File(context.filesDir, "playlists"), "$name.json")
            if (!file.exists()) return false
            val items = json.decodeFromString<List<PlaylistItem>>(file.readText())
            _queue.value = items
            _currentIndex.value = 0
            _channelMode.value = ChannelMode.NONE
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load playlist", e)
            false
        }
    }
}
