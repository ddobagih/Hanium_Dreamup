package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.*
import org.junit.Assert.*
import org.junit.Test

/** Exact queued packet, real new observations, and the controller's watchdog/native START guard. */
class RouteNormalFreshDirectionStartTest {
    @Test fun aFreshFullySupportingMeasurementKeepsTheQueuedPacketAndAllowsItsDelayedNativeStart() {
        for (started in listOf(false, true)) {
            val f = queued()
            val token = pending(f)
            f.now = 1_600L; f.heading = good(f.now); f.validatingStarted = started
            assertFalse(token.hasCurrentFacingEvidence(f.now))
            assertTrue("stage actualSTART=$started", f.sent.single().current())
            assertEquals(token, pending(f))
            assertEquals(1_000L, token.facingObservedAtElapsedRealtimeMs)
            assertEquals(1_500L, token.facingValidUntilElapsedRealtimeMs)
            assertNull(token.directionGraceEpisodeId)
            assertFalse(f.nav.isDirectionRecheckPending())
            val proof = requireNotNull(f.nav.currentDirectionGraceSnapshot())
            assertEquals(RouteDirectionGraceMode.NORMAL, proof.mode)
            assertEquals(RouteDirectionGraceAuthorization.NORMAL_FRESH_REVALIDATED, proof.authorizationKind)
            assertEquals(1_600L, proof.authorizationObservedAtElapsedRealtimeMs)
            assertEquals(2_100L, proof.authorizationDeadlineElapsedRealtimeMs)
            assertFalse(f.events.contains("cancel"))
            f.now = 1_700L; f.sent.single().done()
            assertEquals(1, f.lastResults.size)
        }
    }

    @Test fun generationFromARealFourHundredFiftyMillisecondSampleDoesNotLoseOneHundredFiftyMillisecondStart() {
        val f = GuidanceFixture(); f.now = 1_450L; f.install()
        f.controller.updateRouteGuidance(f.fix)
        val token = pending(f)
        assertEquals(1_000L, token.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_500L, token.facingValidUntilElapsedRealtimeMs)
        f.now = 1_600L; f.heading = good(f.now); f.validatingStarted = true
        assertTrue(f.sent.single().message, f.sent.single().current())
        assertFalse(f.nav.isDirectionRecheckPending())
    }

    @Test fun anUnchangedStaleFutureBadOrDisjointObservationCannotCreateNormalFreshAuthorization() {
        val observations = listOf<RouteFacingObservation?>(null, good(1_000L).observation,
            good(1_099L).observation, good(1_601L).observation,
            good(1_600L).observation!!.copy(headingAccuracyDegrees = 31.0),
            good(1_600L).observation!!.copy(degreesTrueNorth = 150.0),
            good(1_600L).observation!!.copy(degreesTrueNorth = 270.0))
        for (observation in observations) {
            val f = queued(); val token = pending(f)
            f.now = 1_600L; f.heading = RouteCompassHeadingResult(observation, RouteCompassHeadingReason.VALID)
            assertFalse("observation=$observation", f.sent.single().current())
            assertEquals(1_000L, token.facingObservedAtElapsedRealtimeMs)
            assertEquals(1_500L, token.facingValidUntilElapsedRealtimeMs)
        }
    }

    @Test fun freshSupportCannotCrossTheOutputOwnerOrRestoreARetiredPacket() {
        for (retire in listOf<(GuidanceFixture) -> Unit>({ it.requestGeneration++ },
            { it.feedbackCurrent = false }, { it.nav.clear() }, { it.nav.onPositioningEvidenceInterrupted() })) {
            val f = queued(); retire(f)
            f.now = 1_600L; f.heading = good(f.now)
            assertFalse(f.sent.single().current())
            f.sent.single().done()
            assertTrue(f.lastResults.isEmpty())
        }
    }

