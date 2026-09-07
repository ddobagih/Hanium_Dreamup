package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationReplayFixtureTest {
    @Test
    fun fixtureUsesOnlySyntheticLocalEnuDataAndContainsRequiredScenarios() {
        val text = requireNotNull(
            javaClass.classLoader?.getResource("navigation/location_replay_v1.jsonl"),
        ).readText()

        val fixture = LocationReplayFixtureParser.parse(text)

        assertEquals("walksafe.location_replay.v1", fixture.schemaVersion)
        assertEquals("LOCAL_ENU_METERS", fixture.coordinateFrame)
        assertFalse(fixture.absoluteCoordinates)
        assertFalse(fixture.wallClockTime)
        assertTrue(fixture.syntheticContractOnly)
        assertEquals(
            setOf(
                "nominal_walk",
                "gps_outlier",
                "gnss_outage",
                "low_speed",
                "cane_sweep_stop_go",
                "parallel_walkway",
            ),
            fixture.scenarios.keys,
        )
        assertFalse(text.contains("latitude", ignoreCase = true))
        assertFalse(text.contains("longitude", ignoreCase = true))
        assertTrue(text.contains("\"wall_clock_time\":false"))
    }

    @Test
    fun fixtureProducesTheExpectedBaselineContractMetrics() {
        val text = requireNotNull(
            javaClass.classLoader?.getResource("navigation/location_replay_v1.jsonl"),
        ).readText()
        val nominal = requireNotNull(LocationReplayFixtureParser.parse(text).scenarios["nominal_walk"])

        val result = LocationEvaluationMetrics.calculate(nominal)

        assertEquals(4, result.sampleCount)
        assertEquals(3, result.availableCount)
        assertEquals(1.0, result.p50ErrorM, 0.0001)
        assertEquals(3.0, result.p95ErrorM, 0.0001)
        assertEquals(0.5, result.twoMeterCoverage, 0.0001)
        assertEquals(0.75, result.availability, 0.0001)
    }
}
