package com.nuvio.tv.domain.model

/** Canonicalize only metadata returned for an independently resolved TMDB alias. */
internal fun canonicalizeResolvedTmdbAlias(
    primary: Meta,
    candidate: Meta,
    queriedId: String,
    resolvedTmdbId: String?
): Meta {
    val tmdbId = resolvedTmdbId?.trim()?.removePrefix("tmdb:")?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
        ?: return candidate
    val aliases = setOf(tmdbId, "tmdb:$tmdbId")
    if (primary.id.startsWith("tmdb:") && primary.id !in aliases) return candidate
    if (queriedId !in aliases || candidate.id !in aliases || primary.type != candidate.type) return candidate
    fun normalized(title: String) = title.trim().lowercase().replace(Regex("\\s+"), " ")
    if (normalized(primary.name).isBlank() || normalized(primary.name) != normalized(candidate.name)) return candidate
    fun year(meta: Meta) = Regex("(?:19|20)\\d{2}").find(meta.releaseInfo.orEmpty())?.value
    val primaryYear = year(primary)
    if (primaryYear == null || primaryYear != year(candidate)) return candidate
    val primaryImdb = primary.imdbId?.trim()?.takeIf { Regex("tt\\d+").matches(it) }
    val candidateImdb = candidate.imdbId?.trim()?.takeIf { Regex("tt\\d+").matches(it) }
    if (primaryImdb != null && candidateImdb != null && primaryImdb != candidateImdb) return candidate
    return candidate.copy(id = primary.id, imdbId = primaryImdb ?: candidate.imdbId)
}
