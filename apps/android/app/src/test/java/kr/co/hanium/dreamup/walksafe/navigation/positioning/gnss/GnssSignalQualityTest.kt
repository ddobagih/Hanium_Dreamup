package kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssSignalQualityTest {
    @Test
    fun classifiesL1AndL5FamiliesWithInclusiveTolerance() {
        val tolerance = 2_000_000.0

        assertEquals(GnssFrequencyBand.L1_FAMILY, GnssFrequencyClassifier.classify(1_575_420_000.0))
        assertEquals(
            GnssFrequencyBand.L5_FAMILY,
            GnssFrequencyClassifier.classify(1_176_450_000.0 + tolerance, tolerance),
        )
        assertEquals(
            GnssFrequencyBand.OTHER,
            GnssFrequencyClassifier.classify(1_176_450_000.0 + tolerance + 1.0, tolerance),
        )
    }

    @Test
    fun invalidOrMissingCarrierFrequencyIsUnknown() {
        assertEquals(GnssFrequencyBand.UNKNOWN, GnssFrequencyClassifier.classify(null))
        assertEquals(GnssFrequencyBand.UNKNOWN, GnssFrequencyClassifier.classify(Double.NaN))
        assertEquals(GnssFrequencyBand.UNKNOWN, GnssFrequencyClassifier.classify(Double.POSITIVE_INFINITY))
        assertEquals(GnssFrequencyBand.UNKNOWN, GnssFrequencyClassifier.classify(0.0))
    }

    @Test
    fun reportsDualFrequencyOnlyForTheSameConstellationAndSvid() {
        val summary = summarizeGnssFrequencies(
            listOf(
                signal(constellation = 1, svid = 7, frequencyHz = GnssFrequencyClassifier.L1_CENTER_HZ, used = true),
                signal(constellation = 1, svid = 7, frequencyHz = GnssFrequencyClassifier.L5_CENTER_HZ, used = true),
                signal(constellation = 1, svid = 8, frequencyHz = GnssFrequencyClassifier.L1_CENTER_HZ, used = true),
                signal(constellation = 1, svid = 9, frequencyHz = GnssFrequencyClassifier.L5_CENTER_HZ),
            ),
        )

        assertEquals(2, summary.l1SignalCount)
        assertEquals(2, summary.l5SignalCount)
        assertEquals(1, summary.dualFrequencySatelliteCount)
        assertEquals(3, summary.trackedSatelliteCount)
        assertEquals(2, summary.usedInFixSatelliteCount)
        assertTrue(summary.usedInFixKnown)
        assertEquals(DualFrequencyObservationState.DUAL_FREQUENCY_OBSERVED, summary.state)
    }

    @Test
    fun differentSatellitesDoNotFormADualFrequencyPair() {
        val summary = summarizeGnssFrequencies(
            listOf(
                signal(1, 3, GnssFrequencyClassifier.L1_CENTER_HZ),
                signal(1, 4, GnssFrequencyClassifier.L5_CENTER_HZ),
                signal(3, 3, GnssFrequencyClassifier.L5_CENTER_HZ),
            ),
        )

        assertEquals(0, summary.dualFrequencySatelliteCount)
        assertEquals(DualFrequencyObservationState.SINGLE_FREQUENCY_OBSERVED, summary.state)
    }

    @Test
    fun missingOrOtherFrequenciesRemainUnknownInsteadOfUnsupported() {
        val summary = summarizeGnssFrequencies(
            listOf(
                signal(1, 3, null, used = true),
                signal(1, 4, 1_300_000_000.0),
            ),
        )

        assertEquals(DualFrequencyObservationState.UNKNOWN, summary.state)
        assertEquals(0, summary.dualFrequencySatelliteCount)
        assertEquals(1, summary.usedInFixSatelliteCount)
        assertTrue(summary.usedInFixKnown)
    }

    private fun signal(
        constellation: Int,
        svid: Int,
        frequencyHz: Double?,
        used: Boolean = false,
    ) = GnssSatelliteSignal(
        constellation = constellation,
        svid = svid,
        carrierFrequencyHz = frequencyHz,
        cn0DbHz = null,
        usedInFix = used,
    )
}
