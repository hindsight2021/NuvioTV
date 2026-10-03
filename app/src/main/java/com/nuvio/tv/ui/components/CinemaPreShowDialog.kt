package com.nuvio.tv.ui.components

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.core.ha.CinemaLightingController
import com.nuvio.tv.core.preshow.MovieTriviaItem
import com.nuvio.tv.core.preshow.PreShowAudioPlayer
import com.nuvio.tv.core.preshow.PreShowTrailer
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CinemaGold = Color(0xFFFFD700)
private val CinemaRed = Color(0xFFD32F2F)
private val CinemaRedDark = Color(0xFF6B0000)
private val CinemaEmerald = Color(0xFF00E676)
private val CinemaCardBg = Color(0xCC13111A)
private val CinemaCardFocused = Color(0xE6262238)

enum class PreShowStage {
    SHOWTIME_INTRO,
    TRIVIA,
    COMING_ATTRACTIONS,
    TRAILER,
    FEATURE_PRESENTATION
}

@Composable
fun CinemaPreShowDialog(
    visible: Boolean,
    movieTitle: String,
    backdropUrl: String? = null,
    trivia: List<MovieTriviaItem>,
    trailers: List<PreShowTrailer> = emptyList(),
    isLoading: Boolean,
    onStartMovie: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val context = LocalContext.current
    val audioPlayer = remember { PreShowAudioPlayer() }
    val lightingController = remember { CinemaLightingController() }
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf(PreShowStage.SHOWTIME_INTRO) }
    var currentTriviaIndex by remember { mutableIntStateOf(0) }
    var currentTrailerIndex by remember { mutableIntStateOf(0) }
    var userSelectedIndex by remember { mutableStateOf<Int?>(null) }
    var answerRevealed by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            audioPlayer.release()
        }
    }

    // Audio coordination per stage
    LaunchedEffect(stage) {
        when (stage) {
            PreShowStage.SHOWTIME_INTRO -> {
                audioPlayer.playIntro(context) {
                    scope.launch {
                        if (stage == PreShowStage.SHOWTIME_INTRO) {
                            stage = PreShowStage.TRIVIA
                        }
                    }
                }
            }
            PreShowStage.TRIVIA -> {
                audioPlayer.startTriviaBed(context)
            }
            PreShowStage.COMING_ATTRACTIONS -> {
                audioPlayer.fadeOut(700L)
            }
            PreShowStage.FEATURE_PRESENTATION -> {
                audioPlayer.fadeOut(600L)
                lightingController.configure(isMovie = true)
                lightingController.onPlaybackStarted()
            }
            else -> Unit
        }
    }

    fun advanceTrivia() {
        if (currentTriviaIndex + 1 < trivia.size) {
            currentTriviaIndex++
            userSelectedIndex = null
            answerRevealed = false
        } else {
            userSelectedIndex = null
            answerRevealed = false
            stage = if (trailers.isNotEmpty()) PreShowStage.COMING_ATTRACTIONS else PreShowStage.FEATURE_PRESENTATION
        }
    }

    fun advanceTrailer() {
        if (currentTrailerIndex + 1 < trailers.size) {
            currentTrailerIndex++
        } else {
            stage = PreShowStage.FEATURE_PRESENTATION
        }
    }

    val rootFocusRequester = remember { FocusRequester() }

    LaunchedEffect(stage) {
        if (stage != PreShowStage.TRIVIA) {
            delay(50)
            runCatching { rootFocusRequester.requestFocus() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFocusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    val keyCode = event.nativeKeyEvent.keyCode
                    when (stage) {
                        PreShowStage.SHOWTIME_INTRO -> {
                            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                                keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                keyCode == KeyEvent.KEYCODE_ENTER ||
                                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                                keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                            ) {
                                stage = PreShowStage.TRIVIA
                                true
                            } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                                audioPlayer.stop()
                                onStartMovie()
                                true
                            } else false
                        }
                        PreShowStage.TRIVIA -> {
                            if (keyCode == KeyEvent.KEYCODE_BACK) {
                                audioPlayer.stop()
                                onStartMovie()
                                true
                            } else if (answerRevealed && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                                keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                                keyCode == KeyEvent.KEYCODE_MEDIA_NEXT)
                            ) {
                                advanceTrivia()
                                true
                            } else if (!answerRevealed && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                                keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                                keyCode == KeyEvent.KEYCODE_MEDIA_NEXT)
                            ) {
                                answerRevealed = true
                                true
                            } else false
                        }
                        PreShowStage.TRAILER -> {
                            // User option to skip trailers during trailer!
                            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                                keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                                keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                            ) {
                                advanceTrailer()
                                true
                            } else if (keyCode == KeyEvent.KEYCODE_BACK ||
                                keyCode == KeyEvent.KEYCODE_MEDIA_STOP
                            ) {
                                stage = PreShowStage.FEATURE_PRESENTATION
                                true
                            } else false
                        }
                        PreShowStage.FEATURE_PRESENTATION -> {
                            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                keyCode == KeyEvent.KEYCODE_ENTER ||
                                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                                keyCode == KeyEvent.KEYCODE_BACK
                            ) {
                                audioPlayer.stop()
                                onStartMovie()
                                true
                            } else false
                        }
                        else -> false
                    }
                }
        ) {
            // Background backdrop if available
            if (!backdropUrl.isNullOrBlank() && stage != PreShowStage.TRAILER) {
                AsyncImage(
                    model = backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.40f)
                )
            }

            // Dark theatrical gradient scrim
            if (stage != PreShowStage.TRAILER) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.82f),
                                    Color(0xFF07050A).copy(alpha = 0.65f),
                                    Color.Black.copy(alpha = 0.92f)
                                )
                            )
                        )
                )
            }

            when (stage) {
                PreShowStage.SHOWTIME_INTRO -> {
                    ShowtimeIntroAct(
                        movieTitle = movieTitle,
                        onSkip = { stage = PreShowStage.TRIVIA }
                    )
                }

                PreShowStage.TRIVIA -> {
                    val currentItem = trivia.getOrNull(currentTriviaIndex)
                    if (currentItem == null) {
                        if (isLoading) {
                            TriviaStandbyAct(movieTitle = movieTitle)
                        } else {
                            LaunchedEffect(Unit) {
                                stage = if (trailers.isNotEmpty()) PreShowStage.COMING_ATTRACTIONS else PreShowStage.FEATURE_PRESENTATION
                            }
                        }
                    } else {
                        TriviaAct(
                            item = currentItem,
                            index = currentTriviaIndex,
                            totalCount = trivia.size,
                            userSelected = userSelectedIndex,
                            answerRevealed = answerRevealed,
                            onOptionSelected = { idx ->
                                userSelectedIndex = idx
                                answerRevealed = true
                            },
                            onTimeout = {
                                answerRevealed = true
                            },
                            onAdvance = { advanceTrivia() }
                        )
                    }
                }

                PreShowStage.COMING_ATTRACTIONS -> {
                    ComingAttractionsAct(
                        onFinished = { stage = PreShowStage.TRAILER }
                    )
                }

                PreShowStage.TRAILER -> {
                    val currentTrailer = trailers.getOrNull(currentTrailerIndex)
                    if (currentTrailer == null) {
                        LaunchedEffect(Unit) { stage = PreShowStage.FEATURE_PRESENTATION }
                    } else {
                        key(currentTrailerIndex, currentTrailer.videoUrl) {
                            TrailerAct(
                                trailer = currentTrailer,
                                trailerIndex = currentTrailerIndex,
                                totalTrailers = trailers.size,
                                onTrailerEnded = { advanceTrailer() },
                                onSkipTrailer = { advanceTrailer() },
                                onStartMovieDirectly = {
                                    stage = PreShowStage.FEATURE_PRESENTATION
                                }
                            )
                        }
                    }
                }

                PreShowStage.FEATURE_PRESENTATION -> {
                    FeaturePresentationAct(
                        onFinished = {
                            audioPlayer.stop()
                            onStartMovie()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ShowtimeIntroAct(
    movieTitle: String,
    onSkip: () -> Unit
) {
    val curtainAnim = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        curtainAnim.animateTo(0f, animationSpec = tween(3200, easing = LinearEasing))
    }

    val infiniteTransition = rememberInfiniteTransition(label = "spotlight")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "glow"
    )

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Radial cinema spotlight
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            CinemaGold.copy(alpha = glowAlpha * 0.28f),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 48.dp)
        ) {
            Text(
                text = "🍿 NUVIO+ CINEMA",
                color = CinemaGold.copy(alpha = 0.9f),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp
                )
            )

            Text(
                text = "SHOWTIME",
                color = CinemaGold,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 76.sp,
                    letterSpacing = 16.sp
                )
            )

            Text(
                text = movieTitle.uppercase(),
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 6.sp
                ),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            Text(
                text = "[ PRESS ▶ OR D-PAD RIGHT TO SKIP ]",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelMedium.copy(
                    letterSpacing = 3.sp
                )
            )
        }

        // Left velvet curtain parting
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(0.5f * curtainAnim.value)
                .background(
                    Brush.horizontalGradient(listOf(CinemaRed, CinemaRedDark))
                )
        )

        // Right velvet curtain parting
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.5f * curtainAnim.value)
                .background(
                    Brush.horizontalGradient(listOf(CinemaRedDark, CinemaRed))
                )
        )
    }
}

