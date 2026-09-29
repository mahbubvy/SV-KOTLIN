package com.secretvault.app.ui.decoy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretvault.app.ui.decoy.components.WeatherHeader
import com.secretvault.app.ui.decoy.components.WeatherHourlyStrip
import com.secretvault.app.ui.decoy.components.WeatherMetricsGrid
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.WeatherBackground
import com.secretvault.app.ui.theme.WeatherSky
import com.secretvault.app.ui.theme.WeatherSurface

@Composable
fun WeatherHomeScreen(
    viewModel: WeatherViewModel,
    onVisibilityTrigger: () -> Unit,
    onCameraShortcut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.syncKeepUnlockedState()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WeatherBackground)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            androidx.compose.ui.graphics.Color(0xFF1E88E5).copy(alpha = 0.25f),
                            androidx.compose.ui.graphics.Color(0xFF0F172A).copy(alpha = 0f)
                        ),
                        center = androidx.compose.ui.geometry.Offset(800f, 200f),
                        radius = 1000f
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            WeatherHeader(
                current = state.current,
                isKeepUnlockedActive = state.isKeepUnlockedActive,
                onCameraShortcutClicked = onCameraShortcut
            )

            Spacer(modifier = Modifier.height(16.dp))

            WeatherHourlyStrip(hourly = state.hourly)

            Spacer(modifier = Modifier.height(16.dp))

            // 7-Day Forecast Card
            DailyForecastCard(daily = state.daily)

            Spacer(modifier = Modifier.height(16.dp))

            // Metrics Grid (Contains the secret Visibility trigger)
            WeatherMetricsGrid(
                metrics = state.metrics,
                onVisibilityClicked = onVisibilityTrigger
            )
        }
    }
}

@Composable
private fun DailyForecastCard(
    daily: List<DailyForecast>,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CalendarToday,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.padding(2.dp)
                )
                Text(
                    text = "7-DAY FORECAST",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            daily.forEachIndexed { index, item ->
                DailyForecastRow(item = item)
                if (index < daily.size - 1) {
                    HorizontalDivider(
                        color = WeatherBackground.copy(alpha = 0.5f),
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DailyForecastRow(item: DailyForecast) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = item.dayLabel,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )

        val icon = if (item.condition.contains("Sunny", ignoreCase = true)) {
            Icons.Default.WbSunny
        } else {
            Icons.Default.Cloud
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = WeatherSky,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1.5f)
        ) {
            Text(
                text = "${item.lowTemp}°",
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.padding(end = 12.dp)
            )
            Text(
                text = "${item.highTemp}°",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        }
    }
}
