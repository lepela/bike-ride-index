package dev.lepela.rideindex.data

import dev.lepela.rideindex.engine.ModelSeries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.util.Locale

/** Open-Meteo 다중 모델 조회. 요청 형식은 pipeline/bri/openmeteo.py 와 같다. */
object OpenMeteo {
    private const val BASE = "https://api.open-meteo.com/v1/forecast"

    private val VAR_MAP = linkedMapOf(
        "temperature_2m" to "temp",
        "apparent_temperature" to "apparent",
        "dew_point_2m" to "dewpoint",
        "precipitation" to "precip",
        "snowfall" to "snowfall",
        "precipitation_probability" to "pop",
        "wind_gusts_10m" to "gust",
        "soil_temperature_0cm" to "surface_temp",
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun buildUrl(lat: Double, lon: Double, models: List<String>): String {
        val params = listOf(
            "latitude" to String.format(Locale.US, "%.4f", lat),
            "longitude" to String.format(Locale.US, "%.4f", lon),
            "hourly" to VAR_MAP.keys.joinToString(","),
            "models" to models.joinToString(","),
            "past_days" to "2",
            "forecast_days" to "2",
            "timezone" to "Asia/Seoul",
            "wind_speed_unit" to "ms",
        )
        return BASE + "?" + params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    fun parse(body: String, models: List<String>): List<ModelSeries> {
        val hourly = Json.parseToJsonElement(body).jsonObject.getValue("hourly").jsonObject
        val times = hourly.getValue("time").jsonArray.map { LocalDateTime.parse(it.jsonPrimitive.content) }
        return models.mapNotNull { model ->
            val values = VAR_MAP.entries.associate { (src, name) ->
                val arr = hourly["${src}_$model"]?.jsonArray
                name to (arr?.map { if (it is JsonNull) null else it.jsonPrimitive.doubleOrNull }
                    ?: List(times.size) { null })
            }
            if (values.getValue("temp").all { it == null }) null else ModelSeries(model, times, values)
        }
    }

    /** 실패하면 한 번 다시 시도하고, 그래도 실패하면 null. */
    suspend fun fetch(lat: Double, lon: Double, models: List<String>): List<ModelSeries>? =
        withContext(Dispatchers.IO) {
            repeat(2) {
                runCatching { parse(httpGet(buildUrl(lat, lon, models)), models) }.onSuccess { return@withContext it }
            }
            null
        }
}

internal fun httpGet(url: String, timeoutMs: Int = 15_000): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = timeoutMs
    conn.readTimeout = timeoutMs
    conn.setRequestProperty("Cache-Control", "no-cache")
    try {
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
