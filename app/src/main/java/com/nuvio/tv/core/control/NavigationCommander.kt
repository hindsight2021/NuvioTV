package com.nuvio.tv.core.control

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A sealed hierarchy describing every navigation-related command that can be
 * dispatched through [NavigationCommander].
 */
sealed interface NavigationRequest {

    /** Navigate to the given [route]. */
    data class NavigateTo(val route: String) : NavigationRequest

    /** Open the details screen for the given [contentId] and [contentType]. */
    data class OpenDetails(val contentId: String, val contentType: String) : NavigationRequest

    /** Trigger a search with the given [query]. */
    data class Search(val query: String) : NavigationRequest

    /** Forward a D-pad [key] press to the UI layer. */
    data class SendDpad(val key: DpadKey) : NavigationRequest

    /** Pop the current destination off the back stack. */
    data object PopBack : NavigationRequest
}

/**
 * Central command bus for navigation and input events.
 *
 * Producers (e.g. remote control handlers, AI operator) call the convenience methods
 * below. MainActivity collects [requests] and performs the actual navigation.
 */
@Singleton
class NavigationCommander @Inject constructor() {

    private val _requests = MutableSharedFlow<NavigationRequest>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Hot stream of navigation requests. */
    val requests: SharedFlow<NavigationRequest> = _requests.asSharedFlow()

    /** Request navigation to [route]. */
    fun navigateTo(route: String) {
        _requests.tryEmit(NavigationRequest.NavigateTo(route))
    }

    /** Request opening the details screen for [contentId] of [contentType]. */
    fun openDetails(contentId: String, contentType: String) {
        _requests.tryEmit(NavigationRequest.OpenDetails(contentId, contentType))
    }

    /** Request a search for [query]. */
    fun search(query: String) {
        _requests.tryEmit(NavigationRequest.Search(query))
    }

    /** Request forwarding a D-pad [key] press. */
    fun sendDpad(key: DpadKey) {
        _requests.tryEmit(NavigationRequest.SendDpad(key))
    }

    /** Request popping the current destination off the back stack. */
    fun popBack() {
        _requests.tryEmit(NavigationRequest.PopBack)
    }
}
