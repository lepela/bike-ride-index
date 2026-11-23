package dev.lepela.rideindex.engine

import java.time.LocalDateTime

/** 한 지점·한 모델의 시간별 값. times 는 KST 현지 시각. (pipeline/bri/series.py) */
data class ModelSeries(
    val model: String,
    val times: List<LocalDateTime>,
    val values: Map<String, List<Double?>>,
)

val WINDOW_VARS = listOf("temp", "apparent", "dewpoint", "precip", "snowfall", "gust", "surface_temp", "pty")

/** start <= t <= end 인 시각만 변수별로. */
fun window(s: ModelSeries, start: LocalDateTime, end: LocalDateTime): Map<String, List<Double?>> {
    val idx = s.times.indices.filter { !s.times[it].isBefore(start) && !s.times[it].isAfter(end) }
    return s.values.mapValues { (_, v) -> idx.map { v[it] } }
}

/** [before - hours, before) 구간 합. 값이 하나도 없으면 null. */
fun pastSum(s: ModelSeries, variable: String, before: LocalDateTime, hours: Long): Double? {
    val vals = s.values[variable] ?: return null
    val start = before.minusHours(hours)
    val picked = s.times.indices
        .filter { !s.times[it].isBefore(start) && s.times[it].isBefore(before) }
        .mapNotNull { vals[it] }
    return if (picked.isEmpty()) null else picked.sum()
}

fun buildModelWindows(series: List<ModelSeries>, start: LocalDateTime, end: LocalDateTime): Map<String, ModelWindow> =
    series.associate { s ->
        val w = window(s, start, end)
        val n = w.values.firstOrNull()?.size ?: 0
        fun v(name: String) = w[name] ?: List(n) { null }
        s.model to ModelWindow(
            temp = v("temp"), apparent = v("apparent"), dewpoint = v("dewpoint"), precip = v("precip"),
            snowfall = v("snowfall"), gust = v("gust"), surfaceTemp = v("surface_temp"), pty = v("pty"),
            past24Precip = pastSum(s, "precip", start, 24),
            past48Snowfall = pastSum(s, "snowfall", start, 48),
        )
    }
