package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.*
import org.junit.Assert.*
import org.junit.Test

/** Actual publisher -> compass session -> guidance controller -> Navigator -> speech ports. */
class RouteDirectionSessionFunctionalGraceTest {
    @Test fun aShortQualityFaultCoalescedWithGoodNeverStopsAnAlreadyStartedInstruction() {
        val f = Fixture(); f.startPlaying()
        f.g.now = 1_100L
        assertTrue(f.g.sent.single().current()) // actual START validator
        f.sample(1_200L, 31.0)
        assertFalse(f.session.invalidationPending)
        assertEquals(0L, f.session.revision)
        f.sample(1_500L, 3.0)
        assertEquals(1, f.ui.size)
        f.ui.removeAt(0).run()
        assertFalse(f.g.events.toString(), f.g.events.contains("cancel"))
        assertTrue(f.g.events.contains("fault:1200"))
        assertFalse(f.g.nav.isDirectionRecheckPending())
        assertEquals(RouteDirectionGraceMode.NORMAL, f.g.nav.currentDirectionGraceSnapshot()?.mode)
        assertEquals(1, f.g.sent.size)
    }

    @Test fun thirteenRealHeadingSamplesInATwoHundredMillisecondSpikeKeepTheNativeCue() {
        val f = Fixture(); f.startPlaying()
        val trend = RouteHeadingTrend()
        repeat(7) { i ->
            val at = 880L + i * 20L
            trend.observe(RouteFacingObservation(90.0, 3.0, at), at)
        }
        val cue = f.g.sent.single()
        f.g.now = 1_100L
        f.g.validatingStarted = true
        assertTrue(cue.current())
        f.g.validatingStarted = false
        repeat(13) { i ->
            f.sample(1_200L + i * 16L, 3.0, 180.0, trend)
            while (f.ui.isNotEmpty()) f.ui.removeAt(0).run()
            assertFalse(f.g.events.toString(), f.g.events.contains("cancel"))
            assertTrue(f.g.heading.observation!!.headingTrend!!.largeTurnPending)
        }
        f.sample(1_450L, 3.0, 90.0, trend)
        while (f.ui.isNotEmpty()) f.ui.removeAt(0).run()
        assertFalse(f.g.events.toString(), f.g.events.contains("cancel"))
        assertFalse(f.g.nav.isDirectionRecheckPending())
        assertEquals(1, f.g.sent.size)
        cue.done()
        assertEquals(1, f.g.lastResults.size)
    }

    @Test fun aOneSecondAccuracyFaultAfterNativeStartRetainsTheInstructionAndRecovers() {
        val f = Fixture(); f.startPlaying()
        val cue = f.g.sent.single()
        f.g.now = 1_050L
        f.g.validatingStarted = true
        assertTrue(cue.current())
        f.g.validatingStarted = false
        for (at in 1_100L..2_100L step 100L) {
            f.sample(at, 31.0)
            while (f.ui.isNotEmpty()) f.ui.removeAt(0).run()
            assertFalse(f.g.events.toString(), f.g.events.contains("cancel"))
        }
        f.sample(2_200L, 3.0)
        while (f.ui.isNotEmpty()) f.ui.removeAt(0).run()
        assertFalse(f.g.events.toString(), f.g.events.contains("cancel"))
        assertEquals(1, f.g.sent.size)
        cue.done()
        assertEquals(1, f.g.lastResults.size)
    }

    @Test fun oscillatingTwentyNineAndThirtyOneDegreeAccuracyDoesNotCancelPlayingSpeech() {
        val f = Fixture(); f.startPlaying()
        f.g.now = 1_050L
        f.g.validatingStarted = true
        assertTrue(f.g.sent.single().current())
        f.g.validatingStarted = false
        repeat(12) { i ->
            f.sample(1_100L + i * 80L, if (i % 2 == 0) 31.0 else 29.0)
            while (f.ui.isNotEmpty()) f.ui.removeAt(0).run()
            assertFalse(f.g.events.toString(), f.g.events.contains("cancel"))
        }
        assertEquals(1, f.g.sent.size)
    }

