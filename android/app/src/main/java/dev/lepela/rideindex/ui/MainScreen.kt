package dev.lepela.rideindex.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lepela.rideindex.data.SettingsStore
import dev.lepela.rideindex.display.DisplayResolver
import dev.lepela.rideindex.display.DisplaySlot
import dev.lepela.rideindex.display.Origin
import dev.lepela.rideindex.model.ModelHours
import dev.lepela.rideindex.widget.gradeColor
import dev.lepela.rideindex.work.RefreshScheduler
import java.time.LocalDate
import java.time.ZoneId

private val FLOOR_LABEL = mapOf("ice" to "결빙", "snow" to "눈")
private val POINT_LABEL = mapOf("home" to "집", "work" to "회사")

@Composable
fun MainScreen(store: SettingsStore, modifier: Modifier = Modifier) {
    val snap by store.snapshots.collectAsState(initial = null)
    val context = LocalContext.current
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val s = snap
        if (s == null) {
            Text("불러오는 중")
            return@Column
        }
        val d = DisplayResolver.resolve(LocalDate.now(ZoneId.of("Asia/Seoul")), s.lastPublished, s.lastDevice,
            s.lastPublished ?: s.lastDevice)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("갱신: ${d.updatedAt ?: "없음"}", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f))
            Button(onClick = { RefreshScheduler.refreshNow(context) }) { Text("지금 갱신") }
        }
        if (s.home == null || s.work == null) {
            Text("설정에서 집·회사를 등록하면 게시값이 없을 때 기기에서 계산합니다.",
                style = MaterialTheme.typography.bodySmall)
        }
        d.slots.forEach { SlotCard(it) }
    }
}

@Composable
private fun SlotCard(s: DisplaySlot) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val name = if (s.id == "commute_in") "출근" else "퇴근"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$name ${s.time}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(12.dp))
                Text(s.score?.toString() ?: "--", fontSize = 32.sp, fontWeight = FontWeight.Bold,
                    color = gradeColor(s.grade))
                Spacer(Modifier.width(8.dp))
                Text(s.grade ?: "", fontWeight = FontWeight.Bold, color = gradeColor(s.grade))
            }
            val src = s.source
            val originText = if (s.stale) "어제 값 · ${s.origin.label}" else s.origin.label
            Text("출처: $originText" + (src?.let { " · 신뢰도 ${it.confidence}" } ?: ""),
                style = MaterialTheme.typography.bodySmall)
            if (s.origin == Origin.NONE || src == null) return@Column
            Text(src.summary)
            src.reasons.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            val diff = src.score - src.ruleScore
            Text("기준 점수 ${src.ruleScore}점" + if (diff != 0) " (차이 ${if (diff > 0) "+" else ""}$diff)" else "",
                style = MaterialTheme.typography.bodySmall)
            src.deviationReason?.takeIf { it.isNotBlank() }?.let {
                Text("차이 이유: $it", style = MaterialTheme.typography.bodySmall)
            }
            src.floorApplied?.let {
                Text("안전 하한 적용: ${FLOOR_LABEL[it] ?: it}", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            val w = src.weather
            Text("기온 ${w.tempC ?: "-"}°C · 체감 ${w.apparentC ?: "-"}°C · 강수 ${w.precipMmH.min ?: "-"}~${w.precipMmH.max ?: "-"}mm/h" +
                " · 눈 예보 모델 ${w.snowModels}개 · 결빙 ${w.iceRisk} · 돌풍 ${w.gustMs ?: "-"}m/s",
                style = MaterialTheme.typography.bodySmall)
            if (src.weatherByModel.isNotEmpty()) {
                HorizontalDivider()
                src.weatherByModel.forEach { (point, models) -> ModelTable(POINT_LABEL[point] ?: point, models) }
            }
        }
    }
}

/** 모델별 시간 표: 시각마다 강수(mm)·적설(cm)·기온(°C). */
@Composable
private fun ModelTable(point: String, models: Map<String, ModelHours>) {
    Text(point, style = MaterialTheme.typography.labelLarge)
    models.toSortedMap().forEach { (model, h) ->
        val cells = h.hours.indices.joinToString("  ") { i ->
            val p = h.precip.getOrNull(i)
            val sn = h.snowfall.getOrNull(i)
            val t = h.temp.getOrNull(i)
            "${h.hours[i]} ${p ?: "-"}mm ${sn ?: "-"}cm ${t ?: "-"}°"
        }
        Text("$model  $cells", style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
    }
    Spacer(Modifier.height(4.dp))
}