@Composable
private fun TriviaAct(
    item: MovieTriviaItem,
    index: Int,
    totalCount: Int,
    userSelected: Int?,
    answerRevealed: Boolean,
    onOptionSelected: (Int) -> Unit,
    onTimeout: () -> Unit,
    onAdvance: () -> Unit
) {
    var timerProgress by remember(item) { mutableFloatStateOf(1f) }
    var revealTimerProgress by remember(item, answerRevealed) { mutableFloatStateOf(1f) }
    val firstOptionFocus = remember { FocusRequester() }

    // 15-second countdown timer
    LaunchedEffect(item, answerRevealed) {
        if (answerRevealed) return@LaunchedEffect
        val totalMs = 15_000
        val stepMs = 50
        var elapsed = 0
        while (elapsed < totalMs && !answerRevealed) {
            delay(stepMs.toLong())
            elapsed += stepMs
            timerProgress = (1f - (elapsed.toFloat() / totalMs)).coerceIn(0f, 1f)
        }
        if (!answerRevealed) {
            onTimeout()
        }
    }

    // 6-second reveal countdown before auto-advance
    LaunchedEffect(answerRevealed) {
        if (!answerRevealed) return@LaunchedEffect
        val totalMs = 6_500
        val stepMs = 50
        var elapsed = 0
        while (elapsed < totalMs) {
            delay(stepMs.toLong())
            elapsed += stepMs
            revealTimerProgress = (1f - (elapsed.toFloat() / totalMs)).coerceIn(0f, 1f)
        }
        onAdvance()
    }

    LaunchedEffect(item) {
        delay(120)
        runCatching { firstOptionFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 96.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "🍿", fontSize = 22.sp)
                    Text(
                        text = "CINEMA TRIVIA",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp
                        ),
                        color = CinemaGold
                    )
                }

                Text(
                    text = "QUESTION ${index + 1} OF $totalCount",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 2.sp
                    ),
                    color = Color.White.copy(alpha = 0.8f)
                )
            }

            // Animated countdown progress bar
            LinearProgressIndicator(
                progress = { if (!answerRevealed) timerProgress else revealTimerProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (!answerRevealed) {
                    if (timerProgress < 0.25f) CinemaRed else CinemaGold
                } else {
                    CinemaEmerald
                },
                trackColor = Color.White.copy(alpha = 0.12f)
            )
        }

        // Center Question & Options
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = item.question,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    lineHeight = 36.sp
                ),
                color = Color.White
            )

            // Multiple choice option cards
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item.options.forEachIndexed { optIdx, optText ->
                    val isCorrect = answerRevealed && optText.equals(item.answer, ignoreCase = true)
                    val isUserSelection = userSelected == optIdx
                    val isWrongSelection = answerRevealed && isUserSelection && !isCorrect

                    TriviaChoiceCard(
                        text = optText,
                        index = optIdx,
                        isCorrect = isCorrect,
                        isWrong = isWrongSelection,
                        isSelected = isUserSelection,
                        enabled = !answerRevealed,
                        focusRequester = if (optIdx == 0) firstOptionFocus else null,
                        onClick = {
                            if (!answerRevealed) {
                                onOptionSelected(optIdx)
                            }
                        }
                    )
                }
            }

            // Behind-The-Scenes Fact Reveal Card
            AnimatedVisibility(
                visible = answerRevealed,
                enter = fadeIn(tween(400)),
                exit = fadeOut(tween(200))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x2800E676))
                        .border(1.dp, CinemaEmerald.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "🎬 BEHIND THE SCENES",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            ),
                            color = CinemaEmerald
                        )
                        Text(
                            text = item.funFact.ifBlank { "Answer: ${item.answer}" },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.sp,
                                lineHeight = 21.sp
                            ),
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Bottom Navigation Hint
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (answerRevealed) "[ D-PAD RIGHT: NEXT QUESTION ]" else "[ SELECT YOUR GUESS OR WAIT FOR TIMER ]",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 2.sp
                ),
                color = Color.White.copy(alpha = 0.55f)
            )

            Text(
                text = "[ BACK: START MOVIE IMMEDIATELY ]",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 2.sp
                ),
                color = Color.White.copy(alpha = 0.45f)
            )
        }
    }
}