    @Test fun aHardReversalCoalescedWithGoodStillRetiresAndStopsTheOldInstruction() {
        val f = Fixture(); f.startPlaying()
        f.g.now = 1_100L
        val old = f.g.sent.single(); assertTrue(old.current())
        f.sample(1_200L, 3.0, 270.0)
        assertTrue(f.session.invalidationPending)
        assertFalse(old.current())
        f.sample(1_300L, 3.0)
        f.ui.removeAt(0).run()
        assertTrue(f.g.events.toString(), f.g.events.contains("cancel"))
        old.done()
        assertTrue("Retired DONE cannot claim delivery", f.g.lastResults.isEmpty())
        assertFalse(f.session.invalidationPending)
    }

    @Test fun graceNewInstructionUsesTheActualControllerStartValidatorThenExpiresAtTheOriginalDeadline() {
        val f = Fixture(); f.startPlaying()
        f.g.now = 1_100L; f.g.sent.single().done()
        f.sample(1_200L, 31.0); f.ui.removeAt(0).run()
        f.finishRecheckStatuses()
        assertEquals("A recoverable direction grace must not interrupt useful guidance with a warning",0,f.g.statusSent.size)
        f.g.now = 2_000L
        val cue = requireNotNull(f.g.nav.currentGuidance(f.g.now, f.g.heading.observation))
        f.g.controller.tryDispatchRouteInstruction(cue, f.g.epoch, f.g.heading)
        val retained = f.g.sent.last()
        assertTrue(retained.message, retained.message.contains("약 12시 방향"))
        f.g.now = 2_100L; assertTrue(retained.current())
        assertEquals(1_000L, cue.speechCueToken?.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_500L, cue.speechCueToken?.facingValidUntilElapsedRealtimeMs)
        assertEquals(2_700L, cue.speechCueToken?.directionGraceDeadlineElapsedRealtimeMs)
        f.g.now = 2_699L
        f.g.controller.retryCurrentNavigationSpeechAfterReady(f.g.now)
        assertTrue(f.g.events.toString(), f.g.events.none { it == "cancel" })
        f.g.now = 2_700L
        f.g.controller.retryCurrentNavigationSpeechAfterReady(f.g.now)
        assertTrue(f.g.events.toString(), f.g.events.contains("cancel"))
        assertFalse(retained.current())
        retained.done()
        assertEquals(1, f.g.lastResults.size) // only the first real DONE
        assertEquals(RouteDirectionGraceMode.PROBLEM, f.g.nav.currentDirectionGraceSnapshot()?.mode)
    }

    @Test fun ownerLossRetiresGraceAndMakesItsLateCompletionUnusable() {
        val f = Fixture(); f.startPlaying()
        val first = f.g.sent.single()
        f.sample(1_200L, 31.0); f.ui.removeAt(0).run()
        assertEquals(RouteDirectionGraceMode.GRACE_RETAINED, f.g.nav.currentDirectionGraceSnapshot()?.mode)
        f.session.stopRouteCompassPresentationUpdates()
        assertNull(f.g.nav.currentDirectionGraceSnapshot())
        first.done()
        assertTrue(f.g.lastResults.isEmpty())
        assertFalse(first.current())
    }

