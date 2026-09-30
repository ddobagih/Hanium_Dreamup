package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteNavigatorProtectedGuideProgressRegressionTest {
    @Test
    fun turnThenCrosswalkNeedsFreshObservationsAfterOvershoot() {
        assertSeparatedGuides(
            guide(0, 20.0, 13),
            guide(1, 30.0, 211),
        )
        assertSeparatedGuides(
            guide(0, 20.0, 13),
            guide(1, 30.0, 11, "crosswalk"),
        )
    }

    @Test
    fun crosswalkThenTurnNeedsFreshObservationsAfterOvershoot() {
        assertSeparatedGuides(
            guide(0, 20.0, 211),
            guide(1, 30.0, 13),
        )
    }

    @Test
    fun coincidentProtectedGuidesAdvanceOnlyAfterDistinctLaterFixes() {
        for (crosswalkFirst in listOf(false, true)) {
            val navigator = if (crosswalkFirst) {
                navigator(guide(0, 50.0, 211), guide(1, 50.0, 13))
            } else {
                navigator(guide(0, 50.0, 13), guide(1, 50.0, 211))
            }
            assertGuideIndex(navigator, 0.0, 1_000L, 0)
            assertGuideIndex(navigator, 30.0, 9_000L, 0)
            assertGuideIndex(navigator, 58.0, 17_000L, 1)
            assertGuideIndex(navigator, 62.0, 18_000L, 1)
            assertGuideIndex(navigator, 70.0, 20_000L, 1)
            assertGuideIndex(navigator, 85.0, 24_000L, null)
        }
    }

    @Test
    fun ordinaryGuideBetweenProtectedGuidesDoesNotResetTheOneFixLimit() {
        val navigator = navigator(
            guide(0, 20.0, 13),
            guide(1, 25.0, 11),
            guide(2, 30.0, 211),
        )
        assertGuideIndex(navigator, 0.0, 1_000L, 0)
        assertGuideIndex(navigator, 40.0, 11_000L, 2)
        assertGuideIndex(navigator, 42.0, 12_000L, 2)
        assertGuideIndex(navigator, 48.0, 14_000L, 2)
        assertGuideIndex(navigator, 60.0, 17_000L, null)
    }

    @Test
    fun duplicateEqualTimeAndRegressedFixesCannotCountAsNewEvidence() {
        val rejectedFixes = listOf(
            location(40.0, 11_000L),
            location(42.0, 11_000L),
            location(42.0, 10_000L),
        )
        for (rejectedFix in rejectedFixes) {
            val navigator = navigator(guide(0, 20.0, 13), guide(1, 30.0, 211))
            assertGuideIndex(navigator, 0.0, 1_000L, 0)
            assertGuideIndex(navigator, 40.0, 11_000L, 1)
            navigator.update(rejectedFix, nowMs = 11_001L, requestInFlight = false)
            assertGuideIndex(navigator, 42.0, 12_000L, 1)
            assertGuideIndex(navigator, 45.0, 13_000L, 1)
            assertGuideIndex(navigator, 50.0, 14_000L, 1)
            assertGuideIndex(navigator, 60.0, 16_000L, null)
        }
    }

    @Test
    fun realForwardMovementAfterTerminalSaturationCanFinishTheProtectedGuide() {
        for (summaryDistanceM in listOf(200, 400)) {
            val navigator = terminalNavigator(summaryDistanceM)
            anchorNearTerminal(navigator)

            assertGuideIndex(navigator, 204.0, 65_000L, null)
            assertTrue(navigator.currentInstruction(location(204.0, 65_000L))
                ?.contains("요청한 목적지의 최종 접근을 확인하세요") == true)
        }
    }

    @Test
    fun protectedGuidesAtTheRouteEndCanPassWithCompleteAcceptedForwardEvidence() {
        for (guideEastM in listOf(198.0, 200.0)) {
            for (summaryDistanceM in listOf(200, 400)) {
                val navigator = terminalNavigator(summaryDistanceM, guideEastM)
                anchorNearTerminal(navigator)
                // A precise beyond-guide fix establishes the full passage interval;
                // the next accepted 3 m fix must clear even its upper edge.
                assertGuideIndex(navigator, 200.4, 65_000L, 1, accuracyM = 0.1f)
                assertGuideIndex(navigator, 204.0, 69_000L, null)
                assertTrue(navigator.currentInstruction(location(204.0, 69_000L))
                    ?.contains("요청한 목적지의 최종 접근을 확인하세요") == true)
            }
        }
    }

    @Test
    fun terminalGuidePassageRetainsTheFullUncertaintyOfAcceptedForwardFixes() {
        for (guideEastM in listOf(198.0, 200.0)) {
            val navigator = terminalNavigator(secondGuideEastM = guideEastM)
            anchorNearTerminal(navigator)
            assertGuideIndex(navigator, 203.5, 65_000L, 1)
            // Wider uncertainty still needs a disjoint forward interval and a normal
            // accepted match. [207, 249] clears the earlier [200.5, 206.5].
            assertGuideIndex(navigator, 228.0, 73_000L, null, accuracyM = 21f)
        }
    }

    @Test
    fun protectedGuidesAtTheRouteEndStillRejectStationaryJitterAndRetreat() {
        for (guideEastM in listOf(198.0, 200.0)) {
            for (positions in listOf(listOf(200.4, 200.4, 200.4), listOf(200.5, 200.3, 200.4), listOf(200.2, 200.0, 199.8))) {
                val navigator = terminalNavigator(secondGuideEastM = guideEastM)
                anchorNearTerminal(navigator)
                assertGuideIndex(navigator, 200.4, 65_000L, 1, accuracyM = 0.1f)
                positions.forEachIndexed { index, eastM ->
                    assertGuideIndex(navigator, eastM, 69_000L + index * 4_000L, 1, accuracyM = 0.1f)
                }
            }
        }
    }

    @Test
    fun terminalGuideStillRequiresForwardEvidenceAfterSidewaysOrUncorroboratedFixes() {
        for (guideEastM in listOf(198.0, 200.0)) {
            val navigator = terminalNavigator(secondGuideEastM = guideEastM)
            anchorNearTerminal(navigator)
            assertGuideIndex(navigator, 200.4, 65_000L, 1, accuracyM = 0.1f)
            val sidewaysPoint = point(200.4, 1.0)
            val sideways = navigator.update(
                TrustedLocation(sidewaysPoint.latitude, sidewaysPoint.longitude, 3f, 69_000L),
                69_000L, false,
            )
            assertEquals(RouteMatchReason.MATCHED, navigator.currentAcceptedRouteMatchFor(69_000L)?.reason)
            assertEquals("route_guidance", sideways.reason)
            assertEquals(1, sideways.guideIndex)
            val uncorroborated = navigator.update(
                location(197.0, 73_000L), 73_000L, false,
                kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition(
                    point(204.0), 3.0, 73_000L,
                ),
            )
            assertEquals(RouteMatchReason.MATCHED, navigator.currentAcceptedRouteMatchFor(73_000L)?.reason)
            assertEquals("route_guidance", uncorroborated.reason)
            assertEquals(1, uncorroborated.guideIndex)
            repeat(4) { index ->
                assertGuideIndex(navigator, 204.0, 77_000L + index * 4_000L, 1)
            }
        }
    }

    @Test
    fun terminalSaturationDoesNotReleaseTheProtectedGuideForStationaryOrJitterFixes() {
        for (positions in listOf(listOf(197.0, 197.0, 197.0, 197.0), listOf(199.0, 201.0, 198.0, 197.0), listOf(195.0, 193.0, 194.0, 191.0))) {
            val navigator = terminalNavigator()
            anchorNearTerminal(navigator)
            positions.forEachIndexed { index, eastM ->
                assertGuideIndex(navigator, eastM, 65_000L + index * 4_000L, 1)
            }
        }
    }

    @Test
    fun sidewaysMovementNearTerminalDoesNotReplaceForwardEvidence() {
        val navigator = terminalNavigator()
        anchorNearTerminal(navigator)
        for ((index, northM) in listOf(2.0, 4.0, 2.0, 0.0).withIndex()) {
            val atMs = 65_000L + index * 4_000L
            val point = point(197.0, northM)
            val update = navigator.update(TrustedLocation(point.latitude, point.longitude, 3f, atMs), atMs, false)
            assertEquals("route_guidance", update.reason)
            assertNotNull(navigator.currentAcceptedRouteMatchFor(atMs))
            assertEquals(1, update.guideIndex)
        }
    }

    @Test
    fun firstGuideConsumedAtTheEndpointStillRequiresIndependentForwardEvidence() {
        val navigator = terminalNavigator()
        assertGuideIndex(navigator, 200.0, 1_000L, 1, accuracyM = 0.1f)
        assertGuideIndex(navigator, 200.2, 2_000L, 1, accuracyM = 0.1f)
        assertGuideIndex(navigator, 200.4, 4_000L, 1, accuracyM = 0.1f)
        assertGuideIndex(navigator, 200.8, 6_000L, null, accuracyM = 0.1f)
    }

    @Test
    fun oneJumpToTheEndpointAndFreshStationaryFixesDoNotFinishTwoGuides() {
        val navigator = terminalNavigator()
        listOf(0.0, 30.0, 70.0, 110.0, 150.0, 180.0).forEachIndexed { index, eastM ->
            assertGuideIndex(navigator, eastM, 1_000L + index * 10_000L, if (index == 0) 0 else 1)
        }
        assertGuideIndex(navigator, 199.0, 61_000L, 1)
        repeat(4) { index ->
            assertGuideIndex(navigator, 199.0, 65_000L + index * 4_000L, 1)
        }
    }

    @Test
    fun terminalForwardFilteredFixMustStillAgreeWithTheRawObservation() {
        val navigator = terminalNavigator()
        anchorNearTerminal(navigator)
        val atMs = 65_000L
        val update = navigator.update(
            location(197.0, atMs), atMs, false,
            kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition(
                point(204.0), 3.0, atMs,
            ),
        )
        assertNotNull(navigator.currentAcceptedRouteMatchFor(atMs))
        assertEquals("route_guidance", update.reason)
        assertEquals(1, update.guideIndex)
    }

    private fun terminalNavigator(
        summaryDistanceM: Int = 200,
        secondGuideEastM: Double = 190.0,
    ): RouteNavigator = RouteNavigator(
        RouteNavigatorConfig(maximumProgressAdvanceM = 40.0, guidanceIntervalMs = 0L),
    ).apply {
        val scale = summaryDistanceM / 200
        setRoute(
            WalkingRoute(
                priority = "TEST",
                summary = WalkingRouteSummary(summaryDistanceM, summaryDistanceM),
                polyline = listOf(point(0.0), point(200.0)),
                guidePoints = listOf(guide(0, 20.0, 13), guide(1, secondGuideEastM, 211)).map { guide ->
                    guide.copy(
                        distanceFromStartM = requireNotNull(guide.distanceFromStartM) * scale,
                        remainingDistanceM = requireNotNull(guide.remainingDistanceM) * scale,
                    )
                },
            ),
            destination = point(300.0),
        )
    }

    private fun anchorNearTerminal(navigator: RouteNavigator) {
        listOf(0.0, 30.0, 70.0, 110.0, 150.0, 180.0, 197.0).forEachIndexed { index, eastM ->
            assertGuideIndex(navigator, eastM, 1_000L + index * 10_000L, if (index == 0) 0 else 1)
        }
    }

    private fun assertSeparatedGuides(
        first: WalkingRouteGuidePoint,
        second: WalkingRouteGuidePoint,
    ) {
        val navigator = navigator(first, second)
        assertGuideIndex(navigator, 0.0, 1_000L, 0)
        assertGuideIndex(navigator, 40.0, 11_000L, 1)
        assertGuideIndex(navigator, 42.0, 12_000L, 1)
        assertGuideIndex(navigator, 48.0, 14_000L, 1)
        assertGuideIndex(navigator, 60.0, 17_000L, null)
    }

    private fun assertGuideIndex(
        navigator: RouteNavigator,
        eastM: Double,
        atMs: Long,
        expected: Int?,
        accuracyM: Float = 3f,
    ) {
        val update = navigator.update(location(eastM, atMs, accuracyM), nowMs = atMs, requestInFlight = false)
        assertEquals("route_guidance", update.reason)
        val accepted = navigator.currentAcceptedRouteMatchFor(atMs)
        assertNotNull(accepted)
        assertEquals(RouteMatchReason.MATCHED, accepted?.reason)
        assertFalse(update.directionOnly)
        assertFalse(update.arrivalCandidate)
        assertEquals("eastM=" + eastM + ", atMs=" + atMs + ", reason=" + update.reason, expected, update.guideIndex)
    }

    private fun navigator(vararg guides: WalkingRouteGuidePoint): RouteNavigator =
        RouteNavigator(
            RouteNavigatorConfig(maximumProgressAdvanceM = 40.0, guidanceIntervalMs = 0L),
        ).apply {
            setRoute(
                WalkingRoute(
                    priority = "TEST",
                    summary = WalkingRouteSummary(200, 200),
                    polyline = listOf(point(0.0), point(200.0)),
                    guidePoints = guides.toList(),
                ),
            )
        }

    private fun guide(
        index: Int,
        eastM: Double,
        turnType: Int,
        instruction: String? = null,
    ): WalkingRouteGuidePoint = WalkingRouteGuidePoint(
        index,
        point(eastM),
        instruction,
        eastM.toInt(),
        200 - eastM.toInt(),
        turnType = turnType,
    )

    private fun location(eastM: Double, atMs: Long, accuracyM: Float = 3f): TrustedLocation {
        val point = point(eastM)
        return TrustedLocation(point.latitude, point.longitude, accuracyM, atMs)
    }

    private fun point(eastM: Double, northM: Double = 0.0): RoutePoint =
        RoutePoint(
            northM / 111194.92664455874,
            eastM / 111194.92664455874,
        )
}
