package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure history tests; Tracker/SDK registration and native callbacks are separate boundaries. */
class DeviceEarthOrientationHistoryTest {
    @Test
    fun captureUsesPastFullMatrixEvenWhenLatestMeasurementIsInItsFuture() {
        val history = DeviceEarthOrientationHistory()
        val past = orientation(995L, ROLLED, 7f, EarthOrientationAccuracy.MEDIUM)
        val future = orientation(1_005L, IDENTITY, 3f, EarthOrientationAccuracy.HIGH)
        assertTrue(history.add(past))
        assertTrue(history.add(future))

        val selected = history.atOrBefore(1_000L, 150L)

        assertSame(past, selected)
        assertEquals(ROLLED, selected?.deviceToMagneticEnu)
        assertEquals(995L, selected?.observedAtElapsedRealtimeMs)
        assertEquals(7f, requireNotNull(selected).headingErrorDeg, 0f)
        assertEquals(EarthOrientationAccuracy.MEDIUM, selected.accuracy)
        assertSame(future, history.atOrBefore(1_005L, 0L))
    }

    @Test
    fun futureOnlyHistoryAndNegativeQueryOrAgeRemainUnavailable() {
        val history = DeviceEarthOrientationHistory()
        history.add(orientation(1_005L))

        assertNull(history.atOrBefore(1_000L, 150L))
        assertNull(history.atOrBefore(-1L, 150L))
        assertNull(history.atOrBefore(1_005L, -1L))
        assertNull(history.atOrBefore(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun callerAgeIsInclusiveAndRetentionDoesNotAuthorizeOlderEvidence() {
        val history = DeviceEarthOrientationHistory()
        val original = orientation(1_000L)
        history.add(original)

        assertSame(original, history.atOrBefore(1_150L, 150L))
        assertNull(history.atOrBefore(1_151L, 150L))
        assertSame(original, history.atOrBefore(1_000L, 0L))
        assertNull(history.atOrBefore(1_001L, 0L))
    }

    @Test
    fun malformedNegativeDuplicateAndOlderSamplesDoNotConsumeHistoryTime() {
        val history = DeviceEarthOrientationHistory()
        val original = orientation(1_000L)
        assertTrue(history.add(original))
        assertFalse(history.add(orientation(-1L)))
        assertFalse(history.add(orientation(999L)))
        assertFalse(history.add(orientation(1_000L, ROLLED)))
        assertFalse(history.add(orientation(1_001L, IDENTITY.copy(m12 = Float.NaN))))
        assertFalse(history.add(orientation(1_001L, error = Float.NaN)))
        assertFalse(history.add(orientation(1_001L, error = Float.POSITIVE_INFINITY)))
        assertFalse(history.add(orientation(1_001L, error = -1f)))
        assertSame(original, history.atOrBefore(1_001L, 150L))
        assertTrue(history.add(orientation(1_001L, ROLLED)))
    }

    @Test
    fun clearRevokesOldMatricesAndAllowsNewLifetimeMeasurementTimes() {
        val history = DeviceEarthOrientationHistory()
        history.add(orientation(2_000L))

        history.clear()

        assertNull(history.atOrBefore(2_000L, 150L))
        val nextLifetime = orientation(100L, ROLLED)
        assertTrue(history.add(nextLifetime))
        assertSame(nextLifetime, history.atOrBefore(100L, 0L))
    }

    @Test
    fun resourceCountAndRetentionAgeEvictWithoutReturningFutureMatrices() {
        val byCount = DeviceEarthOrientationHistory(maximumEntries = 2)
        listOf(1_000L, 1_100L, 1_200L).forEach { byCount.add(orientation(it)) }
        assertNull(byCount.atOrBefore(1_050L, 150L))
        assertEquals(1_100L, byCount.atOrBefore(1_150L, 150L)?.observedAtElapsedRealtimeMs)
        val byAge = DeviceEarthOrientationHistory(maximumRetainedAgeMs = 100L)
        byAge.add(orientation(1_000L))
        byAge.add(orientation(1_100L))
        assertEquals(1_000L, byAge.atOrBefore(1_000L, 0L)?.observedAtElapsedRealtimeMs)
        byAge.add(orientation(1_101L))
        assertNull(byAge.atOrBefore(1_000L, 150L))
    }

    @Test
    fun qualityAndLargeFiniteErrorArePreservedForExistingConsumerGates() {
        val history = DeviceEarthOrientationHistory()
        val degraded = orientation(1_000L, ROLLED, 100f, EarthOrientationAccuracy.LOW)
        history.add(degraded)

        assertSame(degraded, history.atOrBefore(1_000L, 0L))
        assertEquals(EarthOrientationAccuracy.LOW, history.atOrBefore(1_000L, 0L)?.accuracy)
        assertEquals(100f, requireNotNull(history.atOrBefore(1_000L, 0L)).headingErrorDeg, 0f)
    }

    private fun orientation(at: Long, matrix: RotationMatrix3 = IDENTITY, error: Float = 5f,
        accuracy: EarthOrientationAccuracy = EarthOrientationAccuracy.HIGH) =
        DeviceEarthOrientation(matrix, at, error, accuracy)

    private companion object {
        val IDENTITY = RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val ROLLED = RotationMatrix3(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
    }
}
