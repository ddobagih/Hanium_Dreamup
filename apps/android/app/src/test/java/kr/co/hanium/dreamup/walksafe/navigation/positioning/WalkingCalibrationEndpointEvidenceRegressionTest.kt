package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Real segment/collector outputs; endpoint evidence must survive without changing learned distance. */
class WalkingCalibrationEndpointEvidenceRegressionTest {
    @Test
    fun equalDistanceAndStepsDoNotDiscardDifferentReportedEndpointAccuracies() {
        val precise = straightSegment(reportedAccuracyM = 1.0)
        val lessPrecise = straightSegment(reportedAccuracyM = 3.0)

        assertEquals(22.4, precise.distanceM, 0.001)
        assertEquals(20, precise.stepCount)
        assertEquals(16_000L, precise.durationMs)
        assertSameLegacyMeasurement(precise, lessPrecise)
        assertNotEquals(
            "The sample must preserve the distinct original endpoint accuracy evidence",
            precise,
            lessPrecise,
        )
        assertEquals(WalkingCalibrationEndpointEvidence(1_000L, 1.0), precise.startEndpointEvidence)
        assertEquals(WalkingCalibrationEndpointEvidence(17_000L, 1.0), precise.endEndpointEvidence)
        assertEquals(WalkingCalibrationEndpointEvidence(1_000L, 3.0), lessPrecise.startEndpointEvidence)
        assertEquals(WalkingCalibrationEndpointEvidence(17_000L, 3.0), lessPrecise.endEndpointEvidence)
    }

    @Test
    fun equalEndpointCountsDoNotDiscardDifferentActualDetectorBrackets() {
        val regular = alignedWalk(firstDetectorAtMs = 750L)
        val earlierFirstDetector = alignedWalk(firstDetectorAtMs = 400L)

        assertEquals(15.4, regular.distanceM, 0.001)
        assertEquals(22, regular.stepCount)
        assertEquals(11_000L, regular.durationMs)
        assertSameLegacyMeasurement(regular, earlierFirstDetector)
        assertNotEquals(
            "The sample must preserve the real start brackets 750..1250 and 400..1250",
            regular,
            earlierFirstDetector,
        )
        assertEquals(
            WalkingCalibrationEndpointEvidence(1_000L, 1.0, WalkingCalibrationStepBracket(750L, 1_250L)),
            regular.startEndpointEvidence,
        )
        assertEquals(
            WalkingCalibrationEndpointEvidence(1_000L, 1.0, WalkingCalibrationStepBracket(400L, 1_250L)),
            earlierFirstDetector.startEndpointEvidence,
        )
        assertEquals(
            WalkingCalibrationEndpointEvidence(12_000L, 1.0, WalkingCalibrationStepBracket(11_750L, 12_250L)),
            regular.endEndpointEvidence,
        )
        assertEquals(regular.endEndpointEvidence, earlierFirstDetector.endEndpointEvidence)
    }

    @Test
    fun legacySamplesKeepUnknownEndpointsAndTheirOriginalComponentOrder() {
        val legacy = WalkingCalibrationSample(15.4, 22, 11_000L, true)

        assertNull(legacy.startEndpointEvidence)
        assertNull(legacy.endEndpointEvidence)
        val (distance, steps, duration, trusted) = legacy
        assertEquals(15.4, distance, 0.0)
        assertEquals(22, steps)
        assertEquals(11_000L, duration)
        assertEquals(true, trusted)
        val copied = legacy.copy(distanceM = 16.0)
        assertNull(copied.startEndpointEvidence)
        assertNull(copied.endEndpointEvidence)
        assertEquals(22, copied.stepCount)
        assertEquals(11_000L, copied.durationMs)
        assertEquals(true, copied.trustedPositionSegment)
    }

    @Test
    fun contradictoryOptionalBracketsStayUnknownWithoutChangingSegmentAdmission() {
        val ordinary = straightSegment(reportedAccuracyM = 1.0)
        val invalidBrackets: List<(Long) -> WalkingCalibrationStepBracket> = listOf(
            { atMs -> WalkingCalibrationStepBracket(atMs + 1L, atMs + 500L) },
            { atMs -> WalkingCalibrationStepBracket(atMs - 500L, atMs) },
            { atMs -> WalkingCalibrationStepBracket(-1L, atMs + 500L) },
        )

        invalidBrackets.forEach { bracket ->
            val sample = straightSegment(reportedAccuracyM = 1.0, stepBracketForFix = bracket)

            assertEquals(ordinary, sample)
            assertNull(requireNotNull(sample.startEndpointEvidence).stepBracket)
            assertNull(requireNotNull(sample.endEndpointEvidence).stepBracket)
        }
    }

    @Test
    fun adjacentSegmentsShareOnlyTheirBoundaryEvidenceAndKeepDisjointStepCounts() {
        val samples = alignedSamples(firstDetectorAtMs = 750L, lastSecond = 22)

        assertEquals(2, samples.size)
        samples.forEach { sample ->
            assertEquals(15.4, sample.distanceM, 0.001)
            assertEquals(22, sample.stepCount)
            assertEquals(11_000L, sample.durationMs)
        }
        assertEquals(samples.first().endEndpointEvidence, samples.last().startEndpointEvidence)
        assertEquals(1_000L, requireNotNull(samples.first().startEndpointEvidence).elapsedRealtimeMs)
        assertEquals(12_000L, requireNotNull(samples.last().startEndpointEvidence).elapsedRealtimeMs)
        assertEquals(
            WalkingCalibrationEndpointEvidence(23_000L, 1.0, WalkingCalibrationStepBracket(22_750L, 23_250L)),
            samples.last().endEndpointEvidence,
        )
    }

