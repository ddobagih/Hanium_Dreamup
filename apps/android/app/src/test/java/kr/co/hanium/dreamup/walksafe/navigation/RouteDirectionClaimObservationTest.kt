package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

/** Callback classification is read-only; the UI retry still owns every state transition. */
class RouteDirectionClaimObservationTest {
    @Test fun usableButImpreciseObservationInvalidatesTheClaimBeforeTheNextPeriodicCheck() {
        val nav = navigator()
        val playing = offer(nav)
        assertTrue(nav.reserveInstruction(playing))
        // This accepted RV error crosses four sectors; 20 degrees alone now supports RANGE.
        val imprecise = RouteFacingObservation(75.0, 30.0, 1_100L)
        assertTrue(RouteFacingGuidance.isUsableObservation(imprecise, 1_100L))
        assertNull(RouteFacingGuidance.evaluate(90.0, imprecise, 1_100L).clockRange)
        assertTrue(nav.hasInvalidatedDirectionClaim(1_100L, imprecise))
        assertFalse(nav.isDirectionRecheckPending())
        assertNotNull(nav.currentAcceptedRouteMatchFor(1_000L))
        assertNull(nav.currentAcceptedRouteMatchFor(1_100L))
        val consumed = requireNotNull(nav.retryGuidance(1_200L, imprecise, null, 1_100L))
        assertFalse(consumed.cancelStaleNavigationSpeech)
        assertEquals("guidance_in_flight", consumed.reason)
        assertNull(consumed.instruction)
        assertTrue(nav.isDirectionRecheckPending())
        assertFalse(nav.reserveInstruction(playing))
        assertNull(nav.retryGuidance(2_599L, imprecise.copy(observedAtElapsedRealtimeMs = 2_599L))?.instruction)
        val problem = requireNotNull(nav.retryGuidance(2_600L, imprecise.copy(observedAtElapsedRealtimeMs = 2_600L)))
        assertNotNull(problem.instruction)
        assertTrue(problem.cancelStaleNavigationSpeech)
        assertFalse(nav.isInstructionCompletionCurrent(playing, 2_600L))
    }

