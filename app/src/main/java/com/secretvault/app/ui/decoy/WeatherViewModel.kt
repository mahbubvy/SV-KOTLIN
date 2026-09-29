package com.secretvault.app.ui.decoy

import androidx.lifecycle.ViewModel
import com.secretvault.app.core.security.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CurrentWeather(
    val city: String = "San Francisco",
    val temperatureCelsius: Int = 21,
    val condition: String = "Partly Cloudy",
    val highTemp: Int = 24,
    val lowTemp: Int = 15,
    val feelsLike: Int = 21
)

data class HourlyForecast(
    val timeLabel: String,
    val temperature: Int,
    val condition: String,
    val precipitationPercent: Int
)

data class DailyForecast(
    val dayLabel: String,
    val dateLabel: String,
    val highTemp: Int,
    val lowTemp: Int,
    val condition: String
)

data class WeatherMetric(
    val title: String,
    val value: String,
    val unit: String,
    val description: String,
    val isStealthTrigger: Boolean = false
)

data class DecoyWeatherState(
    val current: CurrentWeather = CurrentWeather(),
    val hourly: List<HourlyForecast> = emptyList(),
    val daily: List<DailyForecast> = emptyList(),
    val metrics: List<WeatherMetric> = emptyList(),
    val isKeepUnlockedActive: Boolean = false
)

class WeatherViewModel(
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _state = MutableStateFlow(DecoyWeatherState())
    val state: StateFlow<DecoyWeatherState> = _state.asStateFlow()

    init {
        loadMockData()
    }

    fun syncKeepUnlockedState() {
        _state.value = _state.value.copy(
            isKeepUnlockedActive = sessionManager.keepUnlocked.value && sessionManager.isUnlocked.value
        )
    }

    private fun loadMockData() {
        val hourlyList = listOf(
            HourlyForecast("Now", 21, "Partly Cloudy", 0),
            HourlyForecast("12 PM", 22, "Sunny", 0),
            HourlyForecast("1 PM", 24, "Sunny", 0),
            HourlyForecast("2 PM", 24, "Partly Cloudy", 10),
            HourlyForecast("3 PM", 23, "Partly Cloudy", 10),
            HourlyForecast("4 PM", 22, "Mostly Cloudy", 20),
            HourlyForecast("5 PM", 20, "Cloudy", 20),
            HourlyForecast("6 PM", 18, "Sunset", 0),
            HourlyForecast("7 PM", 17, "Clear", 0),
            HourlyForecast("8 PM", 16, "Clear", 0)
        )

        val dailyList = listOf(
            DailyForecast("Today", "Aug 29", 24, 15, "Partly Cloudy"),
            DailyForecast("Sun", "Aug 30", 25, 16, "Sunny"),
            DailyForecast("Mon", "Aug 31", 23, 14, "Mostly Sunny"),
            DailyForecast("Tue", "Sep 1", 21, 13, "Rain Shower"),
            DailyForecast("Wed", "Sep 2", 22, 14, "Partly Cloudy"),
            DailyForecast("Thu", "Sep 3", 26, 17, "Sunny"),
            DailyForecast("Fri", "Sep 4", 25, 16, "Sunny")
        )

        val metricsList = listOf(
            WeatherMetric("Visibility", "10", "km", "Clear view", isStealthTrigger = true),
            WeatherMetric("Humidity", "62", "%", "Moderate moisture"),
            WeatherMetric("Wind", "14", "km/h", "NW gentle breeze"),
            WeatherMetric("Air Quality", "28", "AQI", "Good air quality"),
            WeatherMetric("Pressure", "1014", "hPa", "Normal barometric"),
            WeatherMetric("UV Index", "4", "MOD", "Sun protection advised")
        )

        _state.value = DecoyWeatherState(
            current = CurrentWeather(),
            hourly = hourlyList,
            daily = dailyList,
            metrics = metricsList,
            isKeepUnlockedActive = sessionManager.keepUnlocked.value && sessionManager.isUnlocked.value
        )
    }
}
