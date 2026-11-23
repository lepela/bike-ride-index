package dev.lepela.rideindex.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.lepela.rideindex.data.SettingsStore
import dev.lepela.rideindex.engine.LatLon
import kotlinx.coroutines.launch

private enum class Target(val label: String) { HOME("집"), WORK("회사") }

@Composable
fun SettingsScreen(store: SettingsStore, modifier: Modifier = Modifier) {
    val snap by store.snapshots.collectAsState(initial = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Target?>(null) }
    var url by remember { mutableStateOf("") }
    LaunchedEffect(snap?.pagesUrl) { snap?.pagesUrl?.let { url = it } }

    fun register(target: Target) {
        scope.launch {
            message = "${target.label} 위치를 읽는 중"
            val p = runCatching { LocationRegistrar(context).current() }.getOrNull()
            if (p == null) {
                message = "위치를 읽지 못했습니다. 위치 설정을 켠 뒤 다시 누릅니다."
                return@launch
            }
            if (target == Target.HOME) store.setHome(p) else store.setWork(p)
            message = "${target.label}을 등록했습니다: ${formatCoord(p.lat)}, ${formatCoord(p.lon)}"
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val t = pending
        if (result.values.any { it } && t != null) register(t) else message = "위치 권한이 없어 등록하지 못했습니다."
    }

    fun request(target: Target) {
        pending = target
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) register(target) else launcher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("게시 주소", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Button(onClick = { scope.launch { store.setPagesUrl(url); message = "게시 주소를 저장했습니다." } }) {
            Text("주소 저장")
        }

        Text("집·회사 등록", style = MaterialTheme.typography.titleMedium)
        Text("버튼을 누르면 지금 위치를 이 기기에만 저장합니다. 게시값이 없을 때 이 좌표로 기기에서 계산합니다.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { request(Target.HOME) }) { Text("지금 위치를 집으로 등록") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { request(Target.WORK) }) { Text("지금 위치를 회사로 등록") }
        }
        val s = snap
        Text("집: ${s?.home?.let { "${formatCoord(it.lat)}, ${formatCoord(it.lon)}" } ?: "미등록"}")
        Text("회사: ${s?.work?.let { "${formatCoord(it.lat)}, ${formatCoord(it.lon)}" } ?: "미등록"}")
        val text = secretsText(s?.home, s?.work)
        OutlinedButton(enabled = text.isNotEmpty(), onClick = {
            copy(context, text)
            message = "좌표 네 줄을 복사했습니다."
        }) { Text("GitHub Secrets용 좌표 복사") }
        Text("복사한 값은 GitHub 저장소 Secrets의 HOME_LAT·HOME_LON·WORK_LAT·WORK_LON에 넣습니다.",
            style = MaterialTheme.typography.bodySmall)
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.primary)
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("ride-index-coords", text))
}
