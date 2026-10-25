package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import org.junit.Assert.*
import org.junit.Test

class RouteNavigatorFacingGuidanceTest {
    // These fixtures isolate measured-clock wording, confidence, and speech ownership.
    // Production 1.5-second delay and immediate safety actions are covered by RouteDirectionRecheckGraceTest.

    @Test
    fun initialGuidanceSeparatesPhoneFacingFromUpcomingRouteTurn() {
        listOf(90.0 to "약 12시 방향", 0.0 to "안내한 방향으로 몸을 돌려주세요",
            180.0 to "안내한 방향으로 몸을 돌려주세요", 270.0 to "약 6시 방향").forEach { (heading, phrase) ->
            val navigator = navigator()
            val update = navigator.update(fix(), 1_000, false, facingObservation = facing(heading, 1_000))
            assertTrue(update.instruction?.contains(phrase) == true)
            // S08: only an aligned observation may combine facing with a movement instruction.
            assertEquals(heading == 90.0, update.instruction?.contains("다음 안내 지점에서 경로 기준으로 3시 방향으로 꺾으세요.") == true)
            assertFalse(update.instruction?.contains("뒤로 걸") == true)
            val aligning = requireNotNull(navigator.currentGuidance(1_001, facing(90.0, 1_001)))
            if (heading != 90.0) {
                assertTrue(aligning.instruction.orEmpty().contains("방향이 확인되면 안내를 이어갑니다"))
                assertFalse(aligning.instruction.orEmpty().contains("꺾으세요"))
            }
            val aligned = requireNotNull(navigator.currentGuidance(1_002, facing(90.0, 1_002)))
            assertTrue(aligned.instruction?.contains("다음 안내 지점에서 경로 기준으로 3시 방향으로 꺾으세요.") == true)
        }
    }

    @Test
    fun clockManeuverAndCurrentFacingClockNameTheirSeparateReferenceFrames() {
        val clockTurn = navigator(turnType = 18)
        clockTurn.update(fix(), 1_000, false, facingObservation = facing(90.0, 1_000))
        val clockSpeech = requireNotNull(clockTurn.currentGuidance(1_001, facing(90.0, 1_001))?.instruction)
        assertEquals(2, Regex("""(?:1[0-2]|[1-9])시 방향""").findAll(clockSpeech).count())
        assertTrue(clockSpeech.contains("바라보고 있는 방향 기준 약 12시 방향"))
        assertTrue(clockSpeech.endsWith("다음 안내 지점에서 경로 기준으로 2시 방향 길로 이동하세요."))

        val ordinaryTurn = navigator()
        ordinaryTurn.update(fix(), 1_000, false, facingObservation = facing(90.0, 1_000))
        val ordinarySpeech = requireNotNull(ordinaryTurn.currentGuidance(1_001, facing(90.0, 1_001))?.instruction)
        assertTrue(ordinarySpeech.contains("약 12시 방향"))
        assertTrue(ordinarySpeech.endsWith("다음 안내 지점에서 경로 기준으로 3시 방향으로 꺾으세요."))
    }

