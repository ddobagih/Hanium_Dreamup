package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import org.junit.Assert.*
import org.junit.Test

class RouteNavigatorUncertainGuidanceTest {
    @Test
    fun unreservedConfidentOfferCannotBeReservedAfterPositionBecomesUncertain() {
        val navigator = nearbyBranches()
        val confident = navigator.update(location(15.0, 0.0, 1_000), 1_000, false)
        assertEquals(RouteMatchQuality.HIGH, navigator.currentRouteMatch()?.quality)
        val uncertain = navigator.update(location(15.0, 2.4, 2_000), 2_000, false)
        assertEquals(RouteMatchQuality.LOW, navigator.currentRouteMatch()?.quality)
        assertEquals(confident.guideIndex, uncertain.guideIndex)
        assertEquals(confident.speechCueToken?.distanceBand, uncertain.speechCueToken?.distanceBand)
        assertTrue(confident.instruction?.contains("사용자 방향을 확인할 수 없어 다음 방향 안내를 보류합니다") == true)
        assertNotNull(uncertain.routeAlignmentDiagnostic?.bearingDegreesTrueNorth)
        assertTrue(uncertain.instruction?.contains("사용자 방향을 확인할 수 없어 다음 방향 안내를 보류합니다") == true)
        assertFalse(confident.instruction?.contains("추정") == true)
        assertFalse(confident.infoUtterance?.message?.contains("추정") == true)
        assertFalse(uncertain.instruction?.contains("추정") == true)
        assertNull(uncertain.infoUtterance)
        assertNotEquals(confident.speechCueToken, uncertain.speechCueToken)
        assertFalse(navigator.reserveInstruction(confident))
        assertTrue(navigator.reserveInstruction(uncertain))
    }

