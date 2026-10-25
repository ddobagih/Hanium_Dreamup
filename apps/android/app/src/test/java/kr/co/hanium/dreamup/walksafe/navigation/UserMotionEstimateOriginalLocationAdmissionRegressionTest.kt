package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingSpeedObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** ADD-MOTION-032/035: supplied original fix facts must pass admission before producing motion. */
class UserMotionEstimateOriginalLocationAdmissionRegressionTest {
    @Test
    fun futureRawClockCannotBorrowTheFreshEnvelopeWhilePhoneHeadingRemainsIndependent() {
        val original = location().copy(measurementElapsedRealtimeNanos = 2_000_000_000L)

        assertRejectedMovement(original)
        assertEquals(2_000_000_000L, original.measurementElapsedRealtimeNanos)
        assertEquals(FIX_AT_MS, original.observedAtElapsedRealtimeMs)
    }

    @Test
    fun negativeOriginalClockIsRejectedWithoutRewritingItsFacts() {
        val original = location().copy(measurementElapsedRealtimeNanos = -1L)

        assertRejectedMovement(original)
        assertEquals(-1L, original.measurementElapsedRealtimeNanos)
    }

    @Test
    fun originalClockMustAgreeWithItsEnvelopeAtMillisecondPrecision() {
        for (nanos in listOf(999_999_999L, 1_001_000_000L, Long.MAX_VALUE)) {
            assertRejectedMovement(location().copy(measurementElapsedRealtimeNanos = nanos))
        }
    }

