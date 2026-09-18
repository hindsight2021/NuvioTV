package com.nuvio.tv.ui.screens.calendar

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.core.sound.AudioFeedbackManager
import com.nuvio.tv.data.simkl.calendar.CalendarCategory
import com.nuvio.tv.data.simkl.calendar.CalendarDayGroup
import com.nuvio.tv.data.simkl.calendar.CalendarItemType
import com.nuvio.tv.data.simkl.calendar.CalendarMediaItem
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Android TV Leanback-style Calendar screen.
 *
 * Displays an upcoming release calendar with a day navigator, category filters,
 * a grid of media items, and a preview pane for the currently focused item.
 */
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onItemClick: (CalendarMediaItem) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    BackHandler { onNavigateBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        // Ambient Backdrop from focused item
        val backdropUrl = uiState.focusedItem?.backdropUrl ?: uiState.focusedItem?.posterUrl
        if (!backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Horizontal gradient for contrast with cards and text
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                NuvioTheme.colors.Background.copy(alpha = 0.97f),
                                NuvioTheme.colors.Background.copy(alpha = 0.85f),
                                NuvioTheme.colors.Background.copy(alpha = 0.95f)
                            )
                        )
                    )
            )
            // Vertical gradient to preserve header/bottom visibility
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                NuvioTheme.colors.Background.copy(alpha = 0.90f),
                                Color.Transparent,
                                NuvioTheme.colors.Background.copy(alpha = 0.95f)
                            )
                        )
                    )
            )
        }

        // Loading State
        if (uiState.isLoading && uiState.days.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = NuvioTheme.colors.Primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Loading Upcoming Calendar…",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NuvioTheme.colors.TextPrimary
                    )
                }
            }
            return@Box
        }

        // Error State
        if (uiState.errorMessage != null && uiState.days.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = uiState.errorMessage ?: "Failed to load calendar",
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    Spacer(Modifier.height(16.dp))
                    Card(
                        onClick = {
                            AudioFeedbackManager.playClick(context)
                            viewModel.retry()
                        },
                        colors = CardDefaults.colors(containerColor = NuvioTheme.colors.Primary)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Retry", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            return@Box
        }

        // Main Screen Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = NuvioTheme.spacing.xxl, end = NuvioTheme.spacing.xxl, top = NuvioTheme.spacing.xl, bottom = NuvioTheme.spacing.lg)
        ) {
            // Header Bar
            CalendarHeader(uiState = uiState)

            Spacer(Modifier.height(14.dp))

            // Day Navigator Bar
            DayNavigator(
                days = uiState.days,
                selectedDayIndex = uiState.selectedDayIndex,
                onSelect = { index ->
                    AudioFeedbackManager.playClick(context)
                    viewModel.selectDay(index)
                }
            )

            Spacer(Modifier.height(10.dp))

            // Category Filter Tabs
            CategoryFilters(
                selected = uiState.selectedCategory,
                onSelect = { category ->
                    AudioFeedbackManager.playClick(context)
                    viewModel.selectCategory(category)
                }
            )

            Spacer(Modifier.height(14.dp))

            // Main 2-Pane Content: Grid (Left) & Detailed Preview (Right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Left Pane: Grid of Releases
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (uiState.filteredItems.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No releases scheduled for this filter",
                                style = MaterialTheme.typography.titleMedium,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(end = 16.dp, bottom = 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(
                                items = uiState.filteredItems,
                                key = { index, item -> "${item.type}_${item.id}_${item.date}_${item.season}_${item.episode}_$index" }
                            ) { _, item ->
                                CalendarGridItem(
                                    item = item,
                                    onClick = {
                                        AudioFeedbackManager.playClick(context)
                                        onItemClick(item)
                                    },
                                    onFocused = { viewModel.onItemFocused(item) }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(16.dp))

                // Right Pane: Rich Detail Preview
                CalendarPreviewPane(
                    item = uiState.focusedItem,
                    onItemClick = { item ->
                        AudioFeedbackManager.playClick(context)
                        onItemClick(item)
                    }
                )
            }
        }
    }
}

@Composable
private fun CalendarHeader(uiState: CalendarViewModel.CalendarUiState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        Icon(
            imageVector = Icons.Default.CalendarToday,
            contentDescription = null,
            tint = NuvioTheme.colors.Primary,
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = "UPCOMING CALENDAR",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF0055A5).copy(alpha = 0.25f))
                .border(1.dp, Color(0xFF0055A5), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = "SIMKL TRACKER",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF4DA6FF)
            )
        }
        if (uiState.totalAvailableToStream > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF10B981).copy(alpha = 0.22f))
                    .border(1.dp, Color(0xFF10B981), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "⚡ ${uiState.totalAvailableToStream} STREAMING NOW",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF34D399)
                )
            }
        }
    }
}

