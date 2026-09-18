package com.nuvio.tv.ambient.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.ambient.AmbientCandidate
import com.nuvio.tv.ambient.settings.AmbientSettings
import com.nuvio.tv.core.ha.HomeAssistantWeather
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Ambient screensaver overlay rendered on top of the ambient video.
 *
 * Provides a minimal, Apple TV "Aerials"-style HUD:
 *  - Cinematic top/bottom gradient scrims for text legibility.
 *  - Clock, date and optional Home Assistant weather badge (top-right).
 *  - Location / info tag with quality badges (bottom-left).
 *
 * All content respects the burn-in protection offset supplied by the caller.
 */
@Composable
fun AmbientOverlay(
    candidate: AmbientCandidate?,
    settings: AmbientSettings,
    weather: HomeAssistantWeather,
    isVisible: Boolean,
    offsetX: Float,
    offsetY: Float,
    modifier: Modifier = Modifier
) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1_000L)
        }
    }

    val textShadow = Shadow(
        color = Color.Black.copy(alpha = 0.7f),
        blurRadius = 4f
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .offset(x = offsetX.dp, y = offsetY.dp)
    ) {
        // --- Cinematic scrims ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.45f),
                        0.25f to Color.Transparent
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.75f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.55f)
                    )
                )
        )

        // --- Clock / Date / Weather (top-right) ---
        AnimatedVisibility(
            visible = isVisible && (settings.showClock || settings.showDate),
            enter = fadeIn(tween(800)),
            exit = fadeOut(tween(1200)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 56.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (settings.showClock) {
                    Text(
                        text = formatClock(now),
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 52.sp,
                            fontWeight = FontWeight.Light,
                            shadow = textShadow
                        )
                    )
                }
                if (settings.showDate) {
                    Text(
                        text = formatDate(now),
                        style = TextStyle(
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            shadow = textShadow
                        )
                    )
                }
                if (settings.showWeather && weather.isAvailable) {
                    WeatherBadge(weather = weather, textShadow = textShadow)
                }
            }
        }

        // --- Location / Info tag (bottom-left) ---
        AnimatedVisibility(
            visible = isVisible && settings.showLocation && candidate != null,
            enter = fadeIn(tween(800)),
            exit = fadeOut(tween(1200)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 56.dp, bottom = 56.dp)
        ) {
            candidate?.let { c ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val primary = c.location?.takeIf { it.isNotBlank() } ?: c.title
                    Text(
                        text = primary,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            shadow = textShadow
                        )
                    )

                    val secondary = c.country?.takeIf { it.isNotBlank() }
                        ?: c.category.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    Text(
                        text = secondary,
                        style = TextStyle(
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Normal,
                            shadow = textShadow
                        )
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Badge(text = if (c.is4K) "4K UHD" else "HD", textShadow = textShadow)
                        if (c.isHdr) {
                            Badge(text = "HDR", textShadow = textShadow)
                        }
                        if (c.fps >= 60) {
                            Badge(text = "${c.fps} FPS", textShadow = textShadow)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(
    text: String,
    textShadow: Shadow
) {
    Box(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.35f),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                shadow = textShadow
            )
        )
    }
}

@Composable
private fun WeatherBadge(
    weather: HomeAssistantWeather,
    textShadow: Shadow
) {
    Row(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.35f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (weather.outdoorTemp.isNotBlank()) {
            Text(
                text = weather.outdoorTemp,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    shadow = textShadow
                )
            )
        }
        if (weather.condition.isNotBlank()) {
            Text(
                text = weather.condition,
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    shadow = textShadow
                )
            )
        }
    }
}

private fun formatClock(date: Date): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)

private fun formatDate(date: Date): String =
    SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(date)