    @Test fun aNormalPacketStartsWithoutStoppingAcrossASoftFaultAndKeepsTheOriginalFixedDeadline() {
        for (startAt in listOf(1_200L, 1_600L)) {
            val f = Fixture(); f.startPlaying()
            val normal = f.g.sent.single()
            // Native START runs before the sensor UI consumer; the event owns the 1100 receipt.
            f.sample(1_100L, 31.0)
            f.g.now = startAt
            f.g.validatingStarted = true
            assertTrue("START=$startAt must retain this exact packet", normal.current())
            f.g.validatingStarted = false
            assertEquals(2_600L, f.g.nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
            assertEquals(RouteDirectionGraceAuthorization.NORMAL_PENDING, f.g.nav.currentDirectionGraceSnapshot()?.authorizationKind)
            assertNotNull(f.g.nav.currentDirectionGraceSnapshot()?.authorizedCueRevision)
            assertFalse(f.g.events.toString(), f.g.events.contains("cancel"))
            assertEquals(1, f.g.sent.size)
            f.g.now = startAt + 100L; normal.done()
            assertEquals(1, f.g.lastResults.size)
            assertTrue(f.g.nav.isDirectionRecheckPending())
            assertEquals(2_600L, f.g.nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        }
    }

    @Test fun aNormalPacketExpiredBeforeAnyFaultCannotBeRevivedButNewGraceCanRecoverAfterTheBoundedRetry() {
        val f = Fixture(); f.startPlaying()
        val normal = f.g.sent.single()
        f.sample(1_600L, 31.0) // The original 1500 normal lease had already expired before BAD.
        f.g.validatingStarted = true
        assertFalse(normal.current())
        f.g.validatingStarted = false
        normal.failed()
        assertEquals(3_001L, f.g.nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        assertNull(f.g.nav.currentDirectionGraceSnapshot()?.authorizedCueRevision)
        f.g.now = 1_850L
        val replacement = requireNotNull(f.g.nav.retryGuidance(f.g.now, f.g.heading.observation))
        assertEquals("route_guidance", replacement.reason)
        assertNotNull(replacement.speechCueToken?.directionGraceEpisodeId)
        assertEquals(1_000L, replacement.speechCueToken?.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_500L, replacement.speechCueToken?.facingValidUntilElapsedRealtimeMs)
        f.g.controller.tryDispatchRouteInstruction(replacement, f.g.epoch, f.g.heading)
        f.finishRecheckStatuses()
        f.g.validatingStarted = true
        assertTrue(f.g.sent.last().current())
        f.g.validatingStarted = false
        assertEquals(3_001L, f.g.nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
    }

    @Test fun aRealTransportFailureStillKeepsItsTwoSecondBackoffDuringSensorGrace() {
        val f = Fixture(); f.startPlaying()
        val normal = f.g.sent.single()
        f.sample(1_100L, 31.0); f.ui.removeAt(0).run()
        // No actual rejected START validator: this is the engine/transport failure terminal.
        normal.failed()
        f.g.now = 1_350L
        assertEquals("guidance_retry_wait", f.g.nav.retryGuidance(f.g.now, f.g.heading.observation)?.reason)
        f.g.now = 2_600L
        val problem = requireNotNull(f.g.nav.retryGuidance(f.g.now, f.g.heading.observation))
        assertNotNull(problem.instruction)
        assertEquals(RouteFacingExpression.UNKNOWN, problem.facingDiagnostic?.expression)
    }

    @Test fun aQueueWatchdogKeepsTheNormalPacketBeforeNativeStartAcrossTheSameSoftEpisode() {
        val f = Fixture(); f.startPlaying()
        val normal = f.g.sent.single()
        f.sample(1_100L, 31.0)
        assertFalse(f.g.validatingStarted)
        assertTrue("Queue watchdog before UI delivery", normal.current())
        f.g.now = 1_600L
        assertTrue("Queue watchdog after the original normal lease", normal.current())
        assertEquals(2_600L, f.g.nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        f.g.validatingStarted = true
        assertTrue(normal.current())
        f.g.validatingStarted = false
        assertFalse(f.g.events.contains("cancel"))
        f.g.now = 1_700L; normal.done()
        assertEquals(1, f.g.lastResults.size)
    }

    @Test fun theQualifiedNormalQueueRemainsExactAndStartsAfterFullySupportingRecovery() {
        val f = Fixture(); f.startPlaying()
        val normal = f.g.sent.single()
        f.sample(1_100L, 31.0); assertTrue(normal.current())
        f.sample(1_500L, 3.0); f.ui.removeAt(0).run()
        assertFalse(f.g.nav.isDirectionRecheckPending())
        assertEquals(RouteDirectionGraceAuthorization.RECOVERED_GRACE_PACKET, f.g.nav.currentDirectionGraceSnapshot()?.authorizationKind)
        assertEquals(2_600L, f.g.nav.currentDirectionGraceSnapshot()?.authorizationDeadlineElapsedRealtimeMs)
        f.g.now = 1_600L
        f.g.validatingStarted = true
        assertTrue(normal.current())
        f.g.validatingStarted = false
        assertFalse(f.g.events.contains("cancel"))
        f.g.now = 1_700L; normal.done()
        assertEquals(1, f.g.lastResults.size)
    }

    private class Fixture {
        val g = GuidanceFixture().apply { captureStatus = true }
        private var finishedStatuses = 0
        val ui = mutableListOf<Runnable>()
        val scheduled = mutableListOf<Pair<Long, Runnable>>()
        val evaluations = mutableListOf<Long>()
        val sourceIdentity = Any()
        val publisher = RouteCompassObservationPublisher().apply { reset(1) }
        var listener: ((RouteCompassObservationEvent) -> Unit)? = null
        val session = RouteCompassSession(g.run, object : RouteCompassSession.Source {
            override fun captureOwner() = RouteCompassSession.CompassObservationOwner(sourceIdentity, g.epoch,
                g.requestGeneration, 1, g.nav.currentTactileRouteProgress().routeVersion)
            override fun ownerCurrent(owner: RouteCompassSession.CompassObservationOwner, exactOwner: Boolean, orientationActive: Boolean) =
                exactOwner && orientationActive && owner == captureOwner()
            override fun collectionCurrent() = true
            override fun mayRetainCompass(orientationActive: Boolean) = orientationActive
            override fun captureSchedule(orientationActive: Boolean): RouteCompassSession.ScheduleLease? =
                if (!orientationActive) null else RouteCompassSession.ScheduleLease({ true }) { evaluations += it }
            override fun headingAllowed(trackerAvailable: () -> Boolean, orientationActive: Boolean) = trackerAvailable() && orientationActive
        }, object : RouteCompassSession.Tracker {
            override val available = true
            override fun listener(listener: (RouteCompassObservationEvent) -> Unit) { this@Fixture.listener = listener }
            override fun start() = Unit
            override fun stop() = Unit
            override fun current(event: RouteCompassObservationEvent) = event.registrationGeneration == 1
            override fun heading(nowMs: Long) = g.heading
        }, object : RouteCompassSession.Actions {
            override fun cancelSpeech() { g.events += "cancel" }
            override fun retry(nowMs: Long) { g.controller.retryCurrentNavigationSpeechAfterReady(nowMs) }
            override fun retryObservation(nowMs: Long, firstBadAtMs: Long, compass: RouteCompassHeadingResult?, publishInstruction: Boolean) {
                if (!publishInstruction) g.events += "fault:$firstBadAtMs"
                g.controller.retryCurrentNavigationSpeechForObservation(nowMs, firstBadAtMs, compass, publishInstruction)
            }
            override fun refreshPresentation() = Unit
        }, object : RouteCompassSession.Callbacks {
            override val viewReady = true
            override fun post(task: Runnable) { ui += task }
            override fun postDelayed(task: Runnable, delayMs: Long): Boolean { scheduled += g.now + delayMs to task; return true }
            override fun remove(task: Runnable) { scheduled.removeAll { it.second === task } }
        }, g.diagnostics, { g.now })
        init { g.compassPeer = session }
        fun startPlaying() { g.install(); session.ensureEarthOrientationForLocation(); g.controller.updateRouteGuidance(g.fix) }
        fun finishRecheckStatuses() {
            var callbacks=0
            while(finishedStatuses<g.statusSent.size) {
                val notice=g.statusSent[finishedStatuses++]
                assertTrue(notice.message,notice.message.contains("방향")&&notice.message.contains("확인"))
                g.validatingStarted=true
                assertTrue("Controlled native START checks the actual captured notice owner",notice.current())
                g.validatingStarted=false
                notice.done()
                while(true) {
                    val due=g.pending.filter{it.first<=g.now}.minByOrNull{it.first}?:break
                    g.pending.remove(due);due.second.run()
                    check(++callbacks<20){"Unexpected immediate speech retry loop"}
                }
            }
        }
        fun sample(at: Long, accuracy: Double, heading: Double = 90.0, trend: RouteHeadingTrend? = null) {
            val raw = RouteFacingObservation(heading, accuracy, at)
            val observation = raw.copy(headingTrend = trend?.observe(raw, at))
            g.now = at; g.heading = good(at).copy(observation = observation)
            listener!!.invoke(requireNotNull(publisher.sample(1, RouteCompassStream.ROTATION_VECTOR,
                at * 1_000_000L, at * 1_000_000L, g.heading)))
        }
    }
    companion object {
        private fun good(at: Long) = RouteCompassHeadingResult(RouteFacingObservation(90.0, 3.0, at), RouteCompassHeadingReason.VALID)
    }
}
