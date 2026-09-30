package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityGnssSignalWeightStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun signalWeightIsPassedSeparatelyFromTheReportedAccuracy() {
        val handler = source.substringAfter("private fun handleLocationUpdate(")
            .substringBefore("expectedGeneration = binding.inputGeneration,")
        val observation = handler.substringAfter("GnssPositionObservation(")
        assertTrue(observation.contains("horizontalAccuracyM = reportedAccuracyM,"))
        assertTrue(observation.contains("measurementNoiseMultiplier = measurementNoiseMultiplier,"))
        assertFalse(observation.contains("horizontalAccuracyM = effectiveAccuracyM"))
    }
}