    @Test
    fun uncertaintyChangeDoesNotInterruptTheSameGuideSentenceAlreadyInFlight() {
        val navigator = nearbyBranches()
        val confident = navigator.update(location(15.0, 0.0, 1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(confident))
        val duringSpeech = navigator.update(location(15.0, 2.4, 2_000), 2_000, false)
        assertEquals(RouteMatchQuality.LOW, navigator.currentRouteMatch()?.quality)
        assertEquals("guidance_in_flight", duringSpeech.reason)
        assertFalse(duringSpeech.cancelStaleNavigationSpeech)
        navigator.acknowledgeInstruction(confident, 2_001)
        val next = requireNotNull(navigator.currentGuidance(2_002))
        assertFalse(next.instruction?.contains("추정") == true)
        assertNull(next.infoUtterance)
        assertNotEquals(confident.speechCueToken, next.speechCueToken)
        assertTrue(navigator.reserveInstruction(next))
    }

    @Test
    fun ambiguityKeepsDebugSpeechAsAnEstimateWithoutPromotingLocationOrFacingEvidence() {
        listOf(false, true).forEach { degraded ->
            // Inspect the emitted uncertainty claim, not its 1.5-second announcement delay.
            val navigator = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = if (degraded) 0L else 1_500L),
                allowDegradedRouteGuidance = degraded)
            navigator.setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400),
                listOf(point(0.0), point(200.0), point(0.0, 4.0)), emptyList()))
            val raw = location(0.0, 4.0, 1_000)
            val update = navigator.update(raw, 1_000, false, facingObservation = RouteFacingObservation(90.0, 5.0, 1_000))
            assertEquals(RouteMatchQuality.LOW, navigator.currentRouteMatch()?.quality)
            assertNull(navigator.currentAcceptedRouteMatchFor(1_000))
            assertNull(navigator.currentBearingDeg())
            assertFalse(update.arrivalCandidate)
            if (degraded) {
                assertFalse(update.instruction?.contains("추정") == true)
                assertNull(update.infoUtterance)
                assertTrue(update.instruction?.contains("현재 위치에서 경로의 진행 방향을 정확히 구분하기 어렵습니다") == true)
                assertFalse(update.instruction?.contains("몸을 돌려주세요") == true)
                assertTrue(navigator.reserveInstruction(update))
                navigator.acknowledgeInstruction(update, 1_001)
                val manual = requireNotNull(navigator.currentGuidance(1_002, RouteFacingObservation(90.0, 5.0, 1_002)))
                assertFalse(manual.instruction?.contains("추정") == true)
                assertNull(manual.infoUtterance)
            } else {
                assertNull(update.pendingUserDecision)
                assertEquals("route_geometry_recheck_wait", update.reason)
                assertNull(update.instruction)
                assertFalse(update.instruction.orEmpty().contains("몸을 돌려주세요"))
                assertFalse(Regex("[0-9]+시 방향").containsMatchIn(update.instruction.orEmpty()))
                assertNull(update.speechCueToken)
                assertFalse(update.cancelStaleNavigationSpeech)
            }
        }
    }

    @Test
    fun arrivalCandidateClearsAfterTwoAccurateOffRouteLocationsAndResumesDeviationPolicy() {
        val navigator = candidate()
        val oldToken = requireNotNull(navigator.pendingDecisionToken())
        assertTrue(navigator.update(location(145.0, -198.0, 13_000), 13_000, false).arrivalCandidate)
        val departed = navigator.update(location(146.0, -198.0, 14_000), 14_000, false)
        assertFalse(departed.arrivalCandidate)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, departed.pendingUserDecision)
        assertEquals("arrival_confirmation_stale", navigator.confirmArrival(oldToken).reason)
        assertEquals(RouteNavigatorUserDecision.REROUTE,
            navigator.update(location(147.0, -198.0, 15_000), 15_000, false).pendingUserDecision)
    }

    @Test
    fun departureDoesNotAccumulateRepeatedRawEvidenceOrPoorAccuracyOrLongGaps() {
        val navigator = candidate()
        val away = location(145.0, -198.0, 13_000)
        navigator.update(away, 13_000, false)
        repeat(5) { index ->
            val filteredTime = 13_001L + index
            val duplicateRaw = navigator.update(away, filteredTime, false,
                FilteredRoutePosition(RoutePoint(away.latitude, away.longitude), 3.0, filteredTime))
            assertTrue(duplicateRaw.arrivalCandidate)
        }
        val coarse = navigator.update(location(146.0, -198.0, 14_000, 20f), 14_000, false)
        assertTrue(coarse.arrivalCandidate)
        assertTrue(navigator.update(location(146.0, -198.0, 15_000), 15_000, false).arrivalCandidate)
        assertTrue(navigator.update(location(146.0, -198.0, 26_000), 26_000, false).arrivalCandidate)
        assertFalse(navigator.update(location(146.0, -198.0, 27_000), 27_000, false).arrivalCandidate)
    }

    @Test
    fun filteredLocationMustAlsoSupportTheDepartureFromArrivalArea() {
        val navigator = candidate()
        val raw = location(145.0, -198.0, 13_000)
        val original = point(100.0, -198.0)
        listOf(13_000L, 14_000L).forEach { time ->
            val update = navigator.update(raw.copy(elapsedRealtimeMs = time), time, false,
                FilteredRoutePosition(original, 3.0, time))
            assertTrue(update.arrivalCandidate)
        }
    }

    private fun candidate(): RouteNavigator = RouteNavigator().apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(300, 300),
            listOf(point(0.0), point(100.0), point(100.0, -200.0)), emptyList()))
        // S07-32 (R6): the arrival question needs 3 s of consecutive near-destination evidence.
        for (at in 1_000L..3_000L step 1_000L) assertFalse(update(location(100.0, -198.0, at), at, false).arrivalCandidate)
        assertTrue(update(location(100.0, -198.0, 4_000), 4_000, false).arrivalCandidate)
    }
    // Isolate uncertainty provenance and lease changes; default timer safety is tested separately.
    private fun nearbyBranches() = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L), allowDegradedRouteGuidance = true).apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(125, 300),
            listOf(point(0.0), point(30.0), point(30.0, 20.0), point(0.0, 20.0), point(0.0, 5.0), point(30.0, 5.0)),
            emptyList()))
    }
    private fun point(east: Double, north: Double = 0.0) = RoutePoint(north / 111194.92664455874, east / 111194.92664455874)
    private fun location(east: Double, north: Double, time: Long, accuracy: Float = 3f): TrustedLocation {
        val point = point(east, north)
        return TrustedLocation(point.latitude, point.longitude, accuracy, time)
    }
}
