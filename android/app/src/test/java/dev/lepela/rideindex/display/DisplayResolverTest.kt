package dev.lepela.rideindex.display

import dev.lepela.rideindex.model.Published
import dev.lepela.rideindex.model.PublishedSlot
import dev.lepela.rideindex.model.SlotWeather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DisplayResolverTest {
    private val today = LocalDate.of(2026, 11, 24)

    private fun slot(id: String, date: String, score: Int) = PublishedSlot(
        id = id, date = date, time = if (id == "commute_in") "08:30" else "18:30", score = score,
        grade = "주의", confidence = "보통", summary = "요약 $score", ruleScore = score,
        weather = SlotWeather(iceRisk = "없음"),
    )

    private fun pub(source: String, vararg slots: PublishedSlot) =
        Published(generatedAt = "2026-11-24T06:34:00+09:00", run = "am", source = source, slots = slots.toList())

    private fun both(source: String, date: String, a: Int, b: Int) =
        pub(source, slot("commute_in", date, a), slot("commute_out", date, b))

    @Test
    fun todayPublishedWins() {
        val d = DisplayResolver.resolve(today, both("claude", "2026-11-24", 72, 60), both("device", "2026-11-24", 1, 2), null)
        assertEquals(listOf(72, 60), d.slots.map { it.score })
        assertTrue(d.slots.all { it.origin == Origin.CLAUDE && !it.stale })
        assertEquals("2026-11-24T06:34:00+09:00", d.updatedAt)
    }

    @Test
    fun ruleSourceLabel() {
        val d = DisplayResolver.resolve(today, both("rule", "2026-11-24", 50, 50), null, null)
        assertTrue(d.slots.all { it.origin == Origin.RULE })
    }

    @Test
    fun yesterdayUsesDevice() {
        val d = DisplayResolver.resolve(today, both("claude", "2026-11-23", 72, 60), both("device", "2026-11-24", 80, 81), null)
        assertEquals(listOf(80, 81), d.slots.map { it.score })
        assertTrue(d.slots.all { it.origin == Origin.DEVICE && !it.stale })
    }

    @Test
    fun noDeviceUsesStale() {
        val old = both("claude", "2026-11-23", 72, 60)
        val d = DisplayResolver.resolve(today, old, null, old)
        assertEquals(listOf(72, 60), d.slots.map { it.score })
        assertTrue(d.slots.all { it.stale && it.origin == Origin.CLAUDE })
    }

    @Test
    fun missingSlotUsesDevice() {
        val pm = pub("claude", slot("commute_out", "2026-11-24", 66))
        val d = DisplayResolver.resolve(today, pm, both("device", "2026-11-24", 80, 81), null)
        assertEquals(Origin.DEVICE, d.slots[0].origin)
        assertEquals(80, d.slots[0].score)
        assertEquals(Origin.CLAUDE, d.slots[1].origin)
        assertEquals(66, d.slots[1].score)
        assertFalse(d.slots[1].stale)
    }

    @Test
    fun nothingGivesNone() {
        val d = DisplayResolver.resolve(today, null, null, null)
        assertEquals(listOf("commute_in", "commute_out"), d.slots.map { it.id })
        assertTrue(d.slots.all { it.origin == Origin.NONE && it.score == null })
        assertNull(d.updatedAt)
    }
}
