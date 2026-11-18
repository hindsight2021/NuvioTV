@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.core.music.DjConsoleTelemetry
import com.nuvio.tv.core.music.DjDeckId
import com.nuvio.tv.core.music.DjDeckSnapshot
import com.nuvio.tv.core.music.EclipseTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val BoothBackground = Color(0xFF0A0C12)
private val SteelPanel = Color(0xFF131722)
private val SteelPanelLight = Color(0xFF1D2231)
private val SteelEdge = Color(0xFF2D3548)
private val DeckAColor = Color(0xFF00E5FF)
private val DeckBColor = Color(0xFFFF2E93)
private val GoldBadge = Color(0xFFFFC94A)
private val LedGreen = Color(0xFF39FF88)
private val LedAmber = Color(0xFFFFC24B)
private val LedRed = Color(0xFFFF4D5E)
private val TextPrimary = Color(0xFFF0F4FA)
private val TextSecondary = Color(0xFF94A0B8)

/**
 * Full-screen 10-foot Classic Dual-Deck Pro AI DJ Mixer Console for TV.
 */
@Composable
fun DjMixerScreen(
    onBack: () -> Unit,
    viewModel: EclipseMusicViewModel = hiltViewModel(),
) {
    val telemetry by viewModel.djTelemetry.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playPauseFocus = remember { FocusRequester() }

    BackHandler { onBack() }

    LaunchedEffect(Unit) {
        runCatching { playPauseFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BoothBackground),
    ) {
        ClubSpotlightsLayer(
            modifier = Modifier.fillMaxSize(),
            beatNumber = telemetry.beatNumber,
            clubLightPulse = telemetry.clubLightPulse,
            isMixing = telemetry.isMixing,
            activeDeck = telemetry.activeDeck,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BoothStatusHeader(
                telemetry = telemetry,
                isBusy = uiState.isDjBusy,
                onBack = onBack,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TurntableDeckCard(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    deckId = DjDeckId.A,
                    snapshot = telemetry.deckA,
                    accent = DeckAColor,
                    isOnAir = telemetry.activeDeck == DjDeckId.A || telemetry.isMixing,
                    isPrimary = telemetry.activeDeck == DjDeckId.A,
                )

                HardwareMixerConsole(
                    modifier = Modifier
                        .width(276.dp)
                        .fillMaxHeight(),
                    telemetry = telemetry,
                )

                TurntableDeckCard(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    deckId = DjDeckId.B,
                    snapshot = telemetry.deckB,
                    accent = DeckBColor,
                    isOnAir = telemetry.activeDeck == DjDeckId.B || telemetry.isMixing,
                    isPrimary = telemetry.activeDeck == DjDeckId.B,
                )
            }

            DualWaveformDisplay(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp),
                telemetry = telemetry,
            )

            BottomBoothDock(
                telemetry = telemetry,
                isBusy = uiState.isDjBusy,
                playPauseFocus = playPauseFocus,
                onMixNow = { viewModel.triggerDjMixNow() },
                onPlayPause = { viewModel.control("toggle") },
                onDropSurprise = { viewModel.injectDjWildcard() },
                onNewSet = { viewModel.startProDjSet(includeSurprises = true) },
                onStopBooth = {
                    viewModel.stopUmbraDj()
                    onBack()
                },
            )
        }
    }
}

@Composable
private fun ClubSpotlightsLayer(
    modifier: Modifier,
    beatNumber: Int,
    clubLightPulse: Float,
    isMixing: Boolean,
    activeDeck: DjDeckId,
) {
    val pulse = clubLightPulse.coerceIn(0f, 1f)
    val boost = if (isMixing) 1.35f else 1.0f
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val baseColor = if (activeDeck == DjDeckId.A) DeckAColor else DeckBColor
        val altColor = if (beatNumber % 2 == 0) DeckBColor else GoldBadge
        val intensity = (0.09f + 0.28f * pulse * boost).coerceIn(0f, 0.45f)

        val corners = listOf(
            Offset(0f, 0f) to baseColor,
            Offset(w, 0f) to altColor,
            Offset(0f, h) to Color(0xFF7C5CFF),
            Offset(w, h) to if (isMixing) GoldBadge else DeckBColor,
        )
        corners.forEachIndexed { idx, (corner, col) ->
            val beatBoost = if ((beatNumber - 1) % 4 == idx) 1.25f else 0.78f
            val radius = w.coerceAtMost(h) * (0.36f + 0.14f * pulse)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        col.copy(alpha = (intensity * beatBoost).coerceIn(0f, 0.50f)),
                        Color.Transparent,
                    ),
                    center = corner,
                    radius = radius,
                ),
                radius = radius,
                center = corner,
            )
        }

        // Top club light beam accent synced with beat pulse
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    baseColor.copy(alpha = (0.06f + 0.14f * pulse).coerceIn(0f, 0.25f)),
                    Color.Transparent,
                    altColor.copy(alpha = (0.04f + 0.10f * pulse).coerceIn(0f, 0.20f)),
                ),
            ),
        )
    }
}

