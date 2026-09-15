package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteNavigatorSpeechRetryTest {
    @Test
    fun initializingTtsCanRetryThreeSecondsLaterAndCompleteWithoutAnotherFix() {
        listOf(false, true).forEach { testMode ->
            val navigator = navigator(testMode, guidanceIntervalMs = 6_000)
            val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
            assertTrue(first.instruction?.contains("길안내를 시작합니다") == true)
            assertTrue(navigator.reserveInstruction(first))
            navigator.releaseInstruction(first, 1_001)

            val ready = requireNotNull(navigator.retryGuidance(4_000))
            assertEquals(first.instruction, ready.instruction)
            assertNotEquals(first.speechCueToken, ready.speechCueToken)
            assertTrue(navigator.reserveInstruction(ready))
            assertEquals("guidance_in_flight", navigator.retryGuidance(4_001)?.reason)
            navigator.acknowledgeInstruction(ready, 4_002)
            assertNull(navigator.retryGuidance(4_003)?.instruction)
            assertNull(navigator.retryGuidance(8_000)?.instruction)
            assertEquals("guidance_already_completed", navigator.retryGuidance(10_003)?.reason)
        }
    }

    @Test
    fun globallyBusyUnreservedOfferCanBeRetriedWithoutConsumingItsCue() {
        val navigator = navigator()
        val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
        val busyRetry = requireNotNull(navigator.retryGuidance(2_000))
        assertEquals(first.speechCueToken, busyRetry.speechCueToken)
        assertEquals(first.instruction, busyRetry.instruction)
        assertTrue(navigator.reserveInstruction(busyRetry))
        navigator.acknowledgeInstruction(busyRetry, 2_001)
        assertNull(navigator.retryGuidance(2_002)?.instruction)
    }

    @Test
    fun timerRetryPreservesGuidanceIntervalForANewDistanceStage() {
        val navigator = navigator(guidanceIntervalMs = 6_000)
        val first = navigator.update(fix(0.0, 1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 1_001)
        val closer = navigator.update(fix(0.00055, 2_000), 2_000, false)
        assertEquals("guidance_rate_limited", closer.reason)
        assertEquals("guidance_rate_limited", navigator.retryGuidance(7_000)?.reason)
        val nextStage = requireNotNull(navigator.retryGuidance(7_001))
        assertEquals("route_guidance", nextStage.reason)
        assertTrue(nextStage.instruction?.contains("50m 앞") == true)
        assertFalse(nextStage.instruction?.contains("길안내를 시작합니다") == true)
        assertTrue(navigator.reserveInstruction(nextStage))
    }

    @Test
    fun timerPollingPreservesMatcherEvidenceAndGpsTimestampOrdering() {
        val navigator = navigator(true)
        val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
        val routeMatch = navigator.currentRouteMatch()
        val instruction = first.instruction
        listOf(1_000L, 1_500L, 2_000L, 4_000L).forEach { atMs ->
            val retry = requireNotNull(navigator.retryGuidance(atMs))
            assertEquals("route_guidance", retry.reason)
            assertEquals(instruction, retry.instruction)
            assertEquals(first.speechCueToken, retry.speechCueToken)
            assertSame(routeMatch, navigator.currentRouteMatch())
            assertSame(routeMatch, navigator.currentAcceptedRouteMatchFor(1_000))
        }
        // Polling at 4 s does not become a GPS observation; a sample after the last GPS is new.
        val newFix = navigator.update(fix(0.00056, 2_500), 2_500, false)
        assertNotEquals("location_sample_not_newer", newFix.reason)
        assertNotNull(navigator.currentAcceptedRouteMatchFor(2_500))
    }

    @Test
    fun retriesRespectFailureBackoffAndIgnoreTheFailedAttemptsLateCompletion() {
        val navigator = navigator()
        val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        navigator.releaseInstruction(first, 1_001)
        assertEquals("guidance_retry_wait", navigator.retryGuidance(3_000)?.reason)
        navigator.acknowledgeInstruction(first, 3_001)
        val retry = requireNotNull(navigator.retryGuidance(3_002))
        assertTrue(navigator.reserveInstruction(retry))
        navigator.acknowledgeInstruction(first, 3_003)
        navigator.releaseInstruction(first, 3_004)
        assertEquals("guidance_in_flight", navigator.retryGuidance(3_005)?.reason)
        navigator.acknowledgeInstruction(retry, 3_006)
        assertEquals("guidance_already_completed", navigator.retryGuidance(3_007)?.reason)
    }

    @Test
    fun replacementRouteNeedsItsOwnPositionAndRejectsPreviousRetryCallbacks() {
        val navigator = navigator()
        val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        navigator.releaseInstruction(first, 1_001)
        val retry = requireNotNull(navigator.retryGuidance(4_000))
        assertTrue(navigator.reserveInstruction(retry))
        navigator.setRoute(route().copy(providerRouteId = "new-route"))
        assertNull(navigator.retryGuidance(4_001))
        assertFalse(navigator.reserveInstruction(retry))
        navigator.acknowledgeInstruction(retry, 4_002)
        val replacement = navigator.update(fix(0.00055, 5_000), 5_000, false)
        assertNotEquals(retry.speechCueToken?.routeRevision, replacement.speechCueToken?.routeRevision)
        assertTrue(navigator.reserveInstruction(replacement))
        navigator.acknowledgeInstruction(retry, 5_001)
        assertEquals("guidance_in_flight", navigator.retryGuidance(5_002)?.reason)
        navigator.clear()
        assertNull(navigator.retryGuidance(5_003))
        assertFalse(navigator.reserveInstruction(replacement))
    }

    @Test
    fun passingGuideDuringFailureRetiresOldCueAndRetriesOnlyTheNewGuide() {
        val navigator = navigator(true)
        val first = navigator.update(fix(0.00082, 1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        navigator.releaseInstruction(first, 1_001)
        val next = navigator.update(TrustedLocation(0.00018, 0.001, 2f, 4_000), 4_000, false)
        assertNotEquals(first.guideIndex, next.guideIndex)
        assertTrue(next.instruction?.contains("오른쪽으로 꺾으세요") == true)
        navigator.acknowledgeInstruction(first, 4_001)
        val retry = requireNotNull(navigator.retryGuidance(4_002))
        assertEquals(next.guideIndex, retry.guideIndex)
        assertEquals(next.instruction, retry.instruction)
        assertFalse(navigator.reserveInstruction(first))
        assertTrue(navigator.reserveInstruction(retry))
    }

    @Test
    fun strictLowQualityRetriesOnlyADirectionReferenceWhileTestModeKeepsActualLowEvidence() {
        listOf(false, true).forEach { testMode ->
            val navigator = navigator(testMode)
            navigator.update(fix(0.00082, 1_000), 1_000, false)
            val corner = navigator.update(TrustedLocation(0.00018, 0.001, 2f, 4_000), 4_000, false)
            val actualMatch = navigator.currentRouteMatch()
            assertEquals(RouteMatchQuality.LOW, actualMatch?.quality)
            assertNull(navigator.currentAcceptedRouteMatchFor(4_000))
            val retry = navigator.retryGuidance(5_000)
            if (testMode) {
                assertEquals(corner.instruction, retry?.instruction)
                assertNotNull(retry?.instruction)
                assertTrue(navigator.reserveInstruction(requireNotNull(retry)))
            } else {
                assertTrue(retry?.directionOnly == true)
                assertNull(retry?.guideIndex)
                assertFalse(retry?.instruction?.contains("꺾으세요") == true)
                assertTrue(navigator.reserveInstruction(requireNotNull(retry)))
            }
            assertSame(actualMatch, navigator.currentRouteMatch())
            assertNull(navigator.currentAcceptedRouteMatchFor(4_000))
        }
    }

    @Test
    fun expiredLocationCannotBeMadeFreshByRepeatedTimerPolls() {
        val navigator = navigator()
        val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
        assertNotNull(navigator.retryGuidance(5_000)?.instruction)
        assertNotNull(navigator.retryGuidance(11_000)?.instruction)
        assertNull(navigator.retryGuidance(11_001))
        assertFalse(navigator.reserveInstruction(first))
        assertNull(navigator.retryGuidance(12_000))
        // Once expired, polling with an older clock cannot resurrect that cache.
        assertNull(navigator.retryGuidance(10_000))
    }

    @Test
    fun rawAndFilteredEvidenceMustBothBeFreshAndNotInTheFutureForRetry() {
        listOf(1_000L to 9_000L, 9_000L to 1_000L, 20_000L to 9_000L, 9_000L to 20_000L).forEach { (rawAt, filteredAt) ->
            val navigator = navigator()
            val raw = fix(0.00055, rawAt)
            val filtered = FilteredRoutePosition(RoutePoint(raw.latitude, raw.longitude), 2.0, filteredAt)
            navigator.update(raw, 9_000, false, filtered)
            assertNull(navigator.retryGuidance(11_001))
        }
    }

    @Test
    fun retryBeforeItsRecordedObservationTimeCannotOfferOrReviveACue() {
        val navigator = navigator()
        val first = navigator.update(fix(0.00055, 1_000), 1_100, false)
        assertNull(navigator.retryGuidance(1_099))
        assertFalse(navigator.reserveInstruction(first))
        assertNull(navigator.retryGuidance(2_000))
    }

    @Test
    fun locationInterruptionAndUntrustedFixFenceCachedRetriesAndLateCallbacks() {
        listOf(false, true).forEach { testMode ->
            listOf(false, true).forEach { untrusted ->
                val navigator = navigator(testMode)
                val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
                assertTrue(navigator.reserveInstruction(first))
                if (untrusted) navigator.onUntrustedLocation() else navigator.onPositioningEvidenceInterrupted()
                assertNull(navigator.retryGuidance(2_000))
                assertFalse(navigator.reserveInstruction(first))
                navigator.acknowledgeInstruction(first, 2_001)
                assertNull(navigator.retryGuidance(3_000))
            }
        }
    }

    @Test
    fun modifiedAndInvalidGpsUpdatesFenceExistingRetryContext() {
        listOf(false, true).forEach { invalid ->
            val navigator = navigator(true)
            val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
            assertTrue(navigator.reserveInstruction(first))
            if (invalid) {
                navigator.update(fix(0.00055, 2_000).copy(latitude = Double.NaN), 2_000, false)
            } else {
                navigator.update(fix(0.00055, 1_000).copy(accuracyM = 3f), 2_000, false)
            }
            assertNull(navigator.retryGuidance(3_000))
            assertFalse(navigator.reserveInstruction(first))
        }
    }

    @Test
    fun arrivalTimerPollsCannotAccumulateConfirmationSamples() {
        val navigator = navigator()
        val endpoint = route().polyline.last()
        val location = TrustedLocation(endpoint.latitude, endpoint.longitude, 2f, 1_000)
        val first = navigator.update(location, 1_000, false)
        assertNull(first.pendingUserDecision)
        listOf(2_000L, 3_000L, 4_000L).forEach { atMs ->
            val retry = requireNotNull(navigator.retryGuidance(atMs))
            assertFalse(retry.arrived)
            assertNull(retry.pendingUserDecision)
        }
        val confirmedCandidate = navigator.update(location.copy(elapsedRealtimeMs = 5_000), 5_000, false)
        assertEquals(RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION, confirmedCandidate.pendingUserDecision)
        assertNull(navigator.retryGuidance(6_000))
    }

    @Test
    fun strictDeviationDecisionCannotBeBypassedByCachedRetry() {
        val navigator = navigator()
        val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        val outside = navigator.update(TrustedLocation(-0.0008, 0.00055, 2f, 2_000), 2_000, false)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, outside.pendingUserDecision)
        assertNull(navigator.retryGuidance(3_000))
        assertFalse(navigator.reserveInstruction(first))
    }

    private fun navigator(testMode: Boolean = false, guidanceIntervalMs: Long = 0) =
        RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = guidanceIntervalMs), allowDegradedRouteGuidance = testMode)
            .apply { setRoute(route()) }

    // Synthetic fixtures exercise retry state, not live TMAP or a recorded physical walk.
    private fun fix(longitude: Double, atMs: Long) = TrustedLocation(0.0, longitude, 2f, atMs)

    private fun route() = WalkingRoute(
        priority = "STAIR_AVOID",
        summary = WalkingRouteSummary(333, 300),
        polyline = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.001), RoutePoint(0.001, 0.001), RoutePoint(0.001, 0.002)),
        guidePoints = listOf(
            WalkingRouteGuidePoint(0, RoutePoint(0.0, 0.0), null, 0, 333, turnType = 200, pointType = "SP"),
            WalkingRouteGuidePoint(1, RoutePoint(0.0, 0.001), null, 111, 222, turnType = 12),
            WalkingRouteGuidePoint(2, RoutePoint(0.001, 0.001), null, 222, 111, turnType = 13),
        ),
    )
}
