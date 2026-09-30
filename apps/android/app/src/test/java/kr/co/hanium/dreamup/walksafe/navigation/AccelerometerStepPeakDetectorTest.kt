package kr.co.hanium.dreamup.walksafe.navigation

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * S09-17 (originals/09.txt:52): accelerometer fallback uses gravity removal, peak and trough, and
 * step-interval continuity. Normal walking is detected; shaking, one shock and a sustained high
 * value are not. The result stays LOW confidence.
 */
class AccelerometerStepPeakDetectorTest {
    @Test
    fun normalWalkingIsCountedWithStepTimesAtThePeaks() {
        for (cadenceHz in listOf(1.6, 2.0)) {
            val steps = run(walking(cadenceHz, amplitude = 3.5, seconds = 10.0))
            val expected = (10.0 * cadenceHz).toInt()
            assertTrue("cadence=$cadenceHz steps=${steps.size}", steps.size in expected - 2..expected)
            val periodNs = (1e9 / cadenceHz).toLong()
            steps.zipWithNext().forEach { (a, b) -> assertTrue(abs((b - a) - periodNs) <= SAMPLE_NS) }
        }
    }

    /** w25 review pos-F9: a sharp short peak with a long shallow trough, and a slow 1.4 Hz gait, are steps. */
    @Test
    fun asymmetricAndSlowGaitsAreCounted() {
        for (cadenceHz in listOf(1.4, 1.8)) {
            val expected = (10.0 * cadenceHz).toInt()
            val asymmetric = run(asymmetricWalking(cadenceHz, peak = 4.0, trough = 1.6, seconds = 10.0))
            assertTrue("asymmetric cadence=$cadenceHz steps=${asymmetric.size}", asymmetric.size in expected - 2..expected)
            val periodNs = (1e9 / cadenceHz).toLong()
            asymmetric.zipWithNext().forEach { (a, b) -> assertTrue(abs((b - a) - periodNs) <= SAMPLE_NS) }
            val gentle = run(asymmetricWalking(cadenceHz, peak = 2.8, trough = 1.0, seconds = 10.0))
            assertTrue("gentle asymmetric cadence=$cadenceHz steps=${gentle.size}", gentle.size in expected - 2..expected)
        }
        val slow = run(walking(cadenceHz = 1.4, amplitude = 3.0, seconds = 10.0))
        assertTrue("slow symmetric steps=${slow.size}", slow.size in 12..14)
    }

    /** The shallower trough does not turn shaking, one shock (with ringing) or a sustained value into steps. */
    @Test
    fun asymmetricShakingAndARingingShockAreStillNotSteps() {
        assertEquals(0, run(asymmetricWalking(cadenceHz = 5.0, peak = 6.0, trough = 1.0, seconds = 5.0)).size)
        assertEquals(0, run(asymmetricWalking(cadenceHz = 4.0, peak = 4.0, trough = 1.6, seconds = 5.0)).size)
        assertEquals(0, run(ringingShock()).size)
    }

    @Test
    fun fastShakingOneShockAndASustainedHighValueAreNotSteps() {
        assertEquals(0, run(walking(cadenceHz = 5.0, amplitude = 6.0, seconds = 5.0)).size)
        assertEquals(0, run(shock()).size)
        assertEquals(0, run(sustainedHigh()).size)
        // The former single-threshold rule counted the sustained value every 300 ms.
        assertTrue(sustainedHigh().count { it.second >= 12.2 } > 10)
    }

    @Test
    fun irregularPeaksBreakTheRhythmAndARestartNeedsTwoConsistentPeaks() {
        val detector = AccelerometerStepPeakDetector()
        val peaksMs = listOf(0L, 500L, 1_000L, 2_900L, 3_100L, 3_600L, 4_100L)
        val steps = peaksMs.flatMap { peakMs ->
            val peakAt = START_NS + peakMs * 1_000_000L
            detector.observe(peakAt - 100_000_000L, 6.5) + // trough
                detector.observe(peakAt, 13.5) + // peak
                detector.observe(peakAt + 30_000_000L, 9.8) // falls back: peak confirmed
        }
        // 0/500/1000 form a rhythm; 2900 breaks 0.5x..2x and only restarts; 3100 is a full oscillation
        // faster than 300 ms and restarts again; 3100/3600 are two consistent peaks, then 4100 follows.
        assertEquals(listOf(0L, 500L, 1_000L, 3_100L, 3_600L, 4_100L).map { START_NS + it * 1_000_000L }, steps)
    }