@Composable
private fun TriviaChoiceCard(
    text: String,
    index: Int,
    isCorrect: Boolean,
    isWrong: Boolean,
    isSelected: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val cardBg = when {
        isCorrect -> Color(0x3D00E676)
        isWrong -> Color(0x3DD32F2F)
        isFocused -> CinemaCardFocused
        isSelected -> Color(0x33FFD700)
        else -> CinemaCardBg
    }

    val cardBorder = when {
        isCorrect -> CinemaEmerald
        isWrong -> CinemaRed
        isFocused -> CinemaGold
        isSelected -> CinemaGold.copy(alpha = 0.6f)
        else -> Color.White.copy(alpha = 0.15f)
    }

    val prefix = when (index) {
        0 -> "A"
        1 -> "B"
        2 -> "C"
        3 -> "D"
        else -> "${index + 1}"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .border(if (isFocused || isCorrect || isWrong) 1.8.dp else 1.dp, cardBorder, RoundedCornerShape(10.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when {
                                isCorrect -> CinemaEmerald
                                isWrong -> CinemaRed
                                isFocused -> CinemaGold
                                else -> Color.White.copy(alpha = 0.2f)
                            }
                        )
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = prefix,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (isFocused || isCorrect || isWrong) Color.Black else Color.White
                    )
                }

                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isFocused || isCorrect) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 17.sp
                    ),
                    color = when {
                        isCorrect -> Color(0xFFB9F6CA)
                        isWrong -> Color(0xFFFFCDD2)
                        isFocused -> Color.White
                        else -> Color(0xFFE0E0E0)
                    }
                )
            }

            if (isCorrect) {
                Text(text = "✓ CORRECT", color = CinemaEmerald, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            } else if (isWrong) {
                Text(text = "✗ YOUR GUESS", color = CinemaRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ComingAttractionsAct(
    onFinished: () -> Unit
) {
    LaunchedEffect(Unit) {
        delay(2800)
        onFinished()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "COMING SOON TO THEATERS",
                color = CinemaGold.copy(alpha = 0.85f),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp
                )
            )

            Text(
                text = "COMING ATTRACTIONS",
                color = Color.White,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 52.sp,
                    letterSpacing = 12.sp
                )
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "[ UPCOMING THEATRICAL TRAILERS ]",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp)
            )
        }
    }
}

