package com.nuvio.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.nuvio.tv.domain.model.Stream

/**
 * Policy that decides whether a startup engine failover is allowed and which
 * live stream candidate should be tried next.
 */
object StartupErrorFailoverPolicy {

    /** HTTP status codes that indicate an unrecoverable source URL failure. */
    val NON_RETRYABLE_HTTP_CODES: Set<Int> = setOf(400, 401, 403, 404, 410)

    private val HTTP_STATUS_PATTERNS: List<Regex> = listOf(
        Regex("""HTTP\s+(\d{3})""", RegexOption.IGNORE_CASE),
        Regex("""Response\s+code\s*:\s*(\d{3})""", RegexOption.IGNORE_CASE),
        Regex("""status\s*=\s*(\d{3})""", RegexOption.IGNORE_CASE),
        Regex("""status\s+(\d{3})""", RegexOption.IGNORE_CASE)
    )

    private const val MAX_CAUSE_DEPTH = 32

    fun extractHttpStatusCode(detailedError: String): Int? {
        if (detailedError.isBlank()) return null
        for (pattern in HTTP_STATUS_PATTERNS) {
            val match = pattern.find(detailedError) ?: continue
            val code = match.groupValues.getOrNull(1)?.toIntOrNull()
            if (code != null) return code
        }
        return null
    }

    fun isNonRetryableHttpStatusCode(code: Int?): Boolean {
        return code != null && code in NON_RETRYABLE_HTTP_CODES
    }

    @OptIn(UnstableApi::class)
    fun isNonRetryableHttpError(
        error: PlaybackException?,
        detailedError: String
    ): Boolean {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            if (cause is HttpDataSource.InvalidResponseCodeException &&
                isNonRetryableHttpStatusCode(cause.responseCode)
            ) {
                return true
            }
            cause = cause.cause
            depth++
        }
        return isNonRetryableHttpStatusCode(extractHttpStatusCode(detailedError))
    }

    fun shouldAllowStartupEngineFailover(
        error: PlaybackException?,
        detailedError: String
    ): Boolean = !isNonRetryableHttpError(error, detailedError)

    fun selectNextLiveCandidate(
        streams: List<Stream>,
        failedUrls: Set<String>,
        failedHashes: Set<String>,
        currentUrl: String,
        currentHash: String?,
        currentName: String?
    ): Stream? {
        if (streams.isEmpty()) return null

        val normalizedCurrentHash = currentHash?.trim()?.lowercase()
        val normalizedFailedHashes = failedHashes.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

        val currentIndex = streams.indexOfFirst { stream ->
            val streamHash = (stream.infoHash ?: stream.clientResolve?.infoHash)?.trim()?.lowercase()
            (currentUrl.isNotBlank() && stream.getStreamUrl() == currentUrl) ||
                (normalizedCurrentHash != null && streamHash == normalizedCurrentHash) ||
                (currentName != null && stream.getDisplayNameOrNull() == currentName)
        }

        val orderedCandidates: List<Stream> = if (currentIndex >= 0) {
            streams.drop(currentIndex + 1) + streams.take(currentIndex)
        } else {
            streams
        }

        return orderedCandidates.firstOrNull { candidate ->
            isEligibleCandidate(
                candidate = candidate,
                failedUrls = failedUrls,
                failedHashes = normalizedFailedHashes,
                currentUrl = currentUrl,
                normalizedCurrentHash = normalizedCurrentHash
            )
        }
    }

    private fun isEligibleCandidate(
        candidate: Stream,
        failedUrls: Set<String>,
        failedHashes: Set<String>,
        currentUrl: String,
        normalizedCurrentHash: String?
    ): Boolean {
        val candidateUrl = candidate.getStreamUrl()
        val candidateHash = candidate.infoHash?.trim()?.lowercase()
        val candidateClientHash = candidate.clientResolve?.infoHash?.trim()?.lowercase()

        if (!candidateUrl.isNullOrBlank() && currentUrl.isNotBlank() && candidateUrl == currentUrl) return false
        if (normalizedCurrentHash != null &&
            (candidateHash == normalizedCurrentHash || candidateClientHash == normalizedCurrentHash)
        ) return false

        if (!candidateUrl.isNullOrBlank() && candidateUrl in failedUrls) return false
        if (candidateHash != null && candidateHash in failedHashes) return false
        if (candidateClientHash != null && candidateClientHash in failedHashes) return false

        return !candidateUrl.isNullOrBlank() ||
            candidate.isTorrent() ||
            candidate.isDirectDebrid()
    }
}
