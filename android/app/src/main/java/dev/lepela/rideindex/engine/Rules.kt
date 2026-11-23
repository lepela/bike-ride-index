package dev.lepela.rideindex.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** rules/rules.json 중 엔진과 앱이 쓰는 값. 원본은 저장소의 rules/rules.json 하나다. */
data class Rules(
    val openmeteoModels: List<String>,
    val weekdays: List<Int>,
    val slots: Map<String, SlotRule>,
    val wetPrecipMm: Double,
    val precipSteps: List<Pair<Double?, Int>>,
    val snowSingle: Int,
    val snowMulti: Int,
    val iceLow: Int,
    val iceHigh: Int,
    val coldApparentC: Double,
    val coldPenalty: Int,
    val veryColdApparentC: Double,
    val veryColdPenalty: Int,
    val gustMs: Double,
    val gustPenalty: Int,
    val highTempMax: Double,
    val highSurfaceMax: Double,
    val lowPrecip24Min: Double,
    val lowTempMax: Double,
    val frostTempMax: Double,
    val frostSpreadMax: Double,
    val floorSnowMulti: Int,
    val floorIceHigh: Int,
    val floorIceLow: Int,
    val floorSnowSingle: Int,
    val grades: List<Pair<Int, String>>,
)

/** 슬롯 시각과 지점별 창(예: home → 08:00..09:00). */
data class SlotRule(val time: String, val points: Map<String, Pair<String, String>>)

fun loadRules(json: String): Rules {
    val root = Json.parseToJsonElement(json).jsonObject
    val score = root.getValue("score").jsonObject
    val ice = root.getValue("ice").jsonObject
    val floors = root.getValue("floors").jsonObject
    fun JsonObject.d(k: String) = getValue(k).jsonPrimitive.double
    fun JsonObject.i(k: String) = getValue(k).jsonPrimitive.int
    return Rules(
        openmeteoModels = root.getValue("openmeteo_models").jsonArray.map { it.jsonPrimitive.content },
        weekdays = root.getValue("weekdays").jsonArray.map { it.jsonPrimitive.int },
        slots = root.getValue("slots").jsonObject.mapValues { (_, v) ->
            val o = v.jsonObject
            SlotRule(
                time = o.getValue("time").jsonPrimitive.content,
                points = o.getValue("points").jsonObject.mapValues { (_, p) ->
                    val a = p.jsonArray
                    a[0].jsonPrimitive.content to a[1].jsonPrimitive.content
                },
            )
        },
        wetPrecipMm = root.d("wet_precip_mm"),
        precipSteps = score.getValue("precip_steps").jsonArray.map {
            val a = it as JsonArray
            (if (a[0] is JsonNull) null else a[0].jsonPrimitive.double) to a[1].jsonPrimitive.int
        },
        snowSingle = score.i("snow_single"),
        snowMulti = score.i("snow_multi"),
        iceLow = score.i("ice_low"),
        iceHigh = score.i("ice_high"),
        coldApparentC = score.d("cold_apparent_c"),
        coldPenalty = score.i("cold_penalty"),
        veryColdApparentC = score.d("very_cold_apparent_c"),
        veryColdPenalty = score.i("very_cold_penalty"),
        gustMs = score.d("gust_ms"),
        gustPenalty = score.i("gust_penalty"),
        highTempMax = ice.d("high_temp_max"),
        highSurfaceMax = ice.d("high_surface_max"),
        lowPrecip24Min = ice.d("low_precip24_min"),
        lowTempMax = ice.d("low_temp_max"),
        frostTempMax = ice.d("frost_temp_max"),
        frostSpreadMax = ice.d("frost_spread_max"),
        floorSnowMulti = floors.i("snow_multi"),
        floorIceHigh = floors.i("ice_high"),
        floorIceLow = floors.i("ice_low"),
        floorSnowSingle = floors.i("snow_single"),
        grades = root.getValue("grades").jsonArray.map {
            val a = it.jsonArray
            a[0].jsonPrimitive.int to a[1].jsonPrimitive.content
        },
    )
}
