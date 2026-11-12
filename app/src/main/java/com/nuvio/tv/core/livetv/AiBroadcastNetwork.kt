package com.nuvio.tv.core.livetv

import android.content.Context
import com.nuvio.tv.core.ai.AiManager
import com.nuvio.tv.core.ai.AiVoicePersona
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/**
 * A single program scheduled on an AI Broadcast Network channel.
 */
@Serializable
data class AiBroadcastProgram(
    val title: String,
    val contentId: String,
    val mediaType: String = "series",
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val synopsis: String = "",
    val genre: String = "",
    val durationMinutes: Int = 45,
    val djIntroLine: String = "",
    val backdropUrl: String? = null
)

/**
 * Configuration for a 24/7 AI Broadcast channel, including DJ voice persona and program block.
 */
@Serializable
data class AiBroadcastChannelConfig(
    val id: String,
    val number: String,
    val name: String,
    val callSign: String,
    val tagline: String,
    val accentColorHex: String = "#7C3AED",
    val voicePersonaId: String = AiVoicePersona.CHIC_CRITIC_BRITISH.id,
    val djHostName: String = "Reginald",
    val djStyle: String = "Dry British film connoisseur",
    val themePrompt: String = "",
    val isBuiltIn: Boolean = false,
    val programs: List<AiBroadcastProgram> = emptyList()
)

/**
 * A resolved EPG slot with concrete wall-clock timing.
 */
data class AiBroadcastEpgSlot(
    val program: AiBroadcastProgram,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val isOnAirNow: Boolean,
    val elapsedMs: Long
) {
    val remainingMinutes: Int
        get() = ((endTimestampMs - (startTimestampMs + elapsedMs)).coerceAtLeast(0L) / 60_000L).toInt()
}

/**
 * SharedPreferences-backed persistence for AI broadcast toggles, custom channels, and built-in customizations.
 */
class AiBroadcastPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Master switch for the 24/7 AI Broadcast Network (opt-in / toggleable with zero overhead when off). */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Whether DJ host intro bumpers are spoken via TTS when tuning into a channel. */
    var djBumpersEnabled: Boolean
        get() = prefs.getBoolean(KEY_DJ_BUMPERS, true)
        set(value) = prefs.edit().putBoolean(KEY_DJ_BUMPERS, value).apply()

    /** User-created custom channels. */
    var customChannels: List<AiBroadcastChannelConfig>
        get() = decodeChannelList(prefs.getString(KEY_CUSTOM_CHANNELS, null))
        set(value) = prefs.edit().putString(KEY_CUSTOM_CHANNELS, json.encodeToString(value)).apply()

    /** Overrides applied to built-in channels, keyed by channel id. */
    var builtInOverrides: Map<String, AiBroadcastChannelConfig>
        get() = decodeChannelMap(prefs.getString(KEY_BUILT_IN_OVERRIDES, null))
        set(value) = prefs.edit().putString(KEY_BUILT_IN_OVERRIDES, json.encodeToString(value)).apply()

    /**
     * Inserts or replaces a channel. Built-in channel customizations are saved as overrides;
     * user-created channels are saved in [customChannels].
     */
    fun saveOrUpdateChannel(config: AiBroadcastChannelConfig) {
        if (config.isBuiltIn) {
            val updated = builtInOverrides.toMutableMap()
            updated[config.id] = config
            builtInOverrides = updated
        } else {
            val updated = customChannels.toMutableList()
            val index = updated.indexOfFirst { it.id == config.id }
            if (index >= 0) {
                updated[index] = config
            } else {
                updated.add(config)
            }
            customChannels = updated
        }
    }

    /** Removes a custom channel by id or resets a built-in override back to default. */
    fun deleteOrResetChannel(channelId: String) {
        if (builtInOverrides.containsKey(channelId)) {
            val updated = builtInOverrides.toMutableMap()
            updated.remove(channelId)
            builtInOverrides = updated
        }
        val filtered = customChannels.filterNot { it.id == channelId }
        if (filtered.size != customChannels.size) {
            customChannels = filtered
        }
    }

    private fun decodeChannelList(raw: String?): List<AiBroadcastChannelConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<AiBroadcastChannelConfig>>(raw)
        }.getOrDefault(emptyList())
    }

    private fun decodeChannelMap(raw: String?): Map<String, AiBroadcastChannelConfig> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, AiBroadcastChannelConfig>>(raw)
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val PREFS_NAME = "nuvio_ai_broadcast_prefs"
        const val KEY_ENABLED = "ai_broadcast_enabled"
        const val KEY_DJ_BUMPERS = "ai_broadcast_dj_bumpers"
        const val KEY_CUSTOM_CHANNELS = "ai_broadcast_custom_channels"
        const val KEY_BUILT_IN_OVERRIDES = "ai_broadcast_built_in_overrides"
    }
}

