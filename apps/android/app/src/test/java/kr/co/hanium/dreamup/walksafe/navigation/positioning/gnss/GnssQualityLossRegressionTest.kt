package kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GnssQualityLossRegressionTest {
    @Test
    fun unsupportedAndInsufficientObservationsRemainNeutral() {
        val unsupported = GnssQualityEstimator()
        assertNeutral(unsupported.snapshot(0L))
        assertNeutral(unsupported.addEpoch(0L, emptyList()))
        assertNeutral(unsupported.addEpoch(SECOND, emptyList()))
        assertNeutral(unsupported.addEpoch(2 * SECOND, emptyList()))

        val insufficient = GnssQualityEstimator()
        assertNeutral(insufficient.addEpoch(SECOND, poorSignals()))
    }

    @Test
    fun assessedPoorQualitySurvivesEmptyEpochOnlyUntilLastSignalExpires() {
        val estimator = GnssQualityEstimator()
        val poor = seedPoorQuality(estimator)
        assertEquals(2.2, poor.measurementNoiseMultiplier, 0.0001)

        val loss = estimator.addEpoch(4 * SECOND, emptyList())
        assertEquals(0, loss.finiteSignalCount)
        assertEquals(poor.signalEnvironmentRisk, loss.signalEnvironmentRisk)
        assertEquals(2.2, loss.measurementNoiseMultiplier, 0.0001)
        assertEquals(2.2, estimator.snapshot(8 * SECOND).measurementNoiseMultiplier, 0.0001)

        val expired = estimator.snapshot(8 * SECOND + 1L)
        assertFalse(expired.isStale)
        assertNeutral(expired)
    }

    @Test
    fun clearDiscardsHeldRisk() {
        val estimator = GnssQualityEstimator()
        seedPoorQuality(estimator)
        estimator.addEpoch(4 * SECOND, emptyList())

        estimator.clear()

        val cleared = estimator.snapshot(4 * SECOND)
        assertNull(cleared.latestEpochAtNanos)
        assertNeutral(cleared)
    }

    @Test
    fun backwardQueriesCannotBorrowFutureAssessmentOrLoss() {
        val estimator = GnssQualityEstimator()
        seedPoorQuality(estimator)
        estimator.addEpoch(4 * SECOND, emptyList())
        assertNeutral(estimator.snapshot(8 * SECOND + 1L))

        assertNeutral(estimator.snapshot(2 * SECOND + SECOND / 2))
        assertEquals(2.2, estimator.snapshot(3 * SECOND).measurementNoiseMultiplier, 0.0001)
        assertEquals(2.2, estimator.snapshot(4 * SECOND).measurementNoiseMultiplier, 0.0001)
    }

    private fun seedPoorQuality(estimator: GnssQualityEstimator): GnssQualitySnapshot {
        estimator.addEpoch(SECOND, poorSignals())
        estimator.addEpoch(2 * SECOND, poorSignals())
        return estimator.addEpoch(3 * SECOND, poorSignals())
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

    private fun assertNeutral(snapshot: GnssQualitySnapshot) {
        assertEquals(SignalEnvironmentRisk.UNKNOWN, snapshot.signalEnvironmentRisk)
        assertNull(snapshot.signalEnvironmentRiskScore)
        assertEquals(1.0, snapshot.measurementNoiseMultiplier, 0.0)
    }

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}
