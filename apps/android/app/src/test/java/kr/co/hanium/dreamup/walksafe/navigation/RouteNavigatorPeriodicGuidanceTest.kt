package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteNavigatorPeriodicGuidanceTest {
    @Test
    fun stoppedUserGetsTheSameDirectionTenSecondsAfterEachCompletedSentence() {
        listOf(false, true).forEach { testMode ->
            val navigator = navigator(testMode)
            val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
            assertTrue(navigator.reserveInstruction(first))
            navigator.acknowledgeInstruction(first, 4_000)

            assertNull(navigator.update(fix(0.00055, 13_999), 13_999, false).instruction)
            val repeat = requireNotNull(navigator.retryGuidance(14_000))
            assertEquals("route_guidance", repeat.reason)
            assertEquals(first.guideIndex, repeat.guideIndex)
            assertEquals(first.speechCueToken?.distanceBand, repeat.speechCueToken?.distanceBand)
            assertNotEquals(first.speechCueToken, repeat.speechCueToken)
            assertTrue(repeat.instruction?.contains("50m 앞, 왼쪽으로 꺾으세요.") == true)
            assertFalse(repeat.instruction?.contains("길안내를 시작합니다") == true)
            assertFalse(repeat.instruction?.contains("전체 경로") == true)
            assertFalse(repeat.cancelStaleNavigationSpeech)
            assertTrue(navigator.reserveInstruction(repeat))
            navigator.acknowledgeInstruction(repeat, 17_000)

            assertNull(navigator.update(fix(0.00055, 26_999), 26_999, false).instruction)
            val third = requireNotNull(navigator.retryGuidance(27_000))
            assertEquals(repeat.instruction, third.instruction)
            assertNotEquals(repeat.speechCueToken, third.speechCueToken)
            assertTrue(navigator.reserveInstruction(third))
        }
    }

    @Test
    fun periodicReminderUsesTheLatestDistanceEvenWithinTheSameApproachBand() {
        listOf(false, true).forEach { testMode ->
            val navigator = navigator(testMode)
            val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
            assertTrue(navigator.reserveInstruction(first))
            navigator.acknowledgeInstruction(first, 2_000)
            val newer = navigator.update(fix(0.00064, 11_999), 11_999, false)
            assertNull(newer.instruction)
            val match = navigator.currentRouteMatch()

            val repeat = requireNotNull(navigator.retryGuidance(12_000))
            assertTrue(repeat.instruction?.contains("40m 앞, 왼쪽으로 꺾으세요.") == true)
            assertEquals(first.speechCueToken?.distanceBand, repeat.speechCueToken?.distanceBand)
            assertSame(match, navigator.currentRouteMatch())
            assertSame(match, navigator.currentAcceptedRouteMatchFor(11_999))
        }
    }

    @Test
    fun longSentenceIsNeverInterruptedByPeriodicDeadlineAndStartsTheClockAtCompletion() {
        val navigator = navigator()
        val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        listOf(10_000L, 20_000L, 30_000L).forEach { atMs ->
            val update = navigator.update(fix(0.00055, atMs), atMs, false)
            assertEquals("guidance_in_flight", update.reason)
            assertFalse(update.cancelStaleNavigationSpeech)
            assertNull(update.instruction)
        }
        navigator.acknowledgeInstruction(first, 31_000)
        assertNull(navigator.update(fix(0.00055, 40_999), 40_999, false).instruction)
        assertNotNull(navigator.retryGuidance(41_000)?.instruction)
    }

    @Test
    fun repeatedCompletionCannotPostponeReminderOrReserveAnAlreadyConsumedCue() {
        val navigator = navigator()
        val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 2_000)
        assertFalse(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 8_000)
        navigator.releaseInstruction(first, 8_001)
        navigator.update(fix(0.00055, 11_999), 11_999, false)
        val repeat = requireNotNull(navigator.retryGuidance(12_000))
        assertNotNull(repeat.instruction)
        assertTrue(navigator.reserveInstruction(repeat))
        navigator.acknowledgeInstruction(first, 12_001)
        navigator.releaseInstruction(first, 12_002)
        assertEquals("guidance_in_flight", navigator.retryGuidance(12_003)?.reason)
    }

    @Test
    fun busyPeriodicOfferKeepsOneTokenUntilItCanBeReserved() {
        val navigator = navigator()
        val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 2_000)
        val repeat = navigator.update(fix(0.00055, 12_000), 12_000, false)
        assertNotNull(repeat.instruction)
        listOf(12_001L, 13_000L, 14_000L).forEach { atMs ->
            val retry = requireNotNull(navigator.retryGuidance(atMs))
            assertEquals(repeat.speechCueToken, retry.speechCueToken)
            assertEquals(repeat.instruction, retry.instruction)
        }
        assertTrue(navigator.reserveInstruction(repeat))
    }

    @Test
    fun failedPeriodicReminderRetriesThenWaitsTenSecondsAfterActualSuccess() {
        val navigator = navigator()
        val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 2_000)
        val repeat = navigator.update(fix(0.00055, 12_000), 12_000, false)
        assertTrue(navigator.reserveInstruction(repeat))
        navigator.releaseInstruction(repeat, 12_001)
        assertEquals("guidance_retry_wait", navigator.retryGuidance(14_000)?.reason)
        val retry = requireNotNull(navigator.retryGuidance(14_001))
        assertNotEquals(repeat.speechCueToken, retry.speechCueToken)
        assertTrue(navigator.reserveInstruction(retry))
        navigator.acknowledgeInstruction(repeat, 14_002)
        assertEquals("guidance_in_flight", navigator.retryGuidance(14_003)?.reason)
        navigator.acknowledgeInstruction(retry, 15_000)
        assertNull(navigator.update(fix(0.00055, 24_999), 24_999, false).instruction)
        assertNotNull(navigator.retryGuidance(25_000)?.instruction)
    }

    @Test
    fun periodicDeadlineCannotRefreshAnExpiredLocation() {
        listOf(false, true).forEach { testMode ->
            val navigator = navigator(testMode)
            val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
            assertTrue(navigator.reserveInstruction(first))
            navigator.acknowledgeInstruction(first, 2_000)
            assertNull(navigator.retryGuidance(12_000))
            assertFalse(navigator.reserveInstruction(first))
            val fresh = navigator.update(fix(0.00055, 13_000), 13_000, false)
            assertNotNull(fresh.instruction)
            assertFalse(fresh.instruction?.contains("전체 경로") == true)
        }
    }

    @Test
    fun repeatingFinalSegmentWithNoGuideDoesNotCreateArrivalEvidence() {
        val navigator = RouteNavigator().apply {
            setRoute(route().copy(guidePoints = emptyList()))
        }
        val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
        assertNull(first.guideIndex)
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 2_000)
        val repeat = navigator.update(fix(0.00055, 12_000), 12_000, false)
        assertTrue(repeat.instruction?.contains("목적지까지 약 272m 남았습니다.") == true)
        assertFalse(repeat.arrived)
        assertFalse(repeat.arrivalCandidate)
        assertNull(repeat.guideIndex)
        assertTrue(navigator.reserveInstruction(repeat))
    }

    @Test
    fun urgentApproachStageAndNextGuideRemainEligibleBeforePeriodicDeadline() {
        val navigator = navigator(true)
        val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 2_000)
        val near = navigator.update(fix(0.00082, 3_000), 3_000, false)
        assertTrue(near.instruction?.contains("20m 앞, 왼쪽으로 꺾으세요.") == true)
        assertTrue(navigator.reserveInstruction(near))
        val turnPassed = navigator.update(TrustedLocation(0.00018, 0.001, 2f, 5_000), 5_000, false)
        assertTrue(turnPassed.cancelStaleNavigationSpeech)
        assertTrue(turnPassed.instruction?.contains("오른쪽으로 꺾으세요.") == true)
        assertTrue(navigator.reserveInstruction(turnPassed))
        navigator.acknowledgeInstruction(near, 5_001)
        assertEquals("guidance_in_flight", navigator.retryGuidance(5_002)?.reason)
    }

    @Test
    fun routeReplacementAndEndRetirePeriodicOffersAndTheirCallbacks() {
        val navigator = navigator()
        val first = navigator.update(fix(0.00055, 1_000), 1_000, false)
        assertTrue(navigator.reserveInstruction(first))
        navigator.acknowledgeInstruction(first, 2_000)
        val repeat = navigator.update(fix(0.00055, 12_000), 12_000, false)
        assertTrue(navigator.reserveInstruction(repeat))
        navigator.setRoute(route().copy(providerRouteId = "replacement"))
        navigator.acknowledgeInstruction(repeat, 12_001)
        navigator.releaseInstruction(repeat, 12_002)
        assertNull(navigator.retryGuidance(12_003))
        assertFalse(navigator.reserveInstruction(repeat))
        val replacement = navigator.update(fix(0.00055, 13_000), 13_000, false)
        assertTrue(replacement.instruction?.contains("길안내를 시작합니다") == true)
        assertTrue(navigator.reserveInstruction(replacement))
        navigator.clear()
        navigator.acknowledgeInstruction(replacement, 13_001)
        assertFalse(navigator.reserveInstruction(replacement))
        assertNull(navigator.retryGuidance(23_001))
    }

    private fun navigator(testMode: Boolean = false) =
        RouteNavigator(allowDegradedRouteGuidance = testMode).apply { setRoute(route()) }

    // Synthetic fixtures verify timing and speech ownership, not physical walking accuracy.
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
