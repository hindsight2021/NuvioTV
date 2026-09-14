package com.nuvio.tv.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.ha.HomeAssistantWeatherService
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeClockWithDate(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf(LocalDateTime.now()) }
    val weatherState by HomeAssistantWeatherService.weatherState.collectAsState()

    // Clock update loop
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalDateTime.now()
            delay(1000L)
        }
    }

    // Home Assistant weather refresh loop
    LaunchedEffect(Unit) {
        while (true) {
            HomeAssistantWeatherService.refresh()
            delay(60_000L)
        }
    }

    val locale = remember { Locale.getDefault() }
    val is24Hour = remember(context) {
        DateFormat.is24HourFormat(context)
    }

    val timeFormatter = remember(is24Hour, locale) {
        val skeleton = if (is24Hour) "Hm" else "hm"
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
        DateTimeFormatter.ofPattern(pattern, locale)
    }

    val dateFormatter = remember(locale) {
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEMMMd")
        DateTimeFormatter.ofPattern(pattern, locale)
    }

    val timeString = remember(currentTime, timeFormatter) {
        currentTime.format(timeFormatter)
    }
    val dateString = remember(currentTime, dateFormatter) {
        currentTime.format(dateFormatter)
    }

    Row(
        modifier = modifier
            .background(
                color = Color(0x800A0C10),
                shape = RoundedCornerShape(14.dp)
            )
            .border(
                width = 1.dp,
                color = Color(0x33FFFFFF),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Time (bigger) & Date (smaller)
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = timeString,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = NuvioTheme.colors.TextPrimary
            )
            Text(
                text = dateString,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.85f)
            )
        }

        if (weatherState.isAvailable) {
            // Subtle vertical separator
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .width(1.dp)
                    .background(Color(0x33FFFFFF))
            )

            // Right column: Outdoor weather/temp & Indoor temp
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center
            ) {
                // Outdoor: Icon + Temp + Condition
                if (weatherState.outdoorTemp.isNotBlank() || weatherState.condition.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (weatherState.conditionIcon.isNotBlank()) {
                            Text(
                                text = weatherState.conditionIcon,
                                fontSize = 14.sp
                            )
                        }
                        if (weatherState.outdoorTemp.isNotBlank()) {
                            Text(
                                text = weatherState.outdoorTemp,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = NuvioTheme.colors.TextPrimary
                            )
                        }
                        if (weatherState.condition.isNotBlank()) {
                            Text(
                                text = weatherState.condition,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Normal
                                ),
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                    }
                }

                // Indoor: House icon + Indoor Temp
                if (weatherState.indoorTemp.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = "🏠",
                            fontSize = 12.sp
                        )
                        Text(
                            text = "${weatherState.indoorTemp} In",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = Color(0xFF81D4FA)
                        )
                    }
                }
            }
        }
    }
}
