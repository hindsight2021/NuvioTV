package com.nuvio.tv.ambient.seeds

import com.nuvio.tv.ambient.AmbientCandidate
import com.nuvio.tv.ambient.AmbientChannel

/**
 * Curated seed list of high-quality 4K/HDR aerial YouTube videos for the AERIAL channel.
 */
object AerialWorldSeeds {

    private const val ONE_HOUR_SECONDS: Long = 3600L

    private fun aerial(
        youtubeId: String,
        title: String,
        location: String,
        country: String,
        subcategories: List<String>,
        weatherTags: List<String> = listOf("clear", "sunny", "clouds"),
        seasonTags: List<String> = listOf("summer", "spring", "autumn"),
        timeTags: List<String> = listOf("day", "morning", "afternoon", "golden_hour"),
        durationSeconds: Long = ONE_HOUR_SECONDS,
        isHdr: Boolean = true
    ): AmbientCandidate = AmbientCandidate(
        id = "yt_$youtubeId",
        provider = "youtube",
        youtubeVideoId = youtubeId,
        title = title,
        location = location,
        country = country,
        category = AmbientChannel.AERIAL.name.lowercase(),
        subcategories = subcategories,
        weatherTags = weatherTags,
        seasonTags = seasonTags,
        timeTags = timeTags,
        is4K = true,
        isHdr = isHdr,
        fps = 60,
        durationSeconds = durationSeconds,
        qualityScore = 1.0,
        calmnessScore = 0.95,
        reliabilityScore = 1.0
    )

    val items: List<AmbientCandidate> = listOf(
        aerial(
            youtubeId = "uYyc-ORMG54",
            title = "Switzerland 4K - Lauterbrunnen & Matterhorn",
            location = "Lauterbrunnen, Swiss Alps",
            country = "Switzerland",
            subcategories = listOf("alps", "mountains", "valleys")
        ),
        aerial(
            youtubeId = "CxwJrzEdw1U",
            title = "Norway 4K - Lofoten Islands & Fjords",
            location = "Lofoten",
            country = "Norway",
            subcategories = listOf("fjords", "ocean", "mountains", "nordic")
        ),
        aerial(
            youtubeId = "AybNYgQi9hY",
            title = "Iceland 4K - Waterfalls & Volcanic Landscapes",
            location = "Skógafoss",
            country = "Iceland",
            subcategories = listOf("waterfalls", "volcano", "nordic"),
            seasonTags = listOf("summer", "winter", "autumn")
        ),
        aerial(
            youtubeId = "CTVfDyyW1dg",
            title = "The Dolomites 4K - Italian Alpine Peaks",
            location = "Dolomites",
            country = "Italy",
            subcategories = listOf("dolomites", "alps", "mountains")
        ),
        aerial(
            youtubeId = "WWn4lfNQy2s",
            title = "Dolomites 4K - Tre Cime & Alpine Valleys",
            location = "Tre Cime di Lavaredo",
            country = "Italy",
            subcategories = listOf("mountains", "valleys", "sunset")
        ),
        aerial(
            youtubeId = "6_2ZFMrocCw",
            title = "New Zealand 4K - Milford Sound & Southern Alps",
            location = "Milford Sound",
            country = "New Zealand",
            subcategories = listOf("fjords", "mountains", "coasts")
        ),
        aerial(
            youtubeId = "vtxVK3sbZ0o",
            title = "New Zealand 4K - Serene Landscapes & Lakes",
            location = "Lake Wanaka",
            country = "New Zealand",
            subcategories = listOf("lakes", "mountains")
        ),
        aerial(
            youtubeId = "Gf5rSeTKpj4",
            title = "Canada 4K - Banff & Lake Louise Rockies",
            location = "Banff National Park",
            country = "Canada",
            subcategories = listOf("rockies", "lakes", "mountains")
        ),
        aerial(
            youtubeId = "7h2Yq5fB3aE",
            title = "Canada 4K - Jasper & Canadian Wilderness",
            location = "Jasper, Alberta",
            country = "Canada",
            subcategories = listOf("wilderness", "forest", "mountains")
        ),
        aerial(
            youtubeId = "MxcJtLbIhvs",
            title = "Hawaii 4K - Kauai & Na Pali Coast",
            location = "Kauai",
            country = "United States",
            subcategories = listOf("tropical", "coast", "ocean")
        ),
        aerial(
            youtubeId = "bQmzk05I3nw",
            title = "Australia 4K - Great Barrier Reef & Coastlines",
            location = "Queensland",
            country = "Australia",
            subcategories = listOf("coasts", "ocean", "coral_reef")
        ),
        aerial(
            youtubeId = "k_n7U_oH1sM",
            title = "Canada Autumn 4K - Golden Forests & Serene Lakes",
            location = "Quebec",
            country = "Canada",
            subcategories = listOf("autumn", "foliage", "forest"),
            seasonTags = listOf("autumn")
        )
    )
}
