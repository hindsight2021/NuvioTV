package com.nuvio.tv.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.nuvio.tv.domain.model.AnimatedBackdropMode

@Composable
fun Modifier.cinematicBackdrop(
    mode: AnimatedBackdropMode,
    key: Any? = null
): Modifier {
    if (mode == AnimatedBackdropMode.OFF) return this

    return key(key) {
        val infiniteTransition = rememberInfiniteTransition(label = "cinematicBackdrop")

        val targetScale = when (mode) {
            AnimatedBackdropMode.AMBIENT_BREATHE -> 1.045f
            AnimatedBackdropMode.CINEMATIC_FLOW -> 1.095f
            else -> 1.075f
        }
        val scaleDuration = when (mode) {
            AnimatedBackdropMode.AMBIENT_BREATHE -> 8_000
            AnimatedBackdropMode.CINEMATIC_FLOW -> 16_000
            else -> 14_000
        }

        val scale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = targetScale,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = scaleDuration, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "cinematicScale"
        )

        val transXRange = when (mode) {
            AnimatedBackdropMode.AMBIENT_BREATHE -> 0f
            AnimatedBackdropMode.CINEMATIC_FLOW -> 26f
            else -> 20f
        }
        val transX by infiniteTransition.animateFloat(
            initialValue = -transXRange,
            targetValue = transXRange,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 20_000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "cinematicTransX"
        )

        val transYRange = when (mode) {
            AnimatedBackdropMode.AMBIENT_BREATHE -> 0f
            AnimatedBackdropMode.CINEMATIC_FLOW -> 14f
            else -> 10f
        }
        val transY by infiniteTransition.animateFloat(
            initialValue = -transYRange,
            targetValue = transYRange,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 16_000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "cinematicTransY"
        )

        this.graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = transX
            translationY = transY
        }
    }
}
