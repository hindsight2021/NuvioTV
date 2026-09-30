package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush

/**
 * Compact "NEW" badge marker, styled consistently with [WatchedMarker].
 *
 * Displays a bold uppercase "NEW" label on an accent-gradient background,
 * used as an overlay on newly released movie/series cards.
 */
@Composable
fun NewBadgeMarker(
    modifier: Modifier = Modifier
) {
    val palette = NuvioTheme.palette
    val shape = RoundedCornerShape(4.dp)

    Box(
        modifier = modifier
            .shadow(
                elevation = 4.dp,
                shape = shape,
                spotColor = Color.Black.copy(alpha = 0.6f)
            )
            .clip(shape)
            .background(brush = palette.accentBrush(), shape = shape)
            .padding(PaddingValues(horizontal = 5.dp, vertical = 1.5.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "NEW",
            color = palette.onSecondary,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center
            ),
            maxLines = 1
        )
    }
}
