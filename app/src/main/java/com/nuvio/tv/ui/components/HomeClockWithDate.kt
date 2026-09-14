package com.nuvio.tv.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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

    val timeDigits = remember(currentTime, is24Hour, locale) {
        val pattern = if (is24Hour) "HH:mm" else "h:mm"
        currentTime.format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    val amPmIndicator = remember(currentTime, is24Hour, locale) {
        if (is24Hour) "" else {
            currentTime.format(DateTimeFormatter.ofPattern("a", locale))
                .uppercase()
                .replace(".", "")
                .trim()
        }
    }

    val dateString = remember(currentTime, locale) {
        val skeleton = "EEEEMMMd"
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
        currentTime.format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    val scandiShadow = remember {
        Shadow(
            color = Color(0x99000000),
            offset = Offset(0f, 1f),
            blurRadius = 4f
        )
    }

    Column(
        modifier = modifier
            .background(
                color = Color(0x1F0A0C12),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // Line 1: Time • Outdoor Weather
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = timeDigits,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                    shadow = scandiShadow
                ),
                color = Color(0xFFF7F7F8)
            )

            if (amPmIndicator.isNotBlank()) {
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = amPmIndicator,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 0.5.sp,
                        shadow = scandiShadow
                    ),
                    color = Color(0xB3FFFFFF)
                )
            }

            if (weatherState.isAvailable && (weatherState.outdoorTemp.isNotBlank() || weatherState.condition.isNotBlank())) {
                Text(
                    text = "  •  ",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 10.sp,
                        shadow = scandiShadow
                    ),
                    color = Color(0x40FFFFFF)
                )

                if (weatherState.conditionIcon.isNotBlank()) {
                    Text(
                        text = weatherState.conditionIcon,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 3.dp)
                    )
                }

                if (weatherState.outdoorTemp.isNotBlank()) {
                    Text(
                        text = weatherState.outdoorTemp,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            shadow = scandiShadow
                        ),
                        color = Color(0xFFEEEEF0)
                    )
                }

                if (weatherState.condition.isNotBlank()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = weatherState.condition,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Light,
                            shadow = scandiShadow
                        ),
                        color = Color(0xB3FFFFFF)
                    )
                }
            }
        }

        // Line 2: Date • Indoor Climate
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = dateString,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.2.sp,
                    shadow = scandiShadow
                ),
                color = Color(0x99FFFFFF)
            )

            if (weatherState.isAvailable && weatherState.indoorTemp.isNotBlank()) {
                Text(
                    text = "  •  ",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 10.sp,
                        shadow = scandiShadow
                    ),
                    color = Color(0x40FFFFFF)
                )

                Text(
                    text = "🏠",
                    fontSize = 10.sp,
                    modifier = Modifier.padding(end = 3.dp)
                )

                Text(
                    text = weatherState.indoorTemp,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.2.sp,
                        shadow = scandiShadow
                    ),
                    color = Color(0xFF81D4FA)
                )
            }
        }
    }
}