@Composable
private fun BoothStatusHeader(
    telemetry: DjConsoleTelemetry,
    isBusy: Boolean,
    onBack: () -> Unit,
) {
    val pulseColor = if (telemetry.isMixing) GoldBadge else LedGreen
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SteelPanel.copy(alpha = 0.94f))
            .border(1.dp, SteelEdge, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(
            onClick = onBack,
            colors = IconButtonDefaults.colors(
                containerColor = SteelPanelLight,
                contentColor = TextPrimary,
                focusedContainerColor = DeckAColor,
                focusedContentColor = Color.Black,
            ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
            )
        }

        Column(modifier = Modifier.width(210.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "ECLIPSE PRO DJ BOOTH",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (telemetry.isMixing) GoldBadge.copy(alpha = 0.22f)
                            else DeckAColor.copy(alpha = 0.16f),
                        )
                        .border(
                            1.dp,
                            if (telemetry.isMixing) GoldBadge else DeckAColor.copy(alpha = 0.7f),
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (telemetry.isMixing) "MIXING" else "AI BEATMIX",
                        color = if (telemetry.isMixing) GoldBadge else DeckAColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                text = telemetry.commentary?.takeIf { it.isNotBlank() }
                    ?: if (isBusy) "AI DJ curating harmonic transitions…" else "Dual-Deck Tempo Lock & Bass Swap",
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Transition Plan pill
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(SteelPanelLight)
                .border(1.dp, SteelEdge, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = telemetry.activeTransitionNote?.takeIf { it.isNotBlank() }
                    ?: "Waiting for cue transition…",
                color = if (telemetry.isMixing) GoldBadge else TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Master BPM & 4-Beat LED Stepper
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "MASTER",
                color = TextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${"%.1f".format(telemetry.masterBpm)} BPM",
                color = GoldBadge,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (beat in 1..4) {
                val active = telemetry.beatNumber == beat
                val dotColor = when {
                    active && beat == 1 -> DeckBColor
                    active -> GoldBadge
                    else -> SteelPanelLight
                }
                Box(
                    modifier = Modifier
                        .size(if (active) 14.dp else 11.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                        .border(1.dp, if (active) Color.White.copy(alpha = 0.7f) else SteelEdge, CircleShape),
                )
            }
        }

        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (isBusy) LedAmber else pulseColor),
        )
    }
}

