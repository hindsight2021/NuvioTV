package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class CachedStreamLink(
    val url: String,
    val streamName: String,
    val headers: Map<String, String>,
    val cachedAtMs: Long,
    val filename: String? = null,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val sources: List<String>? = null,
    val bingeGroup: String? = null,
    val contentLanguage: String? = null,
    val year: String? = null,
    val isDebridLink: Boolean = false,
    val clientResolveService: String? = null,
    val clientResolveMagnet: String? = null,
    val clientResolveType: String? = null,
    val clientResolveTorrentName: String? = null
) {
    fun isDebridUrlExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        (isDebridLink || isLikelyDebridCdnUrl(url)) &&
            cachedAtMs > 0L &&
            (nowMs - cachedAtMs) > DEBRID_CDN_MAX_AGE_MS

    fun canRefreshDebridLink(): Boolean =
        !clientResolveService.isNullOrBlank() &&
            (!infoHash.isNullOrBlank() ||
                !clientResolveMagnet.isNullOrBlank() ||
                !clientResolveTorrentName.isNullOrBlank() ||
                !filename.isNullOrBlank())

    companion object {
        const val DEBRID_CDN_MAX_AGE_MS = 45L * 60L * 1000L

        private val DEBRID_CDN_HOSTS = listOf(
            "tb-cdn.io",
            "torbox.app",
            "premiumize.me",
            "real-debrid.com",
            "rdeb.io",
            "alldebrid",
            "debrid-link",
            "offcloud",
            "easynews",
            "put.io"
        )

        fun isLikelyDebridCdnUrl(url: String): Boolean {
            if (url.isBlank()) return false
            val lower = url.lowercase()
            return DEBRID_CDN_HOSTS.any { lower.contains(it) }
        }
    }
}

@Singleton
class StreamLinkCacheDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "stream_link_cache"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    suspend fun save(
        contentKey: String,
        url: String,
        streamName: String,
        headers: Map<String, String>?,
        filename: String? = null,
        videoHash: String? = null,
        videoSize: Long? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        sources: List<String>? = null,
        bingeGroup: String? = null,
        contentLanguage: String? = null,
        year: String? = null,
        isDebridLink: Boolean = false,
        clientResolveService: String? = null,
        clientResolveMagnet: String? = null,
        clientResolveType: String? = null,
        clientResolveTorrentName: String? = null
    ) {
        val effectiveIsDebrid = isDebridLink || CachedStreamLink.isLikelyDebridCdnUrl(url)
        val payload = JSONObject().apply {
            put("url", url)
            put("streamName", streamName)
            put("cachedAtMs", System.currentTimeMillis())
            put("headers", JSONObject(headers ?: emptyMap<String, String>()))
            put("filename", filename)
            put("videoHash", videoHash)
            videoSize?.let { put("videoSize", it) }
            infoHash?.let { put("infoHash", it) }
            fileIdx?.let { put("fileIdx", it) }
            sources?.let { put("sources", JSONArray(it)) }
            bingeGroup?.let { put("bingeGroup", it) }
            contentLanguage?.let { put("contentLanguage", it) }
            year?.let { put("year", it) }
            put("isDebridLink", effectiveIsDebrid)
            clientResolveService?.let { put("clientResolveService", it) }
            clientResolveMagnet?.let { put("clientResolveMagnet", it) }
            clientResolveType?.let { put("clientResolveType", it) }
            clientResolveTorrentName?.let { put("clientResolveTorrentName", it) }
        }.toString()

        store().edit { prefs ->
            prefs[cachePrefKey(contentKey)] = payload
        }
    }

    suspend fun getValid(
        contentKey: String,
        maxAgeMs: Long,
        allowExpiredDebridRefresh: Boolean = false
    ): CachedStreamLink? {
        if (maxAgeMs <= 0L) return null

        val key = cachePrefKey(contentKey)
        val raw = store().data.first()[key] ?: return null

        val parsed = runCatching {
            val json = JSONObject(raw)
            val cachedAtMs = json.optLong("cachedAtMs", 0L)
            val age = System.currentTimeMillis() - cachedAtMs
            if (cachedAtMs <= 0L || age > maxAgeMs) return@runCatching null

            val headersJson = json.optJSONObject("headers")
            val headers = buildMap {
                headersJson?.keys()?.forEach { headerKey ->
                    put(headerKey, headersJson.optString(headerKey, ""))
                }
            }.filterValues { it.isNotEmpty() }

            val url = json.optString("url", "")
            val streamName = json.optString("streamName", "")
            val infoHash = json.optString("infoHash", "").ifBlank { null }
            // Accept entry if it has either a real URL (HTTP stream) or an
            // infoHash (torrent stream — URL is re-resolved on each playback).
            if (streamName.isBlank() || (url.isBlank() && infoHash == null)) return@runCatching null

            val sourcesJson = json.optJSONArray("sources")
            val sources = sourcesJson?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).takeIf { it.isNotEmpty() }
                }
            }?.takeIf { it.isNotEmpty() }

            CachedStreamLink(
                url = url,
                streamName = streamName,
                headers = headers,
                cachedAtMs = cachedAtMs,
                filename = json.optString("filename", "").ifBlank { null },
                videoHash = json.optString("videoHash", "").ifBlank { null },
                videoSize = json.optLong("videoSize", -1L).takeIf { it >= 0L },
                infoHash = infoHash,
                fileIdx = if (json.has("fileIdx")) json.optInt("fileIdx", -1).takeIf { it >= 0 } else null,
                sources = sources,
                bingeGroup = json.optString("bingeGroup", "").ifBlank { null },
                contentLanguage = json.optString("contentLanguage", "").ifBlank { null },
                year = json.optString("year", "").ifBlank { null },
                isDebridLink = json.optBoolean("isDebridLink", false),
                clientResolveService = json.optString("clientResolveService", "").ifBlank { null },
                clientResolveMagnet = json.optString("clientResolveMagnet", "").ifBlank { null },
                clientResolveType = json.optString("clientResolveType", "").ifBlank { null },
                clientResolveTorrentName = json.optString("clientResolveTorrentName", "").ifBlank { null }
            )
        }.getOrNull()

        if (parsed == null) {
            store().edit { mutablePrefs ->
                mutablePrefs.remove(key)
            }
            return null
        }

        if (parsed.isDebridUrlExpired()) {
            if (allowExpiredDebridRefresh && parsed.canRefreshDebridLink()) {
                return parsed
            }
            if (!parsed.canRefreshDebridLink()) {
                store().edit { mutablePrefs ->
                    mutablePrefs.remove(key)
                }
            }
            return null
        }

        return parsed
    }

    suspend fun remove(contentKey: String) {
        val key = cachePrefKey(contentKey)
        store().edit { prefs ->
            prefs.remove(key)
        }
    }

    private fun cachePrefKey(contentKey: String): Preferences.Key<String> {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(contentKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return stringPreferencesKey("stream_link_$digest")
    }
}
