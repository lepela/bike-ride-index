package dev.lepela.rideindex.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 한 모델의 슬롯 창 값. pipeline/bri/engine.py 의 ModelWindow 와 같은 키. */
@Serializable
data class ModelWindow(
    val temp: List<Double?> = emptyList(),
    val apparent: List<Double?> = emptyList(),
    val dewpoint: List<Double?> = emptyList(),
    val precip: List<Double?> = emptyList(),
    val snowfall: List<Double?> = emptyList(),
    val gust: List<Double?> = emptyList(),
    @SerialName("surface_temp") val surfaceTemp: List<Double?> = emptyList(),
    val pty: List<Double?> = emptyList(),
    @SerialName("past24_precip") val past24Precip: Double? = null,
    @SerialName("past48_snowfall") val past48Snowfall: Double? = null,
)

data class PointFeatures(
    val nModels: Int,
    val tempC: Double?,
    val apparentC: Double?,
    val spreadC: Double?,
    val surfaceC: Double?,
    val precipMedian: Double?,
    val precipMin: Double?,
    val precipMax: Double?,
    val precipModels: Int,
    val snowModels: Int,
    val gustMs: Double?,
    val past24PrecipMm: Double?,
    val past48SnowCm: Double?,
)

data class SlotEval(
    val features: PointFeatures,
    val iceRisk: String,
    val ruleScore: Int,
    val confidence: String,
    val floorCap: Int?,
    val floorLabel: String?,
)

/**
 * pipeline/bri/engine.py 의 Kotlin 포팅. 두 구현은 rules/test_vectors 로 같은 결과를 확인한다.
 * 규칙을 바꾸면 양쪽과 테스트 케이스를 함께 고친다.
 */
object RuleEngine {
    private val iceOrder = mapOf("없음" to 0, "낮음" to 1, "높음" to 2)

    private fun List<Double?>.vals() = filterNotNull()
    private fun List<Double?>.minOrNullValue() = vals().minOrNull()
    private fun List<Double?>.maxOrNullValue() = vals().maxOrNull()

    /** Python statistics.median 과 같다: 짝수 개면 가운데 두 값의 평균. */
    fun median(xs: List<Double?>): Double? {
        val v = xs.vals().sorted()
        if (v.isEmpty()) return null
        val n = v.size
        return if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2.0
    }

    private data class Reduced(
        val hasTemp: Boolean, val temp: Double?, val apparent: Double?, val surface: Double?,
        val precip: Double?, val gust: Double?, val spread: Double?, val snow: Boolean,
        val past24: Double?, val past48: Double?,
    )

    private fun reduce(w: ModelWindow): Reduced {
        val spreads = w.temp.zip(w.dewpoint).mapNotNull { (t, d) -> if (t != null && d != null) t - d else null }
        val snow = w.snowfall.vals().any { it > 0 } || w.pty.vals().any { it == 2.0 || it == 3.0 }
        return Reduced(
            hasTemp = w.temp.vals().isNotEmpty(),
            temp = w.temp.minOrNullValue(),
            apparent = w.apparent.minOrNullValue(),
            surface = w.surfaceTemp.minOrNullValue(),
            precip = w.precip.maxOrNullValue(),
            gust = w.gust.maxOrNullValue(),
            spread = spreads.minOrNull(),
            snow = snow,
            past24 = w.past24Precip,
            past48 = w.past48Snowfall,
        )
    }

    fun pointFeatures(models: Map<String, ModelWindow>, rules: Rules): PointFeatures {
        val r = models.values.map(::reduce)
        val precips = r.map { it.precip }
        return PointFeatures(
            nModels = r.count { it.hasTemp },
            tempC = median(r.map { it.temp }),
            apparentC = median(r.map { it.apparent }),
            spreadC = median(r.map { it.spread }),
            surfaceC = median(r.map { it.surface }),
            precipMedian = median(precips),
            precipMin = precips.minOrNullValue(),
            precipMax = precips.maxOrNullValue(),
            precipModels = precips.count { it != null && it >= rules.wetPrecipMm },
            snowModels = r.count { it.snow },
            gustMs = median(r.map { it.gust }),
            past24PrecipMm = median(r.map { it.past24 }),
            past48SnowCm = median(r.map { it.past48 }),
        )
    }

    private fun le(a: Double?, b: Double) = a != null && a <= b
    private fun ge(a: Double?, b: Double) = a != null && a >= b

