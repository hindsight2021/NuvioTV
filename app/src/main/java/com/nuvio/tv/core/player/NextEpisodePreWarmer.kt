package com.nuvio.tv.core.player

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

/**
 * Disk-only, bounded pre-warmer for the opening segment of a media stream.
 *
 * Downloads a small leading slice of a progressive HTTP(S) media URL into a bounded
 * on-disk Media3 [SimpleCache] (32 MB max) using a tiny 32 KB read buffer so ExoPlayer
 * can start the next episode with near-zero transition buffering while keeping RAM
 * impact negligible. Any error or active-playback stall aborts cleanly.
 */
@OptIn(UnstableApi::class)
object NextEpisodePreWarmer {

    /** Maximum size of the on-disk pre-warm cache (32 MB). */
    private const val CACHE_MAX_BYTES: Long = 32L * 1024L * 1024L

    /** Minimum free space required on cache storage before creating the cache (128 MB). */
    private const val MIN_FREE_SPACE_BYTES: Long = 128L * 1024L * 1024L

    /** Size of the read buffer used while streaming bytes into the cache (32 KB). */
    private const val READ_BUFFER_BYTES: Int = 32 * 1024

    /** Fragment size for [CacheDataSink] (1 MB). */
    private const val SINK_FRAGMENT_BYTES: Long = 1024L * 1024L

    /** Fraction of the target that counts as already warm enough. */
    private const val READY_THRESHOLD_FRACTION: Double = 0.85

    /** Default pre-warm target when the device is not low-RAM (4 MB). */
    private const val TARGET_BYTES_DEFAULT: Long = 4L * 1024L * 1024L

    /** Reduced pre-warm target for low-RAM devices (2 MB). */
    private const val TARGET_BYTES_LOW_RAM: Long = 2L * 1024L * 1024L

    /** Memory class threshold (MB) below which the device is treated as low-RAM. */
    private const val LOW_RAM_MEMORY_CLASS_THRESHOLD: Int = 256

    private const val CACHE_DIR_NAME: String = "nuvio_prewarm_cache"

    private val cacheLock = Any()

    @Volatile
    private var simpleCache: SimpleCache? = null

    @Volatile
    private var databaseProvider: StandaloneDatabaseProvider? = null

    private val _status = MutableStateFlow(PreWarmStatus(state = PreWarmState.IDLE))
    val status: StateFlow<PreWarmStatus> = _status.asStateFlow()

