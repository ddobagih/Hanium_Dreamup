package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCorridorMatcherBranchConfirmationGapTest {
    @Test
    fun oldOrDifferentBranchEvidenceCannotConfirmAcrossGapButFreshSameBranchCan() {
        val route = listOf(
            RoutePoint(37.0, 126.999),
            RoutePoint(37.0, 127.0),
            RoutePoint(37.001, 127.0),
            RoutePoint(37.0, 127.0),
            RoutePoint(37.0, 127.001),
        )
        val start = RoutePoint(37.0, 126.9995)
        val crossing = RoutePoint(37.0, 127.0)

        fun match(
            matcher: RouteCorridorMatcher,
            point: RoutePoint,
            headingDeg: Double,
            atMs: Long,
            previousProgressM: Double? = 300.0,
        ): RouteCorridorMatchResult = matcher.match(
            routeId = "confirmation-gap",
            polyline = route,
            position = FilteredRoutePosition(
                point = point,
                horizontalAccuracyM = 3.0,
                elapsedRealtimeMs = atMs,
                heading = RouteHeadingEstimate(headingDeg, standardDeviationDeg = 5.0),
            ),
            previousProgressM = previousProgressM,
        )

        val matcher = RouteCorridorMatcher()
        assertEquals(0, match(matcher, start, 90.0, 1_000L, null).segmentIndex)
        assertEquals(RouteMatchReason.BRANCH_SWITCH_PENDING, match(matcher, crossing, 180.0, 2_000L).reason)

        val afterTenMinutes = match(matcher, crossing, 180.0, 602_000L)
        assertEquals(RouteMatchReason.BRANCH_SWITCH_PENDING, afterTenMinutes.reason)
        assertEquals(RouteMatchQuality.LOW, afterTenMinutes.quality)
        assertEquals(0, afterTenMinutes.segmentIndex)
        assertTrue(afterTenMinutes.confidence < 0.4)

        val freshConfirmation = match(matcher, crossing, 180.0, 606_000L)
        assertEquals(RouteMatchReason.MATCHED, freshConfirmation.reason)
        assertEquals(2, freshConfirmation.segmentIndex)
        assertTrue(freshConfirmation.quality == RouteMatchQuality.HIGH || freshConfirmation.quality == RouteMatchQuality.MEDIUM)

        val otherMatcher = RouteCorridorMatcher()
        assertEquals(0, match(otherMatcher, start, 90.0, 1_000L, null).segmentIndex)
        assertEquals(RouteMatchReason.BRANCH_SWITCH_PENDING, match(otherMatcher, crossing, 180.0, 2_000L).reason)

        val otherBranch = match(otherMatcher, RoutePoint(37.0, 127.0005), 90.0, 3_000L)
        assertEquals(RouteMatchReason.BRANCH_SWITCH_PENDING, otherBranch.reason)
        assertEquals(RouteMatchQuality.LOW, otherBranch.quality)
        assertEquals(3, otherBranch.segmentIndex)
        assertTrue(otherBranch.confidence < 0.4)

        val returnedCandidate = match(otherMatcher, crossing, 180.0, 4_000L)
        assertEquals(RouteMatchReason.BRANCH_SWITCH_PENDING, returnedCandidate.reason)
        assertEquals(RouteMatchQuality.LOW, returnedCandidate.quality)
        assertEquals(0, returnedCandidate.segmentIndex)
        assertTrue(returnedCandidate.confidence < 0.4)

        val confirmedCandidate = match(otherMatcher, crossing, 180.0, 8_000L)
        assertEquals(RouteMatchReason.MATCHED, confirmedCandidate.reason)
        assertEquals(2, confirmedCandidate.segmentIndex)
    }
}
