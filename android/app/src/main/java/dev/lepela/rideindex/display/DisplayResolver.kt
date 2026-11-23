package dev.lepela.rideindex.display

import dev.lepela.rideindex.model.Published
import dev.lepela.rideindex.model.PublishedSlot
import java.time.LocalDate

enum class Origin(val label: String) { CLAUDE("Claude"), RULE("규칙"), DEVICE("자체 계산"), NONE("데이터 없음") }

data class DisplaySlot(
    val id: String,
    val time: String,
    val score: Int?,
    val grade: String?,
    val summary: String,
    val origin: Origin,
    val stale: Boolean,
    val source: PublishedSlot? = null,
)

data class WidgetDisplay(val slots: List<DisplaySlot>, val updatedAt: String?)

/**
 * 슬롯마다 표시할 값을 고른다(설계 6.2절).
 * 1) 게시값의 오늘 슬롯 → 2) 기기 계산의 오늘 슬롯 → 3) 마지막으로 알던 값(어제 값) → 4) 없음
 */
object DisplayResolver {
    private val SLOTS = listOf("commute_in" to "08:30", "commute_out" to "18:30")

    private fun origin(source: String) = when (source) {
        "claude" -> Origin.CLAUDE
        "rule" -> Origin.RULE
        "device" -> Origin.DEVICE
        else -> Origin.RULE
    }

    private fun Published.slot(id: String) = slots.firstOrNull { it.id == id }

    fun resolve(today: LocalDate, published: Published?, device: Published?, lastKnown: Published?): WidgetDisplay {
        val day = today.toString()
        var updatedAt: String? = null
        val out = SLOTS.map { (id, time) ->
            val fresh = published?.slot(id)?.takeIf { it.date == day }
            val dev = device?.slot(id)?.takeIf { it.date == day }
            val old = lastKnown?.slot(id)
            when {
                fresh != null -> {
                    updatedAt = updatedAt ?: published.generatedAt
                    DisplaySlot(id, time, fresh.score, fresh.grade, fresh.summary, origin(published.source), false, fresh)
                }
                dev != null -> {
                    updatedAt = updatedAt ?: device.generatedAt
                    DisplaySlot(id, time, dev.score, dev.grade, dev.summary, Origin.DEVICE, false, dev)
                }
                old != null -> {
                    updatedAt = updatedAt ?: lastKnown.generatedAt
                    DisplaySlot(id, time, old.score, old.grade, old.summary, origin(lastKnown.source), true, old)
                }
                else -> DisplaySlot(id, time, null, null, "", Origin.NONE, false)
            }
        }
        return WidgetDisplay(out, updatedAt)
    }
}