/**
 * Central registry, deterministic wall-clock EPG scheduler, and AI channel generator for the
 * personal 24/7 Living-Room Broadcast Network.
 */
object AiBroadcastNetwork {

    const val STREAM_URL_PREFIX = "nuvio://ai-channel/"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val builtInChannels: List<AiBroadcastChannelConfig> = listOf(
        AiBroadcastChannelConfig(
            id = "ai-9000",
            number = "9000",
            name = "NUVIO PRIME",
            callSign = "NVPR",
            tagline = "Prestige television, curated by a very particular critic.",
            accentColorHex = "#7C3AED",
            voicePersonaId = AiVoicePersona.CHIC_CRITIC_BRITISH.id,
            djHostName = "Reginald",
            djStyle = "Dry British film connoisseur",
            themePrompt = "Prestige drama and elevated television",
            isBuiltIn = true,
            programs = listOf(
                AiBroadcastProgram(
                    title = "Succession",
                    contentId = "tt7660850",
                    mediaType = "series",
                    season = 2,
                    episode = 3,
                    episodeTitle = "Hunting",
                    synopsis = "The Roy family circles one another as a retreat turns into a power play.",
                    genre = "Drama",
                    durationMinutes = 60,
                    djIntroLine = "Ah, the Roys. A family reunion with the warmth of a hostile takeover."
                ),
                AiBroadcastProgram(
                    title = "Severance",
                    contentId = "tt11280740",
                    mediaType = "series",
                    season = 1,
                    episode = 4,
                    episodeTitle = "The You You Are",
                    synopsis = "The severed floor begins to fray at the edges as routines break down.",
                    genre = "Sci-Fi Thriller",
                    durationMinutes = 55,
                    djIntroLine = "Work-life balance, taken to its most literal and unsettling conclusion."
                ),
                AiBroadcastProgram(
                    title = "The Bear",
                    contentId = "tt14452776",
                    mediaType = "series",
                    season = 1,
                    episode = 7,
                    episodeTitle = "Review",
                    synopsis = "A brutal service pushes the kitchen to its breaking point.",
                    genre = "Dramedy",
                    durationMinutes = 30,
                    djIntroLine = "Yes, chef. Thirty minutes of controlled chaos and exquisite sandwiches."
                ),
                AiBroadcastProgram(
                    title = "The White Lotus",
                    contentId = "tt13406094",
                    mediaType = "series",
                    season = 1,
                    episode = 5,
                    episodeTitle = "The Lotus-Eaters",
                    synopsis = "Paradise curdles as guests confront their own reflections.",
                    genre = "Satire",
                    durationMinutes = 60,
                    djIntroLine = "A holiday so relaxing it may well be fatal. Do enjoy the view."
                ),
                AiBroadcastProgram(
                    title = "True Detective",
                    contentId = "tt2356777",
                    mediaType = "series",
                    season = 1,
                    episode = 4,
                    episodeTitle = "Who Goes There",
                    synopsis = "A legendary single-take descent into the underworld of a biker gang.",
                    genre = "Crime",
                    durationMinutes = 58,
                    djIntroLine = "Time is a flat circle, and so, regrettably, is the remote control."
                )
            )
        ),
        AiBroadcastChannelConfig(
            id = "ai-9001",
            number = "9001",
            name = "COSMOS LATE NIGHT",
            callSign = "CSMS",
            tagline = "Big ideas, bigger silences, and a voice like warm static.",
            accentColorHex = "#0EA5E9",
            voicePersonaId = AiVoicePersona.CINEMA_BARITONE_MALE.id,
            djHostName = "Vance",
            djStyle = "Gravelly midnight philosopher",
            themePrompt = "Science fiction, cosmic wonder and cerebral cinema",
            isBuiltIn = true,
            programs = listOf(
                AiBroadcastProgram(
                    title = "Blade Runner 2049",
                    contentId = "tt1856101",
                    mediaType = "movie",
                    synopsis = "A young blade runner unearths a secret that could unravel what remains of society.",
                    genre = "Sci-Fi",
                    durationMinutes = 164,
                    djIntroLine = "Pour something amber. We are about to spend a long, beautiful evening in the rain."
                ),
                AiBroadcastProgram(
                    title = "Interstellar",
                    contentId = "tt0816692",
                    mediaType = "movie",
                    synopsis = "Explorers travel through a wormhole in search of a new home for humanity.",
                    genre = "Sci-Fi",
                    durationMinutes = 169,
                    djIntroLine = "Bring tissues. And perhaps a physics textbook. Both will be useful."
                ),
                AiBroadcastProgram(
                    title = "Arrival",
                    contentId = "tt2543164",
                    mediaType = "movie",
                    synopsis = "A linguist races to communicate with visitors whose language bends time itself.",
                    genre = "Sci-Fi Drama",
                    durationMinutes = 116,
                    djIntroLine = "Language is first contact. Listen closely; the ending is already here."
                ),
                AiBroadcastProgram(
                    title = "Dark",
                    contentId = "tt5753856",
                    mediaType = "series",
                    season = 1,
                    episode = 5,
                    episodeTitle = "Truths",
                    synopsis = "The town's tangled timelines tighten around a single missing child.",
                    genre = "Mystery Sci-Fi",
                    durationMinutes = 55,
                    djIntroLine = "A German town, a cave, and a family tree that eats itself. Sleep well."
                ),
                AiBroadcastProgram(
                    title = "Black Mirror",
                    contentId = "tt2085059",
                    mediaType = "series",
                    season = 3,
                    episode = 4,
                    episodeTitle = "San Junipero",
                    synopsis = "Two women meet in a seaside town where time behaves strangely.",
                    genre = "Anthology Sci-Fi",
                    durationMinutes = 61,
                    djIntroLine = "The rare Black Mirror that leaves you smiling. Mostly. For now."
                )
            )
        ),
        AiBroadcastChannelConfig(
            id = "ai-9002",
            number = "9002",
            name = "THE SITCOM VAULT",
            callSign = "LAFF",
            tagline = "Comfort viewing, laugh track optional, joy mandatory.",
            accentColorHex = "#F59E0B",
            voicePersonaId = AiVoicePersona.STUDIO_NATURAL_FEMALE.id,
            djHostName = "Nova",
            djStyle = "Bright, warm and relentlessly upbeat",
            themePrompt = "Classic sitcoms and comfort comedy",
            isBuiltIn = true,
            programs = listOf(
                AiBroadcastProgram(
                    title = "Friends",
                    contentId = "tt0108778",
                    mediaType = "series",
                    season = 4,
                    episode = 12,
                    episodeTitle = "The One with the Embryos",
                    synopsis = "A trivia contest escalates into an apartment swap.",
                    genre = "Sitcom",
                    durationMinutes = 24,
                    djIntroLine = "Could this be any more comforting? Grab a mug, we are staying in."
                ),
                AiBroadcastProgram(
                    title = "Seinfeld",
                    contentId = "tt0098904",
                    mediaType = "series",
                    season = 7,
                    episode = 6,
                    episodeTitle = "The Soup Nazi",
                    synopsis = "A soup stand's strict rules push Jerry and friends to the limit.",
                    genre = "Sitcom",
                    durationMinutes = 23,
                    djIntroLine = "No soup for you. Plenty of laughs, though. Enjoy."
                ),
                AiBroadcastProgram(
                    title = "The Office",
                    contentId = "tt0386676",
                    mediaType = "series",
                    season = 2,
                    episode = 12,
                    episodeTitle = "The Injury",
                    synopsis = "Michael turns a minor morning mishap into a full Dunder Mifflin summit.",
                    genre = "Mockumentary Sitcom",
                    durationMinutes = 22,
                    djIntroLine = "Scranton's finest branch is open for business. Bubble wrap included."
                ),
                AiBroadcastProgram(
                    title = "Frasier",
                    contentId = "tt0106004",
                    mediaType = "series",
                    season = 5,
                    episode = 1,
                    episodeTitle = "Frasier's Imaginary Friend",
                    synopsis = "Frasier's romance sparks speculation across Seattle.",
                    genre = "Sitcom",
                    durationMinutes = 24,
                    djIntroLine = "Tossed salads and scrambled eggs, served with a side of penthouse wit."
                ),
                AiBroadcastProgram(
                    title = "Parks and Recreation",
                    contentId = "tt1266020",
                    mediaType = "series",
                    season = 3,
                    episode = 13,
                    episodeTitle = "The Fight",
                    synopsis = "A Pawnee night out spirals into chaos and waffles.",
                    genre = "Sitcom",
                    durationMinutes = 22,
                    djIntroLine = "Treat yourself. It is the law of Pawnee, and tonight we obey."
                )
            )
        ),
        AiBroadcastChannelConfig(
            id = "ai-9003",
            number = "9003",
            name = "REALITY RECKONING",
            callSign = "DRMA",
            tagline = "Champagne problems, served with a withering side-eye.",
            accentColorHex = "#EC4899",
            voicePersonaId = AiVoicePersona.CHIC_CRITIC_BRITISH.id,
            djHostName = "Reginald",
            djStyle = "Dry British reality observer",
            themePrompt = "Reality television and delicious social chaos",
            isBuiltIn = true,
            programs = listOf(
                AiBroadcastProgram(
                    title = "Below Deck Mediterranean",
                    contentId = "tt5209564",
                    mediaType = "series",
                    season = 5,
                    episode = 8,
                    episodeTitle = "Charter Chaos",
                    synopsis = "A demanding yacht charter pushes the interior crew to the brink.",
                    genre = "Reality",
                    durationMinutes = 44,
                    djIntroLine = "The Mediterranean is calm; the superyacht galley is anything but."
                ),
                AiBroadcastProgram(
                    title = "Vanderpump Rules",
                    contentId = "tt2642544",
                    mediaType = "series",
                    season = 6,
                    episode = 10,
                    episodeTitle = "The Last Straw",
                    synopsis = "Old tensions boil over during a marathon SUR dinner service.",
                    genre = "Reality",
                    durationMinutes = 43,
                    djIntroLine = "Restaurant drama with a side of pinot grigio. Do try to keep up."
                ),
                AiBroadcastProgram(
                    title = "Poker Face",
                    contentId = "tt19901816",
                    mediaType = "series",
                    season = 1,
                    episode = 5,
                    episodeTitle = "Time of the Monkey",
                    synopsis = "Charlie crosses paths with two octogenarian former activists.",
                    genre = "Mystery",
                    durationMinutes = 58,
                    djIntroLine = "A human lie detector on the highway. And someone just shouted bullshit."
                ),
                AiBroadcastProgram(
                    title = "Knives Out",
                    contentId = "tt8946378",
                    mediaType = "movie",
                    synopsis = "Benoit Blanc investigates the death of a famed mystery author.",
                    genre = "Mystery Comedy",
                    durationMinutes = 130,
                    djIntroLine = "A donut hole within a donut's hole. Benoit Blanc has the floor."
                ),
                AiBroadcastProgram(
                    title = "Only Murders in the Building",
                    contentId = "tt12851524",
                    mediaType = "series",
                    season = 1,
                    episode = 7,
                    episodeTitle = "The Boy from 6B",
                    synopsis = "A near-silent episode reframes the Arconia investigation.",
                    genre = "Mystery Comedy",
                    durationMinutes = 33,
                    djIntroLine = "Three podcasters, one building, and far too many secrets in the courtyard."
                )
            )
        ),
        AiBroadcastChannelConfig(
            id = "ai-9004",
            number = "9004",
            name = "MIDNIGHT SLEUTH",
            callSign = "CLUE",
            tagline = "Rain-slicked streets, locked rooms, and one last twist.",
            accentColorHex = "#F43F5E",
            voicePersonaId = AiVoicePersona.CINEMA_BARITONE_MALE.id,
            djHostName = "Noir",
            djStyle = "Smoky detective narrator",
            themePrompt = "Noir mystery, neo-detective thrillers and whodunits",
            isBuiltIn = true,
            programs = listOf(
                AiBroadcastProgram(
                    title = "Chinatown",
                    contentId = "tt0071315",
                    mediaType = "movie",
                    synopsis = "J.J. Gittes uncovers a municipal conspiracy beneath a simple matrimonial tail.",
                    genre = "Noir Mystery",
                    durationMinutes = 130,
                    djIntroLine = "Keep your coat collar up. Some questions only bring trouble in Los Angeles."
                ),
                AiBroadcastProgram(
                    title = "Mindhunter",
                    contentId = "tt5290382",
                    mediaType = "series",
                    season = 1,
                    episode = 2,
                    episodeTitle = "Episode 2",
                    synopsis = "FBI agents sit down inside Kemper's interview room for the first time.",
                    genre = "Crime Psychological",
                    durationMinutes = 56,
                    djIntroLine = "Tape recorder is spinning in Quantico. Pay attention to what is left unsaid."
                ),
                AiBroadcastProgram(
                    title = "Sherlock",
                    contentId = "tt1475582",
                    mediaType = "series",
                    season = 2,
                    episode = 1,
                    episodeTitle = "A Scandal in Belgravia",
                    synopsis = "Holmes trades wits with Irene Adler over a locked camera phone.",
                    genre = "Mystery",
                    durationMinutes = 89,
                    djIntroLine = "221B Baker Street is open after midnight. Four digits, no margin for error."
                ),
                AiBroadcastProgram(
                    title = "L.A. Confidential",
                    contentId = "tt0119488",
                    mediaType = "movie",
                    synopsis = "Three radically different detectives untangle corruption after the Nite Owl killings.",
                    genre = "Neo-Noir",
                    durationMinutes = 138,
                    djIntroLine = "Off the record, on the QT, and very hush-hush."
                )
            )
        ),
        AiBroadcastChannelConfig(
            id = "ai-9005",
            number = "9005",
            name = "ANIME PULSE",
            callSign = "ANIM",
            tagline = "Neon skies, wild imagination, and zero filler episodes.",
            accentColorHex = "#10B981",
            voicePersonaId = AiVoicePersona.STUDIO_NATURAL_FEMALE.id,
            djHostName = "Sora",
            djStyle = "High-energy late-night anime VJ",
            themePrompt = "Iconic anime series and visionary animated films",
            isBuiltIn = true,
            programs = listOf(
                AiBroadcastProgram(
                    title = "Cowboy Bebop",
                    contentId = "tt0213338",
                    mediaType = "series",
                    season = 1,
                    episode = 5,
                    episodeTitle = "Ballad of Fallen Angels",
                    synopsis = "Spike's past catches up in a stained-glass cathedral showdown.",
                    genre = "Sci-Fi Anime",
                    durationMinutes = 25,
                    djIntroLine = "3, 2, 1, let's jam. Tonight on Anime Pulse: Spike Spiegel meets old ghosts."
                ),
                AiBroadcastProgram(
                    title = "Spirited Away",
                    contentId = "tt0245429",
                    mediaType = "movie",
                    synopsis = "Chihiro must work inside a bathhouse of spirits to rescue her parents.",
                    genre = "Fantasy Anime",
                    durationMinutes = 125,
                    djIntroLine = "Step through the red tunnel with us—just remember your real name."
                ),
                AiBroadcastProgram(
                    title = "Attack on Titan",
                    contentId = "tt2560140",
                    mediaType = "series",
                    season = 3,
                    episode = 17,
                    episodeTitle = "Hero",
                    synopsis = "The Survey Corps stakes everything on a desperate gambit at Shiganshina.",
                    genre = "Action Anime",
                    durationMinutes = 24,
                    djIntroLine = "Turn up the soundbar. This episode earns every ounce of hype."
                ),
                AiBroadcastProgram(
                    title = "Akira",
                    contentId = "tt0094625",
                    mediaType = "movie",
                    synopsis = "A biker gang in Neo-Tokyo collides with psychic military experiments.",
                    genre = "Cyberpunk Anime",
                    durationMinutes = 124,
                    djIntroLine = "Neo-Tokyo at 2 AM in hand-drawn 4K glory. Buckle in."
                ),
                AiBroadcastProgram(
                    title = "Death Note",
                    contentId = "tt0877057",
                    mediaType = "series",
                    season = 1,
                    episode = 7,
                    episodeTitle = "Overcast",
                    synopsis = "Light Yagami closes in on Naomi Misora before she can reach task force HQ.",
                    genre = "Thriller Anime",
                    durationMinutes = 23,
                    djIntroLine = "A battle of wits where one name ends the game."
                )
            )
        )
    )

