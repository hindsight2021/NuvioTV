package com.nuvio.tv.core.control

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.navigation.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JustPlayCoordinator @Inject constructor(
    private val navigation: NavigationCommander,
    private val bridge: PlayerPlaybackBridge,
    private val profileManager: ProfileManager,
    private val watchProgress: WatchProgressRepository,
) {
    enum class Status { RESOLVING, STARTED, FAILED, CANCELLED }

    data class Request(
        val id: String,
        val contentId: String,
        val contentType: String,
        val profileId: Int,
        val season: Int?,
        val episode: Int?,
        val status: Status,
        val message: String? = null,
    )

    private val lock = Any()
    private val requests = LinkedHashMap<String, Request>()
    private val timeouts = HashMap<String, Job>()
    private val capturedSessions = HashMap<String, String?>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<Map<String, Request>>(emptyMap())
    val stateFlow: StateFlow<Map<String, Request>> = _state.asStateFlow()

    init {
        scope.launch {
            bridge.playbackSnapshot.collect { snap ->
                if (snap == null) return@collect
                val pid = profileManager.activeProfileId.value
                synchronized(lock) {
                    val matched = requests.values.firstOrNull { r ->
                        r.status == Status.RESOLVING &&
                            r.profileId == pid &&
                            snap.contentId == r.contentId &&
                            snap.contentType == r.contentType &&
                            snap.profileId == r.profileId &&
                            (r.season == null || r.season == snap.season) &&
                            (r.episode == null || r.episode == snap.episode) &&
                            snap.sessionId != null &&
                            snap.sessionId != capturedSessions[r.id] &&
                            snap.isPlaying && !snap.isBuffering
                    }
                    if (matched != null) {
                        requests[matched.id] = matched.copy(
                            status = Status.STARTED,
                            season = snap.season ?: matched.season,
                            episode = snap.episode ?: matched.episode,
                        )
                        timeouts.remove(matched.id)?.cancel()
                        capturedSessions.remove(matched.id)
                        publish()
                    }
                }
            }
        }
        scope.launch {
            profileManager.activeProfileId.collect { pid ->
                synchronized(lock) {
                    val toCancel = requests.values.filter {
                        it.status == Status.RESOLVING && it.profileId != pid
                    }
                    toCancel.forEach { cancelLocked(it.id) }
                    if (toCancel.isNotEmpty()) publish()
                }
            }
        }
    }

    suspend fun request(cmd: AppCommand.PlayMedia): CommandResult =
        withContext(Dispatchers.Main.immediate) {
            if (cmd.contentId.isBlank()) return@withContext CommandResult.Unavailable("Blank contentId")
            val type = cmd.contentType.lowercase()
            if (type != "movie" && type != "series") {
                return@withContext CommandResult.Unavailable("Unsupported contentType")
            }
            val hasSeason = cmd.season != null
            val hasEpisode = cmd.episode != null
            if (hasSeason != hasEpisode) {
                return@withContext CommandResult.Unavailable("Season and episode must both be set or both null")
            }
            if (type == "movie" && (hasSeason || hasEpisode)) {
                return@withContext CommandResult.Unavailable("Movie cannot have season/episode")
            }
            if (hasSeason && (cmd.season!! < 0 || cmd.episode!! <= 0)) {
                return@withContext CommandResult.Unavailable("Invalid season/episode")
            }
            val pid = profileManager.activeProfileId.value
            val capturedSession = bridge.playbackSnapshot.value?.sessionId

            val decision = synchronized(lock) {
                val pending = requests.values.filter { it.status == Status.RESOLVING }
                val exact = pending.firstOrNull {
                    it.profileId == pid &&
                        it.contentId == cmd.contentId &&
                        it.contentType == type &&
                        it.season == cmd.season &&
                        it.episode == cmd.episode
                }
                if (exact != null) {
                    return@synchronized Decision.Duplicate(exact.id)
                }
                if (pending.isNotEmpty()) {
                    return@synchronized Decision.Conflict
                }
                val id = UUID.randomUUID().toString()
                val req = Request(
                    id = id,
                    contentId = cmd.contentId,
                    contentType = type,
                    profileId = pid,
                    season = cmd.season,
                    episode = cmd.episode,
                    status = Status.RESOLVING,
                )
                if (requests.size >= 20) {
                    val oldest = requests.values.firstOrNull { it.status != Status.RESOLVING }
                    if (oldest != null) {
                        requests.remove(oldest.id)
                        capturedSessions.remove(oldest.id)
                    }
                }
                requests[id] = req
                capturedSessions[id] = capturedSession
                publish()
                Decision.Created(id)
            }

            when (decision) {
                is Decision.Duplicate -> CommandResult.Success(
                    message = "Already accepted",
                    data = status(decision.id),
                )
                Decision.Conflict -> CommandResult.Unavailable("Another just-play request is pending")
                is Decision.Created -> {
                    val id = decision.id
                    val route = Screen.Detail.createRoute(
                        itemId = cmd.contentId,
                        itemType = type,
                        returnFocusSeason = cmd.season,
                        returnFocusEpisode = cmd.episode,
                        playOnLoad = true,
                        justPlayRequestId = id,
                        justPlayProfileId = pid,
                    )
                    val ok = navigation.tryNavigateTo(route)
                    if (!ok) {
                        fail(id, "TV navigation is not ready")
                        return@withContext CommandResult.Unavailable("TV navigation is not ready")
                    }
                    val job = scope.launch {
                        delay(120_000)
                        fail(id, "Playback was not confirmed. Retry or choose a source on TV.")
                    }
                    synchronized(lock) { timeouts[id] = job }
                    CommandResult.Success(message = "Playback request accepted", data = status(id))
                }
            }
        }

    suspend fun requestTitle(
        title: String,
        type: String?,
        season: Int?,
        episode: Int?,
    ): CommandResult = withContext(Dispatchers.Main.immediate) {
        val pid = profileManager.activeProfileId.value
        val all = withTimeoutOrNull(10_000) {
            watchProgress.allProgress.first()
        } ?: return@withContext CommandResult.Unavailable("Progress lookup timed out")
        val matches = all.filter { wp ->
            wp.name.equals(title, ignoreCase = true) &&
                (type == null || wp.contentType.equals(type, ignoreCase = true))
        }.distinctBy { it.contentId }
        if (matches.size != 1) {
            navigation.tryNavigateTo(Screen.Search.createRoute(title))
            return@withContext CommandResult.Unavailable("Choose a title on TV, then Play.")
        }
        val match = matches.first()
        if (profileManager.activeProfileId.value != pid) {
            return@withContext CommandResult.Unavailable("Profile changed")
        }
        request(
            AppCommand.PlayMedia(
                contentId = match.contentId,
                contentType = match.contentType,
                season = season,
                episode = episode,
            )
        )
    }

    fun status(id: String): Request? = synchronized(lock) { requests[id] }

    fun cancel(id: String) {
        synchronized(lock) {
            cancelLocked(id)
            publish()
        }
    }

    private fun cancelLocked(id: String) {
        val r = requests[id] ?: return
        if (r.status == Status.RESOLVING) {
            requests[id] = r.copy(status = Status.CANCELLED)
            timeouts.remove(id)?.cancel()
            capturedSessions.remove(id)
        }
    }

    fun fail(id: String, message: String) {
        synchronized(lock) {
            val r = requests[id] ?: return
            if (r.status == Status.RESOLVING) {
                requests[id] = r.copy(status = Status.FAILED, message = message)
                timeouts.remove(id)?.cancel()
                capturedSessions.remove(id)
                publish()
            }
        }
    }

    fun isActive(id: String, profileId: Int): Boolean = synchronized(lock) {
        val r = requests[id] ?: return false
        r.status == Status.RESOLVING &&
            r.profileId == profileId &&
            profileManager.activeProfileId.value == profileId
    }

    private fun publish() {
        _state.value = requests.toMap()
    }

    private sealed interface Decision {
        data class Created(val id: String) : Decision
        data class Duplicate(val id: String) : Decision
        data object Conflict : Decision
    }
}
