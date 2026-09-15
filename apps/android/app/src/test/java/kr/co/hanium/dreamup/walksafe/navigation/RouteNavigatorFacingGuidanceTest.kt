package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import org.junit.Assert.*
import org.junit.Test

class RouteNavigatorFacingGuidanceTest {
    @Test
    fun initialGuidanceSeparatesPhoneFacingFromUpcomingRouteTurn() {
        listOf(90.0 to "앞쪽이 경로", 0.0 to "오른쪽 경로 방향으로 몸을 돌리세요",
            180.0 to "왼쪽 경로 방향으로 몸을 돌리세요", 270.0 to "경로 진행 방향이 뒤쪽").forEach { (heading, phrase) ->
            val navigator = navigator()
            val update = navigator.update(fix(), 1_000, false, facingObservation = facing(heading, 1_000))
            assertTrue(update.instruction?.contains(phrase) == true)
            assertTrue(update.instruction?.contains("50m 앞, 오른쪽으로 꺾으세요.") == true)
            assertFalse(update.instruction?.contains("뒤로 걸") == true)
        }
    }

    @Test
    fun stationaryTurnUpdatesRetryFacingWithoutCreatingNewPositionEvidence() {
        val navigator = navigator()
        val first = navigator.update(fix(), 1_000, false, facingObservation = facing(90.0, 1_000))
        val match = navigator.currentRouteMatch()
        val turned = requireNotNull(navigator.retryGuidance(1_100, facing(270.0, 1_100)))
        assertTrue(turned.instruction?.contains("경로 진행 방향이 뒤쪽") == true)
        assertSame(match, navigator.currentRouteMatch())
        assertSame(match, navigator.currentAcceptedRouteMatchFor(1_000))
        assertNotEquals(first.speechCueToken, turned.speechCueToken)
        assertFalse(navigator.reserveInstruction(first))
        assertTrue(navigator.reserveInstruction(turned))
    }

    @Test
    fun periodicGuidanceUsesCurrentFacingAfterThePreviousSentenceFinishes() {
        val navigator = navigator()
        val first = navigator.update(fix(), 1_000, false, facingObservation = facing(90.0, 1_000))
        assertTrue(navigator.reserveInstruction(first))
        val turning = requireNotNull(navigator.retryGuidance(2_000, facing(270.0, 2_000)))
        assertEquals("guidance_in_flight", turning.reason)
        assertFalse(turning.cancelStaleNavigationSpeech)
        navigator.acknowledgeInstruction(first, 3_000)
        navigator.update(fix(12_000), 12_000, false, facingObservation = facing(270.0, 12_000))
        val periodic = requireNotNull(navigator.retryGuidance(13_000, facing(270.0, 13_000)))
        assertTrue(periodic.instruction?.contains("경로 진행 방향이 뒤쪽") == true)
        assertTrue(navigator.reserveInstruction(periodic))
    }

    @Test
    fun expiredMissingAndInaccurateFacingNeverFallBackToGpsTravelCourse() {
        listOf<RouteFacingObservation?>(null, facing(90.0, 499), facing(90.0, 1_001),
            facing(90.0, 1_000).copy(headingAccuracyDegrees = 31.0)).forEach { observation ->
            val navigator = navigator()
            val raw = fix()
            val update = navigator.update(raw, 1_000, false,
                FilteredRoutePosition(RoutePoint(raw.latitude, raw.longitude), 2.0, 1_000,
                    heading = RouteHeadingEstimate(90.0, 5.0)), facingObservation = observation)
            assertTrue(update.instruction?.contains("현재 바라보는 방향을 확인할 수 없습니다") == true)
            assertFalse(update.instruction?.contains("앞쪽이 경로") == true)
            assertNotNull(navigator.currentAcceptedRouteMatchFor(1_000))
        }
    }

    @Test
    fun freshFacingCannotReviveExpiredPositionOrChooseAnUncertainRouteDirection() {
        val navigator = navigator()
        navigator.update(fix(), 1_000, false, facingObservation = facing(90.0, 1_000))
        assertNull(navigator.retryGuidance(11_001, facing(90.0, 11_001)))
        assertNull(navigator.currentGuidance(11_002, facing(90.0, 11_002)))
        val coarse = navigator(true)
        val update = coarse.update(fix().copy(accuracyM = 80f), 1_000, false,
            facingObservation = facing(90.0, 1_000))
        assertTrue(update.instruction?.contains("현재 위치에서 경로의 진행 방향을 정확히 구분하기 어렵습니다") == true)
    }

