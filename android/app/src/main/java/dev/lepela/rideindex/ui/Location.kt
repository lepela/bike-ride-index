package dev.lepela.rideindex.ui

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.lepela.rideindex.engine.LatLon
import kotlinx.coroutines.tasks.await
import java.util.Locale

/** 등록 버튼을 누를 때만 현재 위치를 한 번 읽는다. 권한 확인은 호출하는 화면이 한다. */
class LocationRegistrar(private val context: Context) {
    @SuppressLint("MissingPermission")
    suspend fun current(): LatLon? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val loc = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
        return loc?.let { LatLon(it.latitude, it.longitude) }
    }
}

fun formatCoord(x: Double): String = String.format(Locale.US, "%.4f", x)

/** GitHub Secrets 에 넣을 네 줄(HOME_LAT=.. 형식). 등록 안 된 지점은 뺀다. */
fun secretsText(home: LatLon?, work: LatLon?): String = buildList {
    home?.let { add("HOME_LAT=${formatCoord(it.lat)}"); add("HOME_LON=${formatCoord(it.lon)}") }
    work?.let { add("WORK_LAT=${formatCoord(it.lat)}"); add("WORK_LON=${formatCoord(it.lon)}") }
}.joinToString("\n")
