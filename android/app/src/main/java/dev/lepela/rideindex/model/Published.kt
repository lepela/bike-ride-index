package dev.lepela.rideindex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** docs/latest.json 형식(설계 5.6절). 키는 snake_case. */
@Serializable
data class Published(
    @SerialName("generated_at") val generatedAt: String,
    val run: String,
    val source: String,
    @SerialName("models_used") val modelsUsed: List<String> = emptyList(),
    val slots: List<PublishedSlot> = emptyList(),
)

@Serializable
data class PublishedSlot(
    val id: String,
    val date: String,
    val time: String,
    val score: Int,
    val grade: String,
    val confidence: String,
    val summary: String,
    val reasons: List<String> = emptyList(),
    @SerialName("rule_score") val ruleScore: Int,
    @SerialName("floor_applied") val floorApplied: String? = null,
    @SerialName("deviation_reason") val deviationReason: String? = null,
    val weather: SlotWeather,
    @SerialName("weather_by_model") val weatherByModel: Map<String, Map<String, ModelHours>> = emptyMap(),
)

@Serializable
data class SlotWeather(
    @SerialName("temp_c") val tempC: Double? = null,
    @SerialName("apparent_c") val apparentC: Double? = null,
    @SerialName("precip_mm_h") val precipMmH: PrecipRange = PrecipRange(),
    @SerialName("snow_models") val snowModels: Int = 0,
    @SerialName("ice_risk") val iceRisk: String,
    @SerialName("gust_ms") val gustMs: Double? = null,
)

@Serializable
data class PrecipRange(val min: Double? = null, val max: Double? = null)

@Serializable
data class ModelHours(
    val hours: List<String> = emptyList(),
    val temp: List<Double?> = emptyList(),
    val precip: List<Double?> = emptyList(),
    val snowfall: List<Double?> = emptyList(),
    val pty: List<Double?> = emptyList(),
)

val PublishedJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun parsePublished(text: String): Published = PublishedJson.decodeFromString(Published.serializer(), text)

fun Published.toJson(): String = PublishedJson.encodeToString(Published.serializer(), this)
