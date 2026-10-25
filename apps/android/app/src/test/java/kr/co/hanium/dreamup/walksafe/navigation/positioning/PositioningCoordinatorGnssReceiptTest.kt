package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssSatelliteSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ADD-MOTION-007: the coordinator API retains only a supplied actual receiver timestamp. */
class PositioningCoordinatorGnssReceiptTest {
    @Test
    fun explicitlySuppliedReceiptSurvivesTheCoordinatorApi() {
        val result = PositioningCoordinator().addGnssSignalEpoch(SECOND, signals(), SECOND + 100_000_000L)
        assertEquals(SECOND, result.latestEpochAtNanos)
        assertEquals(SECOND + 100_000_000L, result.latestEpochReceivedAtNanos)
    }

    @Test
    fun legacyInputDoesNotManufactureAnActualReceiptFromTheMeasurementTime() {
        val result = PositioningCoordinator().addGnssSignalEpoch(SECOND, signals())
        assertEquals(SECOND, result.latestEpochAtNanos)
        assertNull(result.latestEpochReceivedAtNanos)
    }

    @Test
    fun invalidReceiptCannotConsumeTheMeasurementTimestamp() {
        val coordinator = PositioningCoordinator()
        val rejected = coordinator.addGnssSignalEpoch(SECOND, signals(), SECOND - 1L)
        assertNull(rejected.latestEpochAtNanos)
        val accepted = coordinator.addGnssSignalEpoch(SECOND, signals(), SECOND + 100_000_000L)
        assertEquals(SECOND, accepted.latestEpochAtNanos)
        assertEquals(SECOND + 100_000_000L, accepted.latestEpochReceivedAtNanos)
    }

    private fun signals() = listOf(GnssSatelliteSignal(1, 1, 1_575_420_000.0, 35.0, true))

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}