@Composable
private fun TurntableDeckCard(
    modifier: Modifier,
    deckId: DjDeckId,
    snapshot: DjDeckSnapshot,
    accent: Color,
    isOnAir: Boolean,
    isPrimary: Boolean,
) {
    val track = snapshot.track
    val isWildcard = track?.djMeta?.isWildcard == true
    val pitchPct = (snapshot.tempoRatio - 1.0f) * 100f

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SteelPanel.copy(alpha = 0.94f))
            .border(
                width = if (isPrimary) 1.5.dp else 1.dp,
                color = if (isPrimary) accent.copy(alpha = 0.75f) else SteelEdge,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Deck header + ON AIR / CUED + Wildcard
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "DECK ${deckId.name}",
                color = accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            if (isWildcard) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(GoldBadge.copy(alpha = 0.22f))
                        .border(1.dp, GoldBadge, RoundedCornerShape(8.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "★ SURPRISE PICK",
                        color = GoldBadge,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            val statusLabel = when {
                snapshot.isPlaying && isPrimary -> "ON AIR"
                snapshot.isPlaying -> "MIXING IN"
                snapshot.isBuffering -> "SYNCING…"
                snapshot.isCued -> "CUED NEXT"
                else -> "STANDBY"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isOnAir) accent.copy(alpha = 0.22f) else SteelPanelLight)
                    .border(1.dp, if (isOnAir) accent else SteelEdge, RoundedCornerShape(10.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            ) {
                Text(
                    text = statusLabel,
                    color = if (isOnAir) accent else TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Responsive Spinning Vinyl Platter
        SpinningVinylPlatter(
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .padding(4.dp),
            accent = accent,
            artUrl = track?.displayArtUrl()?.takeIf { it.isNotBlank() },
            isPlaying = snapshot.isPlaying,
            bpm = snapshot.bpm,
            beatPulse = snapshot.beatPulse,
        )

        Spacer(Modifier.height(6.dp))

        // Readouts row: BPM | PITCH | KEY
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SteelPanelLight)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            DeckStatCell("BPM", "%.1f".format(snapshot.bpm), accent)
            DeckStatCell("PITCH", "%+.1f%%".format(pitchPct), TextPrimary)
            DeckStatCell("KEY", snapshot.musicalKey.ifBlank { "—" }, GoldBadge)
            DeckStatCell(
                "TIME",
                "${formatDeckTime(snapshot.positionMs)} / ${formatDeckTime(snapshot.durationMs)}",
                TextSecondary,
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = track?.title ?: "Standby Deck",
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = track?.artist ?: "Waiting for next track",
            color = TextSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeckStatCell(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = TextSecondary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(text = value, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SpinningVinylPlatter(
    modifier: Modifier,
    accent: Color,
    artUrl: String?,
    isPlaying: Boolean,
    bpm: Float,
    beatPulse: Float,
) {
    var angle by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying, bpm) {
        if (!isPlaying) return@LaunchedEffect
        var lastNs = 0L
        val degPerSec = (bpm / 120f).coerceIn(0.6f, 1.8f) * 115f
        while (true) {
            withFrameMillis { frameMs ->
                if (lastNs != 0L) {
                    val dt = ((frameMs - lastNs).coerceIn(0L, 100L)) / 1000f
                    angle = (angle + degPerSec * dt) % 360f
                }
                lastNs = frameMs
            }
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // Outer chrome platter housing & beat strobe glow
            drawCircle(
                color = accent.copy(alpha = (0.18f + beatPulse * 0.45f).coerceIn(0f, 0.7f)),
                radius = radius,
                center = center,
                style = Stroke(width = 4f),
            )
            drawCircle(color = Color(0xFF06080D), radius = radius * 0.97f, center = center)

            rotate(degrees = angle, pivot = center) {
                // Vinyl concentric grooves
                for (i in 1..12) {
                    val r = radius * (0.36f + 0.56f * i / 12f)
                    drawCircle(
                        color = if (i % 3 == 0) Color(0xFF212838) else Color(0xFF141824),
                        radius = r,
                        center = center,
                        style = Stroke(width = 1.2f),
                    )
                }
                // Strobe dots around platter rim
                val dots = 24
                for (i in 0 until dots) {
                    val rad = (i.toFloat() / dots) * 2f * PI.toFloat()
                    val r = radius * 0.91f
                    drawCircle(
                        color = if (i % 4 == 0) accent else Color(0xFF3A4358),
                        radius = if (i % 4 == 0) 3.2f else 1.8f,
                        center = Offset(center.x + r * cos(rad), center.y + r * sin(rad)),
                    )
                }
                // Metallic shine wedge on rotating vinyl
                drawArc(
                    color = Color.White.copy(alpha = 0.07f),
                    startAngle = -20f,
                    sweepAngle = 55f,
                    useCenter = true,
                    topLeft = Offset(center.x - radius * 0.90f, center.y - radius * 0.90f),
                    size = Size(radius * 1.80f, radius * 1.80f),
                )
            }

            // Inner glowing collar around label
            drawCircle(
                color = accent.copy(alpha = if (isPlaying) 0.90f else 0.35f),
                radius = radius * 0.40f,
                center = center,
                style = Stroke(width = 2.2f),
            )
        }

        // Rotating Circular Center Label with Album Art
        Box(
            modifier = Modifier
                .fillMaxSize(0.39f)
                .graphicsLayer { rotationZ = angle }
                .clip(CircleShape)
                .background(SteelPanelLight)
                .border(1.5.dp, accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (artUrl != null) {
                AsyncImage(
                    model = artUrl,
                    contentDescription = "Deck vinyl label",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(28.dp),
                )
            }
            // Center spindle hole
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0A0C12))
                    .border(1.dp, Color.White.copy(alpha = 0.65f), CircleShape),
            )
        }
    }
}

@Composable
private fun HardwareMixerConsole(
    modifier: Modifier,
    telemetry: DjConsoleTelemetry,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0F121B))
            .border(1.5.dp, SteelEdge, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "DJM-AI PRO MIXER",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
        )

        // EQ Rotary Knobs + Dual 12-Segment LED VU Meters
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // CH A EQ Knobs
            ChannelEqColumn(
                label = "CH 1",
                accent = DeckAColor,
                highGain = telemetry.deckA.highGain,
                midGain = telemetry.deckA.midGain,
                lowGain = telemetry.deckA.lowGain,
            )

            // Dual 12-Segment LED VU Meters
            DualLedVuMeter(
                levelA = telemetry.deckA.vuLevel,
                levelB = telemetry.deckB.vuLevel,
                modifier = Modifier
                    .width(46.dp)
                    .fillMaxHeight(0.88f),
            )

            // CH B EQ Knobs
            ChannelEqColumn(
                label = "CH 2",
                accent = DeckBColor,
                highGain = telemetry.deckB.highGain,
                midGain = telemetry.deckB.midGain,
                lowGain = telemetry.deckB.lowGain,
            )
        }

        // Crossfader & Active Mix Progress Bar
        if (telemetry.isMixing) {
            val pct = (telemetry.mixProgress * 100f).roundToInt().coerceIn(0, 100)
            Text(
                text = "BEATMATCH MIXING • $pct%",
                color = GoldBadge,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        } else {
            Text(
                text = "CROSSFADER (CH1 ◀ ▶ CH2)",
                color = TextSecondary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        CrossfaderSliderCanvas(
            position = telemetry.crossfader,
            isMixing = telemetry.isMixing,
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp),
        )
    }
}

@Composable
private fun ChannelEqColumn(
    label: String,
    accent: Color,
    highGain: Float,
    midGain: Float,
    lowGain: Float,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(text = label, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        EqRotaryKnob(label = "HI", value = highGain, accent = accent)
        EqRotaryKnob(label = "MID", value = midGain, accent = accent)
        EqRotaryKnob(label = "LOW", value = lowGain, accent = accent)
    }
}

@Composable
private fun EqRotaryKnob(
    label: String,
    value: Float,
    accent: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(modifier = Modifier.size(30.dp)) {
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = Color(0xFF1F2536), radius = r, center = center)
            drawCircle(color = SteelEdge, radius = r, center = center, style = Stroke(width = 1.5f))

            // Normalized knob angle: 0.0 -> -135 deg, 1.0 -> 0 deg (12 o'clock), 1.5 -> +135 deg
            val norm = ((value - 0.1f) / 1.25f).coerceIn(0f, 1f)
            val sweepDeg = -135f + norm * 210f
            drawArc(
                color = accent,
                startAngle = -225f,
                sweepAngle = 210f * norm,
                useCenter = false,
                topLeft = Offset(2f, 2f),
                size = Size(size.width - 4f, size.height - 4f),
                style = Stroke(width = 2.6f, cap = StrokeCap.Round),
            )
            // Indicator line on knob cap
            val rad = ((sweepDeg - 90f) * PI / 180.0).toFloat()
            drawLine(
                color = Color.White,
                start = center,
                end = Offset(center.x + (r * 0.72f) * cos(rad), center.y + (r * 0.72f) * sin(rad)),
                strokeWidth = 2.2f,
                cap = StrokeCap.Round,
            )
        }
        Text(text = label, color = TextSecondary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DualLedVuMeter(
    levelA: Float,
    levelB: Float,
    modifier: Modifier,
) {
    Canvas(modifier = modifier) {
        val segments = 12
        val gap = 3.dp.toPx()
        val segHeight = ((size.height - gap * (segments - 1)) / segments).coerceAtLeast(2f)
        val colWidth = (size.width - 6.dp.toPx()) / 2f

        fun drawColumn(xStart: Float, level: Float) {
            val activeCount = (level.coerceIn(0f, 1f) * segments * 1.15f).roundToInt().coerceIn(0, segments)
            for (s in 0 until segments) {
                val segFromBottom = s
                val y = size.height - (segFromBottom + 1) * (segHeight + gap) + gap
                val on = segFromBottom < activeCount
                val segColor = when {
                    segFromBottom >= 10 -> LedRed
                    segFromBottom >= 8 -> LedAmber
                    else -> LedGreen
                }
                drawRoundRect(
                    color = if (on) segColor else Color(0xFF1C212F),
                    topLeft = Offset(xStart, y),
                    size = Size(colWidth, segHeight),
                    cornerRadius = CornerRadius(2f, 2f),
                )
            }
        }
        drawColumn(0f, levelA)
        drawColumn(colWidth + 6.dp.toPx(), levelB)
    }
}

@Composable
private fun CrossfaderSliderCanvas(
    position: Float,
    isMixing: Boolean,
    modifier: Modifier,
) {
    val p = position.coerceIn(0f, 1f)
    Canvas(modifier = modifier) {
        val midY = size.height / 2f
        val padX = 16.dp.toPx()
        val usableW = (size.width - padX * 2f).coerceAtLeast(10f)

        // Metallic faceplate tick marks
        for (i in 0..8) {
            val x = padX + usableW * (i / 8f)
            val tickH = if (i == 4) 12.dp.toPx() else 7.dp.toPx()
            drawLine(
                color = SteelEdge,
                start = Offset(x, midY - tickH),
                end = Offset(x, midY + tickH),
                strokeWidth = 1.5f,
            )
        }

        // Crossfader rail slot
        drawRoundRect(
            color = Color(0xFF06080D),
            topLeft = Offset(padX, midY - 3.dp.toPx()),
            size = Size(usableW, 6.dp.toPx()),
            cornerRadius = CornerRadius(3f, 3f),
        )

        // Sliding metallic hardware fader cap
        val capX = padX + usableW * p
        val capW = 24.dp.toPx()
        val capH = 26.dp.toPx()
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF4A5368), Color(0xFF282F40), Color(0xFF1B202C)),
            ),
            topLeft = Offset(capX - capW / 2f, midY - capH / 2f),
            size = Size(capW, capH),
            cornerRadius = CornerRadius(4f, 4f),
        )
        drawLine(
            color = if (isMixing) GoldBadge else Color.White,
            start = Offset(capX, midY - capH / 2f + 3f),
            end = Offset(capX, midY + capH / 2f - 3f),
            strokeWidth = 2.5f,
        )
    }
}

