package com.nuvio.tv.ambient.service

import android.os.Bundle
import android.service.dreams.DreamService
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nuvio.tv.ambient.coordinator.AmbientCoordinator
import com.nuvio.tv.ambient.playback.AmbientPlayerPool
import com.nuvio.tv.ambient.ui.AmbientScreen
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * [DreamService] implementation that renders the Nuvio ambient experience as a system screensaver.
 *
 * Implements [LifecycleOwner] and [SavedStateRegistryOwner] to properly host Compose within
 * the DreamService window hierarchy.
 */
@AndroidEntryPoint
class NuvioAmbientDreamService : DreamService(), LifecycleOwner, SavedStateRegistryOwner {

    @Inject
    lateinit var coordinator: AmbientCoordinator

    @Inject
    lateinit var playerPool: AmbientPlayerPool

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        isInteractive = true
        isFullscreen = true

        window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }

        val composeView = ComposeView(this).apply {
            setContent {
                NuvioTheme {
                    AmbientScreen(
                        playerPool = playerPool,
                        onDismiss = { finish() }
                    )
                }
            }
        }

        setContentView(composeView)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        coordinator.startAmbient()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        coordinator.stopAmbient()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onDetachedFromWindow() {
        coordinator.stopAmbient()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDetachedFromWindow()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}
