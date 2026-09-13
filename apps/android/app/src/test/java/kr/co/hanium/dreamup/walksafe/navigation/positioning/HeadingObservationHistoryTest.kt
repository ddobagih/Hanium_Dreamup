package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingObservationHistoryTest {
    @Test
    fun delayedStepGetsPastHeadingEvenWhenNewestSensorSampleIsInItsFuture() {
        val history = HeadingObservationHistory()
        history.add(heading(1_000L, 10.0))
        history.add(heading(1_200L, 90.0))

        assertEquals(heading(1_000L, 10.0), history.atOrBefore(1_100L))
        assertNull(history.atOrBefore(999L))
        assertEquals(heading(1_200L, 90.0), history.atOrBefore(1_200L))
    }

    @Test
    fun queryFreshnessAndGpsAlignmentHaveInclusiveTimeLimits() {
        val history = HeadingObservationHistory()
        history.add(heading(1_000L))

        assertEquals(heading(1_000L), history.atOrBefore(1_500L))
        assertNull(history.atOrBefore(1_501L))
        assertEquals(heading(1_000L), history.atOrBefore(1_100L, maximumAgeMs = 100L))
        assertNull(history.atOrBefore(1_101L, maximumAgeMs = 100L))
    }

    @Test
    fun duplicateOlderAndInvalidSamplesNeverReplaceTrustedHistory() {
        val history = HeadingObservationHistory()
        assertTrue(history.add(heading(1_000L)))

        assertFalse(history.add(heading(1_000L, 90.0)))
        assertFalse(history.add(heading(999L, 90.0)))
        assertFalse(history.add(heading(1_100L, Double.NaN)))
        assertFalse(history.add(heading(1_100L).copy(accuracyDegrees = null)))
        assertFalse(history.add(heading(-1L)))
        assertEquals(heading(1_000L), history.atOrBefore(1_100L))
    }

    @Test
    fun entryAndAgeLimitsEvictOldSamplesWithoutReturningFutureSamples() {
        val byCount = HeadingObservationHistory(maximumEntries = 2)
        listOf(1_000L, 1_100L, 1_200L).forEach { byCount.add(heading(it)) }
        assertNull(byCount.atOrBefore(1_050L))
        assertEquals(heading(1_100L), byCount.atOrBefore(1_150L))
        val byAge = HeadingObservationHistory(maximumRetainedAgeMs = 100L)
        byAge.add(heading(1_000L))
        byAge.add(heading(1_100L))
        assertEquals(heading(1_000L), byAge.atOrBefore(1_000L))
        byAge.add(heading(1_101L))
        assertNull(byAge.atOrBefore(1_000L))
    }

    @Test
    fun invalidGateClearPreventsReuseAndAllowsFreshEpochTimestamps() {
        val history = HeadingObservationHistory()
        history.add(heading(2_000L))

        history.clear()

        assertNull(history.atOrBefore(2_100L))
        assertTrue(history.add(heading(100L, 90.0)))
        assertEquals(heading(100L, 90.0), history.atOrBefore(100L))
    }

    private fun heading(time: Long, degrees: Double = 10.0) = HeadingObservation(degrees, 5.0, time)
}
