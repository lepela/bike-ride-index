package dev.lepela.rideindex.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class WindowsTest {
    private fun at(h: Int) = LocalDateTime.of(2026, 11, 24, h, 0)

    /** C1: 기상청처럼 발표 이후 값만 있는 시계열은 과거 합을 0이 아닌 null 로 둔다. */
    @Test
    fun pastValuesNullWhenSeriesDoesNotCoverWindow() {
        val times = (6..10).map(::at)
        val zeros = List<Double?>(5) { 0.0 }
        val s = ModelSeries("kma_vilage", times, mapOf("temp" to List(5) { -1.0 }, "precip" to zeros, "snowfall" to zeros))
        val w = buildModelWindows(listOf(s), at(8), at(9)).getValue("kma_vilage")
        assertNull(w.past24Precip)
        assertNull(w.past48Snowfall)
    }

    @Test
    fun pastSumWhenCovered() {
        val start = LocalDateTime.of(2026, 11, 22, 8, 0)
        val times = (0 until 50).map { start.plusHours(it.toLong()) }
        val s = ModelSeries("m", times, mapOf("temp" to List(50) { 1.0 }, "snowfall" to List(50) { 0.1 },
            "precip" to List(50) { 0.2 }))
        val w = buildModelWindows(listOf(s), at(8), at(9)).getValue("m")
        assertEquals(4.8, w.past48Snowfall!!, 1e-9)
        assertEquals(4.8, w.past24Precip!!, 1e-9)
        assertEquals(listOf<Double?>(1.0, 1.0), w.temp)
    }

    /** I2: 끝 시각은 올림한다(08:30~09:30 → 08·09·10시). Python publish.window_bounds 와 같다. */
    @Test
    fun windowBoundsRoundEndUp() {
        assertEquals(at(8) to at(10), windowBounds(java.time.LocalDate.of(2026, 11, 24), "08:30", "09:30"))
        assertEquals(at(8) to at(9), windowBounds(java.time.LocalDate.of(2026, 11, 24), "08:00", "09:00"))
    }
}
