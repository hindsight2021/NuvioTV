package com.nuvio.tv.core.localmedia

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Represents the parsed metadata from a Kodi/Jellyfin .nfo file.
 */
data class NfoMetadata(
    val title: String? = null,
    val year: Int? = null,
    val plot: String? = null,
    val tmdbId: String? = null,
    val imdbId: String? = null,
    val posterFile: File? = null,
    val fanartFile: File? = null
)

/**
 * Parses Kodi and Jellyfin `.nfo` files using [XmlPullParser].
 * Discovers local artwork (`poster.jpg`, `fanart.jpg`, and common variants)
 * located alongside the `.nfo` file.
 */
@Singleton
class KodiNfoParser @Inject constructor() {

    companion object {
        /** Common poster filenames, in order of preference. */
        private val POSTER_NAMES = listOf(
            "poster.jpg", "poster.jpeg", "poster.png",
            "folder.jpg", "folder.jpeg", "folder.png",
            "cover.jpg", "cover.png", "movie.jpg", "movie.png"
        )

        /** Common fanart filenames, in order of preference. */
        private val FANART_NAMES = listOf(
            "fanart.jpg", "fanart.jpeg", "fanart.png",
            "backdrop.jpg", "backdrop.jpeg", "backdrop.png",
            "background.jpg", "background.png"
        )

        private const val TAG_TITLE = "title"
        private const val TAG_ORIGINAL_TITLE = "originaltitle"
        private const val TAG_YEAR = "year"
        private const val TAG_PLOT = "plot"
        private const val TAG_OUTLINE = "outline"
        private const val TAG_TMDB = "tmdbid"
        private const val TAG_IMDB = "imdbid"
        private const val TAG_UNIQUEID = "uniqueid"
        private const val TAG_ID = "id"
    }

    /**
     * Finds and parses an .nfo file in the given directory or for a specific video file.
     */
    fun findAndParseNfo(directory: File, videoFileName: String? = null): NfoMetadata {
        val candidates = mutableListOf<File>()
        if (!videoFileName.isNullOrBlank()) {
            candidates += File(directory, "${videoFileName}.nfo")
        }
        candidates += File(directory, "movie.nfo")
        candidates += File(directory, "tvshow.nfo")

        val nfoFile = candidates.firstOrNull { it.exists() && it.isFile && it.canRead() }
            ?: directory.listFiles()?.firstOrNull { it.extension.equals("nfo", ignoreCase = true) }

        val metadata = if (nfoFile != null) parse(nfoFile) else NfoMetadata()
        return metadata.withArtwork(directory)
    }

    /**
     * Parses the given `.nfo` [file] and resolves local artwork from its parent directory.
     */
    fun parse(file: File): NfoMetadata {
        val metadata = try {
            FileInputStream(file).use { stream -> parseStream(stream) }
        } catch (_: Exception) {
            NfoMetadata()
        }
        return metadata.withArtwork(file.parentFile)
    }

    /**
     * Parses an `.nfo` document from an arbitrary [InputStream].
     */
    fun parseStream(input: InputStream): NfoMetadata {
        var title: String? = null
        var year: Int? = null
        var plot: String? = null
        var tmdbId: String? = null
        var imdbId: String? = null

        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)

            var event = parser.eventType
            var currentTag: String? = null

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name?.lowercase()
                        if (currentTag == TAG_UNIQUEID) {
                            val type = parser.getAttributeValue(null, "type")?.lowercase()
                            val value = readText(parser)
                            when (type) {
                                "tmdb" -> if (tmdbId.isNullOrBlank()) tmdbId = value
                                "imdb" -> if (imdbId.isNullOrBlank()) imdbId = value
                            }
                            currentTag = null
                        }
                    }

                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            when (currentTag) {
                                TAG_TITLE -> if (title.isNullOrBlank()) title = text
                                TAG_ORIGINAL_TITLE -> if (title.isNullOrBlank()) title = text
                                TAG_YEAR -> if (year == null) year = text.toIntOrNull()
                                TAG_PLOT -> if (plot.isNullOrBlank()) plot = text
                                TAG_OUTLINE -> if (plot.isNullOrBlank()) plot = text
                                TAG_TMDB -> if (tmdbId.isNullOrBlank()) tmdbId = text
                                TAG_IMDB -> if (imdbId.isNullOrBlank()) imdbId = text
                                TAG_ID -> {
                                    if (text.startsWith("tt") && imdbId.isNullOrBlank()) {
                                        imdbId = text
                                    } else if (text.all { it.isDigit() } && tmdbId.isNullOrBlank()) {
                                        tmdbId = text
                                    }
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> currentTag = null
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // Return whatever was parsed before failure
        }

        return NfoMetadata(
            title = title?.takeIf { it.isNotBlank() },
            year = year,
            plot = plot?.takeIf { it.isNotBlank() },
            tmdbId = tmdbId?.takeIf { it.isNotBlank() },
            imdbId = imdbId?.takeIf { it.isNotBlank() }
        )
    }

    private fun readText(parser: XmlPullParser): String? {
        return try {
            if (parser.next() == XmlPullParser.TEXT) {
                val text = parser.text?.trim()
                if (parser.next() == XmlPullParser.END_TAG) {
                    return text?.takeIf { it.isNotEmpty() }
                }
                text?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun findPoster(folder: File?): File? {
        if (folder == null || !folder.isDirectory) return null
        return findArtwork(folder, POSTER_NAMES)
    }

    fun findFanart(folder: File?): File? {
        if (folder == null || !folder.isDirectory) return null
        return findArtwork(folder, FANART_NAMES)
    }

    private fun NfoMetadata.withArtwork(folder: File?): NfoMetadata {
        if (folder == null || !folder.isDirectory) return this
        return copy(
            posterFile = posterFile ?: findPoster(folder),
            fanartFile = fanartFile ?: findFanart(folder)
        )
    }

    private fun findArtwork(folder: File, names: List<String>): File? {
        for (name in names) {
            val candidate = File(folder, name)
            if (candidate.isFile && candidate.canRead()) return candidate
        }
        return null
    }
}
