package dev.lepela.rideindex.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.lepela.rideindex.engine.LatLon
import dev.lepela.rideindex.model.Published
import dev.lepela.rideindex.model.parsePublished
import dev.lepela.rideindex.model.toJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 앱 설정과 마지막으로 받은·계산한 값. 좌표는 이 기기 안에만 저장한다. */
class SettingsStore(private val context: Context) {
    private object K {
        val PAGES_URL = stringPreferencesKey("pages_url")
        val HOME_LAT = doublePreferencesKey("home_lat")
        val HOME_LON = doublePreferencesKey("home_lon")
        val WORK_LAT = doublePreferencesKey("work_lat")
        val WORK_LON = doublePreferencesKey("work_lon")
        val LAST_PUBLISHED = stringPreferencesKey("last_published")
        val LAST_DEVICE = stringPreferencesKey("last_device")
        val CACHED_RULES = stringPreferencesKey("cached_rules")
    }

    data class Snapshot(
        val pagesUrl: String,
        val home: LatLon?,
        val work: LatLon?,
        val lastPublished: Published?,
        val lastDevice: Published?,
        val cachedRules: String?,
    )

    val snapshots: Flow<Snapshot> = context.dataStore.data.map(::toSnapshot)

    suspend fun snapshot(): Snapshot = snapshots.first()

    private fun toSnapshot(p: Preferences) = Snapshot(
        pagesUrl = p[K.PAGES_URL] ?: PagesClient.DEFAULT_BASE_URL,
        home = latLon(p[K.HOME_LAT], p[K.HOME_LON]),
        work = latLon(p[K.WORK_LAT], p[K.WORK_LON]),
        lastPublished = p[K.LAST_PUBLISHED]?.let { runCatching { parsePublished(it) }.getOrNull() },
        lastDevice = p[K.LAST_DEVICE]?.let { runCatching { parsePublished(it) }.getOrNull() },
        cachedRules = p[K.CACHED_RULES],
    )

    private fun latLon(lat: Double?, lon: Double?) = if (lat != null && lon != null) LatLon(lat, lon) else null

    suspend fun setPagesUrl(url: String) = context.dataStore.edit { it[K.PAGES_URL] = url.trim() }

    suspend fun setHome(p: LatLon) = context.dataStore.edit { it[K.HOME_LAT] = p.lat; it[K.HOME_LON] = p.lon }

    suspend fun setWork(p: LatLon) = context.dataStore.edit { it[K.WORK_LAT] = p.lat; it[K.WORK_LON] = p.lon }

    suspend fun setLastPublished(p: Published) = context.dataStore.edit { it[K.LAST_PUBLISHED] = p.toJson() }

    suspend fun setLastDevice(p: Published) = context.dataStore.edit { it[K.LAST_DEVICE] = p.toJson() }

    suspend fun setCachedRules(json: String) = context.dataStore.edit { it[K.CACHED_RULES] = json }

    /** Pages 에서 받은 rules.json, 없으면 APK 에 넣은 사본. */
    fun bundledRules(): String = context.assets.open("rules.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
}
