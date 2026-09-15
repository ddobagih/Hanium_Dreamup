package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.EnuPositionCovariance
import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteNavigatorSpeechContinuityTest {
    @Test
    fun distanceBandChangesWaitForSentenceCompletionAndOfferOnlyLatestStage() {
        listOf(false, true).forEach { testMode ->
            val navigator = navigator(testMode)
            val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
            assertTrue(first.instruction?.contains("길안내를 시작합니다") == true)
            assertTrue(navigator.reserveInstruction(first))

            listOf(fix(0.00082, 2_000), fix(0.00095, 3_000)).forEach { nextFix ->
                val update = navigator.update(nextFix, nextFix.elapsedRealtimeMs, false)
                assertEquals("guidance_in_flight", update.reason)
                assertFalse(update.cancelStaleNavigationSpeech)
                assertNull(update.instruction)
                assertFalse(navigator.reserveInstruction(update))
            }
            navigator.acknowledgeInstruction(first, 3_001)
            val nextStage = requireNotNull(navigator.retryGuidance(3_002))
            assertEquals(first.guideIndex, nextStage.guideIndex)
            assertEquals(0, nextStage.speechCueToken?.distanceBand)
            assertTrue(nextStage.instruction?.contains("6m 앞") == true)
            assertFalse(nextStage.instruction?.contains("길안내를 시작합니다") == true)
            assertFalse(nextStage.cancelStaleNavigationSpeech)
            assertTrue(navigator.reserveInstruction(nextStage))
            navigator.acknowledgeInstruction(first, 3_003)
            assertEquals("guidance_in_flight", navigator.retryGuidance(3_004)?.reason)
            navigator.acknowledgeInstruction(nextStage, 3_005)
            assertNull(navigator.retryGuidance(3_006)?.instruction)
        }
    }

    @Test
    fun freshExactRawAndFilteredDuplicatesKeepPendingSpeechAndMatcherEvidence() {
        listOf(false, true).forEach { testMode ->
            val navigator = navigator(testMode)
            val raw = fix(0.00055, 1_000)
            val filtered = filtered(raw)
            val first = navigator.update(raw, 1_000, false, filtered)
            assertTrue(navigator.reserveInstruction(first))
            val match = navigator.currentRouteMatch()
            listOf(1_000L, 2_000L, 3_000L).forEach { nowMs ->
                val duplicate = navigator.update(raw.copy(), nowMs, false, filtered.copy())
                assertEquals("guidance_in_flight", duplicate.reason)
                assertFalse(duplicate.cancelStaleNavigationSpeech)
                assertNull(duplicate.instruction)
                assertSame(match, navigator.currentRouteMatch())
                assertSame(match, navigator.currentAcceptedRouteMatchFor(1_000))
            }
            navigator.acknowledgeInstruction(first, 3_001)
            assertNull(navigator.retryGuidance(3_002)?.instruction)
        }
    }

    @Test
    fun failedSentenceKeepsItsRetryDelayAcrossAPendingDistanceChange() {
        val navigator = navigator()
        val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        assertEquals("guidance_in_flight", navigator.update(fix(0.00082, 2_000), 2_000, false).reason)
        navigator.releaseInstruction(first, 2_001)
        navigator.acknowledgeInstruction(first, 2_002)
        assertEquals("guidance_retry_wait", navigator.retryGuidance(2_003)?.reason)
        assertEquals("guidance_retry_wait", navigator.retryGuidance(4_000)?.reason)
        val latest = requireNotNull(navigator.retryGuidance(4_001))
        assertTrue(latest.instruction?.contains("20m 앞") == true)
        assertTrue(latest.instruction?.contains("길안내를 시작합니다") == true)
        assertNotEquals(first.speechCueToken, latest.speechCueToken)
        assertTrue(navigator.reserveInstruction(latest))
    }

    @Test
    fun repeatedArrivalFixDoesNotBecomeAdditionalConfirmationEvidence() {
        val navigator = navigator()
        val endpoint = route().polyline.last()
        val firstFix = TrustedLocation(endpoint.latitude, endpoint.longitude, 2f, 1_000)
        val first = navigator.update(firstFix, 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        val match = navigator.currentRouteMatch()
        repeat(8) { index ->
            val duplicate = navigator.update(firstFix, 2_000L + index, false)
            assertFalse(duplicate.arrivalCandidate)
            assertFalse(duplicate.cancelStaleNavigationSpeech)
            assertSame(match, navigator.currentRouteMatch())
        }
        val secondFix = navigator.update(firstFix.copy(elapsedRealtimeMs = 4_000), 4_000, false)
        assertTrue(secondFix.arrivalCandidate)
        assertTrue(secondFix.cancelStaleNavigationSpeech)
        assertFalse(navigator.reserveInstruction(first))
    }

    @Test
    fun repeatedDeviationFixDoesNotConfirmRerouteOrRepeatDecisionSpeech() {
        val navigator = navigator()
        val outside = TrustedLocation(-0.0008, 0.00055, 2f, 1_000)
        val first = navigator.update(outside, 1_000, false)
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, first.pendingUserDecision)
        val token = navigator.pendingDecisionToken()
        val match = navigator.currentRouteMatch()
        repeat(8) { index ->
            val duplicate = navigator.update(outside, 2_000L + index, false)
            assertFalse(duplicate.offRoute)
            assertFalse(duplicate.cancelStaleNavigationSpeech)
            assertNull(duplicate.instruction)
            assertEquals(token, navigator.pendingDecisionToken())
            assertSame(match, navigator.currentRouteMatch())
        }
        val secondFix = navigator.update(outside.copy(elapsedRealtimeMs = 4_000), 4_000, false)
        assertEquals(RouteNavigatorUserDecision.REROUTE, secondFix.pendingUserDecision)
        assertTrue(secondFix.cancelStaleNavigationSpeech)
    }

    @Test
    fun changedRawOrFilteredContentsAtSameSourceTimeStillCancelPendingSpeech() {
        listOf(false, true).forEach { testMode ->
            repeat(5) { changedField ->
                val navigator = navigator(testMode)
                val raw = fix(0.00055, 1_000)
                val source = filtered(raw)
                val first = navigator.update(raw, 1_000, false, source)
                assertTrue(navigator.reserveInstruction(first))
                val modifiedRaw = when (changedField) {
                    0 -> raw.copy(longitude = 0.00056)
                    1 -> raw.copy(accuracyM = 3f)
                    else -> raw
                }
                val modifiedFiltered = when (changedField) {
                    2 -> source.copy(point = RoutePoint(0.0, 0.00056))
                    3 -> source.copy(heading = RouteHeadingEstimate(180.0, 5.0))
                    4 -> source.copy(covarianceEnu = EnuPositionCovariance(5.0, 5.0))
                    else -> source
                }
                val modified = navigator.update(modifiedRaw, 2_000, false, modifiedFiltered)
                assertEquals("location_sample_not_newer", modified.reason)
                assertTrue(modified.cancelStaleNavigationSpeech)
                assertNull(modified.instruction)
                navigator.acknowledgeInstruction(first, 2_001)
                assertNull(navigator.retryGuidance(2_002))
            }
        }
    }

    @Test
    fun expiredInvalidAndRegressedSamplesDoNotGetDuplicateContinuityException() {
        listOf(false, true).forEach { testMode ->
            listOf(
                fix(0.00055, 1_000) to 11_001L,
                fix(0.00055, 1_000) to 999L,
                fix(0.00055, 999) to 2_000L,
                fix(0.00055, 2_000).copy(latitude = Double.NaN) to 2_000L,
                fix(0.00055, 3_000) to 2_000L,
            ).forEach { (invalid, atMs) ->
                val navigator = navigator(testMode)
                val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
                assertTrue(navigator.reserveInstruction(first))
                val update = navigator.update(invalid, atMs, false)
                assertTrue(update.cancelStaleNavigationSpeech)
                assertNull(update.instruction)
                assertFalse(navigator.reserveInstruction(first))
                navigator.acknowledgeInstruction(first, 2_001)
                assertNull(navigator.retryGuidance(3_000))
            }
        }
    }

    @Test
    fun actualNextGuideStillCancelsPendingSentenceAndRejectsItsLateCompletion() {
        val navigator = navigator(true)
        val first = navigator.update(fix(0.00082, 1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        val next = navigator.update(TrustedLocation(0.00018, 0.001, 2f, 4_000), 4_000, false)
        assertTrue(next.cancelStaleNavigationSpeech)
        assertNotEquals(first.guideIndex, next.guideIndex)
        assertTrue(next.instruction?.contains("오른쪽으로 꺾으세요") == true)
        assertTrue(navigator.reserveInstruction(next))
        navigator.acknowledgeInstruction(first, 4_001)
        assertEquals("guidance_in_flight", navigator.retryGuidance(4_002)?.reason)
    }

    @Test
    fun sameFixAfterInterruptionCannotRestoreEvidenceOrSpeech() {
        listOf(false, true).forEach { testMode ->
            val navigator = navigator(testMode)
            val raw = fix(0.00055, 1_000)
            val first = navigator.update(raw, 1_000, false)
            assertTrue(navigator.reserveInstruction(first))
            navigator.onPositioningEvidenceInterrupted()
            val old = navigator.update(raw, 2_000, false)
            assertNull(old.instruction)
            assertNull(navigator.currentAcceptedRouteMatchFor(1_000))
            navigator.acknowledgeInstruction(first, 2_001)
            val fresh = navigator.update(raw.copy(elapsedRealtimeMs = 3_000), 3_000, false)
            assertNotNull(fresh.instruction)
            assertTrue(navigator.reserveInstruction(fresh))
        }
    }

    @Test
    fun replacingRouteAfterPendingDistanceChangeStillInvalidatesTheOldCue() {
        val navigator = navigator()
        val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        val near = navigator.update(fix(0.00082, 2_000), 2_000, false)
        assertFalse(near.cancelStaleNavigationSpeech)
        navigator.setRoute(route().copy(providerRouteId = "replacement"))
        navigator.acknowledgeInstruction(first, 2_001)
        assertNull(navigator.retryGuidance(2_002))
        assertFalse(navigator.reserveInstruction(first))
        val replacement = navigator.update(fix(0.00082, 3_000), 3_000, false)
        assertTrue(replacement.instruction?.contains("길안내를 시작합니다") == true)
        assertTrue(navigator.reserveInstruction(replacement))
    }

    private fun navigator(testMode: Boolean = false) =
        RouteNavigator(allowDegradedRouteGuidance = testMode).apply { setRoute(route()) }

    // Synthetic fixtures validate cue continuity and do not represent recorded walking accuracy.
    private fun fix(longitude: Double, atMs: Long) = TrustedLocation(0.0, longitude, 2f, atMs)

    private fun filtered(raw: TrustedLocation) = FilteredRoutePosition(
        RoutePoint(raw.latitude, raw.longitude), raw.accuracyM.toDouble(), raw.elapsedRealtimeMs,
        heading = RouteHeadingEstimate(90.0, 5.0),
    )

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
