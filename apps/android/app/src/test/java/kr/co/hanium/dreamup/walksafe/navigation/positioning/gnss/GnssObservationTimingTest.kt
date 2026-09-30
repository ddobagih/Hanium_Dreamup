package kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GnssObservationTimingTest {
    @Test
    fun delayedMeasurementKeepsItsSourceTimeReceiptAndUncertainty() {
        val epoch = requireNotNull(gnssSignalEpochOrNull(
            signals = emptyList(),
            receivedAtElapsedRealtimeNanos = 2_000L,
            measuredAtElapsedRealtimeNanos = 1_000L,
            timestampUncertaintyNanos = 25.0,
        ))

        assertEquals(1_000L, epoch.elapsedRealtimeNanos)
        assertEquals(2_000L, epoch.receivedAtElapsedRealtimeNanos)
        assertEquals(GnssSignalTimeSource.MEASUREMENT_ELAPSED_REALTIME, epoch.timeSource)
        assertEquals(25.0, requireNotNull(epoch.timestampUncertaintyNanos), 0.0)
    }

    @Test
    fun unavailableMeasurementClockHasExplicitReceiptTimeProvenance() {
        val epoch = requireNotNull(gnssSignalEpochOrNull(emptyList(), 2_000L))

        assertEquals(2_000L, epoch.elapsedRealtimeNanos)
        assertEquals(2_000L, epoch.receivedAtElapsedRealtimeNanos)
        assertEquals(GnssSignalTimeSource.CALLBACK_RECEIVED, epoch.timeSource)
        assertNull(epoch.timestampUncertaintyNanos)
    }

    @Test
    fun missingUncertaintyDoesNotInventAnExactClock() {
        val epoch = requireNotNull(gnssSignalEpochOrNull(emptyList(), 2_000L, 1_000L))

        assertEquals(GnssSignalTimeSource.MEASUREMENT_ELAPSED_REALTIME, epoch.timeSource)
        assertNull(epoch.timestampUncertaintyNanos)
    }

    @Test
    fun invalidProvidedClockIsRejectedInsteadOfFallingBackToReceipt() {
        for (measuredAt in listOf(-1L, 2_001L, Long.MAX_VALUE)) {
            assertNull(gnssSignalEpochOrNull(emptyList(), 2_000L, measuredAt))
        }
    }

    @Test
    fun invalidReceiptOrUncertaintyCannotReachTheObserver() {
        assertNull(gnssSignalEpochOrNull(emptyList(), -1L))
        for (uncertainty in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(gnssSignalEpochOrNull(emptyList(), 2_000L, 1_000L, uncertainty))
        }
    }

    @Test
    fun repeatedSourceTimeWithLaterReceiptDoesNotRenewMeasurementTime() {
        val first = requireNotNull(gnssSignalEpochOrNull(emptyList(), 2_000L, 1_000L))
        val repeated = requireNotNull(gnssSignalEpochOrNull(emptyList(), 3_000L, 1_000L))

        assertEquals(first.elapsedRealtimeNanos, repeated.elapsedRealtimeNanos)
        assertEquals(first.timeSource, repeated.timeSource)
        assertEquals(3_000L, repeated.receivedAtElapsedRealtimeNanos)
    }
}