    fun isAiBroadcastUrl(url: String?): Boolean =
        url?.startsWith(STREAM_URL_PREFIX) == true

    fun extractChannelId(url: String?): String? =
        url?.takeIf { it.startsWith(STREAM_URL_PREFIX) }?.removePrefix(STREAM_URL_PREFIX)?.takeIf { it.isNotBlank() }

    /**
     * Resolves all active AI channels (built-ins with user customizations applied + custom channels),
     * sorted numerically by channel number.
     */
    fun resolveChannels(context: Context): List<AiBroadcastChannelConfig> {
        val prefs = AiBroadcastPreferences(context)
        val overrides = prefs.builtInOverrides
        val mergedBuiltIns = builtInChannels.map { overrides[it.id] ?: it }
        return (mergedBuiltIns + prefs.customChannels).sortedBy { it.number.toIntOrNull() ?: 9999 }
    }

    /**
     * Computes deterministic wall-clock EPG slots for [config] at [nowMs].
     * Slot 0 is always the program airing NOW (with [AiBroadcastEpgSlot.elapsedMs] into the broadcast),
     * followed by [slotCount] - 1 consecutive upcoming programs.
     */
    fun buildScheduleForChannel(
        config: AiBroadcastChannelConfig,
        nowMs: Long = System.currentTimeMillis(),
        slotCount: Int = 5
    ): List<AiBroadcastEpgSlot> {
        val programs = config.programs
        if (programs.isEmpty() || slotCount <= 0) return emptyList()

        val cycleMinutes = programs.sumOf { it.durationMinutes.coerceAtLeast(1).toLong() }.coerceAtLeast(1L)
        val totalMinutesNow = Math.floorDiv(nowMs, 60_000L)
        val intraMinuteMs = Math.floorMod(nowMs, 60_000L)
        val modPosMinutes = Math.floorMod(totalMinutesNow, cycleMinutes)

        var activeIndex = 0
        var elapsedMinutes = 0L
        var cursorMinutes = 0L
        for ((index, program) in programs.withIndex()) {
            val durMin = program.durationMinutes.coerceAtLeast(1).toLong()
            val endMin = cursorMinutes + durMin
            if (modPosMinutes < endMin) {
                activeIndex = index
                elapsedMinutes = modPosMinutes - cursorMinutes
                break
            }
            cursorMinutes = endMin
        }

        val elapsedMs = elapsedMinutes * 60_000L + intraMinuteMs
        val activeStartMs = nowMs - elapsedMs

        val slots = ArrayList<AiBroadcastEpgSlot>(slotCount)
        var slotStartMs = activeStartMs
        for (offset in 0 until slotCount) {
            val program = programs[(activeIndex + offset) % programs.size]
            val durationMs = program.durationMinutes.coerceAtLeast(1) * 60_000L
            val slotEndMs = slotStartMs + durationMs
            val isLive = offset == 0
            slots += AiBroadcastEpgSlot(
                program = program,
                startTimestampMs = slotStartMs,
                endTimestampMs = slotEndMs,
                isOnAirNow = isLive,
                elapsedMs = if (isLive) elapsedMs else 0L
            )
            slotStartMs = slotEndMs
        }
        return slots
    }

