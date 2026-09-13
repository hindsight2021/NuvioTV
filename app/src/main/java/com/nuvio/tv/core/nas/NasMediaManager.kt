package com.nuvio.tv.core.nas

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class NasFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val parsedTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
    val isVideo: Boolean = false
)

object NasMediaManager {
    private const val TAG = "NasMediaManager"
    private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "mov", "ts", "m4v", "webm", "iso", "wmv", "flv")

    private val TV_REGEX = Regex("(?i)^(.*?)[._ -]+s([0-9]{1,2})e([0-9]{1,2})")
    private val MOVIE_REGEX = Regex("(?i)^(.*?)[._ -]+[(]?([12][90][0-9]{2})[)]?")

    private val _currentPath = MutableStateFlow<String>(Environment.getExternalStorageDirectory().absolutePath)
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _items = MutableStateFlow<List<NasFileItem>>(emptyList())
    val items: StateFlow<List<NasFileItem>> = _items.asStateFlow()

    suspend fun listSharesOrRoots(): List<NasFileItem> = withContext(Dispatchers.IO) {
        val roots = mutableListOf<NasFileItem>()
        
        // Android primary external storage
        val primary = Environment.getExternalStorageDirectory()
        if (primary.exists() && primary.canRead()) {
            roots.add(NasFileItem("Internal Storage", primary.absolutePath, isDirectory = true))
        }

        // Nvidia Shield mounted network shares and USB drives live in /storage
        val storageDir = File("/storage")
        if (storageDir.exists() && storageDir.canRead()) {
            storageDir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name != "emulated" && file.name != "self") {
                    val label = if (file.name.contains("nas", ignoreCase = true) || file.name.contains("smb", ignoreCase = true)) {
                        "Mounted NAS: ${file.name}"
                    } else {
                        "External Drive: ${file.name}"
                    }
                    roots.add(NasFileItem(label, file.absolutePath, isDirectory = true))
                }
            }
        }
        roots
    }

    suspend fun browseDirectory(path: String): List<NasFileItem> = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory || !dir.canRead()) {
            Log.w(TAG, "Cannot read directory: $path")
            return@withContext emptyList()
        }

        _currentPath.value = path
        val files = dir.listFiles() ?: return@withContext emptyList()

        val list = files.mapNotNull { file ->
            if (file.name.startsWith(".")) return@mapNotNull null
            val isDir = file.isDirectory
            val ext = file.extension.lowercase()
            val isVid = !isDir && ext in VIDEO_EXTENSIONS

            if (!isDir && !isVid) return@mapNotNull null

            var parsedTitle: String? = null
            var season: Int? = null
            var episode: Int? = null
            var year: Int? = null

            if (isVid) {
                val base = file.nameWithoutExtension
                val tvMatch = TV_REGEX.find(base)
                if (tvMatch != null) {
                    parsedTitle = tvMatch.groupValues[1].replace(".", " ").replace("_", " ").trim()
                    season = tvMatch.groupValues[2].toIntOrNull()
                    episode = tvMatch.groupValues[3].toIntOrNull()
                } else {
                    val movieMatch = MOVIE_REGEX.find(base)
                    if (movieMatch != null) {
                        parsedTitle = movieMatch.groupValues[1].replace(".", " ").replace("_", " ").trim()
                        year = movieMatch.groupValues[2].toIntOrNull()
                    } else {
                        parsedTitle = base.replace(".", " ").replace("_", " ").trim()
                    }
                }
            }

            NasFileItem(
                name = file.name,
                path = file.absolutePath,
                isDirectory = isDir,
                sizeBytes = if (isDir) 0L else file.length(),
                parsedTitle = parsedTitle,
                season = season,
                episode = episode,
                year = year,
                isVideo = isVid
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        _items.value = list
        list
    }
}