    @Test
    fun stationaryTurnUpdatesRetryFacingWithoutCreatingNewPositionEvidence() {
        val navigator = navigator()
        val first = navigator.update(fix(), 1_000, false, facingObservation = facing(90.0, 1_000))
        val match = navigator.currentRouteMatch()
        val turned = requireNotNull(navigator.retryGuidance(1_100, facing(270.0, 1_100)))
        assertTrue(turned.instruction?.contains("방향이 확인되면 안내를 이어갑니다") == true)
        assertFalse(turned.instruction?.contains("몸을 돌려주세요") == true)
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
        // S08-23: an adjacent turn (ahead -> right) finishes the sentence; turning around revokes it
        // (RouteSpeechEvidenceRegressionTest.aReversingTurnRevokes...).
        val turning = requireNotNull(navigator.retryGuidance(2_000, facing(0.0, 2_000)))
        assertEquals("guidance_in_flight", turning.reason)
        assertFalse(turning.cancelStaleNavigationSpeech)
        navigator.acknowledgeInstruction(first, 3_000)
        navigator.update(fix(12_000), 12_000, false, facingObservation = facing(270.0, 12_000))
        val periodic = requireNotNull(navigator.retryGuidance(13_000, facing(270.0, 13_000)))
        assertTrue(periodic.instruction?.contains("약 6시 방향") == true)
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
            assertTrue(update.instruction?.contains("사용자 방향을 확인할 수 없어 다음 방향 안내를 보류합니다") == true)
            assertFalse(update.instruction?.let { Regex("""약 (?:1[0-2]|[1-9])시(?: 방향|에서 (?:1[0-2]|[1-9])시 사이 방향)""").containsMatchIn(it) } == true)
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
        // S08-23: only a turn that does not reverse the playing command keeps it in flight.
        assertEquals("guidance_in_flight", navigator.currentGuidance(1_100, facing(0.0, 1_100))?.reason)
        navigator.acknowledgeInstruction(first, 2_000)
        val manual = requireNotNull(navigator.currentGuidance(2_001, facing(270.0, 2_001)))
        assertTrue(manual.instruction?.contains("방향이 확인되면 안내를 이어갑니다") == true)
        assertFalse(manual.instruction?.contains("몸을 돌려주세요") == true)
        assertFalse(manual.instruction?.contains("전체 경로") == true)
        assertTrue(navigator.reserveInstruction(manual))
        navigator.acknowledgeInstruction(first, 2_002)
        // The second fresh rear-facing sample replaces the now-obsolete recovery explanation.
        val numeric = requireNotNull(navigator.retryGuidance(2_003, facing(270.0, 2_003)))
        assertEquals("route_guidance", numeric.reason)
        assertTrue(numeric.cancelStaleNavigationSpeech)
        assertTrue(numeric.instruction?.contains("약 6시 방향") == true)
        assertTrue(navigator.isInstructionCurrent(numeric, 2_003, facing(270.0, 2_003)))
        assertTrue(navigator.reserveInstruction(numeric))
        navigator.acknowledgeInstruction(manual, 3_000)
        // The retired explanation's DONE cannot consume the newly reserved numeric cue.
        assertEquals("guidance_in_flight", navigator.retryGuidance(3_001, facing(270.0, 3_001))?.reason)
        navigator.acknowledgeInstruction(numeric, 3_000)
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
        assertFalse(update.instruction?.contains("사용자 방향을 확인할 수 없어 다음 방향 안내를 보류합니다") == true)
        assertFalse(update.instruction?.contains("몸을 돌려주세요") == true)
    }

    @Test
    fun changedUnknownReasonRetiresOldUnreservedMessage() {
        val nav = navigator()
        val missing = nav.update(fix(), 1_000, false)
        val measured = requireNotNull(nav.currentGuidance(1_001, RouteFacingObservation(70.0, 20.0, 1_001)))
        assertTrue(missing.instruction?.contains("사용자 방향을 확인할 수 없어 다음 방향 안내를 보류합니다") == true)
        assertTrue(measured.instruction?.contains("나침반 방향은 측정되지만") == true)
        assertNotEquals(missing.speechCueToken, measured.speechCueToken)
        assertFalse(nav.reserveInstruction(missing))
        assertTrue(nav.reserveInstruction(measured))
        assertEquals("guidance_in_flight", nav.currentGuidance(1_002)?.reason)
    }

    private fun navigator(degraded: Boolean = false, turnType: Int = 13) =
        RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L), allowDegradedRouteGuidance = degraded).apply { setRoute(route(turnType)) }
    private fun route(turnType: Int = 13) = WalkingRoute("TEST", WalkingRouteSummary(200, 200),
        listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.0009), RoutePoint(-0.0009, 0.0009)),
        listOf(WalkingRouteGuidePoint(0, RoutePoint(0.0, 0.0009), null, 100, 100, turnType = turnType)))
    private fun fix(now: Long = 1_000) = TrustedLocation(0.0, 0.00045, 2f, now)
    private fun facing(heading: Double, now: Long) = RouteFacingObservation(heading, 5.0, now)
}
