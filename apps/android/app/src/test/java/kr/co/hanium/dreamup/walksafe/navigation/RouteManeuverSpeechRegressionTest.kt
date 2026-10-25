package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteManeuverSpeechRegressionTest {
    @Test
    fun departureIsSkippedAndFirstCueContainsTotalDistanceAndProviderLeftTurn() {
        // Positive maneuver fixture: independent, fresh camera-facing observation; raw fixes are unchanged.
        val navigator = navigator()
        val update = navigator.update(fix(0.0, 1_000L), 1_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 1_000L))

        assertFalse(update.instruction?.contains("전체 경로는 약 333m") == true)
        assertTrue(update.infoUtterance?.message?.contains("전체 경로는 약 333m") == true)
        assertTrue(update.instruction?.contains("약 12시 방향") == true)
        assertTrue(update.instruction?.contains("다음 안내 지점까지 경로를 따라 약 111미터") == true)
        assertTrue(update.instruction?.contains("9시 방향으로 꺾으세요") == true)
        assertEquals(1, update.guideIndex)
        assertFalse(update.instruction?.contains("94m") == true)
    }

    @Test
    fun missingGuideDistanceUsesRemainingPolylinePathInsteadOfStraightLine() {
        // Positive maneuver fixture: independent, fresh camera-facing observation; raw fixes are unchanged.
        val navigator = navigator().apply {
            setRoute(route().copy(guidePoints = listOf(guide(0, RoutePoint(0.001, 0.001), null, 13))))
        }
        val update = navigator.update(fix(0.0, 1_000L), 1_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 1_000L))

        assertTrue(update.instruction?.contains("약 12시 방향") == true)
        assertTrue(update.instruction?.contains("다음 안내 지점까지 경로를 따라 약 222미터") == true)
        assertTrue(update.instruction?.contains("3시 방향으로 꺾으세요") == true)
        assertFalse(update.instruction?.contains("157m") == true)
    }

    @Test
    fun acceptedPendingCueDoesNotRepeatAndCompletionConsumesOnlyItsDistanceStage() {
        // Positive maneuver fixture: independent, fresh camera-facing observation; raw fixes are unchanged.
        val navigator = navigator()
        val first = navigator.update(fix(0.00055, 1_000L), 1_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 1_000L))
        assertTrue(navigator.reserveInstruction(first))
        assertFalse(navigator.reserveInstruction(first))
        val whilePending = navigator.update(fix(0.00055, 2_000L), 2_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 2_000L))
        assertEquals("guidance_in_flight", whilePending.reason)
        navigator.acknowledgeInstruction(first, 2_001L)
        val stopped = navigator.update(fix(0.00055, 10_000L), 10_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 10_000L))
        assertEquals("guidance_already_completed", stopped.reason)

        val near = navigator.update(fix(0.00082, 11_000L), 11_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 11_000L))
        assertTrue(near.instruction?.contains("약 12시 방향") == true)
        assertTrue(near.instruction?.contains("다음 안내 지점까지 경로를 따라 약 20미터") == true)
        assertNotEquals(first.speechCueToken, near.speechCueToken)
    }

    @Test
    fun failedSpeechRetriesAfterDelayAndLateCompletionCannotConsumeRetry() {
        val navigator = navigator()
        val first = navigator.update(fix(0.00055, 1_000L), 1_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 1_000L))
        assertTrue(navigator.reserveInstruction(first))
        navigator.releaseInstruction(first, 1_001L)
        navigator.acknowledgeInstruction(first, 1_002L)
        assertEquals("guidance_retry_wait", navigator.update(fix(0.00055, 2_000L), 2_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 2_000L)).reason)
        val retry = navigator.update(fix(0.00055, 3_100L), 3_100L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 3_100L))
        assertNotNull(retry.instruction)
        assertNotEquals(first.speechCueToken, retry.speechCueToken)
        assertTrue(navigator.reserveInstruction(retry))
        navigator.acknowledgeInstruction(first, 3_101L)
        assertEquals("guidance_in_flight", navigator.update(fix(0.00055, 3_200L), 3_200L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 3_200L)).reason)
        navigator.acknowledgeInstruction(retry, 3_201L)
        assertEquals("guidance_already_completed", navigator.update(fix(0.00055, 4_000L), 4_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 4_000L)).reason)
    }

    @Test
    fun closerStageWaitsForPendingSentenceAndThenOffersTheLatestDistance() {
        // Positive maneuver fixture: independent, fresh camera-facing observation; raw fixes are unchanged.
        val navigator = navigator()
        val far = navigator.update(fix(0.00055, 1_000L), 1_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 1_000L))
        assertTrue(navigator.reserveInstruction(far))
        val near = navigator.update(fix(0.00082, 11_000L), 11_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 11_000L))
        assertFalse(near.cancelStaleNavigationSpeech)
        assertEquals("guidance_in_flight", near.reason)
        assertNull(near.instruction)
        navigator.acknowledgeInstruction(far, 11_001L)
        val afterCompletion = requireNotNull(navigator.retryGuidance(11_002L, facingObservation = RouteFacingObservation(90.0, 5.0, 11_002L)))
        assertTrue(afterCompletion.instruction?.contains("약 12시 방향") == true)
        assertTrue(afterCompletion.instruction?.contains("다음 안내 지점까지 경로를 따라 약 20미터") == true)
        assertTrue(navigator.reserveInstruction(afterCompletion))
        navigator.acknowledgeInstruction(far, 11_003L)
        assertEquals("guidance_in_flight", navigator.update(fix(0.00082, 12_000L), 12_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 12_000L)).reason)
    }

    @Test
    fun passingAnUnspokenTurnAdvancesToTheProviderRightTurn() {
        // Positive maneuver fixture: independent, fresh camera-facing observation; raw fixes are unchanged.
        val navigator = navigator()
        navigator.update(fix(0.00082, 1_000L), 1_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 1_000L))
        // Sparse fixes straddling the corner imply a diagonal course, not its new northbound leg.
        // Keep the real confidence gate, then provide consecutive movement along that new leg.
        val acrossCorner = navigator.update(TrustedLocation(0.00018, 0.001, 2f, 11_000L), 11_000L, false, facingObservation = RouteFacingObservation(0.0, 5.0, 11_000L))
        assertEquals("route_guidance", acrossCorner.reason)
        assertTrue(acrossCorner.directionOnly)
        assertNull(acrossCorner.guideIndex)
        assertFalse(acrossCorner.instruction?.contains("꺾으세요") == true)
        val afterTurn = navigator.update(TrustedLocation(0.00027, 0.001, 2f, 21_000L), 21_000L, false, facingObservation = RouteFacingObservation(0.0, 5.0, 21_000L))

        assertEquals("route_guidance", afterTurn.reason)
        assertEquals(2, afterTurn.guideIndex)
        assertTrue(afterTurn.instruction?.contains("3시 방향으로 꺾으세요") == true)
        assertFalse(afterTurn.instruction?.contains("9시 방향으로 꺾으세요") == true)
    }

    @Test
    fun oldRouteAndClearedRouteCallbacksCannotAcknowledgeTheNewRoute() {
        val navigator = navigator()
        val old = navigator.update(fix(0.00055, 1_000L), 1_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 1_000L))
        assertTrue(navigator.reserveInstruction(old))
        navigator.clear()
        navigator.acknowledgeInstruction(old, 2_000L)
        assertFalse(navigator.reserveInstruction(old))
        navigator.setRoute(route())
        val replacement = navigator.update(fix(0.00055, 3_000L), 3_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 3_000L))
        navigator.acknowledgeInstruction(old, 3_001L)
        assertNotEquals(old.speechCueToken, replacement.speechCueToken)
        assertTrue(navigator.reserveInstruction(replacement))
    }

    @Test
    fun untrustedAndInterruptedPositioningInvalidatePendingSpeech() {
        val navigator = navigator()
        val first = navigator.update(fix(0.00055, 1_000L), 1_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 1_000L))
        assertTrue(navigator.reserveInstruction(first))
        navigator.onPositioningEvidenceInterrupted()
        assertNull(navigator.currentInstruction(fix(0.00055, 2_000L)))
        navigator.acknowledgeInstruction(first, 2_000L)
        val recovering = navigator.update(fix(0.00055, 3_000L), 3_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 3_000L))
        assertNull(recovering.instruction)
        assertNull(recovering.speechCueToken)
        val restored = navigator.update(fix(0.00055, 3_100L), 3_100L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 3_100L))
        assertNotNull(restored.instruction)
        assertTrue(navigator.reserveInstruction(restored))
        assertTrue(navigator.onUntrustedLocation()?.cancelStaleNavigationSpeech == true)
        assertFalse(navigator.reserveInstruction(restored))
        navigator.acknowledgeInstruction(restored, 4_000L)
        assertNull(navigator.currentInstruction(fix(0.00055, 4_000L)))
    }

    @Test
    fun unknownManeuverDoesNotInventTurnFromPolylineShape() {
        // Positive maneuver fixture: independent, fresh camera-facing observation; raw fixes are unchanged.
        val navigator = navigator().apply {
            setRoute(route().copy(guidePoints = listOf(guide(0, RoutePoint(0.0, 0.001), 111, 999))))
        }
        val update = navigator.update(fix(0.00055, 1_000L), 1_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 1_000L))
        assertTrue(update.instruction?.contains("안내 지점") == true)
        assertFalse(update.instruction?.contains("왼쪽") == true)
        assertFalse(update.instruction?.contains("오른쪽") == true)
    }

    @Test
    fun unknownPositiveTurnCodeNeverSpeaksProviderMotionInstruction() {
        val rawCommand = "지금 차도로 오른쪽으로 꺾으세요."
        val unknownGuide = guide(0, RoutePoint(0.0, 0.001), 111, 999)
            .copy(instruction = rawCommand)
        val navigator = navigator().apply {
            setRoute(route().copy(guidePoints = listOf(unknownGuide)))
        }

        assertNull(unknownGuide.maneuverInstruction())
        val current = navigator.currentInstruction(fix(0.00055, 1_000L))
        val update = navigator.update(
            fix(0.00055, 1_000L), 1_000L, false,
            facingObservation = RouteFacingObservation(90.0, 5.0, 1_000L),
        )
        assertTrue(current?.contains("안내 지점") == true)
        assertTrue(update.instruction?.contains("안내 지점") == true)
        assertFalse(current?.contains(rawCommand) == true)
        assertFalse(update.instruction?.contains(rawCommand) == true)
        assertFalse(update.instruction?.contains("3시 방향으로 꺾으세요") == true)
    }

    @Test
    fun missingTurnCodeNeverSpeaksProviderMotionInstruction() {
        val rawCommand = "지금 차도로 오른쪽으로 꺾으세요."
        val uncodedGuide = guide(0, RoutePoint(0.0, 0.001), 111, 999)
            .copy(turnType = null, instruction = rawCommand)
        val navigator = navigator().apply {
            setRoute(route().copy(guidePoints = listOf(uncodedGuide)))
        }

        assertNull(uncodedGuide.maneuverInstruction())
        val current = navigator.currentInstruction(fix(0.00055, 1_000L))
        val update = navigator.update(
            fix(0.00055, 1_000L), 1_000L, false,
            facingObservation = RouteFacingObservation(90.0, 5.0, 1_000L),
        )
        assertTrue(current?.contains("안내 지점") == true)
        assertTrue(update.instruction?.contains("안내 지점") == true)
        assertFalse(current?.contains(rawCommand) == true)
        assertFalse(update.instruction?.contains(rawCommand) == true)
    }

    @Test
    fun backendAcceptedSmallGuideDistanceReversalDoesNotBlockLaterTurns() {
        // Positive maneuver fixture: independent, fresh camera-facing observation; raw fixes are unchanged.
        val navigator = navigator().apply {
            setRoute(route().copy(guidePoints = listOf(
                guide(0, RoutePoint(0.0, 0.001), 111, 12),
                guide(1, RoutePoint(0.0, 0.001), 109, 11),
                guide(2, RoutePoint(0.001, 0.001), 222, 13),
            )))
        }
        navigator.update(fix(0.00082, 1_000L), 1_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 1_000L))
        val acrossCorner = navigator.update(TrustedLocation(0.00018, 0.001, 2f, 11_000L), 11_000L, false, facingObservation = RouteFacingObservation(0.0, 5.0, 11_000L))
        assertEquals("route_guidance", acrossCorner.reason)
        assertTrue(acrossCorner.directionOnly)
        assertNull(acrossCorner.guideIndex)
        assertFalse(acrossCorner.instruction?.contains("꺾으세요") == true)
        val afterTurn = navigator.update(TrustedLocation(0.00027, 0.001, 2f, 21_000L), 21_000L, false, facingObservation = RouteFacingObservation(0.0, 5.0, 21_000L))
        assertEquals("route_guidance", afterTurn.reason)
        assertEquals(2, afterTurn.guideIndex)
        assertTrue(afterTurn.instruction?.contains("3시 방향으로 꺾으세요") == true)
    }

    @Test
    fun guideWithinAcceptedThirtyMeterCorridorCanAdvanceWithoutDistanceMetadata() {
        // Positive maneuver fixture: independent, fresh camera-facing observation; raw fixes are unchanged.
        val navigator = navigator().apply {
            setRoute(route().copy(guidePoints = listOf(
                guide(0, RoutePoint(0.00025, 0.0005), null, 12),
                guide(1, RoutePoint(0.001, 0.001), 222, 13),
            )))
        }
        navigator.update(fix(0.0, 1_000L), 1_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 1_000L))
        val afterFirstGuide = navigator.update(fix(0.0007, 11_000L), 11_000L, false, facingObservation = RouteFacingObservation(90.0, 5.0, 11_000L))
        assertEquals(1, afterFirstGuide.guideIndex)
        assertTrue(afterFirstGuide.instruction?.contains("3시 방향으로 꺾으세요") == true)
    }

    private fun navigator() = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0)).apply { setRoute(route()) }

    // Synthetic coordinates only; no recorded walk or device location is used.
    private fun fix(longitude: Double, atMs: Long) = TrustedLocation(0.0, longitude, 2f, atMs)

    private fun guide(index: Int, point: RoutePoint, progressM: Int?, turnType: Int) = WalkingRouteGuidePoint(
        index, point, null, progressM, progressM?.let { 333 - it }, turnType = turnType,
    )

    private fun route() = WalkingRoute(
        priority = "STAIR_AVOID",
        summary = WalkingRouteSummary(333, 300),
        polyline = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.001), RoutePoint(0.001, 0.001), RoutePoint(0.001, 0.002)),
        guidePoints = listOf(
            guide(0, RoutePoint(0.0, 0.0), 0, 200).copy(pointType = "SP"),
            guide(1, RoutePoint(0.0, 0.001), 111, 12).copy(instruction = "좌회전 후 94m 이동"),
            guide(2, RoutePoint(0.001, 0.001), 222, 13),
        ),
    )
}
