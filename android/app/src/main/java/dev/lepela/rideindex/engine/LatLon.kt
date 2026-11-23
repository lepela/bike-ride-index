package dev.lepela.rideindex.engine

/** 위도·경도. 집·회사 좌표는 기기 안(SettingsStore)에만 둔다. */
data class LatLon(val lat: Double, val lon: Double)