    fun iceRisk(pf: PointFeatures, rules: Rules): String {
        val past48 = pf.past48SnowCm
        if (past48 != null && past48 > 0 &&
            (le(pf.tempC, rules.highTempMax) || le(pf.surfaceC, rules.highSurfaceMax))
        ) return "높음"
        val wet = ge(pf.past24PrecipMm, rules.lowPrecip24Min) && le(pf.tempC, rules.lowTempMax)
        val frost = le(pf.tempC, rules.frostTempMax) && le(pf.spreadC, rules.frostSpreadMax)
        return if (wet || frost) "낮음" else "없음"
    }

    private fun maxN(a: Double?, b: Double?) = listOfNotNull(a, b).maxOrNull()
    private fun minN(a: Double?, b: Double?) = listOfNotNull(a, b).minOrNull()

    private fun combine(a: PointFeatures, b: PointFeatures) = PointFeatures(
        nModels = minOf(a.nModels, b.nModels),
        tempC = minN(a.tempC, b.tempC),
        apparentC = minN(a.apparentC, b.apparentC),
        spreadC = minN(a.spreadC, b.spreadC),
        surfaceC = minN(a.surfaceC, b.surfaceC),
        precipMedian = maxN(a.precipMedian, b.precipMedian),
        precipMin = minN(a.precipMin, b.precipMin),
        precipMax = maxN(a.precipMax, b.precipMax),
        precipModels = maxOf(a.precipModels, b.precipModels),
        snowModels = maxOf(a.snowModels, b.snowModels),
        gustMs = maxN(a.gustMs, b.gustMs),
        past24PrecipMm = maxN(a.past24PrecipMm, b.past24PrecipMm),
        past48SnowCm = maxN(a.past48SnowCm, b.past48SnowCm),
    )

    private fun ruleScore(f: PointFeatures, ice: String, rules: Rules): Int {
        var penalty = 0
        f.precipMedian?.let { pm ->
            val step = rules.precipSteps.first { (limit, _) -> limit == null || pm < limit }
            penalty += step.second
        }
        penalty += when {
            f.snowModels >= 2 -> rules.snowMulti
            f.snowModels == 1 -> rules.snowSingle
            else -> 0
        }
        penalty += when (ice) {
            "높음" -> rules.iceHigh
            "낮음" -> rules.iceLow
            else -> 0
        }
        (f.apparentC ?: f.tempC)?.let { cold ->
            penalty += when {
                cold <= rules.veryColdApparentC -> rules.veryColdPenalty
                cold <= rules.coldApparentC -> rules.coldPenalty
                else -> 0
            }
        }
        if (ge(f.gustMs, rules.gustMs)) penalty += rules.gustPenalty
        return (100 - penalty).coerceIn(0, 100)
    }

    private fun confidence(f: PointFeatures): String = when {
        f.snowModels in 1 until f.nModels -> "낮음"
        f.precipModels in 1 until f.nModels -> "보통"
        else -> "높음"
    }

    private fun floor(f: PointFeatures, ice: String, rules: Rules): Pair<Int?, String?> {
        val candidates = buildList {
            if (f.snowModels >= 2) add(rules.floorSnowMulti to "snow")
            if (ice == "높음") add(rules.floorIceHigh to "ice")
            if (ice == "낮음") add(rules.floorIceLow to "ice")
            if (f.snowModels == 1) add(rules.floorSnowSingle to "snow")
        }
        if (candidates.isEmpty()) return null to null
        var best = candidates.first()
        for (c in candidates.drop(1)) if (c.first < best.first) best = c
        return best
    }

    fun evaluateSlot(points: Map<String, Map<String, ModelWindow>>, rules: Rules): SlotEval {
        val feats = points.values.map { pointFeatures(it, rules) }
        val ice = feats.map { iceRisk(it, rules) }.maxBy { iceOrder.getValue(it) }
        val combined = feats.reduce(::combine)
        val (cap, label) = floor(combined, ice, rules)
        return SlotEval(combined, ice, ruleScore(combined, ice, rules), confidence(combined), cap, label)
    }

    fun applyFloor(score: Int, cap: Int?, label: String?): Pair<Int, String?> =
        if (cap != null && score > cap) cap to label else score to null

    fun grade(score: Int, rules: Rules): String =
        rules.grades.firstOrNull { score >= it.first }?.second ?: rules.grades.last().second
}
