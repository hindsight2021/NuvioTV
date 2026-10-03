package com.nuvio.tv.data.trailer

data class TrailerPlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null
)

data class UpcomingTheatricalTrailer(
    val title: String,
    val videoUrl: String,
    val audioUrl: String? = null,
    val tmdbId: Int? = null,
    val releaseDate: String? = null
)