    @Test
    fun invalidOriginalLatitudeRejectsOnlyLocationOwnedMotion() {
        for (latitude in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -91.0, 91.0)) {
            assertRejectedMovement(location().copy(latitude = latitude))
        }
    }

    @Test
    fun invalidOriginalLongitudeRejectsOnlyLocationOwnedMotion() {
        for (longitude in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -181.0, 181.0)) {
            assertRejectedMovement(location().copy(longitude = longitude))
        }
    }

    @Test
    fun negativeSuppliedIdentityUsesTheSameDomainAsTheStateEstimator() {
        for (id in listOf(-1L, Long.MIN_VALUE)) {
            assertRejectedMovement(location().copy(observationId = id))
        }
    }

    @Test
    fun validOriginalFixKeepsSourceTimestampsAndNanosecondPrecision() {
        val original = location().copy(observationId = 41L)
        val speed = speed()
        val result = estimate(original, speed = speed)

        assertAcceptedMovement(result)
        assertSame(speed, result.speed)
        assertEquals(FIX_AT_MS, result.speed?.elapsedRealtimeMs)
        assertEquals(FIX_AT_MS, result.course?.elapsedRealtimeMs)
        assertEquals(NOW_MS, result.phoneHeading?.elapsedRealtimeMs)
        assertEquals(1_000_123_456L, original.measurementElapsedRealtimeNanos)
        assertEquals(41L, original.observationId)
        assertEquals(37.0, requireNotNull(original.latitude), 0.0)
        assertEquals(127.0, requireNotNull(original.longitude), 0.0)
    }

    @Test
    fun missingOptionalMetadataRetainsLegacyAndIndividuallySuppliedFacts() {
        val legacy = UserMotionLocationEvidence(FIX_AT_MS, 2.0, false)
        for (original in listOf(legacy, legacy.copy(latitude = 37.0), legacy.copy(longitude = 127.0),
            legacy.copy(measurementElapsedRealtimeNanos = 1_000_123_456L), legacy.copy(observationId = 0L))) {
            assertAcceptedMovement(estimate(original))
        }
        assertNull(legacy.measurementElapsedRealtimeNanos)
        assertNull(legacy.observationId)
    }

    @Test
    fun zeroOriginalClockAndEnvelopeAreValidWithoutAReplacementIdentity() {
        val original = location().copy(observedAtElapsedRealtimeMs = 0L, measurementElapsedRealtimeNanos = 0L)
        val result = estimate(original, now = 100L)

        assertAcceptedMovement(result, phoneAt = 100L)
        assertEquals(0L, result.speed?.elapsedRealtimeMs)
        assertEquals(0L, result.course?.elapsedRealtimeMs)
        assertNull(original.observationId)
        assertEquals(0L, original.measurementElapsedRealtimeNanos)
    }

    @Test
    fun coordinateAndIdentityDomainBoundariesRemainAccepted() {
        for (latitude in listOf(-90.0, 90.0)) for (longitude in listOf(-180.0, 180.0)) {
            for (id in listOf(0L, Long.MAX_VALUE)) {
                assertAcceptedMovement(estimate(location().copy(latitude = latitude, longitude = longitude,
                    observationId = id)))
            }
        }
    }

    @Test
    fun rejectedFactsDoNotPoisonAValidRetryOrCreateAStatelessDuplicateWatermark() {
        val original = location().copy(observationId = 41L)
        assertRejectedMovement(original.copy(measurementElapsedRealtimeNanos = 2_000_000_000L))
        val first = estimate(original)
        val repeated = estimate(original)

        assertAcceptedMovement(first)
        assertEquals(first, repeated)
        assertEquals(FIX_AT_MS, repeated.speed?.elapsedRealtimeMs)
        assertEquals(FIX_AT_MS, repeated.course?.elapsedRealtimeMs)
    }

    @Test
    fun originalFixExpiresAtTheExistingBoundaryWithoutExpiringIndependentPhoneHeading() {
        val original = location()
        assertAcceptedMovement(estimate(original, now = FIX_AT_MS + 2_000L), phoneAt = FIX_AT_MS + 2_000L)
        val expired = estimate(original, now = FIX_AT_MS + 2_001L)

        assertNull(expired.speed)
        assertNull(expired.course)
        assertEquals(240.0, requireNotNull(expired.phoneHeadingDegreesTrueNorth), 0.0)
        assertEquals(FIX_AT_MS + 2_001L, expired.phoneHeading?.elapsedRealtimeMs)
    }

    @Test
    fun validOriginalMetadataCannotBypassMockOrNoisyLocationChecks() {
        for (original in listOf(location().copy(isMock = true), location().copy(horizontalAccuracyM = 15.001),
            location().copy(horizontalAccuracyM = Double.NaN), location().copy(horizontalAccuracyM = null))) {
            assertRejectedMovement(original)
        }
    }

    private fun assertRejectedMovement(original: UserMotionLocationEvidence) {
        val result = estimate(original)
        assertEquals("independent phone heading for $original", 240.0,
            requireNotNull(result.phoneHeadingDegreesTrueNorth), 0.0)
        assertEquals(NOW_MS, result.phoneHeading?.elapsedRealtimeMs)
        assertNull("speed for $original", result.speed)
        assertNull("course for $original", result.course)
    }

    private fun assertAcceptedMovement(result: UserMotionEstimate, phoneAt: Long = NOW_MS) {
        assertEquals(1.2, requireNotNull(result.speedMps), 0.0)
        assertEquals(90.0, requireNotNull(result.courseDegreesTrueNorth), 0.0)
        assertEquals(240.0, requireNotNull(result.phoneHeadingDegreesTrueNorth), 0.0)
        assertEquals(phoneAt, result.phoneHeading?.elapsedRealtimeMs)
    }

    private fun estimate(
        original: UserMotionLocationEvidence,
        now: Long = NOW_MS,
        speed: WalkingSpeedObservation = speed(original.observedAtElapsedRealtimeMs),
    ) = UserMotionEstimateFactory.estimate(now, speed,
        HeadingObservation(90.0, 5.0, original.observedAtElapsedRealtimeMs),
        HeadingObservation(240.0, 5.0, now), original)

    private fun location() = UserMotionLocationEvidence(FIX_AT_MS, 2.0, false,
        latitude = 37.0, longitude = 127.0, measurementElapsedRealtimeNanos = 1_000_123_456L)

    private fun speed(at: Long = FIX_AT_MS) = WalkingSpeedObservation(1.2, 0.1, at)

    private companion object {
        const val FIX_AT_MS = 1_000L
        const val NOW_MS = 1_100L
    }
}
