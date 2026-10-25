package kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-007: the real signal source supplies receipt separately from measurement time. */
class GnssQualityObserverReceiptProvenanceTest {
    @Test
    fun identicalMeasuredEpochsWithDifferentActualReceiptsRemainDistinguishable() {
        val first = fixture()
        val second = fixture()
        val firstInput = epoch(1_000_000_000L, 1_100_000_000L)
        val secondInput = epoch(1_000_000_000L, 1_200_000_000L)
        assertEquals(firstInput.elapsedRealtimeNanos, secondInput.elapsedRealtimeNanos)
        assertEquals(firstInput.signals, secondInput.signals)
        assertNotEquals(firstInput.receivedAtElapsedRealtimeNanos, secondInput.receivedAtElapsedRealtimeNanos)
        first.source.emit(firstInput)
        second.source.emit(secondInput)
        val firstResult = first.observer.snapshot(1_250_000_000L)
        val secondResult = second.observer.snapshot(1_250_000_000L)

        assertEquals(1_000_000_000L, firstResult.latestEpochAtNanos)
        assertEquals(firstResult.latestEpochAtNanos, secondResult.latestEpochAtNanos)
        assertEquals(firstResult.evaluatedAtNanos, secondResult.evaluatedAtNanos)
        assertEquals(firstResult.medianCn0DbHz, secondResult.medianCn0DbHz)
        assertEquals(firstResult.frequencySummary, secondResult.frequencySummary)
        assertEquals(firstResult.signalAvailability, secondResult.signalAvailability)
        assertEquals(firstResult.measurementNoiseMultiplier, secondResult.measurementNoiseMultiplier, 0.0)
        assertFalse(firstResult.isStale)
        assertFalse(secondResult.isStale)
        assertEquals(1_100_000_000L, firstResult.latestEpochReceivedAtNanos)
        assertEquals(1_200_000_000L, secondResult.latestEpochReceivedAtNanos)
        assertNotEquals("Different source receipts must survive the actual observer API", firstResult, secondResult)
    }

    @Test
    fun duplicateAndOlderMeasuredEpochsCannotReplaceTheFirstAcceptedObservation() {
        val fixture = fixture()
        fixture.source.emit(epoch(1_000_000_000L, 1_100_000_000L))
        val first = fixture.observer.snapshot(1_250_000_000L)
        fixture.source.emit(epoch(1_000_000_000L, 1_200_000_000L))
        fixture.source.emit(epoch(900_000_000L, 1_210_000_000L))
        val result = fixture.observer.snapshot(1_250_000_000L)

        assertEquals(first, result)
        assertEquals(1, fixture.published.size)
        assertEquals(1, result.epochCount)
        assertEquals(1_000_000_000L, result.latestEpochAtNanos)
    }

    @Test
    fun queriesDoNotTurnExpiredMeasurementsIntoFreshSignalQuality() {
        val fixture = fixture()
        fixture.source.emit(epoch(1_000_000_000L, 1_100_000_000L))
        val first = fixture.observer.snapshot(1_250_000_000L)
        val expired = fixture.observer.snapshot(6_000_000_001L)

        assertTrue(expired.isStale)
        assertNull(expired.latestEpochAtNanos)
        assertEquals(0, expired.epochCount)
        assertEquals(0, expired.finiteSignalCount)
        assertNull(expired.medianCn0DbHz)
        assertEquals(GnssSignalAvailability.LOST_EXPIRED, expired.signalAvailability)
        assertEquals(1.0, expired.measurementNoiseMultiplier, 0.0)
        assertEquals(1_000_000_000L, first.latestEpochAtNanos)
        assertFalse(first.isStale)
        assertEquals(first, fixture.observer.snapshot(1_250_000_000L))
        assertEquals(1, fixture.published.size)
    }

