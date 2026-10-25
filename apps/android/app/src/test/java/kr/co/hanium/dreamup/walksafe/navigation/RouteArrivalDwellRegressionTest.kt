package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S07-32 (Root R6, 07.txt:303/305): the automatic arrival question needs consecutive
 * near-destination evidence that has lasted at least 3 s (measurement time), not only two samples.
 * A continuity break restarts that time; a spoken query cannot bypass it.
 */
class RouteArrivalDwellRegressionTest {
    @Test
    fun twoSamplesOneSecondApartDoNotOpenTheQuestion() {
        val navigator = navigator()
        assertFalse(navigator.update(fix(1_000L), 1_000L, false).arrivalCandidate)
        assertFalse(navigator.update(fix(2_000L), 2_000L, false).arrivalCandidate)
    }

    @Test
    fun threeSecondsOfConsecutiveEvidenceOpenTheQuestion() {
        val navigator = navigator()
        for (at in 1_000L..3_000L step 1_000L) assertFalse("at=$at", navigator.update(fix(at), at, false).arrivalCandidate)
        val question = navigator.update(fix(4_000L), 4_000L, false)
        assertTrue(question.arrivalCandidate)
        assertEquals("arrival_confirmation_required", question.reason)
        // Two samples 3 s apart are consecutive (within the 10 s gap) and also span the dwell.
        val sparse = navigator()
        assertFalse(sparse.update(fix(1_000L), 1_000L, false).arrivalCandidate)
        assertTrue(sparse.update(fix(4_000L), 4_000L, false).arrivalCandidate)
    }

    @Test
    fun aContinuityBreakRestartsTheDwell() {
        val away = navigator()
        away.update(fix(1_000L), 1_000L, false)
        away.update(fix(2_000L), 2_000L, false)
        // 60 m back along the route: not arrival evidence.
        assertFalse(away.update(back(60.0, 3_000L), 3_000L, false).arrivalCandidate)
        for (at in 4_000L..6_000L step 1_000L) assertFalse("at=$at", away.update(fix(at), at, false).arrivalCandidate)
        assertTrue(away.update(fix(7_000L), 7_000L, false).arrivalCandidate)

        val gap = navigator()
        gap.update(fix(1_000L), 1_000L, false)
        gap.update(fix(2_000L), 2_000L, false)
        // An 11 s gap exceeds the consecutive-sample limit: the evidence restarts at 13 s.
        for (at in listOf(13_000L, 14_000L, 15_000L)) assertFalse("at=$at", gap.update(fix(at), at, false).arrivalCandidate)
        assertTrue(gap.update(fix(16_000L), 16_000L, false).arrivalCandidate)
    }

    @Test
    fun theUsersArrivedQueryCannotBypassTheDwell() {
        val navigator = navigator()
        assertFalse(navigator.update(fix(1_000L), 1_000L, false).arrivalCandidate)
        val request = navigator.requestArrivalConfirmation(1_000L)
        assertEquals("arrival_waiting_for_trusted_evidence", request.reason)
        assertEquals(null, request.pendingUserDecision)
        assertFalse(navigator.confirmArrival().arrived)
        assertFalse(navigator.update(fix(2_000L), 2_000L, false).arrivalCandidate)
        assertFalse(navigator.update(fix(3_000L), 3_000L, false).arrivalCandidate)
        assertTrue(navigator.update(fix(4_000L), 4_000L, false).arrivalCandidate)
    }

    @Test fun acceptedEvidenceAndSafetyRetirementCommitAtomicallyAcrossTheDecisionDeadline() {
        var now = 1_000L
        val navigator = RouteNavigator(nowElapsedRealtimeMs = { now }).apply {
            setRoute(WalkingRoute("TEST", WalkingRouteSummary(89, 90),
                listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001)), emptyList()))
        }
        for (at in 1_000L..4_000L step 1_000L) {
            now = at
            navigator.update(fix(at), at, false)
        }
        val token = requireNotNull(navigator.pendingDecisionToken())
        var retirements = 0
        assertFalse(navigator.completeDetectedArrival(token) { false }.arrived)
        assertTrue(navigator.hasRoute())
        assertEquals(token, navigator.pendingDecisionToken())
        val complete = navigator.completeDetectedArrival(token) {
            retirements++
            now += 30_000L // A retired owner cannot be stranded by a second TTL/expiry read.
            true
        }
        assertTrue(complete.arrived)
        assertEquals(1, retirements)
        assertFalse(navigator.hasRoute())
    }

    private fun navigator() = RouteNavigator().apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(89, 90),
            listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.001)), emptyList()))
    }
    private fun fix(at: Long) = TrustedLocation(37.0, 127.001, 1f, at)
    private fun back(metres: Double, at: Long) = TrustedLocation(
        37.0, 127.001 - metres / (111_194.92664455874 * kotlin.math.cos(Math.toRadians(37.0))), 1f, at,
    )
}
