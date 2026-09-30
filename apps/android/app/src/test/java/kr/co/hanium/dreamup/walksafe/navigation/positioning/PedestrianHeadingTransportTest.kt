package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PedestrianHeadingTransportTest {
    @Test
    fun transportsGpsByMeasuredChestTurnWithoutLearningAnOffset() {
        val selector = selector()

        val result = requireNotNull(selector.select(input()))

        assertEquals(100.0, result.headingDegreesTrueNorth, 1e-9)
        assertEquals(PedestrianHeadingSource.GPS_COURSE, result.source)
        assertEquals(1_000L, result.sourceObservationAtElapsedRealtimeMs)
        assertEquals(1_500L, result.rotationObservationAtElapsedRealtimeMs)
        assertEquals(sqrt(75.0), requireNotNull(result.accuracyDegrees), 1e-9)
    }

    @Test
    fun sameGpsCourseWithNewMagneticTimeIsNewTurnEvidenceButRepeatedPairIsNot() {
        val selector = PedestrianHeadingSelector()
        selector.select(input(now = 1_000L, magneticAt = 1_000L, magnetic = 50.0))

        val turned = requireNotNull(selector.select(input()))
        val repeated = requireNotNull(selector.select(input(now = 1_600L)))

        assertTrue(turned.headingDegreesTrueNorth >= 80.0)
        assertEquals(turned.headingDegreesTrueNorth, repeated.headingDegreesTrueNorth, 0.0)
        assertEquals(1_000L, repeated.sourceObservationAtElapsedRealtimeMs)
        assertEquals(1_500L, repeated.rotationObservationAtElapsedRealtimeMs)
    }

    @Test
    fun transportAcrossNorthUsesTwoDegreeTurnAndPreservesOriginalCourseTime() {
        val result = requireNotNull(selector().select(input(gps = 120.0, baseline = 359.0, magnetic = 1.0)))

        assertEquals(122.0, result.headingDegreesTrueNorth, 1e-9)
        assertEquals(1_000L, result.sourceObservationAtElapsedRealtimeMs)
    }

    @Test
    fun reverseTransportAcrossNorthUsesMinusTwoDegreeTurn() {
        val result = requireNotNull(selector().select(input(gps = 1.0, baseline = 1.0, magnetic = 359.0)))

        assertEquals(359.0, result.headingDegreesTrueNorth, 1e-9)
    }

    @Test
    fun missingBadUnmountedFutureOrStaleChestEvidenceNeverAppliesTurn() {
        val valid = input()
        listOf(
            valid.copy(magneticTrueHeadingAtGpsCourse = null),
            valid.copy(magneticTrueHeading = null),
            valid.copy(phoneForwardMounted = false),
            valid.copy(magneticTrueHeadingAtGpsCourse = HeadingObservation(50.0, null, 1_000L)),
            valid.copy(magneticTrueHeadingAtGpsCourse = HeadingObservation(50.0, 5.0, 1_001L)),
            valid.copy(magneticTrueHeadingAtGpsCourse = HeadingObservation(50.0, 5.0, 899L)),
            valid.copy(magneticTrueHeading = HeadingObservation(140.0, 5.0, 1_501L)),
            valid.copy(magneticTrueHeading = HeadingObservation(140.0, 5.0, 999L)),
            valid.copy(magneticTrueHeading = HeadingObservation(140.0, 31.0, 1_500L)),
            valid.copy(magneticTrueHeading = HeadingObservation(Double.NaN, 5.0, 1_500L)),
        ).forEach { unsupported ->
            val result = requireNotNull(selector().select(unsupported))
            assertEquals(10.0, result.headingDegreesTrueNorth, 1e-9)
            assertNull(result.rotationObservationAtElapsedRealtimeMs)
            assertEquals(5.0, requireNotNull(result.accuracyDegrees), 0.0)
        }
    }

    @Test
    fun baselineSkewAndTransportDurationBoundariesAreInclusive() {
        val inside = requireNotNull(selector().select(input(now = 2_900L, baselineAt = 900L, magneticAt = 2_900L)))
        val outside = selector().select(input(now = 2_901L, baselineAt = 900L, magneticAt = 2_901L))

        assertEquals(100.0, inside.headingDegreesTrueNorth, 1e-9)
        assertNull(outside)
    }

    @Test
    fun newMagneticEvidenceCannotMakeStaleAbsoluteGpsCourseFresh() {
        val result = selector().select(input(now = 3_001L, magneticAt = 3_001L))

        assertNull(result)
    }

    @Test
    fun reusedGpsWithOlderRotationSampleDoesNotRewindAcceptedTurn() {
        val selector = selector()
        val first = requireNotNull(selector.select(input()))

        val regressing = selector.select(input(now = 1_600L, magneticAt = 1_400L, magnetic = 80.0))
        val repeated = requireNotNull(selector.select(input(now = 1_700L)))

        assertNull(regressing)
        assertEquals(first.headingDegreesTrueNorth, repeated.headingDegreesTrueNorth, 0.0)
    }

    @Test
    fun olderObservationAndOlderSelectionTimeCannotChangeSmoothedHeading() {
        val selector = PedestrianHeadingSelector()
        selector.select(magneticInput(1_000L, 1_000L, 0.0))
        val first = requireNotNull(selector.select(magneticInput(1_100L, 1_100L, 10.0)))

        assertNull(selector.select(magneticInput(1_200L, 1_050L, 90.0)))
        assertNull(selector.select(magneticInput(1_150L, 1_150L, 90.0)))
        val repeated = requireNotNull(selector.select(magneticInput(1_300L, 1_100L, 10.0)))

        assertEquals(first.headingDegreesTrueNorth, repeated.headingDegreesTrueNorth, 0.0)
    }

    @Test
    fun temporarilyMissingSpeedDoesNotResmoothReusedHeading() {
        val selector = PedestrianHeadingSelector()
        selector.select(magneticInput(1_000L, 1_000L, 0.0))
        val first = requireNotNull(selector.select(magneticInput(1_100L, 1_100L, 10.0)))
        assertNull(selector.select(magneticInput(1_200L, 1_100L, 10.0).copy(speed = null)))

        val repeated = requireNotNull(selector.select(magneticInput(1_300L, 1_100L, 10.0)))

        assertEquals(first.headingDegreesTrueNorth, repeated.headingDegreesTrueNorth, 0.0)
    }

    @Test
    fun oldPendingCourseCannotCompleteHoldUsingRegressingEvidence() {
        val selector = PedestrianHeadingSelector(PedestrianHeadingSelectorConfig(minimumSourceHoldMs = 1_000L))
        selector.select(magneticInput(0L, 0L, 10.0))
        selector.select(input(now = 100L, gpsAt = 100L, magneticAt = 100L, baselineAt = 100L))
        selector.select(input(now = 200L, gpsAt = 200L, magneticAt = 200L, baselineAt = 200L))

        val older = requireNotNull(selector.select(input(now = 1_100L, gpsAt = 150L, magneticAt = 1_100L, baselineAt = 150L)))
        val next = requireNotNull(selector.select(input(now = 1_200L, gpsAt = 300L, magneticAt = 1_200L, baselineAt = 300L)))

        assertEquals(PedestrianHeadingSource.MAGNETIC_TRUE, older.source)
        assertEquals(PedestrianHeadingSource.GPS_COURSE, next.source)
    }

    @Test
    fun resetClearsObservationDeduplicationAndSourceHold() {
        val selector = PedestrianHeadingSelector()
        selector.select(input())
        selector.reset()

        val fresh = requireNotNull(selector.select(magneticInput(100L, 100L, 180.0)))

        assertEquals(PedestrianHeadingSource.MAGNETIC_TRUE, fresh.source)
        assertEquals(180.0, fresh.headingDegreesTrueNorth, 0.0)
        assertNull(fresh.rotationObservationAtElapsedRealtimeMs)
    }

    @Test
    fun magneticSmoothingAcross359To1UsesShortestArcWithoutTurnAmplification() {
        val selector = PedestrianHeadingSelector(PedestrianHeadingSelectorConfig(headingSmoothingAlpha = 0.5))
        selector.select(magneticInput(1_000L, 1_000L, 359.0))

        // S09-28: the configured gain is per nominal magnetic interval (500 ms), not per call.
        val next = requireNotNull(selector.select(magneticInput(1_500L, 1_500L, 1.0)))

        assertEquals(0.0, next.headingDegreesTrueNorth, 1e-9)
    }

    @Test
    fun transportLossCannotRestorePreTurnCourse() {
        val valid = input(now = 1_600L, magneticAt = 1_600L)
        listOf(
            valid.copy(magneticTrueHeadingAtGpsCourse = null),
            valid.copy(magneticTrueHeading = null),
            valid.copy(phoneForwardMounted = false),
            valid.copy(magneticTrueHeadingAtGpsCourse = HeadingObservation(50.0, null, 1_000L)),
            valid.copy(magneticTrueHeadingAtGpsCourse = HeadingObservation(50.0, 5.0, 1_001L)),
            valid.copy(magneticTrueHeadingAtGpsCourse = HeadingObservation(50.0, 5.0, 899L)),
            valid.copy(magneticTrueHeading = HeadingObservation(140.0, 5.0, 1_601L)),
            valid.copy(magneticTrueHeading = HeadingObservation(140.0, 5.0, 999L)),
            valid.copy(magneticTrueHeading = HeadingObservation(140.0, 31.0, 1_600L)),
            valid.copy(magneticTrueHeading = HeadingObservation(Double.NaN, 5.0, 1_600L)),
            valid.copy(
                magneticTrueHeadingAtGpsCourse = HeadingObservation(50.0, 30.0, 1_000L),
                magneticTrueHeading = HeadingObservation(140.0, 30.0, 1_600L),
            ),
        ).forEach { unsupported ->
            val selector = selector()
            assertEquals(100.0, requireNotNull(selector.select(input())).headingDegreesTrueNorth, 1e-9)

            assertNull(selector.select(unsupported))
        }
    }

    @Test
    fun failedTurnTransportCannotReturnRawCourseEvenWithoutPreviousOutput() {
        val selector = selector()
        val uncertain = input().copy(
            magneticTrueHeadingAtGpsCourse = HeadingObservation(50.0, 30.0, 1_000L),
            magneticTrueHeading = HeadingObservation(140.0, 30.0, 1_500L),
        )

        assertNull(selector.select(uncertain))
        assertNull(selector.select(input(now = 1_600L).copy(magneticTrueHeading = null)))
    }

    @Test
    fun rawCourseMustBeStrictlyNewerThanKnownTurn() {
        val selector = selector()
        requireNotNull(selector.select(input()))
        listOf(1_000L, 1_400L, 1_500L).forEach { gpsAt ->
            assertNull(selector.select(input(now = 1_600L, gpsAt = gpsAt).copy(magneticTrueHeading = null)))
        }

        val fresh = requireNotNull(selector.select(
            input(now = 1_600L, gpsAt = 1_501L, gps = 120.0).copy(magneticTrueHeading = null),
        ))
        assertEquals(120.0, fresh.headingDegreesTrueNorth, 1e-9)
        assertEquals(1_501L, fresh.sourceObservationAtElapsedRealtimeMs)
        assertNull(fresh.rotationObservationAtElapsedRealtimeMs)
    }

    @Test
    fun knownTurnSurvivesSourceSwitchAndResetClearsIt() {
        val selector = PedestrianHeadingSelector(PedestrianHeadingSelectorConfig(
            headingSmoothingAlpha = 1.0,
            minimumSourceHoldMs = 0L,
            consecutivePreferredObservationsRequired = 1,
        ))
        requireNotNull(selector.select(input()))
        val magnetic = requireNotNull(selector.select(magneticInput(1_600L, 1_600L, 200.0)))
        assertEquals(PedestrianHeadingSource.MAGNETIC_TRUE, magnetic.source)
        assertNull(selector.select(input(now = 1_700L).copy(magneticTrueHeading = null)))

        selector.reset()
        val freshSession = requireNotNull(selector.select(
            input(now = 100L, gpsAt = 100L).copy(magneticTrueHeading = null),
        ))
        assertEquals(10.0, freshSession.headingDegreesTrueNorth, 1e-9)
    }

    @Test
    fun sameTimestampRotationConflictCannotUndoAcceptedTurn() {
        val selector = selector()
        val accepted = requireNotNull(selector.select(input()))

        assertNull(selector.select(
            input(now = 1_600L, magneticAt = 1_500L, magnetic = 50.0),
        ))
        val duplicate = requireNotNull(selector.select(input(now = 1_650L)))
        assertEquals(accepted.headingDegreesTrueNorth, duplicate.headingDegreesTrueNorth, 0.0)
        assertEquals(accepted.rotationObservationAtElapsedRealtimeMs, duplicate.rotationObservationAtElapsedRealtimeMs)
        assertNull(selector.select(input(now = 1_700L).copy(magneticTrueHeading = null)))
    }

    @Test
    fun validTransportRecoversWithoutRenewingAbsoluteCourseTime() {
        val selector = selector()
        requireNotNull(selector.select(input()))
        assertNull(selector.select(input(now = 1_600L).copy(magneticTrueHeading = null)))

        val recovered = requireNotNull(selector.select(
            input(now = 1_700L, magneticAt = 1_700L, magnetic = 170.0),
        ))
        assertEquals(130.0, recovered.headingDegreesTrueNorth, 1e-9)
        assertEquals(1_000L, recovered.sourceObservationAtElapsedRealtimeMs)
        assertEquals(1_700L, recovered.rotationObservationAtElapsedRealtimeMs)
    }

    @Test
    fun failedTurnEvidenceCannotBeRewrittenAtTheSameTimestamp() {
        val selector = selector()
        val uncertain = input().copy(
            magneticTrueHeadingAtGpsCourse = HeadingObservation(50.0, 30.0, 1_000L),
            magneticTrueHeading = HeadingObservation(140.0, 30.0, 1_500L),
        )
        val conflicting = input(now = 1_600L, magneticAt = 1_500L, magnetic = 50.0)
        assertNull(selector.select(uncertain))
        assertNull(selector.select(conflicting))
        assertNull(selector.select(input(now = 1_650L).copy(magneticTrueHeading = null)))
        val freshAbsolute = requireNotNull(selector.select(
            input(now = 1_700L, gpsAt = 1_600L, gps = 120.0)
                .copy(magneticTrueHeading = null),
        ))
        assertEquals(120.0, freshAbsolute.headingDegreesTrueNorth, 1e-9)
        assertEquals(1_600L, freshAbsolute.sourceObservationAtElapsedRealtimeMs)
        selector.reset()
        val freshSession = requireNotNull(selector.select(conflicting))
        assertEquals(10.0, freshSession.headingDegreesTrueNorth, 1e-9)
    }

    @Test
    fun newerGpsBaselineCanReuseTheSameUnchangedRotationObservation() {
        val selector = selector()
        val firstInput = input()
        assertEquals(100.0, requireNotNull(selector.select(firstInput)).headingDegreesTrueNorth, 1e-9)
        val updated = input(now = 1_600L, gpsAt = 1_400L, gps = 100.0).copy(
            magneticTrueHeadingAtGpsCourse = HeadingObservation(130.0, 5.0, 1_400L),
            magneticTrueHeading = firstInput.magneticTrueHeading,
        )
        // A new course may replace its baseline, not rewrite the same magnetic sample.
        assertNull(selector.select(updated.copy(
            magneticTrueHeading = HeadingObservation(50.0, 5.0, 1_500L),
        )))
        val transported = requireNotNull(selector.select(updated))
        assertEquals(110.0, transported.headingDegreesTrueNorth, 1e-9)
        assertEquals(1_400L, transported.sourceObservationAtElapsedRealtimeMs)
        assertEquals(1_500L, transported.rotationObservationAtElapsedRealtimeMs)
        val alignedCourse = input(now = 1_650L, gpsAt = 1_450L, gps = 110.0).copy(
            magneticTrueHeadingAtGpsCourse = HeadingObservation(140.0, 5.0, 1_450L),
            magneticTrueHeading = firstInput.magneticTrueHeading,
        )
        assertEquals(110.0, requireNotNull(selector.select(alignedCourse)).headingDegreesTrueNorth, 1e-9)
        // A zero-delta rebase must also consume its GPS observation identity.
        assertNull(selector.select(alignedCourse.copy(
            magneticTrueHeadingAtGpsCourse = HeadingObservation(130.0, 5.0, 1_450L),
        )))
    }

    @Test
    fun failedTurnCannotBeErasedByRetimingBaselineForTheSameGpsObservation() {
        val selector = selector()
        val uncertain = input().copy(
            magneticTrueHeadingAtGpsCourse = HeadingObservation(50.0, 30.0, 900L),
            magneticTrueHeading = HeadingObservation(140.0, 30.0, 1_500L),
        )
        assertNull(selector.select(uncertain))
        val retimed = input(now = 1_600L).copy(
            magneticTrueHeadingAtGpsCourse = HeadingObservation(140.0, 5.0, 1_000L),
            magneticTrueHeading = uncertain.magneticTrueHeading,
        )
        assertNull(selector.select(retimed))
    }

    private fun selector() = PedestrianHeadingSelector(PedestrianHeadingSelectorConfig(headingSmoothingAlpha = 1.0))

    private fun input(
        now: Long = 1_500L,
        gpsAt: Long = 1_000L,
        gps: Double = 10.0,
        baselineAt: Long = gpsAt,
        baseline: Double = 50.0,
        magneticAt: Long = 1_500L,
        magnetic: Double = 140.0,
    ) = PedestrianHeadingInput(
        nowElapsedRealtimeMs = now,
        speed = WalkingSpeedObservation(1.1, 0.2, gpsAt),
        gpsCourse = HeadingObservation(gps, 5.0, gpsAt),
        magneticTrueHeading = HeadingObservation(magnetic, 5.0, magneticAt),
        phoneForwardMounted = true,
        magneticTrueHeadingAtGpsCourse = HeadingObservation(baseline, 5.0, baselineAt),
    )

    private fun magneticInput(now: Long, observedAt: Long, heading: Double) = PedestrianHeadingInput(
        now, WalkingSpeedObservation(0.2, 0.2, now), null, HeadingObservation(heading, 5.0, observedAt), true,
    )
}
