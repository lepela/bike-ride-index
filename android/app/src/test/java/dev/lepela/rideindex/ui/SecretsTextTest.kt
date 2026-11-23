package dev.lepela.rideindex.ui

import dev.lepela.rideindex.engine.LatLon
import org.junit.Assert.assertEquals
import org.junit.Test

class SecretsTextTest {
    @Test
    fun fourLinesWithFourDecimals() {
        assertEquals(
            "HOME_LAT=37.5665\nHOME_LON=126.9780\nWORK_LAT=37.4000\nWORK_LON=127.1235",
            secretsText(LatLon(37.56651, 126.978), LatLon(37.4, 127.12346)),
        )
    }

    @Test
    fun missingPointIsSkipped() {
        assertEquals("HOME_LAT=37.5665\nHOME_LON=126.9780", secretsText(LatLon(37.5665, 126.978), null))
        assertEquals("", secretsText(null, null))
    }
}
