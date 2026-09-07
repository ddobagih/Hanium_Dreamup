package kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssQualityEstimatorTest {
    @Test
    fun calculatesStatisticsFromFiniteCn0Only() {
        val estimator = estimator(minEpochs = 1, minSignals = 3)

        val snapshot = estimator.addEpoch(
            1L,
            listOf(
                signal(1, 10.0),
                signal(2, 20.0),
                signal(3, 30.0),
                signal(4, null),
                signal(5, Double.NaN),
                signal(6, Double.POSITIVE_INFINITY),
            ),
        )

        assertEquals(3, snapshot.finiteSignalCount)
        assertEquals(20.0, snapshot.medianCn0DbHz!!, 0.0001)
        assertEquals(15.0, snapshot.p25Cn0DbHz!!, 0.0001)
        assertTrue(snapshot.measurementNoiseMultiplier.isFinite())
        assertTrue(snapshot.measurementNoiseMultiplier in 1.0..4.0)
    }

    @Test
    fun ignoresNonMonotonicEpochsAndBoundsWindowByCountAndDuration() {
        val estimator = GnssQualityEstimator(
            GnssQualityEstimatorConfig(
                windowDurationNanos = 25L,
                staleAfterNanos = 100L,
                maxEpochCount = 2,
                minEpochCountForRisk = 2,
                minSignalCountForRisk = 1,
            ),
        )

        estimator.addEpoch(10L, listOf(signal(1, 10.0)))
        estimator.addEpoch(20L, listOf(signal(1, 20.0)))
        val ignored = estimator.addEpoch(15L, listOf(signal(1, 99.0)))
        val countBounded = estimator.addEpoch(30L, listOf(signal(1, 30.0)))
        val durationBounded = estimator.addEpoch(60L, listOf(signal(1, 40.0)))

        assertEquals(2, ignored.epochCount)
        assertEquals(15.0, ignored.medianCn0DbHz!!, 0.0001)
        assertEquals(2, countBounded.epochCount)
        assertEquals(25.0, countBounded.medianCn0DbHz!!, 0.0001)
        assertEquals(1, durationBounded.epochCount)
        assertEquals(40.0, durationBounded.medianCn0DbHz!!, 0.0001)
    }

    @Test
    fun degradedSignalsIncreaseNoiseWithoutRejectingTheObservation() {
        val estimator = estimator(minEpochs = 3, minSignals = 4, maxMultiplier = 4.0)
        val healthySignals = (1..6).map { signal(it, 35.0) }

        estimator.addEpoch(1L, healthySignals)
        estimator.addEpoch(2L, healthySignals)
        val healthy = estimator.addEpoch(3L, healthySignals)
        val degraded = estimator.addEpoch(4L, listOf(signal(1, 10.0), signal(2, 10.0)))

        assertEquals(SignalEnvironmentRisk.LOW, healthy.signalEnvironmentRisk)
        assertEquals(1.0, healthy.measurementNoiseMultiplier, 0.0001)
        assertEquals(2, degraded.finiteSignalCount)
        assertEquals(SignalEnvironmentRisk.HIGH, degraded.signalEnvironmentRisk)
        assertTrue(degraded.measurementNoiseMultiplier > healthy.measurementNoiseMultiplier)
        assertTrue(degraded.measurementNoiseMultiplier <= 4.0)
    }

    @Test
    fun staleDataClearsToUnknownAndFreshEpochsRecover() {
        val estimator = GnssQualityEstimator(
            GnssQualityEstimatorConfig(
                windowDurationNanos = 100L,
                staleAfterNanos = 20L,
                maxEpochCount = 10,
                minEpochCountForRisk = 2,
                minSignalCountForRisk = 2,
            ),
        )
        val healthySignals = (1..4).map { signal(it, 35.0) }
        estimator.addEpoch(1L, healthySignals)
        estimator.addEpoch(2L, healthySignals)

        val stale = estimator.snapshot(23L)
        val firstRecovered = estimator.addEpoch(24L, healthySignals)
        val recovered = estimator.addEpoch(25L, healthySignals)

        assertTrue(stale.isStale)
        assertEquals(0, stale.epochCount)
        assertEquals(SignalEnvironmentRisk.UNKNOWN, stale.signalEnvironmentRisk)
        assertNull(stale.signalEnvironmentRiskScore)
        assertEquals(1.0, stale.measurementNoiseMultiplier, 0.0001)
        assertFalse(firstRecovered.isStale)
        assertEquals(SignalEnvironmentRisk.UNKNOWN, firstRecovered.signalEnvironmentRisk)
        assertEquals(SignalEnvironmentRisk.LOW, recovered.signalEnvironmentRisk)
    }

    @Test
    fun emptyOrInsufficientWindowIsNeutral() {
        val estimator = estimator(minEpochs = 3, minSignals = 4)

        val empty = estimator.snapshot(1L)
        val insufficient = estimator.addEpoch(2L, listOf(signal(1, 5.0)))

        assertEquals(SignalEnvironmentRisk.UNKNOWN, empty.signalEnvironmentRisk)
        assertEquals(1.0, empty.measurementNoiseMultiplier, 0.0001)
        assertEquals(SignalEnvironmentRisk.UNKNOWN, insufficient.signalEnvironmentRisk)
        assertEquals(1.0, insufficient.measurementNoiseMultiplier, 0.0001)
    }

    private fun estimator(
        minEpochs: Int,
        minSignals: Int,
        maxMultiplier: Double = 4.0,
    ) = GnssQualityEstimator(
        GnssQualityEstimatorConfig(
            windowDurationNanos = 1_000L,
            staleAfterNanos = 100L,
            maxEpochCount = 20,
            minEpochCountForRisk = minEpochs,
            minSignalCountForRisk = minSignals,
            maxMeasurementNoiseMultiplier = maxMultiplier,
        ),
    )

    private fun signal(svid: Int, cn0DbHz: Double?) = GnssSatelliteSignal(
        constellation = 1,
        svid = svid,
        carrierFrequencyHz = GnssFrequencyClassifier.L1_CENTER_HZ,
        cn0DbHz = cn0DbHz,
        usedInFix = true,
    )
}
