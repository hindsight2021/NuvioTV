package com.nuvio.tv.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalDateTime.now()
            delay(1000L)
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
                color = Color(0x730A0A0A),
                shape = RoundedCornerShape(12.dp)
            )
            .border(
                width = 1.dp,
                color = Color(0x33FFFFFF),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = timeString,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            ),
            color = NuvioTheme.colors.TextPrimary
        )
        Box(
            modifier = Modifier
                .size(4.dp)
                .background(Color.White.copy(alpha = 0.4f), shape = CircleShape)
        )
        Text(
            text = dateString,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium
            ),
            color = NuvioTheme.colors.TextSecondary
        )
    }
}
