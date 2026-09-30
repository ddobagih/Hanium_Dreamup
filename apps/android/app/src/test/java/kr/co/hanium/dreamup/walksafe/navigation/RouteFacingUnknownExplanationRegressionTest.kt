package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S08-4: UNKNOWN keeps "measured but ambiguous" distinct from "camera direction unavailable". */
class RouteFacingUnknownExplanationRegressionTest {
    @Test fun directionReferenceDistinguishesMissingCompassFromAmbiguousDirection() {
        val missing = connectorNavigator().update(offset, 1_000L, false, facingObservation = null)
        assertEquals(RouteAlignmentReason.DEPARTURE_CONNECTOR, missing.routeAlignmentDiagnostic?.reason)
        assertTrue(missing.directionOnly)
        assertTrue(missing.instruction.orEmpty().contains("휴대전화 후면 카메라 방향을 확인할 수 없습니다"))
        assertFalse(missing.instruction.orEmpty().contains("측정되지만"))
        assertFalse(missing.instruction.orEmpty().contains("비교하는 중"))

        // Route to the start point is south; 160 +/- 20 degrees is measured but cannot be classified.
        val ambiguous = connectorNavigator().update(offset, 1_000L, false,
            facingObservation = RouteFacingObservation(160.0, 20.0, 1_000L))
        assertEquals(RouteAlignmentReason.DEPARTURE_CONNECTOR, ambiguous.routeAlignmentDiagnostic?.reason)
        assertTrue(ambiguous.instruction.orEmpty().contains("나침반 방향은 측정되지만"))
        assertFalse(ambiguous.instruction.orEmpty().contains("확인할 수 없습니다"))
        assertTrue(ambiguous.instruction.orEmpty().contains("통로는 확인되지 않았습니다"))
    }

    @Test fun unknownRouteGuidanceDoesNotAnnounceARouteCommandThatNeverFollows() {
        listOf(null, RouteFacingObservation(70.0, 20.0, 1_000L)).forEach { observation ->
            val instruction = requireNotNull(routeNavigator().update(
                TrustedLocation(0.0, 0.00045, 2f, 1_000L), 1_000L, false, facingObservation = observation,
            ).instruction)
            assertFalse(instruction, instruction.contains("다음은 경로 기준 안내"))
            assertFalse(instruction, instruction.contains("꺾으세요"))
            assertTrue(instruction, instruction.contains("방향과 위치를 확인한 뒤 다시 안내를 요청해 주세요"))
        }
    }

    /** S08-8: the compass cause reaches the spoken guidance as a distinct recovery step. */
    @Test fun compassCauseSelectsTheSpokenRecoveryStepOnEveryGuidancePath() {
        mapOf(
            RouteCompassHeadingReason.REAR_AXIS_VERTICAL to "휴대전화 후면을 걷는 방향으로 들어 주세요.",
            RouteCompassHeadingReason.GRAVITY_UNSTABLE to "휴대전화 움직임이 커서 방향을 다시 확인하고 있으니 잠시 안정적으로 잡아 주세요.",
            RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY to "나침반 방향이 불안정합니다. 자석이나 금속에서 떨어져 휴대전화를 천천히 움직여 주세요.",
            RouteCompassHeadingReason.REFERENCE_MISSING to "현재 위치를 확인해 나침반의 진북 기준을 맞추고 있습니다.",
            RouteCompassHeadingReason.SENSOR_STALLED to "나침반 센서 값이 들어오지 않고 있습니다.",
            RouteCompassHeadingReason.STALE to "새 나침반 방향을 기다리고 있습니다.",
        ).forEach { (reason, recovery) ->
            val expected = "휴대전화 후면 카메라 방향을 확인할 수 없습니다. $recovery"
            val navigator = routeNavigator()
            val automatic = requireNotNull(navigator.update(routeFix, 1_000L, false,
                facingObservation = null, facingUnavailableReason = reason).instruction)
            assertTrue(automatic, automatic.contains(expected))
            assertFalse(automatic, automatic.contains("꺾으세요"))
            assertTrue(automatic.contains("방향과 위치를 확인한 뒤 다시 안내를 요청해 주세요"))
            val manual = requireNotNull(navigator.currentGuidance(1_001L, null, reason)?.instruction)
            assertTrue(manual, manual.contains(expected))
            val reference = requireNotNull(connectorNavigator().update(offset, 1_000L, false,
                facingObservation = null, facingUnavailableReason = reason).instruction)
            assertTrue(reference, reference.contains(expected))
            assertTrue(reference.contains("통로는 확인되지 않았습니다"))
        }
        // The retry path carries the cause too.
        val retrying = routeNavigator()
        retrying.update(routeFix, 1_000L, false, facingObservation = null)
        assertTrue(requireNotNull(retrying.retryGuidance(1_001L, null, RouteCompassHeadingReason.REAR_AXIS_VERTICAL)
            ?.instruction).contains("휴대전화 후면을 걷는 방향으로 들어 주세요."))
    }

    @Test fun noCauseOrAMeasuredDirectionKeepsTheExistingSentenceWithoutARecoveryStep() {
        val plain = requireNotNull(routeNavigator().update(routeFix, 1_000L, false, facingObservation = null).instruction)
        assertTrue(plain, plain.startsWith("휴대전화 후면 카메라 방향을 확인할 수 없습니다. "))
        assertFalse(plain.contains("기다리고 있습니다"))
        // A measured (usable) observation is never explained by a sensor cause.
        val measured = requireNotNull(routeNavigator().update(routeFix, 1_000L, false,
            facingObservation = RouteFacingObservation(70.0, 20.0, 1_000L),
            facingUnavailableReason = RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY).instruction)
        assertTrue(measured, measured.contains("나침반 방향은 측정되지만"))
        assertFalse(measured.contains("자석"))
    }

    private val routeFix = TrustedLocation(0.0, 0.00045, 2f, 1_000L)
    private val offset = point(0.0, 15.0).let { TrustedLocation(it.latitude, it.longitude, 3f, 1_000L) }
    private fun connectorNavigator() = RouteNavigator(allowDegradedRouteGuidance = true).apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(100, 100), listOf(point(0.0), point(100.0)), emptyList()),
            origin = offset)
    }
    private fun routeNavigator() = RouteNavigator().apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(200, 200),
            listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.0009), RoutePoint(-0.0009, 0.0009)),
            listOf(WalkingRouteGuidePoint(0, RoutePoint(0.0, 0.0009), null, 100, 100, turnType = 13))))
    }
    private fun point(east: Double, north: Double = 0.0) = RoutePoint(north / 111_194.92664455874, east / 111_194.92664455874)
}
