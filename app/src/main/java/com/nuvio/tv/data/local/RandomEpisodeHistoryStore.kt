package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.playlist.RandomPickRecord
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists a per-profile history of randomly picked episodes, keyed by a canonical show ID.
 * Ensures consecutive random plays or shuffle sessions never regurgitate recent picks across
 * IMDB/TMDB/Trakt/Simkl aliases for the same series.
 */
@Singleton
class RandomEpisodeHistoryStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val gson = Gson()
    private val mutex = Mutex()
    private val mapType = object : TypeToken<Map<String, List<StoredPick>>>() {}.type

    suspend fun getRecentPicks(
        contentIds: Collection<String>,
        profileId: Int? = null
    ): List<RandomPickRecord> {
        val resolvedProfileId = profileId ?: profileManager.activeProfileId.value
        val normalizedIds = contentIds.mapNotNull(::normalizeKey).toSet()
        if (normalizedIds.isEmpty()) return emptyList()

        val map = readMap(resolvedProfileId)
        if (map.isEmpty()) return emptyList()

        val merged = LinkedHashMap<Pair<Int, Int>, RandomPickRecord>()
        for (key in normalizedIds) {
            val picks = map[key] ?: continue
            for (pick in picks) {
                val pair = pick.s to pick.e
                val existing = merged[pair]
                if (existing == null || pick.t > existing.pickedAtMs) {
                    merged[pair] = RandomPickRecord(
                        season = pick.s,
                        episode = pick.e,
                        pickedAtMs = pick.t
                    )
                }
            }
        }

        return merged.values
            .sortedByDescending { it.pickedAtMs }
            .take(MAX_PICKS_PER_SERIES)
    }

    suspend fun recordPick(
        contentId: String,
        season: Int,
        episode: Int,
        siblingIds: Collection<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
        profileId: Int? = null
    ) {
        val canonicalKey = pickCanonicalKey(contentId, siblingIds) ?: return
        val resolvedProfileId = profileId ?: profileManager.activeProfileId.value

        mutex.withLock {
            val map = readMap(resolvedProfileId).toMutableMap()

            val aliasKeys = LinkedHashSet<String>()
            normalizeKey(contentId)?.let(aliasKeys::add)
            siblingIds.mapNotNull(::normalizeKey).forEach(aliasKeys::add)
            aliasKeys.add(canonicalKey)

            val mergedPicks = ArrayList<StoredPick>()
            for (alias in aliasKeys) {
                map.remove(alias)?.let(mergedPicks::addAll)
            }
            mergedPicks.add(0, StoredPick(s = season, e = episode, t = nowMs))

            map[canonicalKey] = dedupeNewestFirst(mergedPicks).take(MAX_PICKS_PER_SERIES)
            writeMap(resolvedProfileId, capSeries(map))
        }
    }

    private fun dedupeNewestFirst(picks: List<StoredPick>): List<StoredPick> {
        val best = LinkedHashMap<Pair<Int, Int>, StoredPick>()
        for (pick in picks) {
            val pair = pick.s to pick.e
            val existing = best[pair]
            if (existing == null || pick.t > existing.t) {
                best[pair] = pick
            }
        }
        return best.values.sortedByDescending { it.t }
    }

    private fun capSeries(map: Map<String, List<StoredPick>>): Map<String, List<StoredPick>> {
        if (map.size <= MAX_SERIES) return map
        return map.entries
            .sortedByDescending { entry -> entry.value.maxOfOrNull { it.t } ?: 0L }
            .take(MAX_SERIES)
            .associate { it.key to it.value }
    }

    private fun pickCanonicalKey(contentId: String, siblingIds: Collection<String>): String? {
        val normalized = buildList {
            normalizeKey(contentId)?.let(::add)
            siblingIds.mapNotNull(::normalizeKey).forEach(::add)
        }.distinct()

        normalized.firstOrNull { it.startsWith("tt") && it.length > 2 && !it.contains(':') }?.let { return it }
        normalized.firstOrNull { it.startsWith("tmdb:") }?.let { return it }
        normalized.firstOrNull { it.startsWith("trakt:") }?.let { return it }
        normalized.firstOrNull { it.startsWith("simkl:") }?.let { return it }
        return normalizeKey(contentId)
    }

    private fun normalizeKey(raw: String?): String? {
        val trimmed = raw?.trim()?.lowercase() ?: return null
        return trimmed.ifEmpty { null }
    }

    private suspend fun readMap(profileId: Int): Map<String, List<StoredPick>> {
        val dataStore = factory.get(profileId, FEATURE)
        val raw = runCatching { dataStore.data.first()[KEY] }.getOrNull() ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()

        return runCatching {
            val parsed: Map<String, List<StoredPick>>? = gson.fromJson(raw, mapType)
            parsed?.mapValues { (_, picks) -> picks.filterNotNull() } ?: emptyMap()
        }.getOrElse { emptyMap() }
    }

    private suspend fun writeMap(profileId: Int, map: Map<String, List<StoredPick>>) {
        val dataStore = factory.get(profileId, FEATURE)
        val json = runCatching { gson.toJson(map) }.getOrElse { "{}" }
        dataStore.edit { prefs ->
            prefs[KEY] = json
        }
    }

    data class StoredPick(
        val s: Int,
        val e: Int,
        val t: Long
    )

    private companion object {
        const val FEATURE = "random_episode_history"
        val KEY = stringPreferencesKey("series_random_history_json")
        const val MAX_PICKS_PER_SERIES = 60
        const val MAX_SERIES = 120
    }
}
