package dev.lepela.rideindex.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishedTest {
    private fun resource(name: String): String =
        javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun parsesSample() {
        val p = parsePublished(resource("latest.sample.json"))
        assertTrue(p.slots.isNotEmpty())
        assertTrue(p.slots.all { it.score in 0..100 })
        val s = p.slots.first()
        assertEquals("commute_in", s.id)
        assertTrue(s.weatherByModel.isNotEmpty())
        assertTrue(s.weather.iceRisk.isNotEmpty())
    }

    @Test
    fun ignoresUnknownKeys() {
        val p = parsePublished(
            """{"generated_at":"x","run":"am","source":"rule","models_used":[],"slots":[],"new_key":1}"""
        )
        assertEquals("rule", p.source)
    }

    @Test
    fun specExampleSlot() {
        val p = parsePublished(
            """{"generated_at":"2026-11-24T06:34:00+09:00","run":"am","source":"claude",
               "models_used":["ecmwf_ifs025"],
               "slots":[{"id":"commute_in","date":"2026-11-24","time":"08:30","score":72,"grade":"주의",
               "confidence":"보통","summary":"약한 비","reasons":["a"],"rule_score":75,"floor_applied":null,
               "weather":{"temp_c":4.1,"apparent_c":1.2,"precip_mm_h":{"min":0.0,"max":0.8},
               "snow_models":0,"ice_risk":"없음","gust_ms":null}}]}"""
        )
        val s = p.slots.single()
        assertEquals(72, s.score)
        assertEquals(75, s.ruleScore)
        assertNull(s.floorApplied)
        assertNull(s.weather.gustMs)
        assertEquals(0.8, s.weather.precipMmH.max!!, 1e-9)
    }
}
