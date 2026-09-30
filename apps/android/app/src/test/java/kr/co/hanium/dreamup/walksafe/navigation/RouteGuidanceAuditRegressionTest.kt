package kr.co.hanium.dreamup.walksafe.navigation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RouteGuidanceAuditRegressionTest {
    @Test fun coarseOrUnmeasuredHeadingErrorNeverBecomesAPreciseClockNumber() {
        val coarse = RouteFacingGuidance.evaluate(90.0, RouteFacingObservation(0.0, 30.0, 1_000L), 1_000L)
        assertEquals(RouteFacingDirection.RIGHT, coarse.direction)
        assertNull(coarse.clockHour)
        val fallback = RouteFacingGuidance.evaluate(90.0, RouteFacingObservation(0.0, null, 1_000L,
            RouteFacingSource.GRAVITY_MAGNETIC, RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS, null), 1_000L)
        assertEquals(RouteFacingDirection.RIGHT, fallback.direction)
        assertNull(fallback.clockHour)
        assertEquals(12, RouteFacingGuidance.evaluate(0.0, RouteFacingObservation(359.0, 3.0, 1_000L), 1_000L).clockHour)
        assertNull(RouteFacingGuidance.evaluate(15.0, RouteFacingObservation(0.0, 3.0, 1_000L), 1_000L).clockHour)
    }

    @Test fun sensorLossCancelsInFlightRelativeGuidanceAndOmitsMovementCommand() {
        val navigator = navigator()
        val initial = navigator.update(fix(1_000L), 1_000L, false, facingObservation = facing(1_000L))
        assertTrue(navigator.reserveInstruction(initial))
        val invalid = requireNotNull(navigator.retryGuidance(1_600L, facing(1_000L)))
        assertTrue(invalid.cancelStaleNavigationSpeech)
        assertFalse(invalid.instruction.orEmpty().contains("꺾으세요"))
        assertFalse(navigator.isInstructionCurrent(initial, 1_600L, facing(1_600L)))
    }

    @Test fun playbackValidationRejectsExpiredOrTurnedFacingAndPreviousRoute() {
        val navigator = navigator()
        val initial = navigator.update(fix(1_000L), 1_000L, false, facingObservation = facing(1_000L))
        assertTrue(navigator.isInstructionCurrent(initial, 1_050L, facing(1_050L)))
        assertFalse(navigator.isInstructionCurrent(initial, 1_600L, facing(1_000L)))
        assertFalse(navigator.isInstructionCurrent(initial, 1_100L, facing(1_100L, 270.0)))
        navigator.setRoute(route())
        assertFalse(navigator.isInstructionCurrent(initial, 1_100L, facing(1_100L)))
    }

    @Test fun manualRequestDuringSpeechIsDeliveredOnceAfterCompletion() {
        val navigator = navigator()
        val first = navigator.update(fix(1_000L), 1_000L, false, facingObservation = facing(1_000L))
        assertTrue(navigator.reserveInstruction(first))
        assertEquals("guidance_in_flight", navigator.currentGuidance(1_100L, facing(1_100L))?.reason)
        navigator.acknowledgeInstruction(first, 1_200L)
        val repeat = requireNotNull(navigator.retryGuidance(1_300L, facing(1_300L)))
        assertNotNull(repeat.instruction)
        assertTrue(navigator.reserveInstruction(repeat))
        navigator.acknowledgeInstruction(repeat, 1_400L)
        assertNull(navigator.retryGuidance(1_500L, facing(1_500L))?.instruction)
    }

    @Test fun aNewManualRequestDuringTheRepeatIsNotConsumedByThePreviousCompletion() {
        val navigator = navigator()
        val first = navigator.update(fix(1_000L), 1_000L, false, facingObservation = facing(1_000L))
        assertTrue(navigator.reserveInstruction(first))
        navigator.currentGuidance(1_100L, facing(1_100L))
        navigator.acknowledgeInstruction(first, 1_200L)
        val second = requireNotNull(navigator.retryGuidance(1_300L, facing(1_300L)))
        assertTrue(navigator.reserveInstruction(second))
        navigator.currentGuidance(1_400L, facing(1_400L))
        navigator.acknowledgeInstruction(first, 1_450L)
        navigator.acknowledgeInstruction(second, 1_500L)
        val third = requireNotNull(navigator.retryGuidance(1_600L, facing(1_600L)))
        assertNotNull(third.instruction)
        assertTrue(navigator.reserveInstruction(third))
        navigator.acknowledgeInstruction(third, 1_700L)
        assertNull(navigator.retryGuidance(1_800L, facing(1_800L))?.instruction)
    }

    @Test fun directionAlignmentAndImmediateTurnCommandAreNotSpokenTogether() {
        val navigator = navigator()
        val update = navigator.update(fix(1_000L), 1_000L, false, facingObservation = facing(1_000L, 270.0))
        assertTrue(update.instruction.orEmpty().contains("몸을 돌리"))
        assertFalse(update.instruction.orEmpty().contains("꺾으세요"))
    }

    @Test fun repeatedRouteCoordinateUsesDeclaredTraversalRatherThanFirstVisit() {
        val a = RoutePoint(37.0, 127.0)
        val b = RoutePoint(37.0, 127.001)
        val c = RoutePoint(37.001, 127.001)
        val d = RoutePoint(37.0, 127.002)
        fun point(p: RoutePoint) = JSONObject().put("latitude", p.latitude).put("longitude", p.longitude)
        fun guide(index: Int, location: RoutePoint, distance: Int, instruction: String, turnType: Int) =
            JSONObject().put("index", index).put("point", point(location))
                .put("instruction", instruction).put("turn_type", turnType)
                .put("distance_from_start_m", distance).put("remaining_distance_m", 400 - distance)
        val json = JSONObject().put("schema_version", "walksafe.walking_route.v1").put("provider", "tmap_pedestrian")
            .put("provider_result_code", 0).put("priority", "STAIR_AVOID")
            .put("summary", JSONObject().put("distance_m", 400).put("duration_s", 400))
            .put("polyline", JSONArray().also { array -> listOf(a, b, c, b, d).forEach { array.put(point(it)) } })
            .put("steps", JSONArray()).put("guide_points", JSONArray()
                .put(guide(0, b, 89, "왼쪽으로 꺾으세요.", 12))
                .put(guide(1, c, 200, "뒤로 돌아가세요.", 14))
                .put(guide(2, b, 311, "왼쪽으로 꺾으세요.", 12)))
        val parsed = parseWalkingRoute(json, WalkingRouteRequest(a, d))
        assertEquals(3, parsed.guidePoints.size)
        assertEquals(parsed.guidePoints[0].point, parsed.guidePoints[2].point)
        assertEquals(311, parsed.guidePoints[2].distanceFromStartM)
    }

    private fun navigator() = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0L)).apply { setRoute(route()) }
    private fun route() = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(89, 90),
        listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001)),
        listOf(WalkingRouteGuidePoint(0, RoutePoint(37.0, 127.0009), "우회전", 80, 9, turnType = 13)))
    private fun fix(at: Long) = TrustedLocation(37.0, 127.0, 1f, at)
    private fun facing(at: Long, degrees: Double = 90.0) = RouteFacingObservation(degrees, 3.0, at)
}