@Composable
private fun DualWaveformDisplay(
    modifier: Modifier,
    telemetry: DjConsoleTelemetry,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0B0E16))
            .border(1.dp, SteelEdge, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "DECK A  ${telemetry.deckA.track?.title ?: ""}",
                color = DeckAColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (telemetry.isMixing) "⚡ REAL-TIME WAVEFORM BEAT-LOCK MIX ⚡" else "DUAL PHASE WAVEFORMS",
                color = if (telemetry.isMixing) GoldBadge else TextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = "${telemetry.deckB.track?.title ?: ""}  DECK B",
                color = DeckBColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }

        Spacer(Modifier.height(4.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val w = size.width
            val h = size.height
            val midY = h / 2f

            drawLine(color = SteelEdge, start = Offset(0f, midY), end = Offset(w, midY), strokeWidth = 1f)

            drawWaveformTrack(
                bars = telemetry.deckA.waveform,
                liveBars = telemetry.deckA.liveWaveform,
                progress = if (telemetry.deckA.durationMs > 0L) {
                    (telemetry.deckA.positionMs.toFloat() / telemetry.deckA.durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f,
                accent = DeckAColor,
                top = 0f,
                height = midY - 2f,
                width = w,
                upward = true,
            )

            drawWaveformTrack(
                bars = telemetry.deckB.waveform,
                liveBars = telemetry.deckB.liveWaveform,
                progress = if (telemetry.deckB.durationMs > 0L) {
                    (telemetry.deckB.positionMs.toFloat() / telemetry.deckB.durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f,
                accent = DeckBColor,
                top = midY + 2f,
                height = midY - 2f,
                width = w,
                upward = false,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWaveformTrack(
    bars: List<Float>,
    liveBars: List<Float>,
    progress: Float,
    accent: Color,
    top: Float,
    height: Float,
    width: Float,
    upward: Boolean,
) {
    val count = if (bars.isNotEmpty()) bars.size else 64
    val step = width / count.toFloat()
    val barW = (step * 0.70f).coerceAtLeast(2f)

    // Mix-out zone shading on final 15% of waveform
    drawRect(
        color = GoldBadge.copy(alpha = 0.08f),
        topLeft = Offset(width * 0.84f, top),
        size = Size(width * 0.16f, height),
    )

    for (i in 0 until count) {
        val normPos = i.toFloat() / count.toFloat()
        val amp = (if (bars.isNotEmpty()) bars[i] else 0.35f).coerceIn(0.08f, 1f)
        val liveBoost = if (liveBars.isNotEmpty()) liveBars[i % liveBars.size] * 0.28f else 0f
        val barH = (height * (amp + liveBoost).coerceIn(0.08f, 1f))
        val played = normPos <= progress
        val x = i * step
        val y = if (upward) (top + height - barH) else top
        drawRoundRect(
            color = if (played) accent else accent.copy(alpha = 0.36f),
            topLeft = Offset(x, y),
            size = Size(barW, barH),
            cornerRadius = CornerRadius(2f, 2f),
        )
    }

    // Playhead needle
    val needleX = width * progress
    drawLine(
        color = Color.White,
        start = Offset(needleX, top),
        end = Offset(needleX, top + height),
        strokeWidth = 2f,
    )
}

@Composable
private fun BottomBoothDock(
    telemetry: DjConsoleTelemetry,
    isBusy: Boolean,
    playPauseFocus: FocusRequester,
    onMixNow: () -> Unit,
    onPlayPause: () -> Unit,
    onDropSurprise: () -> Unit,
    onNewSet: () -> Unit,
    onStopBooth: () -> Unit,
) {
    val activePlaying = if (telemetry.activeDeck == DjDeckId.A) telemetry.deckA.isPlaying else telemetry.deckB.isPlaying
    val upcoming = remember(telemetry.queue, telemetry.currentIndex) {
        if (telemetry.currentIndex >= 0) {
            telemetry.queue.drop(telemetry.currentIndex + 1).take(3)
        } else {
            telemetry.queue.take(3)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onMixNow,
            enabled = telemetry.nextTrack != null && !telemetry.isMixing,
            colors = ButtonDefaults.colors(
                containerColor = GoldBadge,
                contentColor = Color.Black,
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black,
            ),
        ) {
            Icon(Icons.Default.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(if (telemetry.isMixing) "Mixing…" else "Mix Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Button(
            onClick = onPlayPause,
            modifier = Modifier.focusRequester(playPauseFocus),
            colors = ButtonDefaults.colors(
                containerColor = DeckAColor,
                contentColor = Color.Black,
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black,
            ),
        ) {
            Icon(
                imageVector = if (activePlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(if (activePlaying) "Pause" else "Play", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Button(
            onClick = onDropSurprise,
            enabled = !isBusy,
            colors = ButtonDefaults.colors(
                containerColor = SteelPanelLight,
                contentColor = GoldBadge,
                focusedContainerColor = GoldBadge,
                focusedContentColor = Color.Black,
            ),
        ) {
            Icon(Icons.Default.Casino, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text("Surprise Track", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        Button(
            onClick = onNewSet,
            enabled = !isBusy,
            colors = ButtonDefaults.colors(
                containerColor = SteelPanelLight,
                contentColor = TextPrimary,
                focusedContainerColor = DeckBColor,
                focusedContentColor = Color.White,
            ),
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text("New AI Set", fontSize = 12.sp)
        }

        Button(
            onClick = onStopBooth,
            colors = ButtonDefaults.colors(
                containerColor = SteelPanelLight,
                contentColor = LedRed,
                focusedContainerColor = LedRed,
                focusedContentColor = Color.White,
            ),
        ) {
            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Stop", fontSize = 12.sp)
        }

        // Up Next crate pills on right
        if (upcoming.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(upcoming, key = { idx, t -> "${t.id}_$idx" }) { _, tr ->
                    val isWild = tr.djMeta?.isWildcard == true
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isWild) GoldBadge.copy(alpha = 0.14f) else SteelPanel)
                            .border(1.dp, if (isWild) GoldBadge else SteelEdge, RoundedCornerShape(8.dp))
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (isWild) {
                            Text("★", color = GoldBadge, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = "${tr.title} • ${tr.resolvedBpm().roundToInt()} BPM",
                            color = if (isWild) GoldBadge else TextSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun formatDeckTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}
