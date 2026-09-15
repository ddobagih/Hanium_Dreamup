package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

class RouteFacingClockGuidanceTest {
    @Test fun everyClockPositionUsesRearFacingAsTwelveOClock() {
        for (hour in 1..12) {
            val facing = RouteFacingObservation((360.0 - hour * 30.0) % 360.0, 5.0, 1_000)
            assertEquals(hour, RouteFacingGuidance.evaluate(0.0, facing, 1_000).clockHour)
        }
    }

    @Test fun northWrapStaysAtTwelveAndOppositeDoesNotChooseATurnSide() {
        assertEquals(12, RouteFacingGuidance.evaluate(1.0, RouteFacingObservation(359.0, 5.0, 1_000), 1_000).clockHour)
        val opposite = RouteFacingGuidance.evaluate(0.0, RouteFacingObservation(180.0, 5.0, 1_000), 1_000)
        assertEquals(6, opposite.clockHour)
        assertEquals(RouteFacingDirection.BEHIND, opposite.direction)
    }

    @Test fun unavailableOrUncertainDirectionDoesNotAcquirePrecisionFromClockFormatting() {
        assertNull(RouteFacingGuidance.evaluate(0.0, null, 1_000).clockHour)
        assertNull(RouteFacingGuidance.evaluate(30.0, RouteFacingObservation(0.0, 30.0, 1_000), 1_000).clockHour)
        assertNull(RouteFacingGuidance.evaluate(0.0, RouteFacingObservation(0.0, 5.0, 499), 1_000).clockHour)
    }
}