    /**
     * Converts configured AI channels into [LiveTvChannel] items for the Live TV guide.
     * Returns an empty list immediately when [AiBroadcastPreferences.isEnabled] is false
     * so disabled users incur zero UI or background overhead.
     */
    fun toLiveTvChannels(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        ignoreEnableToggle: Boolean = false
    ): List<LiveTvChannel> {
        val prefs = AiBroadcastPreferences(context)
        if (!ignoreEnableToggle && !prefs.isEnabled) return emptyList()

        return resolveChannels(context).mapNotNull { config ->
            val slots = buildScheduleForChannel(config, nowMs, slotCount = 2)
            val currentSlot = slots.firstOrNull() ?: return@mapNotNull null
            val nextSlot = slots.getOrNull(1)
            LiveTvChannel(
                id = config.id,
                number = config.number,
                name = config.name,
                callSign = config.callSign,
                category = LiveTvCategory.AI_NETWORK,
                logoText = config.callSign,
                accentColorHex = config.accentColorHex,
                currentProgram = toLiveProgram(currentSlot),
                nextProgram = nextSlot?.let(::toLiveProgram),
                streamUrl = STREAM_URL_PREFIX + config.id,
                logoUrl = null,
                isCustom = !config.isBuiltIn,
                isAiNetwork = true,
                djHostName = config.djHostName,
                voicePersonaId = config.voicePersonaId,
                tagline = config.tagline,
                aiConfigId = config.id
            )
        }
    }

