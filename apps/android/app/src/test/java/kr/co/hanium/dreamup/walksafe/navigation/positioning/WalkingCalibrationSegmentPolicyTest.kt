package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkingCalibrationSegmentPolicyTest {
    @Test
    fun acceptsKnownStraightWalkAndDoesNotReuseEarlierSteps() {
        val policy = WalkingCalibrationSegmentPolicy()
        val samples = (0..22).mapNotNull { second ->
            policy.observe(fix(second, eastM = second * 1.4), totalStepCount = second * 2)
        }

        assertEquals(2, samples.size)
        samples.forEach { sample ->
            assertEquals(15.4, sample.distanceM, 0.001)
            assertEquals(22, sample.stepCount)
            assertEquals(11_000L, sample.durationMs)
            assertEquals(0.7, sample.distanceM / sample.stepCount, 0.0001)
            assertTrue(sample.trustedPositionSegment)
        }
        assertFalse(PedestrianMotionProfile().calibrate(samples.first()).status == MotionCalibrationStatus.REJECTED)
    }

    @Test
    fun requiresDistanceLargeEnoughForBothEndpointAccuracies() {
        val policy = WalkingCalibrationSegmentPolicy()
        (0..15).forEach { second ->
            assertNull(policy.observe(fix(second, eastM = second * 1.4).copy(horizontalAccuracyM = 3.0), second * 2))
        }

        val sample = policy.observe(fix(16, eastM = 22.4).copy(horizontalAccuracyM = 3.0), 32)

        assertNotNull(sample)
        assertEquals(22.4, sample!!.distanceM, 0.001)
    }

    @Test
    fun rejectsLTurnInsteadOfLearningTheShorterChord() {
        val policy = WalkingCalibrationSegmentPolicy()
        // Two 12.6 m legs have a 17.8 m chord: distance/steps alone would pass.
        (0..9).forEach { second ->
            assertNull(policy.observe(fix(second, eastM = second * 1.4), second * 2))
        }
        (10..18).forEach { second ->
            assertNull(policy.observe(fix(second, eastM = 12.6, northM = (second - 9) * 1.4, course = 0.0), second * 2))
        }
    }

    @Test
    fun rejectsUTurnAndDoesNotJoinItsTwoLegs() {
        val policy = WalkingCalibrationSegmentPolicy()
        (0..7).forEach { second ->
            assertNull(policy.observe(fix(second, eastM = second * 1.4), second * 2))
        }
        (8..14).forEach { second ->
            assertNull(policy.observe(fix(second, eastM = (14 - second) * 1.4, course = 270.0), second * 2))
        }
    }

    @Test
    fun rejectsGradualTurnEvenWhenEachCourseChangeIsSmall() {
        val policy = WalkingCalibrationSegmentPolicy()
        var eastM = 0.0
        var northM = 0.0
        (0..20).forEach { second ->
            val course = 90.0 - second * 4.0
            eastM += 1.4 * sin(Math.toRadians(course))
            northM += 1.4 * cos(Math.toRadians(course))
            assertNull(policy.observe(fix(second, eastM, northM, course), second * 2))
        }
    }

    @Test
    fun rejectsCoordinateTurnEvenIfReportedCourseRemainsStraight() {
        val policy = WalkingCalibrationSegmentPolicy()
        (0..6).forEach { second ->
            assertNull(policy.observe(fix(second, eastM = second * 1.4), second * 2))
        }
        (7..20).forEach { second ->
            assertNull(policy.observe(fix(second, eastM = 8.4, northM = (second - 6) * 1.4), second * 2))
        }
    }

    @Test
    fun doesNotInflateDistanceByAddingTinyJitterSegments() {
        val policy = WalkingCalibrationSegmentPolicy()
        (0..100).forEach { second ->
            val eastM = if (second % 2 == 0) 0.5 else -0.5
            assertNull(policy.observe(fix(second, eastM = eastM), second * 2))
        }
    }

    @Test
    fun rejectsPlausibleStrideWhenCoordinateDriftDisagreesWithRawSpeed() {
        val policy = WalkingCalibrationSegmentPolicy()
        (0..24).forEach { second ->
            // A chord-only learner would accept a 0.35 m stride at this point.
            assertNull(policy.observe(fix(second, eastM = second * 0.7), second * 2))
        }
    }

    @Test
    fun rejectsAnIsolatedPositionSpikeAndDiscardsEarlierSegment() {
        val policy = WalkingCalibrationSegmentPolicy()
        observeFirstHalf(policy)
        assertNull(policy.observe(fix(7, eastM = 35.0), 14))

        observeSecondHalfWithoutSample(policy)
    }

    @Test
    fun invalidOrMissingEvidenceInTheMiddleBreaksTheWholeSegment() {
        val credible = fix(7, eastM = 9.8)
        val badFixes = listOf(
            credible.copy(trustedRawFix = false),
            credible.copy(horizontalAccuracyM = null),
            credible.copy(horizontalAccuracyM = 0.0),
            credible.copy(horizontalAccuracyM = 3.1),
            credible.copy(horizontalAccuracyM = Double.NaN),
            credible.copy(speedMps = null),
            credible.copy(speedMps = 0.0),
            credible.copy(speedMps = Double.NaN),
            credible.copy(speedAccuracyMps = null),
            credible.copy(speedAccuracyMps = 0.36),
            credible.copy(courseDegrees = null),
            credible.copy(courseDegrees = Double.NaN),
            credible.copy(courseDegrees = 360.0),
            credible.copy(courseAccuracyDegrees = null),
            credible.copy(courseAccuracyDegrees = 15.1),
            credible.copy(latitude = Double.NaN),
            credible.copy(longitude = 181.0),
            credible.copy(receivedAtElapsedRealtimeMs = credible.elapsedRealtimeMs + 501L),
            credible.copy(receivedAtElapsedRealtimeMs = credible.elapsedRealtimeMs - 1L),
        )

        badFixes.forEach { badFix ->
            val policy = WalkingCalibrationSegmentPolicy()
            observeFirstHalf(policy)
            assertNull(policy.observe(badFix, 14))
            observeSecondHalfWithoutSample(policy)
        }
    }

    @Test
    fun neverTrustsRawFixByDefault() {
        val policy = WalkingCalibrationSegmentPolicy()
        (0..22).forEach { second ->
            val trusted = fix(second, eastM = second * 1.4)
            val unspecifiedTrust = RawWalkingCalibrationFix(
                latitude = trusted.latitude,
                longitude = trusted.longitude,
                horizontalAccuracyM = trusted.horizontalAccuracyM,
                elapsedRealtimeMs = trusted.elapsedRealtimeMs,
                receivedAtElapsedRealtimeMs = trusted.receivedAtElapsedRealtimeMs,
                speedMps = trusted.speedMps,
                speedAccuracyMps = trusted.speedAccuracyMps,
                courseDegrees = trusted.courseDegrees,
                courseAccuracyDegrees = trusted.courseAccuracyDegrees,
            )
            assertNull(policy.observe(unspecifiedTrust, second * 2))
        }
    }

    @Test
    fun gapCannotJoinTwoShortWalks() {
        val policy = WalkingCalibrationSegmentPolicy()
        observeFirstHalf(policy)
        (10..16).forEach { second ->
            assertNull(policy.observe(fix(second, eastM = second * 1.4), second * 2))
        }
    }

    @Test
    fun duplicateAndOutOfOrderFixesDiscardThePreviousAnchor() {
        listOf(5, 6).forEach { duplicateOrOlderSecond ->
            val policy = WalkingCalibrationSegmentPolicy()
            observeFirstHalf(policy)
            assertNull(policy.observe(fix(duplicateOrOlderSecond, eastM = duplicateOrOlderSecond * 1.4), 12))
            (7..14).forEach { second ->
                assertNull(policy.observe(fix(second, eastM = second * 1.4), second * 2))
            }
        }
    }

    @Test
    fun stopEvidenceCannotBridgeEarlierAndLaterSteps() {
        val policy = WalkingCalibrationSegmentPolicy()
        observeFirstHalf(policy)
        (7..9).forEach { second ->
            // Even a misleading nonzero platform speed cannot bridge step silence.
            assertNull(policy.observe(fix(second, eastM = 8.4), 12))
        }
        (10..16).forEach { second ->
            assertNull(policy.observe(fix(second, eastM = 8.4 + (second - 9) * 1.4), 12 + (second - 9) * 2))
        }
    }

    @Test
    fun counterResetDiscardsTheOldSteps() {
        val policy = WalkingCalibrationSegmentPolicy()
        observeFirstHalf(policy)
        (7..14).forEach { second ->
            assertNull(policy.observe(fix(second, eastM = second * 1.4), (second - 7) * 2))
        }
    }

    @Test
    fun explicitResetAllowsOnlyTheSubsequentCompleteSegment() {
        val policy = WalkingCalibrationSegmentPolicy()
        observeFirstHalf(policy)
        policy.reset()
        (7..17).forEach { second ->
            assertNull(policy.observe(fix(second, eastM = second * 1.4), second * 2))
        }

        val sample = policy.observe(fix(18, eastM = 25.2), 36)

        assertNotNull(sample)
        assertEquals(22, sample!!.stepCount)
        assertEquals(15.4, sample.distanceM, 0.001)
    }

    @Test
    fun acceptsNorthboundCourseAcrossZeroDegrees() {
        val policy = WalkingCalibrationSegmentPolicy()
        val samples = (0..11).mapNotNull { second ->
            val course = if (second % 2 == 0) 359.0 else 1.0
            policy.observe(fix(second, northM = second * 1.4, course = course), second * 2)
        }

        assertEquals(1, samples.size)
        assertEquals(15.4, samples.single().distanceM, 0.001)
    }

    private fun observeFirstHalf(policy: WalkingCalibrationSegmentPolicy) {
        (0..6).forEach { second ->
            assertNull(policy.observe(fix(second, eastM = second * 1.4), second * 2))
        }
    }

    private fun observeSecondHalfWithoutSample(policy: WalkingCalibrationSegmentPolicy) {
        (8..15).forEach { second ->
            assertNull(policy.observe(fix(second, eastM = second * 1.4), second * 2))
        }
    }

    private fun fix(
        second: Int,
        eastM: Double = 0.0,
        northM: Double = 0.0,
        course: Double = 90.0,
    ): RawWalkingCalibrationFix {
        val latitude = 37.5 + Math.toDegrees(northM / 6_371_000.0)
        val longitude = 127.0 + Math.toDegrees(eastM / (6_371_000.0 * cos(Math.toRadians(37.5))))
        val timestamp = 1_000L + second * 1_000L
        return RawWalkingCalibrationFix(
            latitude = latitude,
            longitude = longitude,
            horizontalAccuracyM = 1.0,
            elapsedRealtimeMs = timestamp,
            receivedAtElapsedRealtimeMs = timestamp + 100L,
            speedMps = 1.4,
            speedAccuracyMps = 0.1,
            courseDegrees = course,
            courseAccuracyDegrees = 5.0,
            trustedRawFix = true,
        )
    }
}