    @Test
    fun aNewScopeRetainsOnlyTheNewWalksOriginalEndpointInputs() {
        val calibration = TimestampAlignedWalkingCalibration()
        val previous = alignedSamples(firstDetectorAtMs = 750L, calibration = calibration).single()
        calibration.resetForScope()

        val current = alignedSamples(
            firstDetectorAtMs = 400L,
            calibration = calibration,
            timeOffsetMs = 20_000L,
        ).single()

        assertSameLegacyMeasurement(previous, current)
        assertEquals(
            WalkingCalibrationEndpointEvidence(21_000L, 1.0, WalkingCalibrationStepBracket(20_400L, 21_250L)),
            current.startEndpointEvidence,
        )
        assertEquals(
            WalkingCalibrationEndpointEvidence(32_000L, 1.0, WalkingCalibrationStepBracket(31_750L, 32_250L)),
            current.endEndpointEvidence,
        )
        assertEquals(1_000L, requireNotNull(previous.startEndpointEvidence).elapsedRealtimeMs)
    }

    private fun assertSameLegacyMeasurement(first: WalkingCalibrationSample, second: WalkingCalibrationSample) {
        assertEquals(first.distanceM, second.distanceM, 0.0)
        assertEquals(first.stepCount, second.stepCount)
        assertEquals(first.durationMs, second.durationMs)
        assertEquals(first.trustedPositionSegment, second.trustedPositionSegment)
    }

    private fun straightSegment(
        reportedAccuracyM: Double,
        stepBracketForFix: ((Long) -> WalkingCalibrationStepBracket)? = null,
    ): WalkingCalibrationSample {
        val policy = WalkingCalibrationSegmentPolicy()
        val samples = (0..16).mapNotNull { second ->
            val rawFix = fix(second, reportedAccuracyM)
            policy.observe(
                rawFix,
                totalStepCount = second * 5 / 4,
                stepBracket = stepBracketForFix?.invoke(rawFix.elapsedRealtimeMs),
            )
        }
        assertEquals(1, samples.size)
        return samples.single()
    }

    private data class Delivery(
        val receivedAtMs: Long,
        val detectorAtMs: Long? = null,
        val rawFix: RawWalkingCalibrationFix? = null,
    )

    private fun alignedWalk(firstDetectorAtMs: Long): WalkingCalibrationSample {
        val samples = alignedSamples(firstDetectorAtMs)
        assertEquals(1, samples.size)
        return samples.single()
    }

    private fun alignedSamples(
        firstDetectorAtMs: Long,
        lastSecond: Int = 11,
        calibration: TimestampAlignedWalkingCalibration = TimestampAlignedWalkingCalibration(),
        timeOffsetMs: Long = 0L,
    ): List<WalkingCalibrationSample> {
        val deliveries = mutableListOf<Delivery>()
        for (detectorAtMs in 750L..(2_000L + lastSecond * 1_000L) step 500L) {
            // Only the first actual measurement moves; later measurements and all fixes stay identical.
            val actualDetectorAtMs =
                (if (detectorAtMs == 750L) firstDetectorAtMs else detectorAtMs) + timeOffsetMs
            deliveries += Delivery(actualDetectorAtMs + 10L, detectorAtMs = actualDetectorAtMs)
        }
        (0..lastSecond).forEach { second ->
            val original = fix(second, reportedAccuracyM = 1.0)
            val rawFix = original.copy(
                elapsedRealtimeMs = original.elapsedRealtimeMs + timeOffsetMs,
                receivedAtElapsedRealtimeMs = original.receivedAtElapsedRealtimeMs + timeOffsetMs,
            )
            deliveries += Delivery(rawFix.receivedAtElapsedRealtimeMs, rawFix = rawFix)
        }
        val samples = deliveries.sortedBy { it.receivedAtMs }.flatMap { delivery ->
            delivery.rawFix?.let(calibration::onFix) ?: calibration.onStep(
                timestampMs = requireNotNull(delivery.detectorAtMs),
                receivedAtElapsedRealtimeMs = delivery.receivedAtMs,
                deltaSteps = 1,
                individualStepDetector = true,
            )
        }
        return samples
    }

    private fun fix(second: Int, reportedAccuracyM: Double): RawWalkingCalibrationFix {
        val elapsedMs = 1_000L + second * 1_000L
        val eastM = second * 1.4
        return RawWalkingCalibrationFix(
            latitude = 37.5,
            longitude = 127.0 + Math.toDegrees(eastM / (6_371_000.0 * cos(Math.toRadians(37.5)))),
            horizontalAccuracyM = reportedAccuracyM,
            elapsedRealtimeMs = elapsedMs,
            receivedAtElapsedRealtimeMs = elapsedMs + 100L,
            speedMps = 1.4,
            speedAccuracyMps = 0.1,
            courseDegrees = 90.0,
            courseAccuracyDegrees = 5.0,
            trustedRawFix = true,
        )
    }
}
