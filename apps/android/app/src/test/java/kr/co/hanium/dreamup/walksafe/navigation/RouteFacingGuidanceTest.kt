package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

class RouteFacingGuidanceTest {
    private val now = 10_000L
    private val northFacing = RouteFacingObservation(0.0, 5.0, now)

    @Test fun routeDirectionsAreRelativeToTheRearCameraFacing() {
        val eastFacing = northFacing.copy(degreesTrueNorth = 90.0)
        assertDirection(RouteFacingDirection.FRONT, 90.0, eastFacing)
        assertDirection(RouteFacingDirection.LEFT, 0.0, eastFacing)
        assertDirection(RouteFacingDirection.RIGHT, 180.0, eastFacing)
        assertDirection(RouteFacingDirection.BEHIND, 270.0, eastFacing)
    }

    @Test fun northWrapUsesTheShortSignedRotationInBothDirections() {
        val clockwise = evaluate(1.0, northFacing.copy(degreesTrueNorth = 359.0))
        val counterclockwise = evaluate(359.0, northFacing.copy(degreesTrueNorth = 1.0))
        assertEquals(RouteFacingDirection.FRONT, clockwise.direction)
        assertEquals(2.0, clockwise.signedDifferenceDegrees!!, 0.000001)
        assertEquals(RouteFacingDirection.FRONT, counterclockwise.direction)
        assertEquals(-2.0, counterclockwise.signedDifferenceDegrees!!, 0.000001)
    }

    @Test fun rightAndLeftHaveOppositeSignedAngles() {
        assertEquals(90.0, evaluate(90.0).signedDifferenceDegrees!!, 0.0)
        assertEquals(-90.0, evaluate(270.0).signedDifferenceDegrees!!, 0.0)
    }

    @Test fun bothSidesOfOppositeFacingRemainBehindWithoutChoosingATurnSide() {
        listOf(179.0, 180.0, 181.0).forEach {
            assertDirection(RouteFacingDirection.BEHIND, it)
        }
        assertEquals(-180.0, evaluate(180.0).signedDifferenceDegrees!!, 0.0)
    }

    @Test fun aTurnWhileStationaryUsesTheNewFacingWithoutAnyTravelCourse() {
        assertDirection(RouteFacingDirection.FRONT, 0.0)
        assertDirection(RouteFacingDirection.BEHIND, 0.0, northFacing.copy(degreesTrueNorth = 180.0))
        assertDirection(RouteFacingDirection.LEFT, 0.0, northFacing.copy(degreesTrueNorth = 90.0))
    }

    @Test fun missingOrUnavailableFacingDoesNotReuseThePreviousDirection() {
        assertDirection(RouteFacingDirection.FRONT, 0.0)
        // Main supplies null for unavailable sensors or an unusable rear-camera projection.
        assertInvalid(evaluate(0.0, null))
    }

    @Test fun freshnessIncludesNowAndExactlyFiveHundredMilliseconds() {
        assertDirection(RouteFacingDirection.FRONT, 0.0, northFacing.copy(observedAtElapsedRealtimeMs = now))
        assertDirection(RouteFacingDirection.FRONT, 0.0, northFacing.copy(observedAtElapsedRealtimeMs = now - 500L))
        assertInvalid(evaluate(0.0, northFacing.copy(observedAtElapsedRealtimeMs = now - 501L)))
    }

    @Test fun futureAndNegativeTimestampsAreUnavailable() {
        assertInvalid(evaluate(0.0, northFacing.copy(observedAtElapsedRealtimeMs = now + 1L)))
        assertInvalid(evaluate(0.0, northFacing.copy(observedAtElapsedRealtimeMs = -1L)))
        assertInvalid(RouteFacingGuidance.evaluate(0.0, northFacing, -1L))
        assertInvalid(RouteFacingGuidance.evaluate(0.0, northFacing.copy(observedAtElapsedRealtimeMs = Long.MIN_VALUE), Long.MAX_VALUE))
    }

    @Test fun zeroAndThirtyDegreeReportedAccuracyAreAccepted() {
        listOf(0.0, 30.0).forEach { accuracy ->
            val observation = northFacing.copy(headingAccuracyDegrees = accuracy)
            assertDirection(RouteFacingDirection.FRONT, 0.0, observation)
            assertDirection(RouteFacingDirection.RIGHT, 90.0, observation)
            assertDirection(RouteFacingDirection.BEHIND, 180.0, observation)
            assertDirection(RouteFacingDirection.LEFT, 270.0, observation)
        }
    }