    fun toLiveProgram(slot: AiBroadcastEpgSlot): LiveProgram {
        val epPrefix = if (slot.program.season != null && slot.program.episode != null) {
            "S${slot.program.season}E${slot.program.episode}"
        } else {
            null
        }
        val epLabel = listOfNotNull(epPrefix, slot.program.episodeTitle?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
            .ifBlank { null }

        return LiveProgram(
            title = slot.program.title,
            episodeTitle = epLabel,
            description = slot.program.synopsis.ifBlank { slot.program.djIntroLine }.ifBlank { null },
            startTimestampMs = slot.startTimestampMs,
            endTimestampMs = slot.endTimestampMs,
            genre = slot.program.genre.ifBlank { null },
            backdropUrl = slot.program.backdropUrl
        )
    }

    fun findChannelById(context: Context, channelId: String): AiBroadcastChannelConfig? =
        resolveChannels(context).firstOrNull { it.id == channelId || it.number == channelId || it.callSign.equals(channelId, ignoreCase = true) }

    fun buildDjIntroSpeech(config: AiBroadcastChannelConfig, slot: AiBroadcastEpgSlot): String {
        val host = config.djHostName.ifBlank { "your host" }
        val program = slot.program
        val elapsedMin = (slot.elapsedMs / 60_000L).toInt()
        val timeNote = if (elapsedMin >= 2) " We are $elapsedMin minutes into tonight's broadcast." else ""
        val epNote = if (program.season != null && program.episode != null) {
            val epTitle = program.episodeTitle?.let { ", $it" }.orEmpty()
            " Season ${program.season}, Episode ${program.episode}$epTitle."
        } else ""
        val remark = program.djIntroLine.ifBlank { program.synopsis }.let {
            if (it.isBlank()) "" else " $it"
        }
        return "You are watching ${config.name}, I am $host. Now on air: ${program.title}.$epNote$timeNote$remark"
    }

    fun buildNavigationTargetJson(
        config: AiBroadcastChannelConfig,
        slot: AiBroadcastEpgSlot,
        startFromBeginning: Boolean
    ): String {
        val schedule = buildScheduleForChannel(config, slot.startTimestampMs, slotCount = 5)
        val queueJson = JSONArray().apply {
            schedule.forEach { s ->
                put(
                    JSONObject().apply {
                        put("title", s.program.title)
                        put("contentId", s.program.contentId)
                        put("mediaType", s.program.mediaType)
                        if (s.program.season != null) put("season", s.program.season)
                        if (s.program.episode != null) put("episode", s.program.episode)
                        put("episodeTitle", s.program.episodeTitle ?: "")
                        put("durationMinutes", s.program.durationMinutes)
                    }
                )
            }
        }
        return JSONObject().apply {
            put("channelId", config.id)
            put("channelNumber", config.number)
            put("channelName", config.name)
            put("callSign", config.callSign)
            put("djHostName", config.djHostName)
            put("voicePersonaId", config.voicePersonaId)
            put("startFromBeginning", startFromBeginning)
            put("seekOffsetMs", if (startFromBeginning) 0L else slot.elapsedMs)
            put("programTitle", slot.program.title)
            put("contentId", slot.program.contentId)
            put("mediaType", slot.program.mediaType)
            if (slot.program.season != null) put("season", slot.program.season)
            if (slot.program.episode != null) put("episode", slot.program.episode)
            put("upcomingBlock", queueJson)
        }.toString()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AiManagerEntryPoint {
        fun aiManager(): AiManager
    }

    private fun resolveAiManager(context: Context): AiManager {
        return runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                AiManagerEntryPoint::class.java
            ).aiManager()
        }.getOrElse {
            AiManager(OkHttpClient())
        }
    }

