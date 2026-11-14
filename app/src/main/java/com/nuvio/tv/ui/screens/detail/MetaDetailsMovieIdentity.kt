package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta

/** Keep a verified movie lookup from being downgraded by an addon's TMDB ID. */
internal fun Meta.withResolvedMovieId(requestedId: String, resolvedId: String): Meta {
    if (type != ContentType.MOVIE || !apiType.equals("movie", ignoreCase = true)) return this
    if (!Regex("tmdb:[0-9]+").matches(requestedId) || id != requestedId) return this
    if (!Regex("tt[0-9]+").matches(resolvedId)) return this
    if (!imdbId.isNullOrBlank() && imdbId != resolvedId) return this
    return copy(id = resolvedId)
}