    @Test fun aSourceSwitchStillNeedsTwoActualFreshMeasurements() {
        val f = queued(); f.now = 1_600L
        val fallback = good(f.now).observation!!.copy(source = RouteFacingSource.GRAVITY_MAGNETIC,
            sensorQuality = RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS,
            headingAccuracyDegrees = null, reportedHeadingAccuracyDegrees = null)
        f.heading = RouteCompassHeadingResult(fallback, RouteCompassHeadingReason.VALID)
        assertFalse(f.sent.single().current())
        assertFalse("Repeated reads are not a second sample", f.sent.single().current())
        f.now = 1_650L; f.heading = good(f.now)
        assertFalse("First returning RV cannot confirm the source", f.sent.single().current())
        assertFalse("Repeated RV read is not a second sample", f.sent.single().current())
        f.now = 1_700L; f.heading = good(f.now)
        assertTrue(f.sent.single().current())
    }

    @Test fun continuedActualFreshSupportIsNotLimitedByAnInventedNormalGraceButExpiredGpsIsRejected() {
        val f = queued(); f.now = 3_001L; f.heading = good(f.now)
        assertTrue(f.sent.single().current())
        assertFalse(f.nav.isDirectionRecheckPending())
        f.now = 12_000L; f.heading = good(f.now)
        assertFalse(f.sent.single().current())
        assertFalse(f.nav.isDirectionRecheckPending())
    }

    @Test fun aNewNormalProofQualifiedAtBadOnsetTransfersOnlyThisPacketToTheFixedGrace() {
        val f = queued(); val token = pending(f)
        f.now = 1_600L; f.heading = good(f.now)
        assertTrue(f.sent.single().current())
        f.now = 1_700L; f.heading = good(f.now).copy(observation = good(f.now).observation!!.copy(headingAccuracyDegrees = 31.0))
        assertTrue(f.sent.single().current())
        assertEquals(3_200L, f.nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        assertEquals(RouteDirectionGraceAuthorization.NORMAL_PENDING, f.nav.currentDirectionGraceSnapshot()?.authorizationKind)
        f.now = 2_000L; f.heading = f.heading.copy(observation = f.heading.observation!!.copy(observedAtElapsedRealtimeMs = f.now))
        assertTrue(f.sent.single().current())
        assertEquals(3_200L, f.nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        f.now = 3_199L; assertTrue(f.sent.single().current())
        f.now = 3_200L; assertFalse(f.sent.single().current())
        f.sent.single().done(); assertTrue(f.lastResults.isEmpty())
        assertEquals(1_000L, token.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_500L, token.facingValidUntilElapsedRealtimeMs)
    }

    @Test fun freshProofRequiresTheOriginalDistanceAndRoutePhaseWhileSmallGpsDriftIsSupported() {
        for (east in listOf(51.0, 60.0, 100.0)) {
            val f = queued(); f.now = 1_600L; f.heading = good(f.now)
            val fix = TrustedLocation(37.0, 127.0 + east / 88_804.0, 2f, f.now)
            f.nav.update(fix, f.now, false, facingObservation = f.heading.observation)
            assertEquals("east=$east", east == 51.0, f.sent.single().current())
        }
    }

    @Test fun repeatedProofReadsPreserveTheActualObservationDeadlineAndRollbackIsRejected() {
        val f = queued(); f.now = 1_600L; f.heading = good(f.now)
        assertTrue(f.sent.single().current())
        f.now = 1_900L; assertTrue(f.sent.single().current())
        assertEquals(1_600L, f.nav.currentDirectionGraceSnapshot()?.authorizationObservedAtElapsedRealtimeMs)
        assertEquals(2_100L, f.nav.currentDirectionGraceSnapshot()?.authorizationDeadlineElapsedRealtimeMs)
        f.now = 1_500L; assertFalse(f.sent.single().current())
        assertNull(f.nav.currentDirectionGraceSnapshot()?.retainedObservedAtElapsedRealtimeMs)
        assertNull(f.nav.currentDirectionGraceSnapshot()?.authorizationKind)
        assertNotEquals(RouteDirectionGraceMode.GRACE_RETAINED, f.nav.currentDirectionGraceSnapshot()?.mode)
    }

    private fun queued() = GuidanceFixture().also { it.install(); it.controller.updateRouteGuidance(it.fix) }
    private fun good(at: Long) = RouteCompassHeadingResult(RouteFacingObservation(90.0, 3.0, at), RouteCompassHeadingReason.VALID)
    private fun pending(f: GuidanceFixture): RouteSpeechCueToken = f.nav.javaClass.getDeclaredField("pendingSpeechCue")
        .also { it.isAccessible = true }.get(f.nav) as RouteSpeechCueToken
}