    /**
     * Generates a brand-new custom 24/7 AI Broadcast channel using [AiManager], falling back cleanly
     * only if the AI provider returns an error.
     */
    suspend fun createCustomChannelWithAi(
        context: Context,
        themePrompt: String,
        channelName: String?,
        djHostName: String?,
        voicePersonaId: String
    ): Result<AiBroadcastChannelConfig> = runCatching {
        val prompt = buildString {
            append("Design a fun 24/7 themed TV broadcast channel around: '$themePrompt'. ")
            append("Return ONLY valid JSON matching: ")
            append("{\"name\":\"CHANNEL NAME\",\"callSign\":\"4LTR\",\"tagline\":\"short tagline\",\"accentColorHex\":\"#7C3AED\",")
            append("\"programs\":[{\"title\":\"Real Show or Movie\",\"contentId\":\"tt1234567\",\"mediaType\":\"series\",")
            append("\"season\":1,\"episode\":1,\"episodeTitle\":\"Ep Title\",\"synopsis\":\"1-line synopsis\",")
            append("\"genre\":\"Genre\",\"durationMinutes\":45,\"djIntroLine\":\"1 witty sentence in host voice\"}]}. ")
            append("Provide 5 real well-known movies or series with accurate IMDb tt IDs.")
        }
        val raw = resolveAiManager(context).queryRaw(
            context = context,
            systemPrompt = "You are a witty television network programmer. Always respond with strict valid JSON.",
            prompt = prompt,
            expectJson = true
        ).getOrThrow()

        val parsed = json.parseToJsonElement(extractJson(raw)).jsonObject
        val programs = parseProgramsArray(parsed) ?: defaultPrograms(themePrompt)

        val existing = resolveChannels(context)
        val maxNum = existing.mapNotNull { it.number.toIntOrNull() }.maxOrNull() ?: 9005
        val nextNum = (maxNum + 1).coerceAtLeast(9006)
        val id = "ai-custom-$nextNum"

        val persona = AiVoicePersona.fromId(voicePersonaId)
        val config = AiBroadcastChannelConfig(
            id = id,
            number = nextNum.toString(),
            name = channelName?.takeIf { it.isNotBlank() }
                ?: parsed["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: themePrompt.take(24).uppercase(),
            callSign = parsed["callSign"]?.jsonPrimitive?.content?.filter { it.isLetterOrDigit() }?.take(4)?.uppercase()?.ifBlank { "AI" } ?: "CSTM",
            tagline = parsed["tagline"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: themePrompt,
            accentColorHex = parsed["accentColorHex"]?.jsonPrimitive?.content?.takeIf { it.startsWith("#") } ?: "#6366F1",
            voicePersonaId = persona.id,
            djHostName = djHostName?.takeIf { it.isNotBlank() } ?: persona.displayName.substringBefore(" "),
            djStyle = persona.description,
            themePrompt = themePrompt,
            isBuiltIn = false,
            programs = programs
        )
        AiBroadcastPreferences(context).saveOrUpdateChannel(config)
        config
    }

    /**
     * Asks [AiManager] to program a fresh 5-slot block for an existing channel while preserving its
     * identity, host, and voice persona.
     */
    suspend fun regenerateChannelLineupWithAi(
        context: Context,
        config: AiBroadcastChannelConfig
    ): Result<AiBroadcastChannelConfig> = runCatching {
        val theme = config.themePrompt.ifBlank { config.tagline }
        val prompt = buildString {
            append("Refresh the 5-show broadcast lineup for TV channel '${config.name}' (theme: '$theme', host: '${config.djHostName}'). ")
            append("Return ONLY JSON: {\"programs\":[{\"title\":\"...\",\"contentId\":\"tt...\",\"mediaType\":\"series\",")
            append("\"season\":1,\"episode\":1,\"episodeTitle\":\"...\",\"synopsis\":\"...\",\"genre\":\"...\",\"durationMinutes\":45,\"djIntroLine\":\"...\"}]}.")
        }
        val raw = resolveAiManager(context).queryRaw(
            context = context,
            systemPrompt = "You are a creative TV broadcast scheduler. Respond with strict valid JSON only.",
            prompt = prompt,
            expectJson = true
        ).getOrThrow()

        val parsed = json.parseToJsonElement(extractJson(raw)).jsonObject
        val programs = parseProgramsArray(parsed) ?: defaultPrograms(theme)
        val updated = config.copy(programs = programs)
        AiBroadcastPreferences(context).saveOrUpdateChannel(updated)
        updated
    }

    private fun parseProgramsArray(parsed: kotlinx.serialization.json.JsonObject): List<AiBroadcastProgram>? {
        val arr = parsed["programs"]?.jsonArray ?: return null
        val list = arr.mapNotNull { el ->
            val o = el.jsonObject
            val title = o["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val mediaType = o["mediaType"]?.jsonPrimitive?.content?.lowercase()?.let {
                if (it == "movie" || it == "series") it else "series"
            } ?: "series"
            AiBroadcastProgram(
                title = title,
                contentId = o["contentId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "tt0000000",
                mediaType = mediaType,
                season = o["season"]?.jsonPrimitive?.intOrNull,
                episode = o["episode"]?.jsonPrimitive?.intOrNull,
                episodeTitle = o["episodeTitle"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                synopsis = o["synopsis"]?.jsonPrimitive?.content ?: "",
                genre = o["genre"]?.jsonPrimitive?.content ?: "Curated",
                durationMinutes = (o["durationMinutes"]?.jsonPrimitive?.intOrNull ?: 45).coerceIn(10, 240),
                djIntroLine = o["djIntroLine"]?.jsonPrimitive?.content ?: ""
            )
        }
        return list.takeIf { it.isNotEmpty() }
    }

    private fun defaultPrograms(theme: String): List<AiBroadcastProgram> = listOf(
        AiBroadcastProgram("Severance", "tt11280740", "series", 1, 1, "Good News About Hell", "Curated opening feature for $theme.", "Sci-Fi Drama", 57, "Welcome to $theme."),
        AiBroadcastProgram("Arrival", "tt2543164", "movie", synopsis = "Linguistics meets first contact.", genre = "Sci-Fi", durationMinutes = 116, djIntroLine = "A standout highlight for $theme."),
        AiBroadcastProgram("The Bear", "tt14452776", "series", 1, 7, "Review", "High-stakes kitchen choreography.", "Dramedy", 30, "Keeping the pace up on $theme.")
    )

    private fun extractJson(raw: String): String {
        val start = raw.indexOfFirst { it == '{' || it == '[' }
        val end = raw.indexOfLast { it == '}' || it == ']' }
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
    }
}
