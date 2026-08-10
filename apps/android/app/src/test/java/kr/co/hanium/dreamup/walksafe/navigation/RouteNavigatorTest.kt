package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteNavigatorTest {
    @Test
    fun emitsFallbackInstructionWhenGuideInstructionMissing() {
        val navigator = RouteNavigator()
        navigator.setRoute(route(guideInstruction = null))

        val update = navigator.update(locationNearStart(), nowMs = 1_000L, requestInFlight = false)

        assertNotNull(update.instruction)
        assertFalse(update.shouldReroute)
    }

    @Test
    fun exposesCurrentRouteBearingForMotionContext() {
        val navigator = RouteNavigator()
        navigator.setRoute(route(bearingDeg = 12.5f))

        assertEquals(12.5f, navigator.currentBearingDeg()!!, 0.001f)
    }

    @Test
    fun exposesCurrentInstructionForAnExplicitVoiceQuery() {
        val navigator = RouteNavigator()
        navigator.setRoute(route(guideInstruction = "오른쪽으로 이동하세요."))

        val instruction = navigator.currentInstruction(locationNearStart())

        assertTrue(instruction?.contains("오른쪽으로 이동하세요.") == true)
    }

    @Test
    fun tmapCrosswalkTurnTypesReplaceRawCommandsWithTheFixedReferenceNotice() {
        listOf(211, 212, 213).forEach { turnType ->
            val navigator = RouteNavigator()
            val rawCommand = "신호가 초록색이니 지금 건너세요."
            val baseRoute = route(guideInstruction = rawCommand)
            navigator.setRoute(
                baseRoute.copy(
                    guidePoints = baseRoute.guidePoints.map { guide ->
                        guide.copy(turnType = turnType)
                    },
                ),
            )

            val current = navigator.currentInstruction(locationNearStart())
            val update = navigator.update(locationNearStart(), nowMs = 1_000L, requestInFlight = false)

            assertEquals(CROSSWALK_REFERENCE_NOTICE_KO, current)
            assertEquals(CROSSWALK_REFERENCE_NOTICE_KO, update.instruction)
            assertFalse(current?.contains(rawCommand) == true)
            assertFalse(current?.contains("지금 건너세요") == true)
        }
    }

    @Test
    fun crosswalkTextMarkersReplaceRawCommandsWithTheFixedReferenceNotice() {
        val cases = listOf(
            Triple("횡단보도를 건너세요.", null, null),
            Triple("CrossWalk ahead, cross now.", null, null),
            Triple("지금 건너도 안전합니다.", "zebra", null),
            Triple("건너도 됩니다.", null, "횡단보도"),
        )

        cases.forEach { (instruction, pointType, pointName) ->
            val navigator = RouteNavigator()
            val baseRoute = route(guideInstruction = instruction)
            navigator.setRoute(
                baseRoute.copy(
                    guidePoints = baseRoute.guidePoints.map { guide ->
                        guide.copy(
                            point = guide.point.copy(name = pointName),
                            pointType = pointType,
                        )
                    },
                ),
            )

            val output = navigator.currentInstruction(locationNearStart())

            assertEquals(CROSSWALK_REFERENCE_NOTICE_KO, output)
            assertFalse(output?.contains("지금 건너세요") == true)
            assertFalse(output?.contains("건너도 됩니다") == true)
            assertFalse(output?.contains("안전합니다") == true)
            assertFalse(output?.contains("cross now", ignoreCase = true) == true)
        }
    }

    @Test
    fun nonCrosswalkInstructionKeepsExistingRouteGuidance() {
        val navigator = RouteNavigator()
        navigator.setRoute(route(guideInstruction = "오른쪽으로 이동하세요."))

        val output = navigator.currentInstruction(locationNearStart())

        assertTrue(output?.contains("오른쪽으로 이동하세요.") == true)
        assertFalse(output == CROSSWALK_REFERENCE_NOTICE_KO)
    }

    @Test
    fun voiceInstructionQueryReturnsNullWithoutAnActiveRoute() {
        assertEquals(null, RouteNavigator().currentInstruction(locationNearStart()))
    }

    @Test
    fun exposesWhetherAnAuthoritativeTmapRouteIsInstalled() {
        val navigator = RouteNavigator()
        assertFalse(navigator.hasRoute())

        navigator.setRoute(route())
        assertTrue(navigator.hasRoute())

        navigator.clear()
        assertFalse(navigator.hasRoute())
    }

    @Test
    fun gatesRerouteByOffRouteCooldownAndInFlight() {
        val navigator = RouteNavigator()
        navigator.setRoute(route())

        val blocked = navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = true)
        val first = navigator.update(offRouteLocation(), nowMs = 2_000L, requestInFlight = false)
        val cooldown = navigator.update(offRouteLocation(), nowMs = 3_000L, requestInFlight = false)

        assertFalse(blocked.shouldReroute)
        assertTrue(first.shouldReroute)
        assertFalse(cooldown.shouldReroute)
        assertEquals("off_route_waiting", cooldown.reason)
        assertTrue(cooldown.instruction?.contains("경로를 벗어났습니다") == true)
        assertFalse(cooldown.instruction?.contains("직진하세요") == true)
    }

    @Test
    fun confirmedOffRouteNeverRepeatsTheStaleTurnWhileRerouteIsInFlight() {
        val navigator = RouteNavigator(RouteNavigatorConfig(offRouteConfirmSamples = 1))
        navigator.setRoute(route(guideInstruction = "오른쪽으로 이동하세요."))

        val update = navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = true)

        assertTrue(update.offRoute)
        assertFalse(update.shouldReroute)
        assertEquals("off_route_waiting", update.reason)
        assertFalse(update.instruction?.contains("오른쪽") == true)
        assertEquals(null, navigator.currentInstruction(offRouteLocation()))
    }

    @Test
    fun requiresConsecutiveOffRouteSamplesBeforeReroute() {
        val navigator = RouteNavigator()
        navigator.setRoute(route())

        val first = navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = false)
        val second = navigator.update(offRouteLocation(), nowMs = 2_000L, requestInFlight = false)

        assertFalse(first.shouldReroute)
        assertFalse(first.offRoute)
        assertEquals("off_route_pending", first.reason)
        assertTrue(second.shouldReroute)
        assertTrue(second.offRoute)
    }

    @Test
    fun ignoresOffRouteWhenAccuracyStillOverlapsRoute() {
        val navigator = RouteNavigator()
        navigator.setRoute(route())

        val first = navigator.update(uncertainOffRouteLocation(), nowMs = 1_000L, requestInFlight = false)
        val second = navigator.update(uncertainOffRouteLocation(), nowMs = 2_000L, requestInFlight = false)
        val preciseFirst = navigator.update(slightlyOffRouteLocation(accuracyM = 3f), nowMs = 8_000L, requestInFlight = false)

        assertFalse(first.shouldReroute)
        assertFalse(first.offRoute)
        assertFalse(second.shouldReroute)
        assertFalse(second.offRoute)
        assertFalse(preciseFirst.shouldReroute)
        assertEquals("off_route_pending", preciseFirst.reason)
    }

    @Test
    fun arrivalClearsRouteOnlyAfterSpeechIsAcknowledged() {
        val navigator = RouteNavigator()
        navigator.setRoute(route())

        val first = navigator.update(
            TrustedLocation(37.0009, 127.0, 5f, 1_000L),
            nowMs = 1_000L,
            requestInFlight = false,
        )
        val second = navigator.update(
            TrustedLocation(37.0009, 127.0, 5f, 2_000L),
            nowMs = 2_000L,
            requestInFlight = false,
        )

        assertFalse(first.arrived)
        assertTrue(second.arrived)
        assertTrue(navigator.hasRoute())

        navigator.acknowledgeInstruction(second, spokenAtMs = 2_000L)

        assertFalse(navigator.hasRoute())
    }

    @Test
    fun inaccurateSingleFixCannotEndNavigation() {
        val navigator = RouteNavigator()
        navigator.setRoute(route())

        val update = navigator.update(
            TrustedLocation(37.00082, 127.0, 10f, 1_000L),
            nowMs = 1_000L,
            requestInFlight = false,
        )

        assertFalse(update.arrived)
        assertNotNull(navigator.currentBearingDeg())
    }

    @Test
    fun passedTurnIsNotConsumedUntilItsSpeechIsAcknowledged() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        val base = route().copy(
            guidePoints = listOf(
                WalkingRouteGuidePoint(0, RoutePoint(37.00045, 127.0), "지난 회전", 50, 50, 0f),
                WalkingRouteGuidePoint(1, RoutePoint(37.00081, 127.0), "다음 회전", 90, 10, 0f),
            ),
        )
        navigator.setRoute(base)

        val unacknowledged = navigator.update(
            TrustedLocation(37.00063, 127.0, 5f, 1_000L),
            nowMs = 1_000L,
            requestInFlight = false,
        )
        val repeated = navigator.update(
            TrustedLocation(37.00063, 127.0, 5f, 1_001L),
            nowMs = 1_001L,
            requestInFlight = false,
        )
        navigator.acknowledgeInstruction(repeated, spokenAtMs = 1_001L)
        val afterSpeech = navigator.update(
            TrustedLocation(37.00063, 127.0, 5f, 1_002L),
            nowMs = 1_002L,
            requestInFlight = false,
        )

        assertTrue(unacknowledged.instruction?.contains("지난 회전") == true)
        assertTrue(repeated.instruction?.contains("지난 회전") == true)
        assertTrue(afterSpeech.instruction?.contains("다음 회전") == true)
    }

    @Test
    fun acknowledgedTurnIsNotConsumedWhileStillStoppedBeforeTheGuide() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        navigator.setRoute(
            route().copy(
                guidePoints = listOf(
                    WalkingRouteGuidePoint(0, RoutePoint(37.00050, 127.0), "첫 회전", 56, 50, 0f),
                    WalkingRouteGuidePoint(1, RoutePoint(37.00081, 127.0), "다음 회전", 90, 10, 0f),
                ),
            ),
        )
        val beforeGuide = TrustedLocation(37.00042, 127.0, 4f, 1_000L)
        val offered = navigator.update(beforeGuide, nowMs = 1_000L, requestInFlight = false)
        navigator.acknowledgeInstruction(offered, spokenAtMs = 1_000L)

        val stillBefore = navigator.update(beforeGuide, nowMs = 1_001L, requestInFlight = false)

        assertTrue(stillBefore.instruction?.contains("첫 회전") == true)
    }

    @Test
    fun guidanceCooldownStartsOnlyAfterCallerAcknowledgesSpeech() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 6_000L))
        navigator.setRoute(route(guideInstruction = "직진하세요."))

        val first = navigator.update(locationNearStart(), nowMs = 1_000L, requestInFlight = false)
        val stillOffered = navigator.update(locationNearStart(), nowMs = 1_100L, requestInFlight = false)
        navigator.acknowledgeInstruction(stillOffered, spokenAtMs = 1_100L)
        val rateLimited = navigator.update(locationNearStart(), nowMs = 1_200L, requestInFlight = false)

        assertEquals("route_guidance", first.reason)
        assertEquals("route_guidance", stillOffered.reason)
        assertEquals("guidance_rate_limited", rateLimited.reason)
        assertEquals(null, rateLimited.instruction)
    }

    @Test
    fun rerouteReplacementPreservesCooldownAndBudget() {
        val navigator = RouteNavigator(
            RouteNavigatorConfig(offRouteConfirmSamples = 1, rerouteCooldownMs = 0, maxRerouteCount = 1),
        )
        navigator.setRoute(route())
        val first = navigator.update(offRouteLocation(), nowMs = 1_000L, requestInFlight = false)
        navigator.setRoute(route(), resetRerouteBudget = false)
        val afterReplacement = navigator.update(offRouteLocation(), nowMs = 2_000L, requestInFlight = false)

        assertTrue(first.shouldReroute)
        assertFalse(afterReplacement.shouldReroute)
    }

    @Test
    fun currentBearingUsesNearestPolylineSegmentAfterLocationUpdate() {
        val navigator = RouteNavigator()
        navigator.setRoute(
            WalkingRoute(
                priority = "STAIR_AVOID",
                summary = WalkingRouteSummary(distanceM = 180, durationS = 120),
                polyline = listOf(
                    RoutePoint(37.0, 127.0),
                    RoutePoint(37.0, 127.001),
                    RoutePoint(37.001, 127.001),
                ),
                guidePoints = listOf(
                    WalkingRouteGuidePoint(0, RoutePoint(37.001, 127.001), "북쪽 회전", 180, 0, 0f),
                ),
            ),
        )

        navigator.update(
            TrustedLocation(37.0, 127.0004, 5f, 1_000L),
            nowMs = 1_000L,
            requestInFlight = false,
        )

        assertEquals(90f, navigator.currentBearingDeg()!!, 1f)
    }

    @Test
    fun crossingRouteProjectionStaysOnTheLaterProgressBranch() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        val crossingRoute = WalkingRoute(
            priority = "STAIR_AVOID",
            summary = WalkingRouteSummary(distanceM = 444, durationS = 300),
            polyline = listOf(
                RoutePoint(37.0, 126.999),
                RoutePoint(37.0, 127.0),
                RoutePoint(37.001, 127.0),
                RoutePoint(37.0, 127.0),
                RoutePoint(37.0, 127.001),
            ),
            guidePoints = emptyList(),
        )
        navigator.setRoute(crossingRoute)
        val north = TrustedLocation(37.001, 127.0, 5f, 1_000L)
        repeat(3) { index -> navigator.update(north, nowMs = 1_000L + index, requestInFlight = false) }
        navigator.update(TrustedLocation(37.0005, 127.0, 5f, 2_000L), nowMs = 2_000L, requestInFlight = false)
        navigator.update(TrustedLocation(37.0, 127.0, 5f, 3_000L), nowMs = 3_000L, requestInFlight = false)

        assertEquals(180f, navigator.currentBearingDeg()!!, 2f)
    }

    @Test
    fun nearDestinationBeforeLoopEndDoesNotArrive() {
        val destination = RoutePoint(37.0, 127.00004)
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        navigator.setRoute(
            WalkingRoute(
                priority = "STAIR_AVOID",
                summary = WalkingRouteSummary(distanceM = 350, durationS = 300),
                polyline = listOf(
                    RoutePoint(37.0, 127.0),
                    RoutePoint(37.002, 127.0),
                    destination,
                ),
                guidePoints = emptyList(),
            ),
            destination = destination,
        )

        val first = navigator.update(TrustedLocation(destination.latitude, destination.longitude, 2f, 1_000L), 1_000L, false)
        val second = navigator.update(TrustedLocation(destination.latitude, destination.longitude, 2f, 2_000L), 2_000L, false)

        assertFalse(first.arrived)
        assertFalse(second.arrived)
    }

    @Test
    fun snappedTmapEndpointDoesNotClaimRequestedPoiArrival() {
        val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
        val route = route()
        val requested = RoutePoint(37.00162, 127.0)
        navigator.setRoute(route, destination = requested)
        val endpoint = route.polyline.last()

        val first = navigator.update(TrustedLocation(endpoint.latitude, endpoint.longitude, 2f, 1_000L), 1_000L, false)
        navigator.acknowledgeInstruction(first, spokenAtMs = 1_000L)
        val second = navigator.update(TrustedLocation(endpoint.latitude, endpoint.longitude, 2f, 2_000L), 2_000L, false)

        assertFalse(first.arrived)
        assertFalse(second.arrived)
        assertTrue(second.instruction?.contains("최종 접근") == true)
    }

    private fun route(guideInstruction: String? = "직진하세요.", bearingDeg: Float? = null): WalkingRoute {
        return WalkingRoute(
            priority = "STAIR_AVOID",
            summary = WalkingRouteSummary(distanceM = 100, durationS = 90),
            polyline = listOf(
                RoutePoint(37.0, 127.0),
                RoutePoint(37.0009, 127.0),
            ),
            guidePoints = listOf(
                WalkingRouteGuidePoint(
                    index = 0,
                    point = RoutePoint(37.00045, 127.0),
                    instruction = guideInstruction,
                    distanceFromStartM = 50,
                    remainingDistanceM = 50,
                    bearingDeg = bearingDeg,
                ),
            ),
        )
    }

    private fun locationNearStart(): TrustedLocation {
        return TrustedLocation(37.0, 127.0, 5f, 1_000L)
    }

    private fun offRouteLocation(): TrustedLocation {
        return TrustedLocation(37.0, 127.01, 5f, 1_000L)
    }

    private fun uncertainOffRouteLocation(): TrustedLocation {
        return slightlyOffRouteLocation(accuracyM = 10f)
    }

    private fun slightlyOffRouteLocation(accuracyM: Float): TrustedLocation {
        return TrustedLocation(37.0, 127.00045, accuracyM, 1_000L)
    }
}
