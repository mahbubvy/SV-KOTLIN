package com.secretvault.app.ui.decoy.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretvault.app.ui.decoy.HourlyForecast
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.WeatherSky
import com.secretvault.app.ui.theme.WeatherSurface

@Composable
fun WeatherHourlyStrip(
    hourly: List<HourlyForecast>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = WeatherSurface.copy(alpha = 0.8f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "HOURLY FORECAST",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextMuted,
                letterSpacing = 1.sp
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                hourly.forEach { item ->
                    HourlyItem(item = item)
                }
            }
        }
    }
}

@Composable
private fun HourlyItem(item: HourlyForecast) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.width(52.dp)
    ) {
        Text(
            text = item.timeLabel,
            fontSize = 13.sp,
            color = TextSecondary
        )

        val icon = if (item.condition.contains("Sunny", ignoreCase = true)) {
            Icons.Default.WbSunny
        } else {
            Icons.Default.Cloud
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = WeatherSky
        )

        if (item.precipitationPercent > 0) {
            Text(
                text = "${item.precipitationPercent}%",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = WeatherSky
            )
        }

        Text(
            text = "${item.temperature}°",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
    }
}