@Composable
private fun DayNavigator(
    days: List<CalendarDayGroup>,
    selectedDayIndex: Int,
    onSelect: (Int) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(
            items = days,
            key = { index, day -> "${day.date}_$index" }
        ) { index, day ->
            val isSelected = index == selectedDayIndex
            Card(
                onClick = { onSelect(index) },
                colors = CardDefaults.colors(
                    containerColor = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.5f),
                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                ),
                border = CardDefaults.border(
                    focusedBorder = Border(
                        border = androidx.compose.foundation.BorderStroke(2.dp, NuvioTheme.colors.Primary),
                        shape = RoundedCornerShape(12.dp)
                    )
                ),
                shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                modifier = Modifier.height(38.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = day.shortLabel,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (isSelected) Color.White else NuvioTheme.colors.TextSecondary
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) Color.White.copy(alpha = 0.25f)
                                else NuvioTheme.colors.Primary.copy(alpha = 0.25f)
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = day.items.size.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isSelected) Color.White else NuvioTheme.colors.Primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryFilters(
    selected: CalendarCategory,
    onSelect: (CalendarCategory) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(CalendarCategory.entries.size) { index ->
            val category = CalendarCategory.entries[index]
            val isSelected = category == selected
            Card(
                onClick = { onSelect(category) },
                colors = CardDefaults.colors(
                    containerColor = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.4f),
                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                ),
                border = CardDefaults.border(
                    focusedBorder = Border(
                        border = androidx.compose.foundation.BorderStroke(2.dp, NuvioTheme.colors.Primary),
                        shape = RoundedCornerShape(18.dp)
                    )
                ),
                shape = CardDefaults.shape(RoundedCornerShape(18.dp)),
                modifier = Modifier.height(32.dp)
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = category.displayName,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isSelected) Color.White else NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarGridItem(
    item: CalendarMediaItem,
    onClick: () -> Unit,
    onFocused: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.Surface.copy(alpha = 0.75f),
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, NuvioTheme.colors.Primary),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                isFocused = it.hasFocus
                if (it.hasFocus) onFocused()
            }
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(Color(0xFF14141C))
            ) {
                if (!item.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Top-Left Event Badge
                val topLeftBadge = when {
                    item.isNextUpForUser ->
                        "▶ NEXT UP" to Color(0xFF10B981)
                    item.isSeriesPremiere ->
                        "🌟 SERIES PREMIERE" to Color(0xFFF59E0B)
                    item.isSeasonPremiere ->
                        "🎉 S${item.season ?: 1} PREMIERE" to Color(0xFF0EA5E9)
                    item.isSeasonFinale ->
                        "🎬 S${item.season ?: 1} FINALE" to Color(0xFFF43F5E)
                    item.isAvailableToStream || item.type == CalendarItemType.DIGITAL_MOVIE ->
                        "⚡ STREAM NOW" to Color(0xFF10B981)
                    item.type == CalendarItemType.TV_EPISODE ->
                        "S${item.season ?: 1}·E${item.episode ?: 1}" to Color(0xFF06B6D4)
                    item.type == CalendarItemType.THEATRICAL_MOVIE ->
                        "CINEMA" to Color(0xFF8B5CF6)
                    else -> null
                }
                topLeftBadge?.let { (label, badgeColor) ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeColor.copy(alpha = 0.95f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black, fontSize = 9.sp),
                            color = Color.White
                        )
                    }
                }

                // Top-Right User Status / Rating Badge
                val topRightBadge = when {
                    item.isActivelyWatching ->
                        "⚡ WATCHING" to Color(0xFF10B981)
                    item.isWatchlist ->
                        "📌 WATCHLIST" to Color(0xFF3B82F6)
                    else -> null
                }

                if (topRightBadge != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(topRightBadge.second.copy(alpha = 0.95f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = topRightBadge.first,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 8.sp),
                            color = Color.White
                        )
                    }
                } else if (item.rating != null && item.rating > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.75f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = Color(0xFFFFC107),
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = String.format("%.1f", item.rating),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                color = Color.White
                            )
                        }
                    }
                }

                // Bottom air time overlay bar
                if (!item.airTimeString.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                )
                            )
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = item.airTimeString,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = Color(0xFFE2E8F0)
                        )
                    }
                }
            }

            // Content Info below poster
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isFocused) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (item.type == CalendarItemType.TV_EPISODE) {
                    val epText = buildString {
                        append("S${item.season ?: 1}·E${item.episode ?: 1}")
                        if (!item.episodeTitle.isNullOrBlank()) {
                            append(" • ${item.episodeTitle}")
                        }
                    }
                    Text(
                        text = epText,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!item.userStatusNote.isNullOrBlank()) {
                    Text(
                        text = item.userStatusNote,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Medium),
                        color = if (item.isNextUpForUser) Color(0xFF34D399) else Color(0xFF60A5FA),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarPreviewPane(
    item: CalendarMediaItem?,
    onItemClick: (CalendarMediaItem) -> Unit
) {
    Box(
        modifier = Modifier
            .width(390.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(NuvioTheme.colors.Surface.copy(alpha = 0.85f))
            .border(1.dp, NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(18.dp)
    ) {
        if (item == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Select a show or movie to preview",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
            return@Box
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                // Fanart / Backdrop Thumbnail
                val previewImage = item.backdropUrl ?: item.posterUrl
                if (!previewImage.isNullOrBlank()) {
                    AsyncImage(
                        model = previewImage,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF14141C))
                    )
                    Spacer(Modifier.height(12.dp))
                }

                // Release & Tracking Status Badges
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    if (item.isActivelyWatching) {
                        item {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF10B981).copy(alpha = 0.22f))
                                    .border(1.dp, Color(0xFF10B981), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "⚡ ACTIVELY WATCHING",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF34D399)
                                )
                            }
                        }
                    } else if (item.isWatchlist) {
                        item {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF3B82F6).copy(alpha = 0.22f))
                                    .border(1.dp, Color(0xFF3B82F6), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "📌 ON WATCHLIST",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF60A5FA)
                                )
                            }
                        }
                    }

                    if (item.isNextUpForUser) {
                        item {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF10B981).copy(alpha = 0.25f))
                                    .border(1.dp, Color(0xFF10B981), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "▶ NEXT UP FOR YOU",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF34D399)
                                )
                            }
                        }
                    }

                    if (item.isSeriesPremiere) {
                        item {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFF59E0B).copy(alpha = 0.22f))
                                    .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "🌟 SERIES PREMIERE",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFFFBBF24)
                                )
                            }
                        }
                    } else if (item.isSeasonPremiere) {
                        item {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF0EA5E9).copy(alpha = 0.22f))
                                    .border(1.dp, Color(0xFF0EA5E9), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "🎉 SEASON ${item.season ?: 1} PREMIERE",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF38BDF8)
                                )
                            }
                        }
                    }

                    if (item.isSeasonFinale) {
                        item {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFF43F5E).copy(alpha = 0.22f))
                                    .border(1.dp, Color(0xFFF43F5E), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "🎬 SEASON ${item.season ?: 1} FINALE",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFFFB7185)
                                )
                            }
                        }
                    }

                    if (item.isAvailableToStream) {
                        item {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF10B981).copy(alpha = 0.22f))
                                    .border(1.dp, Color(0xFF10B981), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "⚡ AVAILABLE TO STREAM",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF34D399)
                                )
                            }
                        }
                    } else if (item.digitalReleaseDate != null) {
                        item {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF06B6D4).copy(alpha = 0.22f))
                                    .border(1.dp, Color(0xFF06B6D4), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "DIGITAL: ${item.digitalReleaseDate}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF22D3EE)
                                )
                            }
                        }
                    }
                    if (item.theatricalReleaseDate != null) {
                        item {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF8B5CF6).copy(alpha = 0.22f))
                                    .border(1.dp, Color(0xFF8B5CF6), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "THEATERS: ${item.theatricalReleaseDate}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFFA78BFA)
                                )
                            }
                        }
                    }
                }

                // Title
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                // Subtitle / Episode / Genres
                val subText = when {
                    item.type == CalendarItemType.TV_EPISODE && !item.episodeTitle.isNullOrBlank() ->
                        "Season ${item.season ?: 1}, Episode ${item.episode ?: 1}: \"${item.episodeTitle}\""
                    item.type == CalendarItemType.TV_EPISODE ->
                        "Season ${item.season ?: 1}, Episode ${item.episode ?: 1}"
                    item.genres.isNotEmpty() ->
                        item.genres.take(3).joinToString(" • ")
                    else -> item.type.name.replace("_", " ")
                }
                Text(
                    text = subText,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = NuvioTheme.colors.Primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (!item.userStatusNote.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (item.isNextUpForUser) Color(0xFF10B981).copy(alpha = 0.15f)
                                else NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.4f)
                            )
                            .border(
                                1.dp,
                                if (item.isNextUpForUser) Color(0xFF10B981).copy(alpha = 0.5f)
                                else NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.6f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "Status: ${item.userStatusNote}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (item.isNextUpForUser) Color(0xFF34D399) else NuvioTheme.colors.TextSecondary
                        )
                    }
                }

                // Synopsis / Overview
                if (!item.overview.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = item.overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Action Button
            Card(
                onClick = { onItemClick(item) },
                colors = CardDefaults.colors(
                    containerColor = if (item.isAvailableToStream) Color(0xFF10B981) else NuvioTheme.colors.Primary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                ),
                border = CardDefaults.border(
                    focusedBorder = Border(
                        border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                        shape = RoundedCornerShape(12.dp)
                    )
                ),
                shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (item.isAvailableToStream) "WATCH / FIND STREAMS" else "EXPLORE SHOW / MOVIE",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }
        }
    }
}