    @Test
    fun manualCurrentGuidanceSharesReservationAndCompletionWithAutomaticGuidance() {
        val navigator = navigator()
        val first = navigator.update(fix(), 1_000, false, facingObservation = facing(90.0, 1_000))
        assertTrue(navigator.reserveInstruction(first))
        assertEquals("guidance_in_flight", navigator.currentGuidance(1_100, facing(270.0, 1_100))?.reason)
        navigator.acknowledgeInstruction(first, 2_000)
        val manual = requireNotNull(navigator.currentGuidance(2_001, facing(270.0, 2_001)))
        assertTrue(manual.instruction?.contains("경로 진행 방향이 뒤쪽") == true)
        assertFalse(manual.instruction?.contains("전체 경로") == true)
        assertTrue(navigator.reserveInstruction(manual))
        navigator.acknowledgeInstruction(first, 2_002)
        assertEquals("guidance_in_flight", navigator.retryGuidance(2_003, facing(270.0, 2_003))?.reason)
        navigator.acknowledgeInstruction(manual, 3_000)
        assertNull(navigator.retryGuidance(3_001, facing(270.0, 3_001))?.instruction)
        navigator.update(fix(12_999), 12_999, false, facingObservation = facing(270.0, 12_999))
        assertNotNull(navigator.retryGuidance(13_000, facing(270.0, 13_000))?.instruction)
    }

    @Test
    fun failedManualRequestRetainsRetryIntentButNotItsStaleToken() {
        val navigator = navigator()
        val first = navigator.update(fix(), 1_000, false, facingObservation = facing(90.0, 1_000))
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 2_000)
        val manual = requireNotNull(navigator.currentGuidance(2_001, facing(90.0, 2_001)))
        assertTrue(navigator.reserveInstruction(manual))
        navigator.releaseInstruction(manual, 2_002)
        assertEquals("guidance_retry_wait", navigator.currentGuidance(4_001, facing(90.0, 4_001))?.reason)
        val retry = requireNotNull(navigator.retryGuidance(4_002, facing(90.0, 4_002)))
        assertNotNull(retry.instruction)
        assertNotEquals(manual.speechCueToken, retry.speechCueToken)
        assertTrue(navigator.reserveInstruction(retry))
        navigator.acknowledgeInstruction(manual, 4_003)
        assertEquals("guidance_in_flight", navigator.retryGuidance(4_004, facing(90.0, 4_004))?.reason)
    }

    @Test
    fun manualRequestCannotBypassLocationInterruptionRouteReplacementOrEnd() {
        val navigator = navigator(true)
        val first = navigator.update(fix(), 1_000, false, facingObservation = facing(90.0, 1_000))
        assertTrue(navigator.reserveInstruction(first))
        navigator.onPositioningEvidenceInterrupted()
        assertNull(navigator.currentGuidance(2_000, facing(90.0, 2_000)))
        navigator.setRoute(route())
        assertNull(navigator.currentGuidance(2_001, facing(90.0, 2_001)))
        navigator.acknowledgeInstruction(first, 2_002)
        assertNull(navigator.currentGuidance(2_003, facing(90.0, 2_003)))
        navigator.clear()
        assertNull(navigator.currentGuidance(2_004, facing(90.0, 2_004)))
    }

    @Test
    fun healthyCompassAtDirectionBoundaryIsNotReportedAsMissing() {
        val nav = navigator()
        val update = nav.update(fix(), 1_000, false,
            facingObservation = RouteFacingObservation(70.0, 20.0, 1_000))
        assertTrue(update.instruction?.contains("나침반 방향은 측정되지만") == true)
        assertFalse(update.instruction?.contains("현재 바라보는 방향을 확인할 수 없습니다") == true)
        assertFalse(update.instruction?.contains("몸을 돌리세요") == true)
    }

    @Test
    fun changedUnknownReasonRetiresOldUnreservedMessage() {
        val nav = navigator()
        val missing = nav.update(fix(), 1_000, false)
        val measured = requireNotNull(nav.currentGuidance(1_001, RouteFacingObservation(70.0, 20.0, 1_001)))
        assertTrue(missing.instruction?.contains("현재 바라보는 방향을 확인할 수 없습니다") == true)
        assertTrue(measured.instruction?.contains("나침반 방향은 측정되지만") == true)
        assertNotEquals(missing.speechCueToken, measured.speechCueToken)
        assertFalse(nav.reserveInstruction(missing))
        assertTrue(nav.reserveInstruction(measured))
        assertEquals("guidance_in_flight", nav.currentGuidance(1_002)?.reason)
    }

    private fun navigator(degraded: Boolean = false) = RouteNavigator(allowDegradedRouteGuidance = degraded).apply { setRoute(route()) }
    private fun route() = WalkingRoute("TEST", WalkingRouteSummary(200, 200),
        listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.0009), RoutePoint(-0.0009, 0.0009)),
        listOf(WalkingRouteGuidePoint(0, RoutePoint(0.0, 0.0009), null, 100, 100, turnType = 13)))
    private fun fix(now: Long = 1_000) = TrustedLocation(0.0, 0.00045, 2f, now)
    private fun facing(heading: Double, now: Long) = RouteFacingObservation(heading, 5.0, now)
}
