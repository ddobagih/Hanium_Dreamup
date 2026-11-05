package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import org.junit.Assert.*
import org.junit.Test

/** Exercises delivered speech/ownership with zero recheck delay; RouteDirectionRecheckGraceTest verifies the production timer. */
class RouteNavigatorClockDistanceGuidanceTest {
    @Test fun frontNamesTwelveOClockAndMeasuredGuideDistance() = assertClockAndDistance(90.0, 12)
    @Test fun rightNamesThreeOClockAndMeasuredGuideDistance() = assertClockAndDistance(0.0, 3)
    @Test fun leftNamesNineOClockAndMeasuredGuideDistance() = assertClockAndDistance(180.0, 9)
    @Test fun behindNamesSixOClockAndMeasuredGuideDistance() = assertClockAndDistance(270.0, 6)

    @Test fun nominalClocksKeepTheMeasuredGuideDistanceAndTheirOwnFreshFacingLease() {
        for (accuracy in listOf(12.0, 20.0)) {
            for ((heading, hour) in listOf(90.0 to 12, 0.0 to 3, 180.0 to 9, 270.0 to 6)) {
                val nav = navigator()
                val observation = facing(heading).copy(headingAccuracyDegrees = accuracy)
                nav.update(fix(50.0, atMs = 999L), 999L, false,
                    facingObservation = facing(heading, 999L).copy(headingAccuracyDegrees = accuracy))
                val update = nav.update(fix(50.0), 1_000L, false,
                    facingObservation = observation)
                val text = requireNotNull(update.instruction)
                assertTrue(text, text.contains("바라보고 있는 방향 기준 약 ${hour}시 방향"))
                assertFalse(text, text.contains("시에서") || text.contains("사이 방향"))
                assertTrue(text, text.contains("다음 안내 지점까지 경로를 따라 약 50미터입니다"))
                assertEquals(RouteFacingExpression.CLOCK, update.facingDiagnostic?.expression)
                assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, update.facingDiagnostic?.clockReferenceKind)
                assertTrue(requireNotNull(update.speechCueToken).requiresFacingEvidence)
                assertTrue(nav.reserveInstruction(update))
                assertTrue(nav.isInstructionCurrent(update, 1_500L, observation))
                assertFalse(nav.isInstructionCurrent(update, 1_501L, observation))
                assertNotNull(nav.currentAcceptedRouteMatchFor(1_000L))
                assertNull(nav.currentAcceptedRouteMatchFor(1_500L))
                assertFalse(text, text.contains("뒤로 걸"))
            }
        }
    }

    @Test fun aUsableObservationWithRejectedNumericQualityKeepsOnlyTheRouteOverview() {
        val observation = RouteFacingObservation(75.0, 30.0, 1_000L)
        assertTrue(RouteFacingGuidance.isUsableObservation(observation, 1_000L))
        assertNull(RouteFacingGuidance.evaluate(90.0, observation, 1_000L).clockRange)
        val update = navigator().update(fix(50.0), 1_000L, false, facingObservation = observation)
        val text = requireNotNull(update.instruction)
        assertNoCurrentFacingOrMovement(text)
        assertTrue(text, text.contains("경로를 따라 50m 가면 다음 안내 지점에는 경로 기준 3시 방향 회전이 있습니다."))
        assertEquals(RouteFacingExpression.UNKNOWN, update.facingDiagnostic?.expression)
        assertFalse(requireNotNull(update.speechCueToken).requiresFacingEvidence)
    }

    @Test fun changingClockStillNeedsTwoDistinctFreshObservationsAndWaitsWithoutCoarseCommands() {
        val nav = navigator()
        val first = nav.update(fix(50.0), 1_000L, false, facingObservation = facing(90.0))
        assertTrue(requireNotNull(first.instruction).contains("약 12시 방향"))
        val edgeObservation = facing(75.0, 1_100L)
        val edge = requireNotNull(nav.currentGuidance(1_100L, edgeObservation))
        assertFalse(edge.instruction.orEmpty().contains("약 1시 방향"))
        assertFalse(edge.instruction.orEmpty().contains("시에서") || edge.instruction.orEmpty().contains("사이 방향"))
        val repeated = requireNotNull(nav.currentGuidance(1_150L, edgeObservation))
        assertFalse(repeated.instruction.orEmpty().contains("약 1시 방향"))
        val stable = requireNotNull(nav.currentGuidance(1_200L, facing(75.0, 1_200L)))
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, stable.facingDiagnostic?.clockReferenceKind)
        assertTrue(stable.instruction.orEmpty().contains("약 1시 방향, 다음 안내 지점까지 경로를 따라 약 50미터입니다."))
        val precise = requireNotNull(nav.currentGuidance(1_300L, facing(60.0, 1_300L)))
        assertEquals(RouteClockReferenceKind.REPORTED_ERROR, precise.facingDiagnostic?.clockReferenceKind)
        assertTrue(precise.instruction.orEmpty().contains("약 1시 방향"))
    }

    @Test fun presentCameraClockAndFutureProviderClockUseSeparateReferenceFrames() {
        val text = requireNotNull(navigator(turnType = 18).update(fix(50.0), 1_000L, false,
            facingObservation = facing(90.0)).instruction)
        assertTrue(text, text.contains("바라보고 있는 방향 기준 약 12시 방향, 다음 안내 지점까지 경로를 따라 약 50미터입니다."))
        assertTrue(text, text.contains("다음 안내 지점에서 경로 기준으로 2시 방향 길로 이동하세요."))
        assertFalse(text, text.contains("50m 앞"))
    }

    @Test fun initialJoinKeepsClockStraightLineDistanceAndUnconfirmedConnector() {
        val origin = fix(0.0, northM = 22.0, accuracyM = 1f)
        val nav = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L)).apply { setRoute(route(), origin = origin, waitForInitialRouteJoin = true) }
        val update = nav.update(origin, 1_000L, false, filteredPosition = filtered(origin),
            facingObservation = facing(90.0).copy(headingAccuracyDegrees = 1.0))
        val text = requireNotNull(update.instruction)
        assertTrue(text, text.contains("바라보고 있는 방향 기준 약 3시 방향, 직선거리 약 22미터입니다."))
        assertFalse(text, text.contains("연결 통로는 확인되지 않았습니다"))
        assertTrue(update.directionOnly)
        assertTrue(nav.isWaitingForInitialRouteJoin())
        assertNull(nav.currentAcceptedRouteMatchFor(1_000L))
        assertFalse(text, text.contains("이동하세요"))
    }

    @Test fun initialJoinNominalClockKeepsStraightLineDistanceAndUnconfirmedConnector() {
        val origin = fix(0.0, northM = 22.0, accuracyM = 1f)
        val nav = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L)).apply { setRoute(route(), origin = origin, waitForInitialRouteJoin = true) }
        val first = nav.update(origin, 1_000L, false, filteredPosition = filtered(origin),
            facingObservation = facing(90.0).copy(headingAccuracyDegrees = 20.0))
        assertFalse(first.instruction.orEmpty().contains("바라보고 있는 방향 기준 약 3시 방향"))
        val confirmed = requireNotNull(nav.currentGuidance(1_001L,
            facing(90.0, 1_001L).copy(headingAccuracyDegrees = 20.0)))
        val text = requireNotNull(confirmed.instruction)
        assertTrue(text, text.contains("바라보고 있는 방향 기준 약 3시 방향"))
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, confirmed.facingDiagnostic?.clockReferenceKind)
        assertFalse(text, text.contains("시에서") || text.contains("사이 방향"))
        assertTrue(text, text.contains("직선거리 약 22미터입니다."))
        assertFalse(text, text.contains("연결 통로는 확인되지 않았습니다"))
        assertFalse(text, text.contains("이동하세요") || text.contains("몸을 돌려주세요"))
        assertTrue(nav.isWaitingForInitialRouteJoin())
        assertNull(nav.currentAcceptedRouteMatchFor(1_000L))
    }

    @Test fun unpassedTurnAtVertexKeepsRouteRelativeManeuverWithoutInventingCameraClock() {
        val corner = WalkingRoute("TEST", WalkingRouteSummary(100, 100),
            listOf(point(0.0), point(20.0), point(20.0, -80.0)),
            listOf(WalkingRouteGuidePoint(0, point(20.0), null, 20, 80, turnType = 13)))
        val nav = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L)).apply { setRoute(corner) }
        val approach = nav.update(fix(10.0, accuracyM = 3f), 1_000L, false, facingObservation = facing(90.0))
        assertTrue(nav.reserveInstruction(approach))
        nav.acknowledgeInstruction(approach, 1_000L)
        val vertex = nav.update(fix(20.0, atMs = 2_000L, accuracyM = 3f), 2_000L, false,
            facingObservation = facing(180.0, 2_000L))
        assertEquals(RouteAlignmentReason.TURN_PASSAGE_PENDING, vertex.routeAlignmentDiagnostic?.reason)
        assertEquals("안내 지점 부근입니다. 경로 기준으로 3시 방향으로 꺾으세요.", vertex.instruction)
        assertEquals(RouteFacingExpression.NOT_SPOKEN, vertex.facingDiagnostic?.expression)
        assertTrue(nav.reserveInstruction(vertex))
        assertTrue(nav.isInstructionCurrent(vertex, 2_000L, facing(180.0, 2_000L)))
    }

    @Test fun directionReferenceWithoutAcceptedProgressDoesNotInventRouteDistance() {
        val origin = fix(0.0, northM = 15.0, accuracyM = 3f)
        val nav = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L), allowDegradedRouteGuidance = true).apply { setRoute(route(), origin = origin) }
        val update = nav.update(origin, 1_000L, false, facingObservation = facing(90.0))
        val text = requireNotNull(update.instruction)
        assertTrue(update.directionOnly)
        assertTrue(text, text.contains("통로는 확인되지 않았습니다"))
        assertFalse(text, Regex("\\d+\\s*(?:m|미터)").containsMatchIn(text))
        assertNull(nav.currentAcceptedRouteMatchFor(1_000L))
        assertFalse(text, text.contains("이동하세요"))
    }

    @Test fun missingAndStaleFacingKeepMeasuredRouteDistanceOnlyAsOverview() {
        for (observation in listOf(null, facing(90.0, 499L))) {
            val text = requireNotNull(navigator().update(fix(50.0), 1_000L, false,
                facingObservation = observation).instruction)
            assertTrue(text, text.contains("사용자 방향을 확인할 수 없어 다음 방향 안내를 보류합니다"))
            assertTrue(text, text.contains("경로를 따라 50m 가면 다음 안내 지점"))
            assertNoCurrentFacingOrMovement(text)
        }
    }

    @Test fun finalSegmentKeepsDestinationDistanceWhenItHasNoNextGuide() {
        val nav = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L)).apply { setRoute(route().copy(guidePoints = emptyList())) }
        val text = requireNotNull(nav.update(fix(50.0), 1_000L, false,
            facingObservation = facing(0.0)).instruction)
        assertTrue(text, text.contains("약 3시 방향"))
        assertTrue(text, text.contains("목적지까지 약 150m 남았습니다."))
        assertFalse(text, text.contains("다음 안내 지점까지"))
    }

    @Test fun aDirectionRecheckHasNoFacingLeaseAndExpiresWhenPreciseDirectionRecovers() {
        val nav = navigator()
        val imprecise = RouteFacingObservation(75.0, 30.0, 1_000L)
        val recheck = nav.update(fix(50.0), 1_000L, false, facingObservation = imprecise)
        assertTrue(requireNotNull(recheck.instruction).contains("시계 방향을 확정하기 어렵습니다"))
        assertTrue(recheck.instruction.orEmpty().contains("방향이 확인되면 안내를 이어갑니다"))
        assertFalse(requireNotNull(recheck.speechCueToken).requiresFacingEvidence)
        assertNull(recheck.speechCueToken!!.facingObservedAtElapsedRealtimeMs)
        assertEquals(RouteFacingExpression.UNKNOWN, recheck.facingDiagnostic?.expression)
        assertTrue(nav.reserveInstruction(recheck))
        assertTrue(nav.isInstructionCurrent(recheck, 1_050L, imprecise.copy(observedAtElapsedRealtimeMs = 1_050L)))
        assertFalse(nav.isInstructionCurrent(recheck, 1_100L, facing(90.0, 1_100L)))
    }

    @Test fun knownDistanceChangesWithinOneBandKeepTheCueAndCompletionCadence() {
        val nav = navigator()
        val first = nav.update(fix(50.0), 1_000L, false, facingObservation = facing(90.0))
        val oneMeterLater = nav.update(fix(51.0, atMs = 1_100L), 1_100L, false,
            facingObservation = facing(90.0, 1_100L))
        assertEquals(first.speechCueToken, oneMeterLater.speechCueToken)
        assertTrue(requireNotNull(oneMeterLater.instruction).contains("약 49미터입니다"))
        assertTrue(nav.reserveInstruction(oneMeterLater))
        assertEquals("guidance_in_flight", nav.retryGuidance(1_200L, facing(90.0, 1_200L))?.reason)
        nav.acknowledgeInstruction(oneMeterLater, 1_300L)
        val beforePeriodic = nav.update(fix(54.0, atMs = 6_299L), 6_299L, false,
            facingObservation = facing(90.0, 6_299L))
        assertNull(beforePeriodic.instruction)
        val periodic = requireNotNull(nav.retryGuidance(6_300L, facing(90.0, 6_300L)))
        assertTrue(requireNotNull(periodic.instruction).contains("약 46미터입니다"))
        assertNotEquals(first.speechCueToken, periodic.speechCueToken)
    }

    @Test fun explicitGuidanceAfterCompletedRecheckUsesTheRecoveredClockImmediately() {
        val nav = navigator()
        val recheck = nav.update(fix(50.0), 1_000L, false,
            facingObservation = RouteFacingObservation(75.0, 30.0, 1_000L))
        assertTrue(nav.reserveInstruction(recheck))
        nav.acknowledgeInstruction(recheck, 1_100L)
        val recovered = requireNotNull(nav.currentGuidance(1_200L, facing(0.0, 1_200L)))
        assertTrue(requireNotNull(recovered.instruction).contains("약 3시 방향, 다음 안내 지점까지 경로를 따라 약 50미터입니다."))
        assertTrue(requireNotNull(recovered.speechCueToken).requiresFacingEvidence)
        assertTrue(nav.reserveInstruction(recovered))
        assertTrue(nav.isInstructionCurrent(recovered, 1_200L, facing(0.0, 1_200L)))
    }

    @Test fun completedRecheckDoesNotDelayRecoveryAndActualClockDoneUsesTheFasterCadence() {
        for (useRetry in listOf(false, true)) {
            val nav = navigator()
            val imprecise = RouteFacingObservation(75.0, 30.0, 1_000L)
            val recheck = nav.update(fix(50.0), 1_000L, false, facingObservation = imprecise)
            assertTrue(nav.reserveInstruction(recheck))
            nav.acknowledgeInstruction(recheck, 2_000L)
            val recovered = if (useRetry) requireNotNull(nav.retryGuidance(2_100L, facing(0.0, 2_100L)))
                else nav.update(fix(50.0, atMs = 2_100L), 2_100L, false,
                    facingObservation = facing(0.0, 2_100L))
            assertTrue(requireNotNull(recovered.instruction).contains("약 3시 방향, 다음 안내 지점까지 경로를 따라 약 50미터입니다."))
            assertTrue(nav.reserveInstruction(recovered))
            assertTrue(nav.isInstructionCurrent(recovered, 2_100L, facing(0.0, 2_100L)))
            nav.acknowledgeInstruction(recheck, 2_101L)
            assertEquals("guidance_in_flight", nav.retryGuidance(2_102L, facing(0.0, 2_102L))?.reason)
            nav.acknowledgeInstruction(recovered, 2_200L)
            val early = nav.update(fix(50.0, atMs = 4_199L), 4_199L, false,
                facingObservation = facing(0.0, 4_199L))
            assertNull(early.instruction)
            assertEquals("guidance_rate_limited", early.reason)
            val sameClock = requireNotNull(nav.retryGuidance(4_200L, facing(0.0, 4_200L)))
            assertNull(sameClock.instruction)
            assertEquals("guidance_already_completed", sameClock.reason)
            assertNull(nav.retryGuidance(7_199L, facing(0.0, 7_199L))?.instruction)
            val periodic = requireNotNull(nav.retryGuidance(7_200L, facing(0.0, 7_200L)))
            assertTrue(periodic.instruction.orEmpty().contains("약 3시 방향"))
            assertTrue(nav.reserveInstruction(periodic))
        }
    }

    private fun assertClockAndDistance(heading: Double, hour: Int) {
        val update = navigator().update(fix(50.0), 1_000L, false, facingObservation = facing(heading))
        val text = requireNotNull(update.instruction)
        assertTrue(text, text.contains("바라보고 있는 방향 기준 약 ${hour}시 방향, 다음 안내 지점까지 경로를 따라 약 50미터입니다."))
        assertEquals(RouteFacingExpression.CLOCK, update.facingDiagnostic?.expression)
        assertFalse(text, text.contains("경로는 오른쪽입니다") || text.contains("경로는 왼쪽입니다") || text.contains("방향이 뒤쪽"))
        if (hour == 12) {
            assertTrue(text, text.contains("다음 안내 지점에서 경로 기준으로 3시 방향으로 꺾으세요."))
        } else {
            val condition = "휴대전화 후면 카메라를 몸 정면에 맞춘 상태에서만"
            val action = "안내한 방향으로 몸을 돌려주세요."
            assertFalse(text, text.contains(condition))
            assertTrue(text, text.contains(action))
            assertFalse(text, text.contains("꺾으세요") || text.contains("이동하세요") || text.contains("뒤로 걸"))
        }
    }

    private fun assertNoCurrentFacingOrMovement(text: String) {
        for (phrase in listOf("바라보고 있는 방향 기준 약", "앞쪽", "뒤쪽", "경로는 왼쪽", "경로는 오른쪽",
            "꺾으세요", "이동하세요", "직진하세요", "돌리세요", "맞추세요")) {
            assertFalse("$phrase in $text", text.contains(phrase))
        }
    }

    private fun navigator(turnType: Int = 13) = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L)).apply { setRoute(route(turnType)) }
    private fun route(turnType: Int = 13) = WalkingRoute("TEST", WalkingRouteSummary(200, 200),
        listOf(point(0.0), point(100.0), point(100.0, -100.0)),
        listOf(WalkingRouteGuidePoint(0, point(100.0), null, 100, 100, turnType = turnType)))
    private fun point(eastM: Double, northM: Double = 0.0) =
        RoutePoint(northM / 111_194.92664455874, eastM / 111_194.92664455874)
    private fun fix(eastM: Double, atMs: Long = 1_000L, northM: Double = 0.0, accuracyM: Float = 2f) =
        point(eastM, northM).let { TrustedLocation(it.latitude, it.longitude, accuracyM, atMs) }
    private fun filtered(fix: TrustedLocation) = FilteredRoutePosition(
        RoutePoint(fix.latitude, fix.longitude), fix.accuracyM.toDouble(), fix.elapsedRealtimeMs)
    private fun facing(degrees: Double, atMs: Long = 1_000L) = RouteFacingObservation(degrees, 3.0, atMs)
}
