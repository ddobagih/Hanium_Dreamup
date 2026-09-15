package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import org.junit.Assert.*
import org.junit.Test

class RouteNavigatorRetreatTest {
    @Test
    fun remainingDistanceTracksApproachRetreatAndReapproachWithoutFreezingOrJumpingForward() {
        listOf(false, true).forEach { degraded ->
            val navigator = navigator(degraded)
            listOf(30.0, 80.0, 30.0, 91.0, 80.0, 30.0).forEachIndexed { index, progress ->
                val now = 1_000L + index * 20_000L
                val update = sample(navigator, progress, now)
                assertEquals("guide at $progress", 0, update.guideIndex)
                assertTrue("at $progress: ${update.instruction}", update.instruction?.contains("${(100 - progress).toInt()}m 앞") == true)
                assertTrue(navigator.reserveInstruction(update))
                navigator.acknowledgeInstruction(update, now + 1)
            }
        }
    }

    @Test
    fun oppositeGpsCourseOnSameCorridorRemainsPositionEvidence() {
        val navigator = navigator()
        sample(navigator, 80.0, 1_000, 90.0)
        val retreat = sample(navigator, 70.0, 11_000, 270.0)
        assertNotNull(retreat.instruction)
        assertTrue(navigator.currentRouteMatch()?.quality in setOf(RouteMatchQuality.HIGH, RouteMatchQuality.MEDIUM))
        assertNotNull(navigator.currentAcceptedRouteMatchFor(11_000))
        assertTrue(retreat.instruction?.contains("30m 앞") == true)
        assertFalse(retreat.offRoute)
    }

    @Test
    fun slowRetreatWithNoReliableMovementHeadingStillUpdatesTheDistance() {
        val navigator = navigator()
        sample(navigator, 80.0, 1_000)
        for (progress in 79 downTo 30) sample(navigator, progress.toDouble(), (81 - progress) * 1_000L)
        assertTrue(navigator.currentInstruction(fix(30.0, 51_000))?.contains("70m 앞") == true)
    }

    @Test
    fun twoFreshPositionsBehindPassedGuideRestoreItAndOneOutlierDoesNot() {
        val navigator = navigator()
        sample(navigator, 90.0, 1_000, 90.0)
        val passed = sample(navigator, 115.0, 11_000, 180.0)
        assertEquals(1, passed.guideIndex)
        sample(navigator, 80.0, 21_000, 270.0)
        val recoveredOutlier = sample(navigator, 115.0, 31_000, 180.0)
        assertEquals(1, recoveredOutlier.guideIndex)
        sample(navigator, 80.0, 41_000, 270.0)
        val restored = sample(navigator, 70.0, 42_000, 270.0)
        assertEquals(0, restored.guideIndex)
        assertTrue(restored.instruction?.contains("30m 앞, 오른쪽으로 꺾으세요.") == true)
        val reapproach = sample(navigator, 71.0, 52_000, 90.0)
        assertEquals(0, reapproach.guideIndex)
        assertTrue(reapproach.instruction?.contains("29m 앞") == true)
    }

    @Test
    fun repeatedCoordinatesAndTimerRetriesCannotConfirmARewind() {
        val navigator = navigator()
        sample(navigator, 90.0, 1_000, 90.0)
        sample(navigator, 115.0, 11_000, 180.0)
        val raw = fix(80.0, 21_000)
        val filtered = FilteredRoutePosition(raw.point(), 3.0, 21_000, heading = RouteHeadingEstimate(270.0, 8.0))
        navigator.update(raw, 21_000, false, filtered)
        repeat(5) { navigator.update(raw, 21_001L + it, false, filtered) }
        assertTrue(navigator.currentInstruction(raw)?.contains("직진하세요") == true)
        val restored = sample(navigator, 79.0, 22_000, 270.0)
        assertEquals(0, restored.guideIndex)
    }

    @Test
    fun interruptionBreaksTheRewindConfirmationSequence() {
        val navigator = navigator(true)
        sample(navigator, 90.0, 1_000, 90.0)
        sample(navigator, 115.0, 11_000, 180.0)
        sample(navigator, 80.0, 21_000, 270.0)
        navigator.onPositioningEvidenceInterrupted()
        sample(navigator, 70.0, 22_000, 270.0)
        assertTrue(navigator.currentInstruction(fix(70.0, 22_000))?.contains("직진하세요") == true)
        val restored = sample(navigator, 69.0, 23_000, 270.0)
        assertEquals(0, restored.guideIndex)
    }

    @Test
    fun adjacentReturningRouteNearItsStartCannotClaimEndProgressOrArrival() {
        listOf(false, true).forEach { degraded ->
            val navigator = navigator(degraded)
            navigator.setRoute(WalkingRoute("TEST", WalkingRouteSummary(400, 400),
                listOf(point(0.0, 0.0), point(200.0, 0.0), point(0.0, 4.0)), emptyList()))
            val nearEnd = TrustedLocation(point(0.0, 4.0).latitude, 0.0, 3f, 1_000)
            val first = navigator.update(nearEnd, 1_000, false)
            val second = navigator.update(nearEnd.copy(elapsedRealtimeMs = 2_000), 2_000, false)
            assertFalse(first.arrivalCandidate)
            assertFalse(second.arrivalCandidate)
            assertNull(navigator.currentAcceptedRouteMatchFor(2_000))
            assertFalse(second.instruction?.contains("목적지까지 약 0m") == true)
        }
    }

    @Test
    fun rejectedArrivalDoesNotRecreateDecisionUntilUserLeavesThenReapproaches() {
        val navigator = navigator()
        sample(navigator, 298.0, 1_000, 180.0)
        assertTrue(sample(navigator, 298.0, 2_000, 180.0).arrivalCandidate)
        navigator.rejectArrival()
        repeat(5) { assertFalse(sample(navigator, 298.0, 3_000L + it * 1_000, 180.0).arrivalCandidate) }
        sample(navigator, 250.0, 20_000, 0.0)
        sample(navigator, 249.0, 21_000, 0.0)
        assertFalse(sample(navigator, 298.0, 30_000, 180.0).arrivalCandidate)
        assertTrue(sample(navigator, 298.0, 31_000, 180.0).arrivalCandidate)
    }

    private fun navigator(degraded: Boolean = false) = RouteNavigator(allowDegradedRouteGuidance = degraded).apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(300, 300),
            listOf(point(0.0), point(100.0), point(100.0, -200.0)), listOf(
                WalkingRouteGuidePoint(0, point(100.0), null, 100, 200, turnType = 13),
                WalkingRouteGuidePoint(1, point(100.0, -150.0), null, 250, 50, turnType = 11))))
    }

    private fun sample(navigator: RouteNavigator, progress: Double, now: Long, heading: Double? = null): RouteNavigatorUpdate {
        val raw = fix(progress, now)
        return if (heading == null) navigator.update(raw, now, false) else navigator.update(raw, now, false,
            FilteredRoutePosition(raw.point(), 3.0, now, heading = RouteHeadingEstimate(heading, 8.0)))
    }
    private fun TrustedLocation.point() = RoutePoint(latitude, longitude)
    private fun fix(progress: Double, now: Long): TrustedLocation {
        val point = if (progress <= 100) point(progress) else point(100.0, 100.0 - progress)
        return TrustedLocation(point.latitude, point.longitude, 3f, now)
    }
    private fun point(east: Double, north: Double = 0.0) = RoutePoint(north / 111194.92664455874, east / 111194.92664455874)
}
