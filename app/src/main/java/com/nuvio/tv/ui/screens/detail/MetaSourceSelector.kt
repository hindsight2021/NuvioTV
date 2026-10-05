package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaSource
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * A sleek, TV-focusable horizontal selector for metadata sources.
 *
 * Renders when there are 2 or more metadata sources (e.g. All (Merged), Cinemeta, MyTrakt, Better Posters).
 *
 * @param sources All available metadata sources.
 * @param selectedSourceId Id of the currently selected source.
 * @param onSourceSelected Invoked with the source id when a pill is selected.
 * @param modifier Modifier applied to the root container.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MetaSourceSelector(
    sources: List<MetaSource>,
    selectedSourceId: String,
    onSourceSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (sources.size <= 1) return

    Row(
        modifier = modifier
            .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xs)
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.detail_meta_source_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.extendedColors.textSecondary,
            maxLines = 1
        )

        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(
                items = sources,
                key = { it.id }
            ) { source ->
                MetaSourcePill(
                    source = source,
                    isSelected = source.id == selectedSourceId,
                    onClick = { onSourceSelected(source.id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MetaSourcePill(
    source: MetaSource,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = remember { RoundedCornerShape(12.dp) }

    val containerColor = when {
        isSelected -> NuvioTheme.extendedColors.surfaceContainerHigh
        isFocused -> NuvioTheme.extendedColors.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }

    val contentColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isFocused -> MaterialTheme.colorScheme.onSurface
        else -> NuvioTheme.extendedColors.textSecondary
    }

    val border = CardDefaults.border(
        border = Border(
            border = BorderStroke(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
            ),
            shape = shape
        ),
        focusedBorder = Border(
            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
            shape = shape
        )
    )

    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = NuvioTheme.extendedColors.surfaceContainerHighest
        ),
        border = border,
        scale = CardDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = source.displayName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val countsLabel = buildCountsLabel(source)
            if (countsLabel != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = countsLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = contentColor.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
        }
    }
}

private fun buildCountsLabel(source: MetaSource): String? {
    val parts = buildList {
        if (source.seasonCount > 0) add("${source.seasonCount}S")
        if (source.episodeCount > 0) add("${source.episodeCount}E")
    }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}