    @Test fun missingOrExcessiveAccuracyNeverProducesAFacingInstruction() {
        listOf(-0.01, 30.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertInvalid(evaluate(0.0, northFacing.copy(headingAccuracyDegrees = it)))
        }
    }

    @Test fun malformedHeadingIsNotSilentlyWrappedIntoAValidHeading() {
        listOf(-0.01, 360.0, 720.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertInvalid(evaluate(0.0, northFacing.copy(degreesTrueNorth = it)))
        }
    }

    @Test fun malformedOrMissingRouteBearingCannotProduceAFacingInstruction() {
        listOf(null, -0.01, 360.0, 720.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertInvalid(evaluate(it))
        }
    }

    @Test fun diagonalRoutesStillGiveAnUnambiguousAlignmentSide() {
        listOf(0.0, 5.0, 30.0).forEach { accuracy ->
            val observation = northFacing.copy(headingAccuracyDegrees = accuracy)
            assertDirection(RouteFacingDirection.RIGHT, 45.0, observation)
            assertDirection(RouteFacingDirection.RIGHT, 135.0, observation)
            assertDirection(RouteFacingDirection.LEFT, 225.0, observation)
            assertDirection(RouteFacingDirection.LEFT, 315.0, observation)
        }
    }

    @Test fun smallNoiseAtDiagonalSectorEdgesKeepsTheSameAlignmentSide() {
        listOf(45.0, 135.0, 225.0, 315.0).forEach { edge ->
            val expected = if (edge < 180.0) RouteFacingDirection.RIGHT else RouteFacingDirection.LEFT
            listOf(-4.0, -1.0, 0.0, 1.0, 4.0).forEach { noise ->
                assertDirection(expected, edge + noise, northFacing.copy(headingAccuracyDegrees = 0.0))
            }
        }
    }

    @Test fun aheadAndBehindRequireErrorMarginButKnownCorrectionSidesRemainUseful() {
        assertDirection(RouteFacingDirection.FRONT, 34.9)
        assertDirection(RouteFacingDirection.RIGHT, 35.0)
        assertDirection(RouteFacingDirection.RIGHT, 124.9)
        assertDirection(RouteFacingDirection.RIGHT, 125.0)
        assertDirection(RouteFacingDirection.RIGHT, 145.0)
        assertDirection(RouteFacingDirection.BEHIND, 145.1)
        assertDirection(RouteFacingDirection.LEFT, 304.9)
        assertDirection(RouteFacingDirection.LEFT, 305.0)
        assertDirection(RouteFacingDirection.LEFT, 325.0)
        assertDirection(RouteFacingDirection.FRONT, 325.1)
    }

    @Test fun uncertaintyAcrossStraightOrOppositeCannotChooseAnAlignmentSide() {
        val uncertainFacing = northFacing.copy(headingAccuracyDegrees = 30.0)
        listOf(30.0, 35.0, 145.0, 150.0, 210.0, 215.0, 325.0, 330.0).forEach {
            val result = evaluate(it, uncertainFacing)
            assertEquals(RouteFacingDirection.UNKNOWN, result.direction)
            assertNotNull(result.signedDifferenceDegrees)
        }
        assertDirection(RouteFacingDirection.RIGHT, 35.1, uncertainFacing)
        assertDirection(RouteFacingDirection.RIGHT, 144.9, uncertainFacing)
        assertDirection(RouteFacingDirection.LEFT, 215.1, uncertainFacing)
        assertDirection(RouteFacingDirection.LEFT, 324.9, uncertainFacing)
    }

    @Test fun theReportedNinetyFivePercentErrorIsNotTreatedAsAStandardDeviation() {
        assertDirection(RouteFacingDirection.FRONT, 20.0, northFacing.copy(headingAccuracyDegrees = 10.0))
        assertDirection(RouteFacingDirection.UNKNOWN, 20.0, northFacing.copy(headingAccuracyDegrees = 20.0))
    }

    private fun evaluate(
        routeBearing: Double?,
        observation: RouteFacingObservation? = northFacing,
    ): RouteFacingGuidanceResult = RouteFacingGuidance.evaluate(routeBearing, observation, now)

    private fun assertDirection(
        expected: RouteFacingDirection,
        routeBearing: Double,
        observation: RouteFacingObservation = northFacing,
    ) {
        assertEquals("Route bearing $routeBearing, facing $observation", expected, evaluate(routeBearing, observation).direction)
    }

    private fun assertInvalid(result: RouteFacingGuidanceResult) {
        assertEquals(RouteFacingDirection.UNKNOWN, result.direction)
        assertNull(result.signedDifferenceDegrees)
    }
}
