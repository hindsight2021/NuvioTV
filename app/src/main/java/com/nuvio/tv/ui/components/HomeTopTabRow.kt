@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.home.HomeTab

@Composable
fun HomeTopTabRow(
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null
) {
    Box(
        modifier = modifier
            .zIndex(10f)
            .then(
                if (downFocusRequester != null) {
                    Modifier.focusProperties { down = downFocusRequester }
                } else Modifier
            )
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0x40121418))
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                RoundedCornerShape(24.dp)
            )
            .padding(4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (downFocusRequester != null) {
                Modifier.focusProperties { down = downFocusRequester }
            } else Modifier
        ) {
            HomeTopTabItem(
                title = stringResource(R.string.home_tab_tv_shows),
                isSelected = selectedTab == HomeTab.TV_SHOWS,
                onClick = { onTabSelected(HomeTab.TV_SHOWS) },
                focusRequester = focusRequester,
                downFocusRequester = downFocusRequester
            )
            HomeTopTabItem(
                title = stringResource(R.string.home_tab_movies),
                isSelected = selectedTab == HomeTab.MOVIES,
                onClick = { onTabSelected(HomeTab.MOVIES) },
                downFocusRequester = downFocusRequester
            )
        }
    }
}

@Composable
private fun HomeTopTabItem(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = tween(150),
        label = "tab_scale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White.copy(alpha = 0.28f)
            isSelected -> Color.White.copy(alpha = 0.14f)
            else -> Color.Transparent
        },
        animationSpec = tween(150),
        label = "tab_bg"
    )

    val textColor by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White
            isSelected -> Color.White
            else -> Color.White.copy(alpha = 0.52f)
        },
        animationSpec = tween(150),
        label = "tab_text"
    )

    val borderStroke = when {
        isFocused -> BorderStroke(1.5.dp, Color.White.copy(alpha = 0.85f))
        isSelected -> BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
        else -> null
    }

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .then(
                if (borderStroke != null) {
                    Modifier.border(borderStroke, RoundedCornerShape(18.dp))
                } else Modifier
            )
            .background(backgroundColor)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (downFocusRequester != null) {
                    Modifier.focusProperties { down = downFocusRequester }
                } else Modifier
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (isSelected || isFocused) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp,
                letterSpacing = 0.4.sp
            ),
            color = textColor
        )
    }
}
