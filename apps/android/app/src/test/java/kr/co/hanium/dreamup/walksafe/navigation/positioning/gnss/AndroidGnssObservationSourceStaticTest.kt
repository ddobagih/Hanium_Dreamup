package kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGnssObservationSourceStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/positioning/gnss/" +
            "AndroidGnssObservationSource.kt",
    ).readText()

    @Test
    fun usesCompatRegistrationAndMatchingPlatformUnregistration() {
        assertTrue(source.contains("LocationManagerCompat.registerGnssStatusCallback"))
        assertTrue(source.contains("LocationManagerCompat.registerGnssMeasurementsCallback"))
        assertTrue(source.contains("LocationManagerCompat.unregisterGnssStatusCallback(locationManager, callback)"))
        assertTrue(source.contains("locationManager.unregisterGnssMeasurementsCallback(callback)"))
    }

    @Test
    fun declaresFineLocationPermissionAtBothSubscriptionBoundaries() {
        assertTrue(source.contains("import androidx.annotation.RequiresPermission"))
        assertEquals(
            2,
            Regex("@RequiresPermission\\(Manifest\\.permission\\.ACCESS_FINE_LOCATION\\)")
                .findAll(source)
                .count(),
        )
    }

    @Test
    fun guardsCarrierFrequencyBeforeReadingIt() {
        val statusGuard = source.indexOf("status.hasCarrierFrequencyHz(index)")
        val statusRead = source.indexOf("status.getCarrierFrequencyHz(index)")
        val measurementGuard = source.indexOf("measurement.hasCarrierFrequencyHz()")
        val measurementRead = source.indexOf("measurement.carrierFrequencyHz")

        assertTrue(statusGuard >= 0)
        assertTrue(statusRead > statusGuard)
        assertTrue(measurementGuard >= 0)
        assertTrue(measurementRead > measurementGuard)
    }

    @Test
    fun convertsCallbacksToSessionOnlyPrimitiveObservations() {
        assertTrue(source.contains("GnssSatelliteSignal("))
        assertTrue(source.contains("GnssSignalEpoch(elapsedRealtimeNanos(), signals)"))
        assertTrue(source.contains("usedInFix = status.usedInFix(index)"))
        assertTrue(source.contains("usedInFix = false"))
        assertTrue(source.contains("usedInFixKnown = true"))
        assertTrue(source.contains("usedInFixKnown = false"))
    }
}
