package kr.co.hanium.dreamup.walksafe.positioneval.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class GnssLoggerRinexRoundTripInstrumentedTest {
    @Test
    fun convertedObservationRoundTripsThroughRtklibParser() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "gnsslogger-roundtrip-${System.nanoTime()}").apply { mkdirs() }
        val input = File(root, "gnsslogger.txt")
        instrumentation.context.assets.open("gnsslogger/gnsslogger_multiepoch.txt").use { source ->
            input.outputStream().use(source::copyTo)
        }
        val observation = File(root, "rover.obs")

        val converted = GnssLoggerRinex3Converter.convert(
            input,
            observation,
            LocalDateTime.of(2026, 9, 4, 0, 0, 0),
        )
        val parsed = Demo5JniPpkEngine().validateObservation(observation)

        assertEquals(2, converted.epochCount)
        assertEquals(2, parsed.epochCount)
        assertEquals(4, parsed.satelliteCount)
        assertEquals(8, parsed.pseudorangeCount)
        assertTrue(parsed.carrierPhaseCount >= 4)
    }
}