@Composable
private fun TrailerAct(
    trailer: PreShowTrailer,
    trailerIndex: Int,
    totalTrailers: Int,
    onTrailerEnded: () -> Unit,
    onSkipTrailer: () -> Unit,
    onStartMovieDirectly: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        TrailerPlayer(
            trailerUrl = trailer.videoUrl,
            trailerAudioUrl = trailer.audioUrl,
            isPlaying = true,
            isPaused = false,
            muted = false,
            onEnded = onTrailerEnded,
            modifier = Modifier.fillMaxSize()
        )

        // Top info bar
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "COMING ATTRACTION (${trailerIndex + 1} OF $totalTrailers)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    ),
                    color = CinemaGold
                )
                Text(
                    text = trailer.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color.White
                )
            }
        }

        // Bottom right HUD pill for skipping trailer (explicit user requirement!)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 48.dp, vertical = 36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                .clickable { onSkipTrailer() }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "▶ D-PAD RIGHT: SKIP TRAILER",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    ),
                    color = CinemaGold
                )
                Text(
                    text = "|",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 12.sp
                )
                Text(
                    text = "BACK: START MOVIE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.sp
                    ),
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun FeaturePresentationAct(
    onFinished: () -> Unit
) {
    val contentAlpha = remember { Animatable(0f) }
    val fadeToBlack = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // Smoothly fade in "Show Time!"
        contentAlpha.animateTo(1f, animationSpec = tween(400))
        // Display for 2 seconds
        delay(2000)
        // Fade smoothly into black / movie handoff
        fadeToBlack.animateTo(1f, animationSpec = tween(750, easing = LinearEasing))
        onFinished()
    }

    val infiniteTransition = rememberInfiniteTransition(label = "fanfare")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "scale"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(horizontal = 48.dp)
                .graphicsLayer {
                    alpha = contentAlpha.value
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            Text(
                text = "🍿 NUVIO+ CINEMA",
                color = CinemaGold.copy(alpha = 0.85f),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp
                )
            )

            Text(
                text = "Show Time!",
                color = CinemaGold,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 76.sp,
                    letterSpacing = 10.sp
                ),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "OUR FEATURE PRESENTATION",
                color = Color.White.copy(alpha = 0.80f),
                style = MaterialTheme.typography.titleSmall.copy(
                    letterSpacing = 5.sp
                )
            )
        }

        if (fadeToBlack.value > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = fadeToBlack.value))
            )
        }
    }
}

@Composable
private fun TriviaStandbyAct(movieTitle: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "🍿 CINEMA TRIVIA CHALLENGE",
                color = CinemaGold,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 6.sp
                )
            )
            Text(
                text = movieTitle.uppercase(),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            )
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator(color = CinemaGold, strokeWidth = 3.dp)
            Text(
                text = "PREPARING THEATRICAL QUESTIONS...",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp)
            )
        }
    }
}