    @Test
    fun aSecondHumpWithoutATroughBelongsToTheSameStep() {
        val detector = AccelerometerStepPeakDetector()
        val steps = listOf(0L, 500L, 1_000L).flatMap { peakMs ->
            val peakAt = START_NS + peakMs * 1_000_000L
            detector.observe(peakAt - 100_000_000L, 6.5) +
                detector.observe(peakAt, 13.5) +
                detector.observe(peakAt + 40_000_000L, 11.0) + // dips but not to a trough
                detector.observe(peakAt + 80_000_000L, 12.8) + // second hump of the same step
                detector.observe(peakAt + 120_000_000L, 9.8)
        }
        assertEquals(listOf(0L, 500L, 1_000L).map { START_NS + it * 1_000_000L }, steps)
    }

    @Test
    fun trackerEmitsOnlyLowConfidenceFallbackEventsFromTheDetector() {
        val counts = mutableListOf<Int>()
        val events = mutableListOf<StepEvent>()
        val tracker = AndroidStepTracker(
            registration = AccelerometerOnlyRegistration(),
            onStepCountChanged = { counts += it },
            onStepEvent = { events += it },
        )
        assertTrue(tracker.start())
        walking(cadenceHz = 2.0, amplitude = 3.5, seconds = 5.0).forEach { (at, magnitude) ->
            tracker.onAccelerometerSample(0f, 0f, magnitude.toFloat(), at)
        }
        assertTrue(events.size in 8..10)
        assertTrue(events.all { it.source == StepEventSource.ACCELEROMETER_FALLBACK && it.confidence == StepEventConfidence.LOW })
        assertEquals(events.size, counts.last())
        sustainedHigh().forEach { (at, magnitude) -> tracker.onAccelerometerSample(0f, 0f, magnitude.toFloat(), at + 10_000_000_000L) }
        assertTrue(events.size in 8..10)
    }

    private fun run(samples: List<Pair<Long, Double>>): List<Long> {
        val detector = AccelerometerStepPeakDetector()
        return samples.flatMap { (at, magnitude) -> detector.observe(at, magnitude) }
    }

    /** Gravity plus a vertical oscillation, sampled at the GAME rate (50 Hz). */
    private fun walking(cadenceHz: Double, amplitude: Double, seconds: Double): List<Pair<Long, Double>> =
        (0 until (seconds * 1e9 / SAMPLE_NS).toInt()).map { index ->
            val at = START_NS + index * SAMPLE_NS
            at to G + amplitude * sin(2.0 * PI * cadenceHz * (at - START_NS) / 1e9)
        }

    /**
     * Heel strike as a short sharp peak (first 20 % of the step period) followed by a long shallow trough,
     * the review's asymmetric gait model (w25 review-positioning probe D).
     */
    private fun asymmetricWalking(
        cadenceHz: Double,
        peak: Double,
        trough: Double,
        seconds: Double,
    ): List<Pair<Long, Double>> = (0 until (seconds * 1e9 / SAMPLE_NS).toInt()).map { index ->
        val at = START_NS + index * SAMPLE_NS
        val phase = ((at - START_NS) / 1e9 * cadenceHz) % 1.0
        val dynamic = if (phase < 0.2) {
            peak * sin(PI * phase / 0.2)
        } else {
            -trough * sin(PI * (phase - 0.2) / 0.8)
        }
        at to G + dynamic
    }

    /** One impact followed by a damped 10 Hz ringing. */
    private fun ringingShock(): List<Pair<Long, Double>> = (0 until 150).map { index ->
        val at = START_NS + index * SAMPLE_NS
        val sinceImpact = index - 50
        val value = if (sinceImpact in 0 until 30) {
            G + 15.0 * kotlin.math.exp(-sinceImpact / 12.0) * kotlin.math.cos(2.0 * PI * 10.0 * sinceImpact * SAMPLE_NS / 1e9)
        } else {
            G
        }
        at to value
    }

    private fun shock(): List<Pair<Long, Double>> = (0 until 150).map { index ->
        val at = START_NS + index * SAMPLE_NS
        val value = when (index) {
            50 -> 25.0
            51 -> 3.0
            52 -> 15.0
            53 -> 6.0
            else -> G
        }
        at to value
    }

    private fun sustainedHigh(): List<Pair<Long, Double>> = (0 until 200).map { index ->
        val at = START_NS + index * SAMPLE_NS
        at to if (index in 20 until 180) 13.0 else G
    }

    private class AccelerometerOnlyRegistration : StepSensorRegistration {
        override fun hasSensor(type: Int): Boolean = type == Sensor.TYPE_ACCELEROMETER
        override fun register(listener: SensorEventListener, type: Int): Boolean = type == Sensor.TYPE_ACCELEROMETER
        override fun unregister(listener: SensorEventListener?) = Unit
    }

    private companion object {
        const val START_NS = 1_000_000_000L
        const val SAMPLE_NS = 20_000_000L
        const val G = 9.80665
    }
}
