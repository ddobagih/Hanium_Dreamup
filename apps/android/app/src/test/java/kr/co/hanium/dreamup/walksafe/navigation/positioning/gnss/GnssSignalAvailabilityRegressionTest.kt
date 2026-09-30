package kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S09-6 (originals/09.txt:23): "never supported" and "observed, then suddenly lost" are different
 * states. A loss keeps the previous risk only for the bounded stale window (5 s) and then expires.
 */
class GnssSignalAvailabilityRegressionTest {
    @Test
    fun unsupportedFromTheStartIsNamedAndNeutral() {
        val estimator = GnssQualityEstimator()
        assertState(estimator.snapshot(0L), GnssSignalAvailability.UNSUPPORTED, 1.0)
        assertState(estimator.addEpoch(SECOND, emptyList()), GnssSignalAvailability.UNSUPPORTED, 1.0)
        assertState(estimator.addEpoch(2 * SECOND, emptyList()), GnssSignalAvailability.UNSUPPORTED, 1.0)
        // Long after only empty epochs it is still "unsupported", never "lost".
        assertState(estimator.snapshot(30 * SECOND), GnssSignalAvailability.UNSUPPORTED, 1.0)
    }

    @Test
    fun lossAfterPoorObservationHoldsThePreviousWeightThenExpires() {
        val estimator = GnssQualityEstimator()
        val poor = seedPoorQuality(estimator)
        assertEquals(GnssSignalAvailability.OBSERVING, poor.signalAvailability)
        assertEquals(2.2, poor.measurementNoiseMultiplier, 0.0001)

        val loss = estimator.addEpoch(4 * SECOND, emptyList())
        assertState(loss, GnssSignalAvailability.LOST_HOLDING, 2.2)
        assertEquals(poor.signalEnvironmentRisk, loss.signalEnvironmentRisk)
        assertState(estimator.snapshot(8 * SECOND), GnssSignalAvailability.LOST_HOLDING, 2.2)

        val expired = estimator.snapshot(8 * SECOND + 1L)
        assertState(expired, GnssSignalAvailability.LOST_EXPIRED, 1.0)
        assertEquals(SignalEnvironmentRisk.UNKNOWN, expired.signalEnvironmentRisk)
        // The expired loss is not reported as "unsupported" and stays neutral (no indefinite bad state).
        val muchLater = estimator.addEpoch(9 * SECOND, emptyList())
        assertState(muchLater, GnssSignalAvailability.LOST_EXPIRED, 1.0)
    }

    @Test
    fun totalCallbackStopExpiresToLostNotUnsupported() {
        val estimator = GnssQualityEstimator()
        seedPoorQuality(estimator)

        val stale = estimator.snapshot(8 * SECOND + 1L)
        assertTrue(stale.isStale)
        assertState(stale, GnssSignalAvailability.LOST_EXPIRED, 1.0)

        // After a stale gap the window restarts, but the session still knows signals were observed.
        assertState(estimator.addEpoch(20 * SECOND, emptyList()), GnssSignalAvailability.LOST_EXPIRED, 1.0)
    }

    @Test
    fun freshSignalsRecoverAndClearReturnsToUnsupported() {
        val estimator = GnssQualityEstimator()
        seedPoorQuality(estimator)
        estimator.addEpoch(4 * SECOND, emptyList())
        estimator.snapshot(9 * SECOND)

        val recovered = estimator.addEpoch(10 * SECOND, poorSignals())
        assertEquals(GnssSignalAvailability.OBSERVING, recovered.signalAvailability)
        assertFalse(recovered.isStale)

        estimator.clear()
        assertState(estimator.snapshot(10 * SECOND), GnssSignalAvailability.UNSUPPORTED, 1.0)
    }

    @Test
    fun backwardQueryBeforeTheFirstSignalDoesNotBorrowALaterObservation() {
        val estimator = GnssQualityEstimator()
        estimator.addEpoch(SECOND, emptyList())
        seedPoorQuality(estimator, startSecond = 2)

        assertState(estimator.snapshot(SECOND + SECOND / 2), GnssSignalAvailability.UNSUPPORTED, 1.0)
        assertEquals(GnssSignalAvailability.OBSERVING, estimator.snapshot(4 * SECOND).signalAvailability)
    }

    /** w25 review pos-F5: nothing is "held" when no risk had been assessed before the loss. */
    @Test
    fun aLossBeforeAnyAssessedRiskIsNotReportedAsHolding() {
        val estimator = GnssQualityEstimator()
        // One epoch is observed, but fewer than the three epochs needed to assess risk.
        val observed = estimator.addEpoch(SECOND, poorSignals())
        assertEquals(GnssSignalAvailability.OBSERVING, observed.signalAvailability)
        assertEquals(SignalEnvironmentRisk.UNKNOWN, observed.signalEnvironmentRisk)
        val loss = estimator.addEpoch(2 * SECOND, emptyList())
        assertState(loss, GnssSignalAvailability.LOST_EXPIRED, 1.0)
        assertEquals(SignalEnvironmentRisk.UNKNOWN, loss.signalEnvironmentRisk)
        // An assessed risk is still held after a later loss.
        seedPoorQuality(estimator, startSecond = 3)
        assertState(estimator.addEpoch(6 * SECOND, emptyList()), GnssSignalAvailability.LOST_HOLDING, 2.2)
    }

    private fun seedPoorQuality(estimator: GnssQualityEstimator, startSecond: Long = 1): GnssQualitySnapshot {
        estimator.addEpoch(startSecond * SECOND, poorSignals())
        estimator.addEpoch((startSecond + 1) * SECOND, poorSignals())
        return estimator.addEpoch((startSecond + 2) * SECOND, poorSignals())
    }

    private fun poorSignals() = (1..4).map { svid ->
        GnssSatelliteSignal(
            constellation = 1,
            svid = svid,
            carrierFrequencyHz = GnssFrequencyClassifier.L1_CENTER_HZ,
            cn0DbHz = 13.0,
            usedInFix = true,
        )
    }

    private fun assertState(snapshot: GnssQualitySnapshot, expected: GnssSignalAvailability, weight: Double) {
        assertEquals(expected, snapshot.signalAvailability)
        assertEquals(weight, snapshot.measurementNoiseMultiplier, 0.0001)
    }

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}