    /**
     * Computes the number of bytes to pre-warm for the opening segment, adapting to device memory.
     */
    fun computeTargetPreWarmBytes(context: Context): Long {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val lowRam = am?.isLowRamDevice == true
            val memoryClass = am?.memoryClass ?: Int.MAX_VALUE
            if (lowRam || memoryClass <= LOW_RAM_MEMORY_CLASS_THRESHOLD) {
                TARGET_BYTES_LOW_RAM
            } else {
                TARGET_BYTES_DEFAULT
            }
        } catch (_: Throwable) {
            TARGET_BYTES_DEFAULT
        }
    }

    /**
     * Returns true only for progressive HTTP(S) URLs that are not adaptive manifests or local.
     */
    fun isPreWarmableUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase(Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (lower.contains(".m3u8")) return false
        if (lower.contains(".mpd")) return false
        if (lower.contains(".ism")) return false
        if (lower.contains("localhost")) return false
        if (lower.contains("127.0.0.1")) return false
        return true
    }

    /**
     * Pre-warms the opening segment of [url] into the disk cache.
     *
     * Never throws; all failures are captured in [PreWarmResult].
     */
    suspend fun preWarmOpeningSegment(
        context: Context,
        targetKey: String,
        targetLabel: String,
        url: String,
        upstreamFactory: DataSource.Factory,
        shouldAbort: () -> Boolean
    ): PreWarmResult {
        val startMs = System.currentTimeMillis()
        fun elapsed(): Long = System.currentTimeMillis() - startMs

        fun aborted(reason: String, cached: Long = 0L, target: Long = 0L): PreWarmResult {
            updateState(PreWarmState.ABORTED, targetKey, targetLabel, cachedBytes = cached, targetBytes = target)
            return PreWarmResult(false, cached, elapsed(), reason)
        }

        fun skipped(reason: String): PreWarmResult {
            updateState(PreWarmState.SKIPPED, targetKey, targetLabel, cachedBytes = 0L, targetBytes = 0L)
            return PreWarmResult(false, 0L, elapsed(), reason)
        }

        try {
            if (shouldAbort()) return aborted("aborted_before_start")
            if (!isPreWarmableUrl(url)) return skipped("not_prewarmable_url")

            val cache = getOrCreateCache(context)
                ?: return skipped("cache_unavailable")

            val targetBytes = computeTargetPreWarmBytes(context)
            if (targetBytes <= 0L) return skipped("invalid_target")

            updateState(PreWarmState.RESOLVING, targetKey, targetLabel, cachedBytes = 0L, targetBytes = targetBytes)

            val alreadyCached = runCatching {
                cache.getCachedBytes(url, 0L, targetBytes)
            }.getOrDefault(0L)
            val readyThreshold = (targetBytes * READY_THRESHOLD_FRACTION).toLong()
            if (alreadyCached >= readyThreshold && alreadyCached > 0L) {
                updateState(
                    PreWarmState.READY,
                    targetKey,
                    targetLabel,
                    cachedBytes = alreadyCached,
                    targetBytes = targetBytes
                )
                return PreWarmResult(true, alreadyCached, elapsed(), "already_cached")
            }

            if (shouldAbort()) return aborted("aborted_before_download", cached = alreadyCached, target = targetBytes)

            updateState(
                PreWarmState.WARMING,
                targetKey,
                targetLabel,
                cachedBytes = alreadyCached,
                targetBytes = targetBytes
            )

            val sinkFactory = CacheDataSink.Factory()
                .setCache(cache)
                .setFragmentSize(SINK_FRAGMENT_BYTES)

            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setCacheWriteDataSinkFactory(sinkFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val dataSource = cacheDataSourceFactory.createDataSource()
            val dataSpec = DataSpec.Builder()
                .setUri(Uri.parse(url))
                .setPosition(0L)
                .setLength(targetBytes)
                .build()

            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0L
            var abortedMidway = false

            try {
                dataSource.open(dataSpec)
                while (total < targetBytes) {
                    if (shouldAbort()) {
                        abortedMidway = true
                        break
                    }
                    val remaining = targetBytes - total
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    if (toRead <= 0) break
                    val read = dataSource.read(buffer, 0, toRead)
                    if (read == -1) break
                    if (read > 0) total += read.toLong()
                }
            } finally {
                runCatching { dataSource.close() }
            }

            if (abortedMidway) {
                return aborted("aborted_midway", cached = total, target = targetBytes)
            }

            if (total > 0L) {
                updateState(
                    PreWarmState.READY,
                    targetKey,
                    targetLabel,
                    cachedBytes = total,
                    targetBytes = targetBytes
                )
                return PreWarmResult(true, total, elapsed(), "warmed")
            }

            updateState(PreWarmState.SKIPPED, targetKey, targetLabel, cachedBytes = 0L, targetBytes = targetBytes)
            return PreWarmResult(false, 0L, elapsed(), "no_bytes_read")
        } catch (t: Throwable) {
            runCatching { updateState(PreWarmState.SKIPPED, targetKey, targetLabel, cachedBytes = 0L) }
            return PreWarmResult(false, 0L, elapsed(), "error:${t.javaClass.simpleName}")
        }
    }

    /**
     * Returns a read-only [CacheDataSource.Factory] backed by the pre-warm cache when [url]
     * already has cached opening bytes; otherwise returns [upstream] unchanged.
     */
    fun wrapDataSourceFactoryIfCached(
        context: Context,
        upstream: DataSource.Factory,
        url: String
    ): DataSource.Factory {
        return try {
            val cache = getOrCreateCache(context) ?: return upstream
            val spans = runCatching { cache.getCachedSpans(url) }.getOrNull()
            if (spans.isNullOrEmpty()) return upstream

            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .setCacheWriteDataSinkFactory(null)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } catch (_: Throwable) {
            upstream
        }
    }

    /** Updates the exposed [status] flow. */
    fun updateState(
        state: PreWarmState,
        targetKey: String? = null,
        targetLabel: String? = null,
        cachedBytes: Long? = null,
        targetBytes: Long? = null
    ) {
        val previous = _status.value
        _status.value = PreWarmStatus(
            state = state,
            targetKey = targetKey ?: previous.targetKey,
            targetLabel = targetLabel ?: previous.targetLabel,
            cachedBytes = cachedBytes ?: previous.cachedBytes,
            targetBytes = targetBytes ?: previous.targetBytes,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    /** Resets the exposed [status] flow to [PreWarmState.IDLE]. */
    fun resetStatus() {
        _status.value = PreWarmStatus(
            state = PreWarmState.IDLE,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    /** Releases the on-disk pre-warm cache. */
    fun clearCache() {
        synchronized(cacheLock) {
            runCatching { simpleCache?.release() }
            simpleCache = null
            runCatching { databaseProvider?.close() }
            databaseProvider = null
        }
        resetStatus()
    }

    private fun getOrCreateCache(context: Context): SimpleCache? {
        simpleCache?.let { return it }
        synchronized(cacheLock) {
            simpleCache?.let { return it }

            val appContext = context.applicationContext
            val usable = runCatching { appContext.cacheDir.usableSpace }.getOrDefault(0L)
            if (usable in 1 until MIN_FREE_SPACE_BYTES) {
                return null
            }

            val cacheDir = File(appContext.cacheDir, CACHE_DIR_NAME)
            return try {
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }
                val dbProvider = databaseProvider
                    ?: StandaloneDatabaseProvider(appContext).also { databaseProvider = it }

                val evictor = LeastRecentlyUsedCacheEvictor(CACHE_MAX_BYTES)
                val cache = SimpleCache(cacheDir, evictor, dbProvider)
                simpleCache = cache
                cache
            } catch (_: Throwable) {
                runCatching { databaseProvider?.close() }
                databaseProvider = null
                null
            }
        }
    }
}

/** Lifecycle state of the pre-warmer. */
enum class PreWarmState {
    IDLE,
    RESOLVING,
    WARMING,
    READY,
    ABORTED,
    SKIPPED
}

/** Snapshot of the pre-warmer status. */
data class PreWarmStatus(
    val state: PreWarmState,
    val targetKey: String? = null,
    val targetLabel: String? = null,
    val cachedBytes: Long = 0L,
    val targetBytes: Long = 0L,
    val updatedAtMs: Long = 0L
)

/** Result of a single pre-warm attempt. */
data class PreWarmResult(
    val success: Boolean,
    val bytesCached: Long,
    val elapsedMs: Long,
    val reason: String
)
