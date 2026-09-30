package kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GnssQualitySnapshotProvenanceTest {
    @Test
    fun snapshotExposesWhetherTheLatestRawEpochUsedTheMeasurementClockOrReceiptTime() {
        val source = ManualSource()
        val snapshots = mutableListOf<GnssQualitySnapshot>()
        GnssQualityObserver(source = source, onSnapshot = snapshots::add).start()

        source.measurements?.onObservation(requireNotNull(gnssSignalEpochOrNull(signals(), 2_000L, 1_000L, 25.0)))
        assertEquals(GnssSignalTimeSource.MEASUREMENT_ELAPSED_REALTIME, snapshots.last().latestEpochTimeSource)
        assertEquals(25.0, requireNotNull(snapshots.last().latestEpochTimestampUncertaintyNanos), 0.0)
        assertEquals(1_000L, snapshots.last().latestEpochAtNanos)

        source.measurements?.onObservation(requireNotNull(gnssSignalEpochOrNull(signals(), 3_000L)))
        assertEquals(GnssSignalTimeSource.CALLBACK_RECEIVED, snapshots.last().latestEpochTimeSource)
        assertNull(snapshots.last().latestEpochTimestampUncertaintyNanos)
    }

    @Test
    fun historicalQueryReportsTheProvenanceOfTheEpochItActuallyUsed() {
        val estimator = GnssQualityEstimator()
        estimator.addEpoch(1_000L, signals(), GnssSignalTimeSource.MEASUREMENT_ELAPSED_REALTIME, 10.0)
        estimator.addEpoch(2_000L, signals(), GnssSignalTimeSource.CALLBACK_RECEIVED, null)

        val historical = estimator.snapshot(1_500L)
        assertEquals(GnssSignalTimeSource.MEASUREMENT_ELAPSED_REALTIME, historical.latestEpochTimeSource)
        assertEquals(10.0, requireNotNull(historical.latestEpochTimestampUncertaintyNanos), 0.0)
        assertEquals(GnssSignalTimeSource.CALLBACK_RECEIVED, estimator.snapshot(2_000L).latestEpochTimeSource)
        assertNull(estimator.snapshot(500L).latestEpochTimeSource)
        assertNull(GnssQualityEstimator().addEpoch(1_000L, signals()).latestEpochTimeSource)
    }

    private fun signals() = listOf(
        GnssSatelliteSignal(1, 1, GnssFrequencyClassifier.L1_CENTER_HZ, 35.0, usedInFixKnown = false),
    )

    private class ManualSource : GnssObservationSource {
        var measurements: GnssObservationListener? = null

        override fun subscribeStatus(listener: GnssObservationListener): GnssObservationSubscription? = null

        override fun subscribeMeasurements(listener: GnssObservationListener): GnssObservationSubscription {
            measurements = listener
            return GnssObservationSubscription { measurements = null }
        }
    }
}
