package dev.lepela.rideindex.data

import dev.lepela.rideindex.engine.loadRules
import dev.lepela.rideindex.model.parsePublished
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DataLayerTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val rules by lazy { loadRules(text("rules.json")) }
    private val models = listOf("ecmwf_ifs025", "gfs_seamless", "icon_seamless", "jma_seamless")

    @Test
    fun parsesOpenMeteoFixture() {
        val series = OpenMeteo.parse(text("openmeteo_seoul.json"), models)
        assertEquals(models, series.map { it.model })
        assertEquals(96, series[0].times.size)
        assertTrue(series[3].values.getValue("gust").all { it == null }) // JMA 돌풍 없음
        assertTrue(series[1].values.getValue("surface_temp").all { it == null }) // GFS 지표면 없음
    }

    @Test
    fun dropsAllNullModel() {
        val body = """{"hourly":{"time":["2026-11-24T08:00"],"temperature_2m_kma_seamless":[null],
            "temperature_2m_icon_seamless":[3.0]}}"""
        assertEquals(listOf("icon_seamless"), OpenMeteo.parse(body, listOf("kma_seamless", "icon_seamless")).map { it.model })
    }

    @Test
    fun urlMatchesPython() {
        val url = OpenMeteo.buildUrl(37.5, 127.0, listOf("ecmwf_ifs025", "gfs_seamless"))
        for (part in listOf("wind_speed_unit=ms", "past_days=2", "forecast_days=2", "timezone=Asia%2FSeoul",
            "models=ecmwf_ifs025%2Cgfs_seamless", "soil_temperature_0cm")) {
            assertTrue(part, url.contains(part))
        }
    }

    /** 같은 고정 응답에서 Python 게시 샘플(docs/sample)의 rule_score 와 같아야 한다. */
    @Test
    fun deviceCalculationMatchesPythonSample() {
        val series = OpenMeteo.parse(text("openmeteo_seoul.json"), models)
        val p = DeviceCalculator.compute(LocalDate.of(2026, 11, 24), mapOf("home" to series, "work" to series), rules)
        val sample = parsePublished(text("latest.sample.json"))
        assertEquals("device", p.source)
        assertEquals(listOf("commute_in", "commute_out"), p.slots.map { it.id })
        for (id in listOf("commute_in", "commute_out")) {
            val mine = p.slots.first { it.id == id }
            val py = sample.slots.first { it.id == id }
            assertEquals(id, py.ruleScore, mine.ruleScore)
            assertEquals(id, py.weather.iceRisk, mine.weather.iceRisk)
            assertEquals(id, py.weather.tempC!!, mine.weather.tempC!!, 0.05)
            assertTrue(mine.summary.isNotBlank() && mine.summary.length <= 60)
            assertEquals("2026-11-24", mine.date)
        }
    }

    @Test
    fun pagesUrlJoin() {
        assertEquals("https://x.github.io/r/latest.json", PagesClient.urlFor("https://x.github.io/r", "latest.json"))
        assertEquals("https://x.github.io/r/latest.json", PagesClient.urlFor("https://x.github.io/r/", "latest.json"))
    }
}
