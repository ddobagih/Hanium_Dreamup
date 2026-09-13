package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingSpeedObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserMotionEstimateTest {
    @Test
    fun missingObservationsRemainUnknownInsteadOfStationary() {
        val result = estimate(speed = null, course = null, phoneHeading = null, location = null)

        assertEquals(UserMotionEstimate(), result)
        assertNull(result.speedMps)
        assertNull(result.courseDegreesTrueNorth)
        assertNull(result.phoneHeadingDegreesTrueNorth)
    }

    @Test
    fun keepsMovementCourseSeparateFromWherePhoneFaces() {
        val result = estimate()

        assertEquals(1.2, requireNotNull(result.speedMps), 1e-9)
        assertEquals(90.0, requireNotNull(result.courseDegreesTrueNorth), 1e-9)
        assertEquals(240.0, requireNotNull(result.phoneHeadingDegreesTrueNorth), 1e-9)
        assertEquals(FIX_AT_MS, requireNotNull(result.speed).elapsedRealtimeMs)
        assertEquals(FIX_AT_MS, requireNotNull(result.course).elapsedRealtimeMs)
        assertEquals(NOW_MS, requireNotNull(result.phoneHeading).elapsedRealtimeMs)
    }

    @Test
    fun accuratelyObservedZeroSpeedHasNoCourse() {
        val result = estimate(speed = speed(value = 0.0, accuracy = 0.2))

        assertEquals(0.0, requireNotNull(result.speedMps), 0.0)
        assertNull(result.course)
        assertEquals(240.0, requireNotNull(result.phoneHeadingDegreesTrueNorth), 1e-9)
    }

    @Test
    fun noisyZeroSpeedDoesNotClaimStationary() {
        val result = estimate(speed = speed(value = 0.0, accuracy = 0.2001))

        assertNull(result.speed)
        assertNull(result.course)
    }

    @Test
    fun uncertainSlowMovementHasSpeedButNoCourseOrPhoneSubstitution() {
        val result = estimate(speed = speed(value = 0.6, accuracy = 0.2))

        assertEquals(0.6, requireNotNull(result.speedMps), 1e-9)
        assertNull(result.course)
        assertEquals(240.0, requireNotNull(result.phoneHeadingDegreesTrueNorth), 1e-9)
    }

    @Test
    fun acceptsCourseAtSpeedLowerBoundAndAccuracyBoundaries() {
        val result = estimate(
            speed = speed(value = 1.3, accuracy = 0.8),
            course = heading(value = 90.0, accuracy = 35.0),
            location = location(accuracy = 15.0),
        )

        assertEquals(90.0, requireNotNull(result.courseDegreesTrueNorth), 1e-9)
    }

    @Test
    fun acceptsLocationAgeBoundaryThenExpiresWithoutReusingMovement() {
        val boundary = estimate(now = FIX_AT_MS + 2_000L, phoneHeading = null)
        val stale = estimate(now = FIX_AT_MS + 2_001L, phoneHeading = null)

        assertEquals(1.2, requireNotNull(boundary.speedMps), 1e-9)
        assertEquals(90.0, requireNotNull(boundary.courseDegreesTrueNorth), 1e-9)
        assertEquals(UserMotionEstimate(), stale)
    }

    @Test
    fun rejectsNegativeOrFutureTimeWithoutOverflow() {
        assertEquals(UserMotionEstimate(), estimate(now = -1L))
        assertEquals(
            UserMotionEstimate(),
            estimate(
                speed = speed(at = -1L),
                course = heading(at = -1L),
                phoneHeading = heading(at = -1L),
                location = location(at = -1L),
            ),
        )
        assertEquals(
            UserMotionEstimate(),
            estimate(
                speed = speed(at = NOW_MS + 1L),
                course = heading(at = NOW_MS + 1L),
                phoneHeading = heading(at = NOW_MS + 1L),
                location = location(at = NOW_MS + 1L),
            ),
        )
        assertEquals(UserMotionEstimate(), estimate(now = Long.MAX_VALUE))
    }

    @Test
    fun missingNoisyAndMockLocationRejectMovementButKeepIndependentPhoneHeading() {
        val locations = listOf(
            null,
            location(accuracy = null),
            location(accuracy = -0.1),
            location(accuracy = 15.001),
            location(accuracy = Double.NaN),
            location(accuracy = Double.POSITIVE_INFINITY),
            location().copy(isMock = true),
        )

        locations.forEach { candidate ->
            val result = estimate(location = candidate)
            assertNull(result.speed)
            assertNull(result.course)
            assertEquals(240.0, requireNotNull(result.phoneHeadingDegreesTrueNorth), 1e-9)
        }
    }

    @Test
    fun speedNeedsPlausibleValueAndExplicitBoundedAccuracy() {
        val speeds = listOf(
            null,
            speed(value = -0.1),
            speed(value = 3.501),
            speed(value = Double.NaN),
            speed(value = Double.POSITIVE_INFINITY),
            speed(accuracy = null),
            speed(accuracy = -0.1),
            speed(accuracy = 0.801),
            speed(accuracy = Double.NaN),
            speed(accuracy = Double.POSITIVE_INFINITY),
        )

        speeds.forEach { candidate ->
            val result = estimate(speed = candidate)
            assertNull(result.speed)
            assertNull(result.course)
        }
        assertEquals(3.5, requireNotNull(estimate(speed = speed(value = 3.5)).speedMps), 1e-9)
    }

    @Test
    fun speedFromDifferentFixCannotBorrowLocationQuality() {
        val result = estimate(speed = speed(at = FIX_AT_MS + 1L))

        assertNull(result.speed)
        assertNull(result.course)
    }

    @Test
    fun courseFromDifferentFixCannotBorrowSpeed() {
        val result = estimate(course = heading(at = FIX_AT_MS + 1L))

        assertEquals(1.2, requireNotNull(result.speedMps), 1e-9)
        assertNull(result.course)
    }

    @Test
    fun invalidCourseDoesNotUsePhoneHeadingAsMovementDirection() {
        val courses = listOf(
            null,
            heading(value = Double.NaN),
            heading(value = Double.POSITIVE_INFINITY),
            heading(accuracy = null),
            heading(accuracy = -0.1),
            heading(accuracy = 35.001),
            heading(accuracy = Double.NaN),
        )

        courses.forEach { candidate ->
            val result = estimate(course = candidate)
            assertNull(result.course)
            assertEquals(1.2, requireNotNull(result.speedMps), 1e-9)
            assertEquals(240.0, requireNotNull(result.phoneHeadingDegreesTrueNorth), 1e-9)
        }
    }

    @Test
    fun phoneHeadingNeedsItsOwnFreshAccurateObservationAndNeverUsesCourse() {
        val phoneHeadings = listOf(
            null,
            heading(value = Double.NaN, at = NOW_MS),
            heading(accuracy = null, at = NOW_MS),
            heading(accuracy = -0.1, at = NOW_MS),
            heading(accuracy = 30.001, at = NOW_MS),
            heading(accuracy = Double.POSITIVE_INFINITY, at = NOW_MS),
            heading(at = NOW_MS - 501L),
        )

        phoneHeadings.forEach { candidate ->
            val result = estimate(phoneHeading = candidate)
            assertNull(result.phoneHeading)
            assertEquals(90.0, requireNotNull(result.courseDegreesTrueNorth), 1e-9)
        }
        val boundary = estimate(phoneHeading = heading(accuracy = 30.0, at = NOW_MS - 500L))
        assertEquals(90.0, requireNotNull(boundary.phoneHeadingDegreesTrueNorth), 1e-9)
    }

    @Test
    fun normalizesIndependentHeadingsWhilePreservingObservationTimestamps() {
        val result = estimate(
            course = heading(value = 450.0),
            phoneHeading = heading(value = -120.0, at = NOW_MS),
        )

        assertEquals(90.0, requireNotNull(result.courseDegreesTrueNorth), 1e-9)
        assertEquals(240.0, requireNotNull(result.phoneHeadingDegreesTrueNorth), 1e-9)
        assertEquals(FIX_AT_MS, requireNotNull(result.course).elapsedRealtimeMs)
        assertEquals(NOW_MS, requireNotNull(result.phoneHeading).elapsedRealtimeMs)
    }

    private fun estimate(
        now: Long = NOW_MS,
        speed: WalkingSpeedObservation? = speed(),
        course: HeadingObservation? = heading(),
        phoneHeading: HeadingObservation? = heading(value = 240.0, at = NOW_MS),
        location: UserMotionLocationEvidence? = location(),
    ) = UserMotionEstimateFactory.estimate(now, speed, course, phoneHeading, location)

    private fun speed(
        value: Double = 1.2,
        accuracy: Double? = 0.2,
        at: Long = FIX_AT_MS,
    ) = WalkingSpeedObservation(value, accuracy, at)

    private fun heading(
        value: Double = 90.0,
        accuracy: Double? = 5.0,
        at: Long = FIX_AT_MS,
    ) = HeadingObservation(value, accuracy, at)

    private fun location(
        accuracy: Double? = 5.0,
        at: Long = FIX_AT_MS,
    ) = UserMotionLocationEvidence(at, accuracy, isMock = false)

    private companion object {
        const val FIX_AT_MS = 1_000L
        const val NOW_MS = 1_200L
    }
}
