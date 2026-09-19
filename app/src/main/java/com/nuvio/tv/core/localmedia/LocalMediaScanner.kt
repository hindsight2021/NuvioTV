package com.nuvio.tv.core.localmedia

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import com.nuvio.tv.domain.model.LocalMediaItem
import com.nuvio.tv.domain.model.LocalMediaScanSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * High-performance local media scanner for Android TV / Nvidia Shield.
 *
 * Scans local storage, external USB drives, and SMB shares mounted under `/storage`
 * (e.g. `Nas/Tv Shows` and `NAS/movie/4k Movies`), parsing filenames into structured
 * [LocalMediaItem] records with resolution, year, season, and episode detection.
 */
@Singleton
class LocalMediaScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "LocalMediaScanner"

        /** Video container extensions recognized as playable media. */
        val VIDEO_EXTENSIONS: Set<String> = setOf(
            "mkv", "mp4", "avi", "mov", "wmv", "flv", "webm",
            "m4v", "mpg", "mpeg", "ts", "m2ts", "vob", "3gp", "ogv", "divx"
        )

        /** Directories that never contain user media and should be skipped. */
        private val IGNORED_DIR_NAMES: Set<String> = setOf(
            "android", "data", "obb", "cache", "tmp", "temp",
            "thumbnails", "logs", "lost.dir", ".thumbnails",
            "recycle", "\$recycle.bin", "system volume information", ".git"
        )

        /** Tokens that indicate sample / trailer / extra files. */
        private val SAMPLE_TOKENS: Set<String> = setOf(
            "sample", "trailer", "preview", "extra", "extras", "featurette"
        )

        private val RESOLUTION_PATTERNS: List<Pair<Regex, Pair<String, Int>>> = listOf(
            Regex("""(?i)(2160p|4k|uhd)""") to ("4K" to 2160),
            Regex("""(?i)(1440p|2k|qhd)""") to ("1440p" to 1440),
            Regex("""(?i)(1080p|fhd|fullhd)""") to ("1080p" to 1080),
            Regex("""(?i)(720p|hd)""") to ("720p" to 720),
            Regex("""(?i)(576p|pal|480p|sd|ntsc)""") to ("SD" to 480)
        )

        private val YEAR_REGEX = Regex("""(?i)[\(\[]?((?:19|20)\d{2})[\)\]]?""")

        // S01E02, s1e2, 1x02, Season 1 Episode 2, S01.E02, S01 E02
        private val SEASON_EPISODE_REGEXES: List<Regex> = listOf(
            Regex("""(?i)S(\d{1,2})[\s._-]*E(\d{1,3})"""),
            Regex("""(?i)(\d{1,2})x(\d{1,3})"""),
            Regex("""(?i)Season[\s._-]*(\d{1,2})[\s._-]*Episode[\s._-]*(\d{1,3})""")
        )

        private val SEASON_DIR_REGEX = Regex("""(?i)(?:Season|Series|Staffel|Saison)[\s._-]*(\d{1,2})""")
        private val EPISODE_DIR_FILE_REGEX = Regex("""(?i)(?:^|[\s._-])(?:E|Ep|Episode)?[\s._-]*(\d{1,3})(?:[\s._-]|$)""")

        /** Scene tags to clean from titles. */
        private val RELEASE_TOKENS: Set<String> = setOf(
            "bluray", "blu-ray", "brrip", "bdrip", "webrip", "web-dl", "webdl", "web",
            "hdtv", "dvdrip", "dvd", "hdrip", "remux", "x264", "x265", "h264", "h265",
            "hevc", "avc", "xvid", "divx", "aac", "ac3", "dts", "dtshd", "truehd",
            "atmos", "ddp", "dd5", "dd7", "eac3", "flac", "mp3", "10bit", "8bit",
            "hdr", "hdr10", "dolby", "vision", "dv", "sdr", "proper", "repack",
            "extended", "uncut", "remastered", "imax", "multi", "dual", "subs",
            "subbed", "dubbed", "amzn", "nf", "dsnp", "hmax", "atvp", "hulu"
        )
    }

    /**
     * Auto-detects available media storage roots on the system.
     * Includes `/storage` mounts (Nvidia Shield SMB shares and USB drives)
     * and internal storage paths.
     */
    fun detectStorageRoots(): List<File> {
        val detected = LinkedHashSet<File>()

        // 1. Nvidia Shield mounted storage under /storage
        val storageDir = File("/storage")
        if (storageDir.exists() && storageDir.isDirectory) {
            val mounts = storageDir.listFiles() ?: emptyArray()
            for (mount in mounts) {
                if (!mount.isDirectory || !mount.canRead()) continue
                if (mount.name.equals("emulated", ignoreCase = true) || mount.name.equals("self", ignoreCase = true)) continue
                detected += mount

                // Look for common subfolders like "Nas/Tv Shows", "NAS/movie/4k Movies"
                val subCandidates = listOf(
                    "Nas/Tv Shows", "NAS/Tv Shows", "Nas/TV Shows", "NAS/TV Shows",
                    "NAS/movie/4k Movies", "Nas/movie/4k Movies", "Nas/Movies", "NAS/Movies",
                    "Tv Shows", "TV Shows", "Movies", "Series", "TV"
                )
                for (sub in subCandidates) {
                    val subFile = File(mount, sub)
                    if (subFile.exists() && subFile.isDirectory) {
                        detected += subFile
                    }
                }
            }
        }

        // 2. Primary External Storage (/sdcard or /storage/emulated/0)
        runCatching {
            val primary = Environment.getExternalStorageDirectory()
            if (primary != null && primary.exists() && primary.canRead()) {
                listOf("Movies", "TV Shows", "TV", "Download", "Videos").forEach { name ->
                    val f = File(primary, name)
                    if (f.exists() && f.isDirectory) detected += f
                }
            }
        }

        // 3. Android StorageManager mounted volumes
        runCatching {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (sm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                for (volume in sm.storageVolumes) {
                    val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        volume.directory
                    } else {
                        @Suppress("DEPRECATION")
                        volume.javaClass.getMethod("getPath").invoke(volume)?.let { File(it as String) }
                    }
                    if (dir != null && dir.exists() && dir.canRead()) {
                        detected += dir
                    }
                }
            }
        }

        return detected.toList()
    }

    /**
     * Recursively scans the given directory paths on [Dispatchers.IO].
     */
    suspend fun scan(
        paths: List<String>,
        minFileSizeBytes: Long = 40L * 1024L * 1024L // 40 MB min to filter samples/clips
    ): Pair<List<LocalMediaItem>, LocalMediaScanSummary> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val items = ArrayList<LocalMediaItem>()
        var totalFilesVisited = 0
        val scannedPaths = ArrayList<String>()

        val roots = paths.mapNotNull { path ->
            val f = File(path)
            if (f.exists() && f.isDirectory && f.canRead()) f else null
        }.distinctBy { it.absolutePath }

        for (root in roots) {
            scannedPaths += root.absolutePath
            val (rootItems, filesCount) = scanDirectory(root, minFileSizeBytes)
            items += rootItems
            totalFilesVisited += filesCount
        }

        val durationMs = System.currentTimeMillis() - startedAt
        val moviesCount = items.count { it.type == LocalMediaItem.TYPE_MOVIE }
        val seriesCount = items.filter { it.type == LocalMediaItem.TYPE_SERIES }.map { it.title.lowercase() }.distinct().size
        val episodesCount = items.count { it.type == LocalMediaItem.TYPE_SERIES }

        val summary = LocalMediaScanSummary(
            totalFilesScanned = totalFilesVisited,
            totalMovies = moviesCount,
            totalSeries = seriesCount,
            totalEpisodes = episodesCount,
            scanDurationMs = durationMs,
            scannedPaths = scannedPaths,
            timestamp = System.currentTimeMillis()
        )

        Log.i(TAG, "Scan completed: $moviesCount movies, $seriesCount series ($episodesCount episodes) across ${scannedPaths.size} paths in ${durationMs}ms")
        items.distinctBy { it.filePath } to summary
    }

    private suspend fun scanDirectory(
        root: File,
        minFileSizeBytes: Long,
        maxDepth: Int = 8
    ): Pair<List<LocalMediaItem>, Int> {
        val results = ArrayList<LocalMediaItem>()
        var filesCount = 0

        suspend fun walk(dir: File, depth: Int) {
            coroutineContext.ensureActive()
            if (depth > maxDepth) return
            if (!dir.isDirectory || !dir.canRead()) return
            val dirNameLower = dir.name.lowercase(Locale.ROOT)
            if (dirNameLower in IGNORED_DIR_NAMES) return

            val children = dir.listFiles() ?: return
            for (child in children) {
                coroutineContext.ensureActive()
                if (child.isDirectory) {
                    walk(child, depth + 1)
                } else if (child.isFile) {
                    filesCount++
                    val item = parseMediaFile(child, minFileSizeBytes)
                    if (item != null) {
                        results += item
                    }
                }
            }
        }

        walk(root, 0)
        return results to filesCount
    }

    /**
     * Parses a single file into a [LocalMediaItem], inspecting its name, parent folders,
     * extension, and size.
     */
    fun parseMediaFile(file: File, minFileSizeBytes: Long = 40L * 1024L * 1024L): LocalMediaItem? {
        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext !in VIDEO_EXTENSIONS) return null
        if (file.length() < minFileSizeBytes) return null

        val name = file.nameWithoutExtension
        val nameLower = name.lowercase(Locale.ROOT)
        if (SAMPLE_TOKENS.any { nameLower.contains(it) }) return null

        val parentDir = file.parentFile
        val parentName = parentDir?.name ?: ""
        val grandParentName = parentDir?.parentFile?.name ?: ""
        val fullPath = file.absolutePath

        // 1. Detect resolution / quality
        val (resolution, qualityValue) = detectResolution(fullPath)

        // 2. Check for TV episode pattern in filename or parent folders
        val episodeMatch = parseEpisodeInfo(name, parentName, grandParentName)

        if (episodeMatch != null) {
            val (seriesTitle, season, episode, epTitle) = episodeMatch
            val stableId = generateStableId(LocalMediaItem.TYPE_SERIES, "$seriesTitle:S${season}E${episode}")
            return LocalMediaItem(
                id = stableId,
                filePath = file.absolutePath,
                fileUri = "file://${file.absolutePath}",
                title = seriesTitle,
                type = LocalMediaItem.TYPE_SERIES,
                year = extractYear(name) ?: extractYear(seriesTitle),
                season = season,
                episode = episode,
                episodeTitle = epTitle,
                fileSize = file.length(),
                resolution = resolution,
                qualityValue = qualityValue,
                format = ext.uppercase(Locale.ROOT),
                lastModified = file.lastModified()
            )
        }

        // 3. Otherwise treat as movie
        val (movieTitle, movieYear) = parseMovieInfo(name, parentName, fullPath)
        val stableId = generateStableId(LocalMediaItem.TYPE_MOVIE, "$movieTitle:${movieYear ?: 0}")
        return LocalMediaItem(
            id = stableId,
            filePath = file.absolutePath,
            fileUri = "file://${file.absolutePath}",
            title = movieTitle,
            type = LocalMediaItem.TYPE_MOVIE,
            year = movieYear,
            season = null,
            episode = null,
            episodeTitle = null,
            fileSize = file.length(),
            resolution = resolution,
            qualityValue = qualityValue,
            format = ext.uppercase(Locale.ROOT),
            lastModified = file.lastModified()
        )
    }

    private fun detectResolution(pathOrName: String): Pair<String, Int> {
        for ((regex, resPair) in RESOLUTION_PATTERNS) {
            if (regex.containsMatchIn(pathOrName)) {
                return resPair
            }
        }
        return "1080p" to 1080
    }

    private data class EpisodeInfo(
        val seriesTitle: String,
        val season: Int,
        val episode: Int,
        val episodeTitle: String?
    )

    private fun parseEpisodeInfo(
        fileName: String,
        parentName: String,
        grandParentName: String
    ): EpisodeInfo? {
        // Pattern 1: SxxExx or 1x02 in filename
        for (regex in SEASON_EPISODE_REGEXES) {
            val match = regex.find(fileName)
            if (match != null) {
                val s = match.groupValues[1].toIntOrNull() ?: continue
                val e = match.groupValues[2].toIntOrNull() ?: continue

                // Series title is either prefix before SxxExx or parent folder
                val rawPrefix = fileName.substring(0, match.range.first)
                    .replace('.', ' ')
                    .replace('_', ' ')
                    .replace('-', ' ')
                    .trim()

                val seriesTitle = if (rawPrefix.isNotBlank() && !isSeasonDirName(rawPrefix)) {
                    cleanTitle(rawPrefix)
                } else if (!isSeasonDirName(parentName) && parentName.isNotBlank()) {
                    cleanTitle(parentName)
                } else {
                    cleanTitle(grandParentName)
                }

                // Any remainder after SxxExx could be episode title
                val rawSuffix = fileName.substring(match.range.last + 1)
                val epTitle = cleanTitle(rawSuffix).takeIf { it.isNotBlank() }

                return EpisodeInfo(
                    seriesTitle = seriesTitle.ifBlank { "Unknown Series" },
                    season = s,
                    episode = e,
                    episodeTitle = epTitle
                )
            }
        }

        // Pattern 2: Parent folder is "Season X" and filename has episode number
        val seasonDirMatch = SEASON_DIR_REGEX.find(parentName)
        if (seasonDirMatch != null) {
            val seasonNum = seasonDirMatch.groupValues[1].toIntOrNull() ?: 1
            val epMatch = EPISODE_DIR_FILE_REGEX.find(fileName)
            if (epMatch != null) {
                val epNum = epMatch.groupValues[1].toIntOrNull()
                if (epNum != null) {
                    val seriesTitle = cleanTitle(grandParentName)
                    return EpisodeInfo(
                        seriesTitle = seriesTitle.ifBlank { "Unknown Series" },
                        season = seasonNum,
                        episode = epNum,
                        episodeTitle = cleanTitle(fileName.replace(epMatch.value, " ")).takeIf { it.isNotBlank() }
                    )
                }
            }
        }

        return null
    }

    private fun isSeasonDirName(name: String): Boolean =
        SEASON_DIR_REGEX.containsMatchIn(name)

    private data class MovieInfo(
        val title: String,
        val year: Int?
    )

    private fun parseMovieInfo(
        fileName: String,
        parentName: String,
        fullPath: String
    ): MovieInfo {
        var year = extractYear(fileName)
        var rawTitle = fileName

        if (year != null) {
            val yearIdx = rawTitle.indexOf(year.toString())
            if (yearIdx > 0) {
                rawTitle = rawTitle.substring(0, yearIdx)
            }
        } else {
            // Check if parent directory has year, e.g. "Dune Part Two (2024)"
            year = extractYear(parentName)
            if (year != null && !isTopLevelMediaFolder(parentName)) {
                val parentYearIdx = parentName.indexOf(year.toString())
                if (parentYearIdx > 0) {
                    rawTitle = parentName.substring(0, parentYearIdx)
                }
            }
        }

        val cleaned = cleanTitle(rawTitle)
        val finalTitle = if (cleaned.isBlank() && !isTopLevelMediaFolder(parentName)) {
            cleanTitle(parentName)
        } else {
            cleaned
        }

        return MovieInfo(
            title = finalTitle.ifBlank { "Unknown Movie" },
            year = year
        )
    }

    private fun extractYear(str: String): Int? {
        val matches = YEAR_REGEX.findAll(str).toList()
        for (m in matches.reversed()) {
            val y = m.groupValues[1].toIntOrNull()
            if (y != null && y in 1900..2099) return y
        }
        return null
    }

    private fun cleanTitle(raw: String): String {
        var s = raw
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

        // Remove bracketed or parenthesized tags
        s = s.replace(Regex("""\([^\)]*\)"""), " ")
        s = s.replace(Regex("""\[[^\]]*\]"""), " ")

        // Split into words and stop at first release token
        val words = s.split(" ").filter { it.isNotBlank() }
        val cleanWords = ArrayList<String>()

        for (word in words) {
            val wLower = word.lowercase(Locale.ROOT)
            if (wLower in RELEASE_TOKENS || RESOLUTION_PATTERNS.any { it.first.matches(word) }) {
                break
            }
            cleanWords += word
        }

        return cleanWords.joinToString(" ").trim()
    }

    private fun isTopLevelMediaFolder(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower in setOf("movies", "movie", "4k movies", "nas", "tv shows", "tv", "series", "videos")
    }

    private fun generateStableId(type: String, seed: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(seed.lowercase(Locale.ROOT).toByteArray())
        val hex = digest.take(6).joinToString("") { "%02x".format(it) }
        return "local:$type:$hex"
    }
}