    @Test
    fun expiryQueryCannotReadmitADuplicateOrOlderMeasurementCallback() {
        val fixture = fixture()
        fixture.source.emit(epoch(1_000_000_000L, 1_100_000_000L))
        assertTrue(fixture.observer.snapshot(6_000_000_001L).isStale)

        fixture.source.emit(epoch(1_000_000_000L, 6_100_000_000L))
        fixture.source.emit(epoch(900_000_000L, 6_200_000_000L))

        assertEquals(1, fixture.published.size)
        assertTrue(fixture.observer.snapshot(6_200_000_000L).isStale)
        val original = fixture.observer.snapshot(1_250_000_000L)
        assertEquals(1_000_000_000L, original.latestEpochAtNanos)
        assertEquals(1_100_000_000L, original.latestEpochReceivedAtNanos)
        fixture.source.emit(epoch(6_300_000_000L, 6_350_000_000L))
        assertEquals(2, fixture.published.size)
        assertEquals(6_300_000_000L, fixture.observer.snapshot(6_400_000_000L).latestEpochAtNanos)
    }

    @Test
    fun stoppingClearsCurrentEvidenceAndRestartIgnoresTheOldSubscription() {
        val fixture = fixture()
        val oldListener = requireNotNull(fixture.source.measurementListener)
        fixture.source.emit(epoch(1_000_000_000L, 1_100_000_000L))
        val first = fixture.observer.snapshot(1_250_000_000L)
        fixture.observer.stop()
        val stopped = fixture.observer.snapshot(1_300_000_000L)
        assertNull(stopped.latestEpochAtNanos)
        assertEquals(0, stopped.epochCount)
        assertEquals(GnssSignalAvailability.UNSUPPORTED, stopped.signalAvailability)

        assertTrue(fixture.observer.start().measurementsRegistered)
        oldListener.onObservation(epoch(1_400_000_000L, 1_450_000_000L))
        assertNull(fixture.observer.snapshot(1_500_000_000L).latestEpochAtNanos)
        assertEquals(1, fixture.published.size)
        fixture.source.emit(epoch(1_400_000_000L, 1_450_000_000L))
        val restarted = fixture.observer.snapshot(1_500_000_000L)
        assertEquals(1_400_000_000L, restarted.latestEpochAtNanos)
        assertEquals(1, restarted.epochCount)
        assertEquals(2, fixture.published.size)
        assertEquals(1_000_000_000L, first.latestEpochAtNanos)
    }

    private fun fixture(): Fixture {
        val source = Source()
        val published = mutableListOf<GnssQualitySnapshot>()
        val observer = GnssQualityObserver(source = source, onSnapshot = published::add)
        val registered = observer.start()
        assertTrue(registered.statusRegistered)
        assertTrue(registered.measurementsRegistered)
        return Fixture(source, observer, published)
    }

    private fun epoch(measuredAtNanos: Long, receivedAtNanos: Long) = requireNotNull(
        gnssSignalEpochOrNull(
            signals = listOf(GnssSatelliteSignal(1, 1, GnssFrequencyClassifier.L1_CENTER_HZ,
                35.0, usedInFix = false, usedInFixKnown = false)),
            receivedAtElapsedRealtimeNanos = receivedAtNanos,
            measuredAtElapsedRealtimeNanos = measuredAtNanos,
            timestampUncertaintyNanos = 10.0,
        ),
    )

    private data class Fixture(val source: Source, val observer: GnssQualityObserver,
        val published: MutableList<GnssQualitySnapshot>)

    private class Source : GnssObservationSource {
        var measurementListener: GnssObservationListener? = null
            private set

        override fun subscribeStatus(listener: GnssObservationListener) = GnssObservationSubscription {}

        override fun subscribeMeasurements(listener: GnssObservationListener): GnssObservationSubscription {
            measurementListener = listener
            return GnssObservationSubscription {
                if (measurementListener === listener) measurementListener = null
            }
        }

        fun emit(epoch: GnssSignalEpoch) = measurementListener?.onObservation(epoch)
    }
}
