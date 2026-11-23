package dev.lepela.rideindex.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.lepela.rideindex.data.SettingsStore
import dev.lepela.rideindex.display.DisplayResolver
import dev.lepela.rideindex.display.DisplaySlot
import dev.lepela.rideindex.display.Origin
import dev.lepela.rideindex.display.WidgetDisplay
import dev.lepela.rideindex.work.RefreshScheduler
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

private val KST = ZoneId.of("Asia/Seoul")

/** 등급 색(설계 5.4절): 좋음 초록, 주의 노랑, 비추천 주황, 타지 않음 빨강. */
fun gradeColor(grade: String?): Color = when (grade) {
    "좋음" -> Color(0xFF2E7D32)
    "주의" -> Color(0xFFF9A825)
    "비추천" -> Color(0xFFEF6C00)
    "타지 않음" -> Color(0xFFC62828)
    else -> Color(0xFF757575)
}

class RideWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = SettingsStore(context).snapshot()
        val today = LocalDate.now(KST)
        val display = DisplayResolver.resolve(today, snap.lastPublished, snap.lastDevice,
            snap.lastPublished ?: snap.lastDevice)
        val now = LocalTime.now(KST)
        provideContent { Content(display, now) }
    }

    @Composable
    private fun Content(d: WidgetDisplay, now: LocalTime) {
        Column(
            modifier = GlanceModifier.fillMaxSize().background(Color(0xFFFAFAFA)).cornerRadius(16.dp)
                .padding(12.dp).clickable(actionRunCallback<RefreshAction>()),
        ) {
            d.slots.forEach { s ->
                val passed = s.id == "commute_in" && now.isAfter(LocalTime.of(8, 30))
                SlotRow(s, dim = passed)
                Spacer(GlanceModifier.height(6.dp))
            }
            Text(text = footer(d), style = TextStyle(fontSize = 11.sp, color = ColorProvider(Color(0xFF757575))))
        }
    }

    @Composable
    private fun SlotRow(s: DisplaySlot, dim: Boolean) {
        val main = if (dim) Color(0xFF9E9E9E) else Color(0xFF212121)
        val gradeC = if (dim) Color(0xFFBDBDBD) else gradeColor(s.grade)
        val name = if (s.id == "commute_in") "출근" else "퇴근"
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
                Text("$name ${s.time}", style = TextStyle(fontSize = 14.sp, color = ColorProvider(main)))
                Spacer(GlanceModifier.width(10.dp))
                Text(
                    s.score?.toString() ?: "--",
                    style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ColorProvider(gradeC)),
                )
                Spacer(GlanceModifier.width(8.dp))
                Text(s.grade ?: "", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = ColorProvider(gradeC)))
            }
            if (s.summary.isNotBlank()) {
                Text(s.summary, maxLines = 1, style = TextStyle(fontSize = 12.sp, color = ColorProvider(main)))
            }
        }
    }

    private fun footer(d: WidgetDisplay): String {
        val time = d.updatedAt?.let {
            runCatching { OffsetDateTime.parse(it).atZoneSameInstant(KST).toLocalTime().toString().take(5) }.getOrNull()
        }
        val origins = d.slots.filter { it.origin != Origin.NONE }.map { it.origin.label }.distinct()
        val stale = d.slots.any { it.stale }
        val parts = buildList {
            if (stale) add("어제 값")
            if (time != null) add("$time 갱신")
            if (origins.isNotEmpty()) add(origins.joinToString("·")) else add(Origin.NONE.label)
            add("탭하면 갱신")
        }
        return parts.joinToString(" · ")
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RefreshScheduler.refreshNow(context)
    }
}

class RideWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RideWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshScheduler.scheduleAll(context)
        RefreshScheduler.refreshNow(context)
    }
}
