package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-007: original location facts survive independently of the movement classification. */
class UserMotionGnssOriginalFixRegressionTest {
    @Test
    fun identicalSpeedsAtDifferentOriginalCoordinatesRemainDistinguishable() {
        val first = snapshot(location(1_000L, 37.0, 127.0, 1_000_123_456L))
        val second = snapshot(location(1_000L, 37.001, 127.002, 1_000_987_654L))

        assertEquals(first.movement, second.movement)
        assertEquals(first.expiresAtElapsedRealtimeMs, second.expiresAtElapsedRealtimeMs)
        assertNotEquals(first.evidenceRefs, second.evidenceRefs)
        val actual = requireNotNull(second.evidenceRefs.single().locationEvidence)
        assertEquals(37.001, requireNotNull(actual.latitude), 0.0)
        assertEquals(127.002, requireNotNull(actual.longitude), 0.0)
        assertEquals(1_000_987_654L, actual.measurementElapsedRealtimeNanos)
        assertEquals(2.0, requireNotNull(actual.horizontalAccuracyM), 0.0)
        assertNull(second.evidenceRefs.single().observationId)
    }

    @Test
    fun originalMeasurementClockAndActualReceiptKeepTheirSeparatePrecision() {
        val state = snapshot(location(1_000L, 37.0, 179.999, 1_000_123_456L))
        val ref = state.evidenceRefs.single()

        assertEquals(1_000L, ref.measuredAtElapsedRealtimeMs)
        assertEquals(1_100L, ref.receivedAtElapsedRealtimeMs)
        assertEquals(1_000_123_456L, ref.locationEvidence?.measurementElapsedRealtimeNanos)
        assertEquals(179.999, requireNotNull(ref.locationEvidence?.longitude), 0.0)
        assertEquals(1_000L, ref.speedObservation?.elapsedRealtimeMs)
        assertEquals(3_000L, state.expiresAtElapsedRealtimeMs)
    }

    @Test
    fun invalidOriginalCoordinatesCannotConsumeTheFixTimestampOrIdentity() {
        val valid = location(1_000L, 37.0, 127.0, 1_000_123_456L).copy(observationId = 41L)
        for (invalid in listOf(
            valid.copy(latitude = Double.NaN), valid.copy(latitude = 91.0),
            valid.copy(longitude = Double.POSITIVE_INFINITY), valid.copy(longitude = 181.0),
        )) assertRejectedThenValidRetry(invalid, valid)
    }

    @Test
    fun invalidOriginalMeasurementNanosCannotConsumeTheFixTimestampOrIdentity() {
        val valid = location(1_000L, 37.0, 127.0, 1_000_123_456L).copy(observationId = 41L)
        for (nanos in listOf(-1L, 2_000_000_000L)) {
            assertRejectedThenValidRetry(valid.copy(measurementElapsedRealtimeNanos = nanos), valid)
        }
    }

    @Test
    fun legacyFixWithoutOriginalMetadataRemainsAccepted() {
        val evidence = UserMotionLocationEvidence(1_000L, 2.0, false)
        assertEquals(evidence, snapshot(evidence).evidenceRefs.single().locationEvidence)
    }

    private fun assertRejectedThenValidRetry(invalid: UserMotionLocationEvidence, valid: UserMotionLocationEvidence) {
        val coordinator = PositioningCoordinator()
        coordinator.observeUserSpeed(speed(1_000L), invalid, 1_100L)
        assertTrue("invalid=$invalid", coordinator.currentUserMotion(1_200L).evidenceRefs.isEmpty())
        coordinator.observeUserSpeed(speed(1_000L), valid, 1_100L)
        assertEquals(valid, coordinator.currentUserMotion(1_200L).evidenceRefs.single().locationEvidence)
    }

    @Test
    fun newerFixAndExpiryCannotMutateAnAlreadyPublishedOriginalFix() {
        val coordinator = PositioningCoordinator()
        val original = location(1_000L, 37.0, 127.0, 1_000_123_456L)
        coordinator.observeUserSpeed(speed(1_000L), original, 1_100L)
        val first = coordinator.currentUserMotion(1_200L)
        val newer = location(2_000L, 37.001, 127.001, 2_000_987_654L)
        coordinator.observeUserSpeed(speed(2_000L), newer, 2_100L)
        val second = coordinator.currentUserMotion(2_200L)

        assertEquals(original, first.evidenceRefs.single().locationEvidence)
        assertEquals(newer, second.evidenceRefs.single().locationEvidence)
        assertEquals(1_100L, first.evidenceRefs.single().receivedAtElapsedRealtimeMs)
        assertTrue(coordinator.currentUserMotion(4_001L).evidenceRefs.isEmpty())
        assertEquals(original, first.evidenceRefs.single().locationEvidence)
        assertEquals(3_000L, first.expiresAtElapsedRealtimeMs)
    }

    private fun snapshot(evidence: UserMotionLocationEvidence): UserMotionStateSnapshot {
        val coordinator = PositioningCoordinator()
        coordinator.observeUserSpeed(speed(evidence.observedAtElapsedRealtimeMs), evidence, 1_100L)
        return coordinator.currentUserMotion(1_200L)
    }

    private fun location(at: Long, latitude: Double, longitude: Double, nanos: Long) =
        UserMotionLocationEvidence(at, 2.0, false, latitude = latitude, longitude = longitude,
            measurementElapsedRealtimeNanos = nanos)

    private fun speed(at: Long) = WalkingSpeedObservation(1.0, 0.1, at)
}
