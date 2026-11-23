package dev.lepela.rideindex.data

import dev.lepela.rideindex.engine.LatLon
import dev.lepela.rideindex.engine.ModelWindow
import dev.lepela.rideindex.engine.RuleEngine
import dev.lepela.rideindex.engine.Rules
import dev.lepela.rideindex.engine.SlotEval
import dev.lepela.rideindex.engine.ModelSeries
import dev.lepela.rideindex.engine.buildModelWindows
import dev.lepela.rideindex.model.ModelHours
import dev.lepela.rideindex.model.PrecipRange
import dev.lepela.rideindex.model.Published
import dev.lepela.rideindex.model.PublishedSlot
import dev.lepela.rideindex.model.SlotWeather
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 게시값이 없을 때 기기 안에서 규칙 점수를 계산한다. Claude 는 거치지 않는다.
 * 창·결빙·하한 계산은 pipeline/bri/__main__.py prepare + publish.final_slot 과 같다.
 */
object DeviceCalculator {
    private val KST = ZoneId.of("Asia/Seoul")
    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

    /** 두 지점의 Open-Meteo 를 받아 계산한다. 실패하면 null. */
    suspend fun fetchAndCompute(today: LocalDate, home: LatLon, work: LatLon, rules: Rules): Published? {
        val h = OpenMeteo.fetch(home.lat, home.lon, rules.openmeteoModels) ?: return null
        val w = OpenMeteo.fetch(work.lat, work.lon, rules.openmeteoModels) ?: return null
        return compute(today, mapOf("home" to h, "work" to w), rules)
    }

    /** 경계 시각을 시 단위로 내린다(08:30 → 08:00). */
    private fun bound(date: LocalDate, hhmm: String): LocalDateTime =
        date.atTime(hhmm.substringBefore(":").toInt(), 0)

    fun compute(today: LocalDate, series: Map<String, List<ModelSeries>>, rules: Rules): Published {
        val slots = listOf("commute_in", "commute_out").map { id ->
            val conf = rules.slots.getValue(id)
            val points = mutableMapOf<String, Map<String, ModelWindow>>()
            val hourly = mutableMapOf<String, Map<String, ModelHours>>()
            for ((point, range) in conf.points) {
                val start = bound(today, range.first)
                val end = bound(today, range.second)
                val windows = buildModelWindows(series[point].orEmpty(), start, end)
                points[point] = windows
                val hours = generateSequence(start) { it.plusHours(1) }.takeWhile { !it.isAfter(end) }
                    .map { it.format(HHMM) }.toList()
                hourly[point] = windows.mapValues { (_, w) ->
                    ModelHours(hours.take(w.temp.size), w.temp, w.precip, w.snowfall, w.pty)
                }
            }
            val ev = RuleEngine.evaluateSlot(points, rules)
            slot(id, today.toString(), conf.time, ev, hourly, rules)
        }
        val used = rules.openmeteoModels.filter { m -> series.values.all { list -> list.any { it.model == m } } }
        return Published(
            generatedAt = ZonedDateTime.now(KST).withNano(0).toOffsetDateTime().toString(),
            run = "device",
            source = "device",
            modelsUsed = used,
            slots = slots,
        )
    }

    private fun Double?.r1(): Double? = this?.let { (it * 10).roundToInt() / 10.0 }

    private fun slot(
        id: String, date: String, time: String, ev: SlotEval,
        hourly: Map<String, Map<String, ModelHours>>, rules: Rules,
    ): PublishedSlot {
        val (score, floorApplied) = RuleEngine.applyFloor(ev.ruleScore, ev.floorCap, ev.floorLabel)
        val f = ev.features
        return PublishedSlot(
            id = id, date = date, time = time, score = score,
            grade = RuleEngine.grade(score, rules),
            confidence = ev.confidence,
            summary = summary(ev),
            reasons = listOf("기기에서 규칙으로 계산", "결빙 위험 ${ev.iceRisk}"),
            ruleScore = ev.ruleScore,
            floorApplied = floorApplied,
            weather = SlotWeather(
                tempC = f.tempC.r1(), apparentC = f.apparentC.r1(),
                precipMmH = PrecipRange(f.precipMin.r1(), f.precipMax.r1()),
                snowModels = f.snowModels, iceRisk = ev.iceRisk, gustMs = f.gustMs.r1(),
            ),
            weatherByModel = hourly,
        )
    }

    /** pipeline/bri/publish.py rule_summary 와 같은 형식. */
    fun summary(ev: SlotEval): String {
        val f = ev.features
        val parts = mutableListOf<String>()
        val pm = f.precipMedian
        parts += if (pm == null || pm == 0.0) "강수 없음" else String.format(java.util.Locale.US, "강수 %.1fmm/h", pm)
        if (f.snowModels > 0) parts += "적설 예보 모델 ${f.snowModels}개"
        parts += "결빙 ${ev.iceRisk}"
        (f.apparentC ?: f.tempC)?.let { parts += String.format(java.util.Locale.US, "체감 %.0f°C", it) }
        return parts.joinToString(" · ").take(60)
    }
}