    @Test fun aRealReversalInvalidatesPlayingClockButAnAdjacentPreciseTurnDoesNot() {
        val nav = navigator()
        val playing = offer(nav)
        assertTrue(nav.reserveInstruction(playing))
        val sectorEdge = facing(1_050L, heading = 80.0)
        val edgeQuality = RouteFacingGuidance.evaluate(90.0, sectorEdge, 1_050L)
        assertFalse(edgeQuality.clockPrecisionAvailable)
        assertEquals(12, edgeQuality.clockHour)
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, edgeQuality.clockReferenceKind)
        assertNull(edgeQuality.clockRange)
        assertTrue(nav.hasInvalidatedDirectionClaim(1_050L, sectorEdge))
        val adjacent = facing(1_100L, heading = 60.0)
        assertFalse(nav.hasInvalidatedDirectionClaim(1_100L, adjacent))
        val continuing = requireNotNull(nav.retryGuidance(1_100L, adjacent))
        assertEquals("guidance_in_flight", continuing.reason)
        assertFalse(continuing.cancelStaleNavigationSpeech)
        val reversed = facing(1_200L, heading = 270.0)
        assertTrue(nav.hasInvalidatedDirectionClaim(1_200L, reversed))
        val consumed = requireNotNull(nav.retryGuidance(1_200L, reversed))
        assertTrue(consumed.cancelStaleNavigationSpeech)
        assertFalse(nav.reserveInstruction(playing))
        val confirmed = requireNotNull(nav.retryGuidance(1_300L, facing(1_300L, 270.0)))
        assertClock(confirmed, 6)
    }

    @Test fun repeatedReadsCannotConfirmAnotherClockSectorOrStartTheGrace() {
        val nav = navigator()
        offer(nav)
        for (at in listOf(1_050L, 1_100L, 1_150L)) {
            assertFalse(nav.hasInvalidatedDirectionClaim(at, facing(at, heading = 0.0)))
            assertFalse(nav.isDirectionRecheckPending())
        }
        // Only the actual actor call consumes the first observation of the new 3 o'clock sector.
        val first = requireNotNull(nav.currentGuidance(1_200L, facing(1_200L, 0.0)))
        assertClock(first, 12)
        assertNotNull(first.speechCueToken?.directionGraceEpisodeId)
        assertFalse(nav.isInstructionCurrent(first, 1_200L, facing(1_200L, 0.0)))
        assertClock(requireNotNull(nav.currentGuidance(1_250L, facing(1_200L, 0.0))), 12)
        assertClock(requireNotNull(nav.currentGuidance(1_300L, facing(1_300L, 0.0))), 3)
    }

    @Test fun readsOfAnotherSourceAndRecoveryEpisodeCannotChangeAcceptedSourceOwnership() {
        val nav = navigator()
        val playing = offer(nav)
        assertTrue(nav.reserveInstruction(playing))
        for (at in listOf(1_050L, 1_100L, 1_150L)) {
            val fallback = fallback(at).copy(fallbackRecoveryEpisode = RouteCompassRecoveryEpisode(1L, 1_025L))
            assertTrue(nav.hasInvalidatedDirectionClaim(at, fallback))
        }
        assertFalse(nav.isDirectionRecheckPending())
        val sameSource = requireNotNull(nav.retryGuidance(1_200L, facing(1_200L)))
        assertEquals("guidance_in_flight", sameSource.reason)
        assertFalse(sameSource.cancelStaleNavigationSpeech)
        // Callback reads did not consume fallback samples. Soft source loss retains playback;
        // returning RV still requires two distinct fresh measurements before actual recovery.
        assertFalse(requireNotNull(nav.retryGuidance(1_300L, fallback(1_300L))).cancelStaleNavigationSpeech)
        assertNull(nav.retryGuidance(1_400L, facing(1_400L))?.instruction)
        assertTrue(nav.isDirectionRecheckPending())
        assertNull(nav.retryGuidance(1_450L, facing(1_400L))?.instruction)
        assertTrue(nav.isDirectionRecheckPending())
        assertEquals("guidance_in_flight", nav.retryGuidance(1_500L, facing(1_500L))?.reason)
        assertFalse(nav.isDirectionRecheckPending())
        assertTrue(nav.isInstructionCurrent(playing, 1_600L, facing(1_600L)))
    }

    @Test fun staleObservationReadsCannotResetClockConfirmationOrRenewPositionEvidence() {
        val nav = navigator()
        offer(nav)
        assertFalse(nav.hasInvalidatedDirectionClaim(1_500L, facing(1_000L)))
        assertTrue(nav.hasInvalidatedDirectionClaim(1_501L, facing(1_000L)))
        assertFalse(nav.isDirectionRecheckPending())
        assertNotNull(nav.currentAcceptedRouteMatchFor(1_000L))
        assertNull(nav.currentAcceptedRouteMatchFor(1_501L))
        // If the stale read cleared the confirmed sector, this first 3 o'clock sample would
        // incorrectly be spoken immediately instead of waiting for a distinct second sample.
        val held = requireNotNull(nav.currentGuidance(1_502L, facing(1_502L, 0.0)))
        assertClock(held, 12)
        assertFalse(nav.isInstructionCurrent(held, 1_502L, facing(1_502L, 0.0)))
        assertClock(requireNotNull(nav.currentGuidance(1_550L, facing(1_502L, 0.0))), 12)
        assertClock(requireNotNull(nav.currentGuidance(1_600L, facing(1_600L, 0.0))), 3)
    }

    @Test fun anExpiredStartLeaseDoesNotMakeFreshSupportingObservationsInvalid() {
        val nav = navigator()
        val playing = offer(nav)
        assertTrue(nav.reserveInstruction(playing))
        assertFalse(nav.hasInvalidatedDirectionClaim(1_501L, facing(1_501L)))
        assertFalse(nav.hasInvalidatedDirectionClaim(2_000L, facing(2_000L)))
        val continuing = requireNotNull(nav.retryGuidance(2_000L, facing(2_000L)))
        assertEquals("guidance_in_flight", continuing.reason)
        assertFalse(continuing.cancelStaleNavigationSpeech)
        // Playback continuation is distinct from starting a queued token past its 500ms lease.
        assertFalse(nav.isInstructionCurrent(playing, 2_000L, facing(2_000L)))
    }

    @Test fun aCompletedLatestClockClaimStillDetectsLossButUnrelatedScopesDoNot() {
        val nav = navigator()
        assertFalse(nav.hasInvalidatedDirectionClaim(1_000L, null))
        val delivered = offer(nav)
        assertTrue(nav.reserveInstruction(delivered))
        nav.acknowledgeInstruction(delivered, 1_100L)
        assertTrue(nav.hasInvalidatedDirectionClaim(1_200L, null))
        assertFalse(nav.isDirectionRecheckPending())
        nav.setRoute(route())
        assertFalse(nav.hasInvalidatedDirectionClaim(1_300L, null))
        nav.update(fix(1_400L), 1_400L, false, facingObservation = null)
        val recheck = requireNotNull(nav.retryGuidance(2_900L, null))
        assertNotNull(recheck.instruction)
        assertFalse(requireNotNull(recheck.speechCueToken).requiresFacingEvidence)
        assertFalse(nav.hasInvalidatedDirectionClaim(3_000L, null))
        nav.clear()
        assertFalse(nav.hasInvalidatedDirectionClaim(3_100L, null))
    }

    @Test fun expiredPositionContextIsReportedWithoutDeletingItOrExtendingItsLifetime() {
        val nav = navigator()
        val offered = offer(nav)
        assertTrue(nav.hasInvalidatedDirectionClaim(12_000L, facing(12_000L)))
        assertFalse(nav.isDirectionRecheckPending())
        // Read-only classification retains the cue. The real START/context guard still refuses
        // expired position evidence and the actor then removes it without generating movement.
        assertTrue(nav.reserveInstruction(offered))
        assertFalse(nav.isInstructionCurrent(offered, 12_000L, facing(12_000L)))
        val cancellation = requireNotNull(nav.retryGuidance(12_000L, facing(12_000L)))
        assertNull(cancellation.instruction)
        assertEquals("stale_route_context", cancellation.reason)
        assertTrue(cancellation.cancelStaleNavigationSpeech)
        assertNull(nav.retryGuidance(12_001L, facing(12_001L)))
        assertFalse(nav.isInstructionCompletionCurrent(offered, 12_001L))
        assertFalse(nav.hasInvalidatedDirectionClaim(12_000L, facing(12_000L)))
    }

    private fun offer(nav: RouteNavigator): RouteNavigatorUpdate =
        nav.update(fix(), 1_000L, false, facingObservation = facing(1_000L)).also { assertClock(it, 12) }

    private fun assertClock(update: RouteNavigatorUpdate, hour: Int) {
        assertTrue(update.instruction.orEmpty().contains("약 ${hour}시 방향"))
        assertTrue(requireNotNull(update.speechCueToken).requiresFacingEvidence)
    }

    private fun navigator() = RouteNavigator().apply { setRoute(route()) }
    private fun route() = WalkingRoute("TEST", WalkingRouteSummary(200, 200),
        listOf(point(0.0), point(200.0)),
        listOf(WalkingRouteGuidePoint(0, point(100.0), null, 100, 100, turnType = 11)))
    private fun point(eastM: Double) = RoutePoint(37.0, 127.0 + eastM / 88_804.0)
    private fun fix(atMs: Long = 1_000L) = point(50.0).let { TrustedLocation(it.latitude, it.longitude, 2f, atMs) }
    private fun facing(atMs: Long, heading: Double = 90.0) = RouteFacingObservation(heading, 3.0, atMs)
    private fun fallback(atMs: Long) = RouteFacingObservation(90.0, null, atMs,
        RouteFacingSource.GRAVITY_MAGNETIC, RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS)
}
