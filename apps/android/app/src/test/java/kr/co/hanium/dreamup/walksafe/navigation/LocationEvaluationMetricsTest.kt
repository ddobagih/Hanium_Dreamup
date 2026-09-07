package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocationEvaluationMetricsTest {
    @Test
    fun calculatesNearestRankPercentilesCoverageAndAvailability() {
        val samples = listOf(
            sample(timestampMs = 0L, estimateEastM = 0.0, estimateAgeMs = 0L),
            sample(timestampMs = 1_000L, estimateEastM = 1.0, estimateAgeMs = 100L),
            sample(timestampMs = 2_000L, estimateEastM = 2.0, estimateAgeMs = 2_000L),
            sample(timestampMs = 3_000L, estimateEastM = 3.0, estimateAgeMs = 100L),
            sample(timestampMs = 4_000L),
            sample(timestampMs = 5_000L, estimateEastM = 100.0, estimateAgeMs = 2_001L),
        )

        val result = LocationEvaluationMetrics.calculate(samples)

        assertEquals(6, result.sampleCount)
        assertEquals(4, result.availableCount)
        assertEquals(1.0, result.p50ErrorM, 0.0001)
        assertEquals(3.0, result.p95ErrorM, 0.0001)
        assertEquals(0.5, result.twoMeterCoverage, 0.0001)
        assertEquals(4.0 / 6.0, result.availability, 0.0001)
    }

    @Test
    fun rejectsDuplicateOrOutOfOrderTimestampsAndTruthGaps() {
        assertRejected(listOf(sample(0L), sample(0L)))
        assertRejected(listOf(sample(1_000L), sample(999L)))
        assertRejected(listOf(sample(0L), sample(1_501L)))
    }

    @Test
    fun rejectsNonFiniteTruthOrEstimateCoordinates() {
        assertRejected(listOf(sample(0L, truthEastM = Double.NaN)))
        assertRejected(listOf(sample(0L, truthNorthM = Double.POSITIVE_INFINITY)))
        assertRejected(listOf(sample(0L, estimateEastM = Double.NaN, estimateAgeMs = 0L)))
    }

    @Test
    fun rejectsIncompleteEstimateAndMissingAvailableOutput() {
        assertRejected(
            listOf(
                sample(0L).copy(estimateEastM = 1.0, estimateNorthM = null, estimateAgeMs = 0L),
            ),
        )
        assertRejected(listOf(sample(0L)))
    }

    private fun assertRejected(samples: List<LocationEvaluationSample>) {
        assertThrows(IllegalArgumentException::class.java) {
            LocationEvaluationMetrics.calculate(samples)
        }
    }

    private fun sample(
        timestampMs: Long,
        truthEastM: Double = 0.0,
        truthNorthM: Double = 0.0,
        estimateEastM: Double? = null,
        estimateNorthM: Double? = estimateEastM?.let { 0.0 },
        estimateAgeMs: Long? = null,
    ) = LocationEvaluationSample(
        scenarioId = "test",
        timestampMs = timestampMs,
        truthEastM = truthEastM,
        truthNorthM = truthNorthM,
        estimateEastM = estimateEastM,
        estimateNorthM = estimateNorthM,
        estimateAgeMs = estimateAgeMs,
    )
}
