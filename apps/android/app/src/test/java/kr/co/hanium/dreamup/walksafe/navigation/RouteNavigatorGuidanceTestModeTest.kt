package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteNavigatorGuidanceTestModeTest {
    @Test
    fun defaultModeStillSuppressesLowCornerMatchAndRequiresLocationRecheck() {
        listOf(RouteNavigator(), RouteNavigator(allowDegradedRouteGuidance = false)).forEach { navigator ->
            navigator.setRoute(route())
            navigator.update(fix(0.0, 0.00082, 1_000), 1_000, false)
            val corner = navigator.update(fix(0.00018, 0.001, 11_000), 11_000, false)
            assertEquals("route_match_untrusted", corner.reason)
            assertNull(corner.instruction)
            assertNull(navigator.currentInstruction(fix(0.00018, 0.001, 11_000)))
            assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, navigator.onUntrustedLocation()?.pendingUserDecision)
            val restored = navigator.update(fix(0.00027, 0.001, 21_000), 21_000, false)
            assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, restored.pendingUserDecision)
        }
    }

    @Test
    fun testModeProjectsLowCornerFixToExistingNextTurnWithoutPromotingMatchEvidence() {
        val navigator = navigator()
        val first = navigator.update(fix(0.0, 0.00082, 1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        val location = fix(0.00018, 0.001, 11_000)
        val corner = navigator.update(location, 11_000, false)

        assertEquals(RouteMatchQuality.LOW, navigator.currentRouteMatch()?.quality)
        assertNull(navigator.currentAcceptedRouteMatchFor(11_000))
        assertNull(navigator.currentBearingDeg())
        assertNull(navigator.currentProjection(location))
        assertEquals("route_guidance", corner.reason)
        assertEquals(1, corner.guideIndex)
        assertTrue(corner.instruction?.contains("오른쪽으로 꺾으세요") == true)
        assertTrue(navigator.currentInstruction(location)?.contains("오른쪽으로 꺾으세요") == true)
        assertTrue(corner.cancelStaleNavigationSpeech)
        assertFalse(navigator.reserveInstruction(first))
        assertTrue(navigator.reserveInstruction(corner))
        navigator.acknowledgeInstruction(first, 11_001)
        navigator.acknowledgeInstruction(corner, 11_002)
        assertEquals("guidance_already_completed", navigator.update(fix(0.00018, 0.001, 12_000), 12_000, false).reason)
    }

    @Test
    fun coarseAccuracyDoesNotKeepTestGuidanceOnAnAlreadyPassedProviderTurn() {
        val navigator = navigator()
        val coarse = fix(0.00054, 0.001, 1_000).copy(accuracyM = 80f)
        val update = navigator.update(coarse, 1_000, false)
        assertEquals(1, update.guideIndex)
        assertTrue(update.instruction?.contains("오른쪽으로 꺾으세요") == true)
        assertEquals(RoutePoint(coarse.latitude, coarse.longitude), navigator.currentRouteMatch()?.filteredPoint)
        assertTrue(navigator.reserveInstruction(update))
    }

    @Test
    fun untrustedTransientCancelsOldCueThenResumesFromFreshUnmatchedCoordinate() {
        val navigator = navigator()
        val first = navigator.update(offRouteFix(1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        val interruption = navigator.onUntrustedLocation()
        assertTrue(interruption?.cancelStaleNavigationSpeech == true)
        assertNull(interruption?.pendingUserDecision)
        assertNull(navigator.currentInstruction(offRouteFix(1_000)))
        assertFalse(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 1_001)

        val restored = navigator.update(offRouteFix(2_000), 2_000, false)
        assertNotNull(restored.instruction)
        assertNull(navigator.pendingDecisionToken())
        assertNull(navigator.currentAcceptedRouteMatchFor(2_000))
        assertNotNull(navigator.currentInstruction(offRouteFix(2_000)))
        assertTrue(navigator.reserveInstruction(restored))
        navigator.acknowledgeInstruction(restored, 2_001)
        assertEquals("guidance_already_completed", navigator.update(offRouteFix(3_000), 3_000, false).reason)
    }

    @Test
    fun evidenceInterruptionResumesSpeechFromNewUnmatchedFixWithoutResumingTrustedEvidence() {
        val navigator = navigator()
        val first = navigator.update(offRouteFix(1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        navigator.onPositioningEvidenceInterrupted()
        assertNull(navigator.currentInstruction(offRouteFix(1_000)))
        navigator.acknowledgeInstruction(first, 1_001)

        val resumed = navigator.update(offRouteFix(2_000), 2_000, false)
        assertTrue(navigator.reserveInstruction(resumed))
        assertNull(navigator.currentAcceptedRouteMatchFor(2_000))
        assertNull(navigator.currentProjection(offRouteFix(2_000)))
        assertNotNull(navigator.currentInstruction(offRouteFix(2_000)))
        navigator.acknowledgeInstruction(resumed, 2_001)
        assertEquals("guidance_already_completed", navigator.update(offRouteFix(3_000), 3_000, false).reason)
    }

    @Test
    fun consecutiveDeviationRemainsDiagnosticAndDoesNotLatchRerouteOrRecheck() {
        val navigator = navigator()
        navigator.update(offRouteFix(1_000), 1_000, false)
        val outside = navigator.update(offRouteFix(2_000), 2_000, true)
        assertTrue(outside.offRoute)
        assertFalse(outside.shouldReroute)
        assertFalse(outside.arrived)
        assertNull(outside.pendingUserDecision)
        assertNotNull(outside.instruction)
        assertEquals(RouteMatchQuality.UNMATCHED, navigator.currentRouteMatch()?.quality)
        assertTrue(navigator.reserveInstruction(outside))
        navigator.releaseInstruction(outside, 2_001)
        val returned = navigator.update(fix(0.0, 0.00055, 5_000), 5_000, false)
        assertNull(returned.pendingUserDecision)
        assertFalse(returned.offRoute)
        assertNotNull(returned.instruction)
    }

    @Test
    fun arrivalEvidenceDoesNotAutomaticallyConfirmOrBlockTestGuidance() {
        val navigator = navigator()
        val endpoint = route().polyline.last()
        val first = navigator.update(fix(endpoint.latitude, endpoint.longitude, 1_000), 1_000, false)
        val second = navigator.update(fix(endpoint.latitude, endpoint.longitude, 2_000), 2_000, false)
        assertFalse(first.arrived)
        assertFalse(second.arrived)
        assertNull(second.pendingUserDecision)
        assertNotNull(second.instruction)
        assertFalse(second.instruction?.contains("도착을 확인했습니다") == true)
        assertEquals("arrival_confirmation_missing", navigator.confirmArrival().reason)
        assertTrue(navigator.hasRoute())
        assertTrue(navigator.reserveInstruction(second))
    }

    @Test
    fun failedTestSpeechRetriesWithNewTokenAndIgnoresLateCompletion() {
        val navigator = navigator()
        val first = navigator.update(offRouteFix(1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        navigator.releaseInstruction(first, 1_001)
        navigator.acknowledgeInstruction(first, 1_002)
        assertEquals("guidance_retry_wait", navigator.update(offRouteFix(2_000), 2_000, false).reason)
        val retry = navigator.update(offRouteFix(3_100), 3_100, false)
        assertNotEquals(first.speechCueToken, retry.speechCueToken)
        assertTrue(navigator.reserveInstruction(retry))
        navigator.acknowledgeInstruction(first, 3_101)
        assertEquals("guidance_in_flight", navigator.update(offRouteFix(3_200), 3_200, false).reason)
        navigator.acknowledgeInstruction(retry, 3_201)
        assertEquals("guidance_already_completed", navigator.update(offRouteFix(4_000), 4_000, false).reason)
    }

    @Test
    fun routeReplacementAndStopInvalidateTestSpeechOwnership() {
        val navigator = navigator()
        val old = navigator.update(offRouteFix(1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(old))
        navigator.setRoute(route().copy(providerRouteId = "replacement"))
        assertFalse(navigator.reserveInstruction(old))
        navigator.acknowledgeInstruction(old, 2_000)
        val replacement = navigator.update(offRouteFix(3_000), 3_000, false)
        assertNotEquals(old.speechCueToken?.routeRevision, replacement.speechCueToken?.routeRevision)
        assertTrue(navigator.reserveInstruction(replacement))
        navigator.clear()
        navigator.acknowledgeInstruction(replacement, 3_001)
        assertFalse(navigator.reserveInstruction(replacement))
        assertNull(navigator.currentInstruction(offRouteFix(3_000)))
        assertEquals("route_missing", navigator.update(offRouteFix(4_000), 4_000, false).reason)
    }

    @Test
    fun testModeRejectsInvalidStaleAndFutureCoordinatesAndRecoversWithFreshFix() {
        val invalidFixes = listOf(
            offRouteFix(1_000).copy(latitude = Double.NaN),
            offRouteFix(1_000).copy(longitude = 181.0),
            offRouteFix(1_000).copy(accuracyM = Float.POSITIVE_INFINITY),
            offRouteFix(1_000).copy(accuracyM = -1f),
            offRouteFix(-1),
            offRouteFix(0),
            offRouteFix(20_001),
        )
        invalidFixes.forEach { invalid ->
            val navigator = navigator()
            val first = navigator.update(offRouteFix(1_000), 1_000, false)
            assertTrue(navigator.reserveInstruction(first))
            val update = navigator.update(invalid, 20_000, false)
            assertEquals("location_sample_invalid_or_stale", update.reason)
            assertNull(update.instruction)
            assertTrue(update.cancelStaleNavigationSpeech)
            assertFalse(navigator.reserveInstruction(first))
            navigator.acknowledgeInstruction(first, 20_001)
            assertNotNull(navigator.update(offRouteFix(21_000), 21_000, false).instruction)
        }
    }

    @Test
    fun newerWallTimeCannotMakeRepeatedOrOutOfOrderPositionSamplesFresh() {
        val navigator = navigator()
        val first = navigator.update(offRouteFix(1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        assertEquals("guidance_in_flight", navigator.update(offRouteFix(1_000), 2_000, false).reason)
        assertEquals("location_sample_not_newer", navigator.update(offRouteFix(999), 3_000, false).reason)
        assertFalse(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 3_001)
        assertNotNull(navigator.update(offRouteFix(4_000), 4_000, false).instruction)
    }

    @Test
    fun invalidFilteredCoordinateCannotUseValidRawFixToFabricateGuidance() {
        val navigator = navigator()
        val raw = offRouteFix(1_000)
        val invalid = FilteredRoutePosition(RoutePoint(Double.NaN, raw.longitude), 2.0, 1_000)
        val update = navigator.update(raw, 1_000, false, invalid)
        assertEquals("location_sample_invalid_or_stale", update.reason)
        assertNull(update.instruction)
        assertNull(navigator.currentAcceptedRouteMatchFor(1_000))
    }

    @Test
    fun missingAndMalformedPolylineCannotProduceTestProjection() {
        listOf(emptyList(), listOf(RoutePoint(0.0, 0.0)), listOf(RoutePoint(0.0, 0.0), RoutePoint(Double.NaN, 0.001))).forEach { polyline ->
            val navigator = navigator().apply { setRoute(route().copy(polyline = polyline, guidePoints = emptyList())) }
            assertNull(navigator.currentInstruction(offRouteFix(1_000)))
            val update = navigator.update(offRouteFix(1_000), 1_000, false)
            assertNull(update.instruction)
            assertFalse(navigator.reserveInstruction(update))
        }
    }

    @Test
    fun crosswalkMarkerKeepsMappedTurnAndRepeatsFromOneHundredToTwentyMeters() {
        listOf(false, true).forEach { testMode ->
            val navigator = navigator(testMode, "횡단보도 방면 좌회전 후 94m 이동")
            val far = navigator.update(fix(0.0, 0.0001, 1_000), 1_000, false)
            assertTrue(far.instruction?.contains("100m 앞, 왼쪽으로 꺾으세요.") == true)
            assertTrue(far.instruction?.contains(CROSSWALK_REFERENCE_NOTICE_KO) == true)
            assertEquals(3, far.speechCueToken?.distanceBand)
            assertTrue(navigator.reserveInstruction(far))
            navigator.acknowledgeInstruction(far, 1_001)
            val near = navigator.update(fix(0.0, 0.00082, 20_000), 20_000, false)
            assertTrue(near.instruction?.contains("20m 앞, 왼쪽으로 꺾으세요.") == true)
            assertTrue(near.instruction?.contains(CROSSWALK_REFERENCE_NOTICE_KO) == true)
            assertFalse(near.instruction?.contains("94m") == true)
            assertEquals(1, near.speechCueToken?.distanceBand)
            assertTrue(navigator.reserveInstruction(near))
        }
    }

    @Test
    fun explicitCrosswalkPointKeepsDistanceWithoutRepeatingUnsafeProviderCommand() {
        listOf(211, 212, 213, 999).forEach { turnType ->
            val navigator = navigator().apply {
                setRoute(route("횡단보도가 초록색이니 지금 건너세요.").let { route ->
                    route.copy(guidePoints = route.guidePoints.map { it.copy(turnType = turnType) })
                })
            }
            val far = navigator.update(fix(0.0, 0.0001, 1_000), 1_000, false)
            assertTrue(far.instruction?.contains("100m 앞, 횡단보도 안내 지점입니다.") == true)
            assertTrue(far.instruction?.contains(CROSSWALK_REFERENCE_NOTICE_KO) == true)
            assertFalse(far.instruction?.contains("지금 건너세요") == true)
            assertTrue(navigator.reserveInstruction(far))
            navigator.acknowledgeInstruction(far, 1_001)
            val near = navigator.update(fix(0.0, 0.00082, 20_000), 20_000, false)
            assertTrue(near.instruction?.contains("20m 앞, 횡단보도 안내 지점입니다.") == true)
            assertNotEquals(far.speechCueToken?.distanceBand, near.speechCueToken?.distanceBand)
        }
    }

    private fun navigator(testMode: Boolean = true, firstInstruction: String? = null) =
        RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0), allowDegradedRouteGuidance = testMode)
            .apply { setRoute(route(firstInstruction)) }

    // Synthetic fixtures exercise policy only; they are not a TMAP response or a recorded walk.
    private fun fix(latitude: Double, longitude: Double, atMs: Long) = TrustedLocation(latitude, longitude, 2f, atMs)

    private fun offRouteFix(atMs: Long) = fix(-0.0008, 0.00055, atMs)

    private fun route(firstInstruction: String? = null) = WalkingRoute(
        priority = "STAIR_AVOID",
        summary = WalkingRouteSummary(333, 300),
        polyline = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.001), RoutePoint(0.001, 0.001), RoutePoint(0.001, 0.002)),
        guidePoints = listOf(
            WalkingRouteGuidePoint(0, RoutePoint(0.0, 0.001), firstInstruction, 111, 222, turnType = 12),
            WalkingRouteGuidePoint(1, RoutePoint(0.001, 0.001), null, 222, 111, turnType = 13),
        ),
    )
}
