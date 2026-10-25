package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.*
import org.junit.Assert.*
import org.junit.Test

/** Actual publisher/session/Navigator path; Android registration and UI execution are controlled ports. */
class RouteCompassSessionTest {
    @Test fun anAudioGenerationReplacementRetiresItsCueButKeepsTheSameSensorFaultDeadline() {
        val f = Fixture(); f.startPlaying(); f.sample(1_100L, 31.0)
        f.g.now = 1_200L; f.ui.removeAt(0).run()
        val before = requireNotNull(f.g.nav.currentDirectionGraceSnapshot())
        val playing = f.g.sent.single()
        f.feedbackGeneration++
        f.session.bindCompassObservationOwner()
        val after = requireNotNull(f.g.nav.currentDirectionGraceSnapshot())
        assertEquals(before.episodeId, after.episodeId)
        assertEquals(before.onsetElapsedRealtimeMs, after.onsetElapsedRealtimeMs)
        assertEquals(before.deadlineElapsedRealtimeMs, after.deadlineElapsedRealtimeMs)
        assertFalse("The retired output cannot keep its START/DONE validator", playing.current())
        playing.done()
        assertTrue("A retired DONE cannot acknowledge the old cue", f.g.lastResults.isEmpty())
        assertEquals(before.onsetElapsedRealtimeMs, f.g.nav.currentDirectionGraceSnapshot()?.onsetElapsedRealtimeMs)
    }

    @Test fun anAudioReplacementCannotDiscardAQueuedMeasuredReversal() {
        val f = Fixture(); f.startPlaying(); f.sample(1_100L, 31.0)
        f.g.now = 1_200L; f.ui.removeAt(0).run()
        assertNotNull(f.g.nav.currentDirectionGraceSnapshot()?.retainedObservedAtElapsedRealtimeMs)
        val playing = f.g.sent.single()
        f.sample(1_250L, 3.0, 270.0)
        assertTrue("The actual measured reversal is queued before UI delivery", f.session.invalidationPending)
        assertEquals(1, f.ui.size)
        f.feedbackGeneration++
        f.session.bindCompassObservationOwner()
        assertNull("A measured reversal retires the retained direction across audio replacement",
            f.g.nav.currentDirectionGraceSnapshot()?.retainedObservedAtElapsedRealtimeMs)
        f.ui.removeAt(0).run()
        f.sample(1_300L, 31.0)
        f.scheduled.removeAt(0).second.run()
        assertFalse(playing.current())
        playing.done()
        assertTrue(f.g.lastResults.isEmpty())
        assertNull("BAD cannot reuse the pre-reversal direction",
            f.g.nav.currentDirectionGraceSnapshot()?.retainedObservedAtElapsedRealtimeMs)
    }

    @Test fun aFreshMeasuredReversalAfterAudioRebindingStillRetiresTheRetainedDirection() {
        val f = Fixture(); f.startPlaying(); f.sample(1_100L, 31.0)
        f.g.now = 1_200L; f.ui.removeAt(0).run()
        f.feedbackGeneration++
        f.session.bindCompassObservationOwner()
        assertNotNull(f.g.nav.currentDirectionGraceSnapshot()?.retainedObservedAtElapsedRealtimeMs)
        f.sample(1_250L, 3.0, 270.0)
        assertTrue("The actual same-sensor reversal remains hard after audio replacement", f.session.invalidationPending)
        f.ui.removeAt(0).run()
        assertEquals("The qualified new direction replaces the pre-reversal 1000ms evidence", 1_250L,
            f.g.nav.currentDirectionGraceSnapshot()?.retainedObservedAtElapsedRealtimeMs)
        assertNull(f.g.nav.currentDirectionGraceSnapshot()?.onsetElapsedRealtimeMs)
        assertFalse("The old START guard remains retired after new direction acceptance", f.g.sent.first().current())
        f.g.sent.first().done()
        assertTrue(f.g.lastResults.isEmpty())
    }

    @Test fun aRealCollectionReplacementStillRetiresTheOldSensorEpisode() {
        val f = Fixture(); f.startPlaying(); f.sample(1_100L, 31.0)
        f.g.now = 1_200L; f.ui.removeAt(0).run()
        assertNotNull(f.g.nav.currentDirectionGraceSnapshot()?.onsetElapsedRealtimeMs)
        f.sourceIdentity = Any()
        f.session.bindCompassObservationOwner()
        assertNull(f.g.nav.currentDirectionGraceSnapshot()?.onsetElapsedRealtimeMs)
        assertNull(f.g.nav.currentDirectionGraceSnapshot()?.retainedObservedAtElapsedRealtimeMs)
        assertFalse(f.g.sent.single().current())
    }

    @Test fun normalSamplesKeepOnePeriodicEvaluationAndNeverRenewGpsTime() {
        val f = Fixture(); f.startPlaying()
        for (at in listOf(1_020L, 1_040L, 1_070L)) f.sample(at, 3.0)
        assertTrue(f.ui.isEmpty())
        assertEquals(1, f.scheduled.size)
        assertEquals(1_300L, f.scheduled.single().first)
        assertTrue(f.evaluations.isEmpty())
        f.g.now = 1_300L; f.scheduled.removeAt(0).second.run()
        assertEquals(listOf(1_300L), f.evaluations)
        assertNotNull(f.g.nav.currentAcceptedRouteMatchFor(1_000L))
        assertNull(f.g.nav.currentAcceptedRouteMatchFor(1_300L))
    }

    @Test fun softPrecisionLossIsKeptBeforeCoalescedGoodWithoutCancellingPlaying() {
        val f = Fixture(); f.startPlaying()
        val playing = f.g.sent.single(); assertTrue(playing.current())
        f.sample(1_100L, 31.0)
        assertNotNull(f.g.heading.observation)
        assertFalse(f.session.invalidationPending)
        assertEquals(0L, f.session.revision)
        assertTrue("The queue watchdog establishes the exact soft GRACE grant before UI delivery", playing.current())
        f.sample(1_150L, 3.0)
        assertEquals(1, f.ui.size)
        f.g.now = 1_200L; f.ui.removeAt(0).run()
        assertFalse(f.g.events.toString(), f.g.events.contains("cancel"))
        assertTrue(f.g.events.contains("fault:1100"))
        assertFalse(f.session.invalidationPending)
        assertFalse(f.g.nav.isDirectionRecheckPending())
        assertNotNull(f.g.nav.currentAcceptedRouteMatchFor(1_000L))
        assertNull(f.g.nav.currentAcceptedRouteMatchFor(1_200L))
    }

    @Test fun anOldQueuedBadCannotAnchorTheNewRoutesRecheck() {
        val f = Fixture(); f.startPlaying(); f.sample(1_100L, 31.0)
        assertEquals(1, f.ui.size)
        f.g.requestGeneration++; f.g.now = 1_200L; f.g.install()
        f.g.heading = good(1_200L)
        f.g.nav.update(f.g.fix.copy(elapsedRealtimeMs = 1_200L), 1_200L, false, facingObservation = f.g.heading.observation)
        f.session.bindCompassObservationOwner()
        f.ui.removeAt(0).run()
        assertTrue(f.g.events.isEmpty())
        assertFalse(f.g.nav.isDirectionRecheckPending())
        assertNotNull(f.g.nav.currentAcceptedRouteMatchFor(1_200L))
    }

    @Test fun duplicateSensorSampleDoesNotReevaluateOrExtendTheFirstDeadline() {
        val f = Fixture(); f.g.install(); f.g.heading = RouteCompassHeadingResult(null, RouteCompassHeadingReason.HEADING_ACCURACY)
        f.g.nav.update(f.g.fix, 1_000L, false, facingObservation = null)
        var evaluations = 0
        fun schedule(at: Long) = f.session.scheduleAdmittedRouteCompassUpdate(at,
            isCurrent = { true }, evaluate = { evaluations++; f.g.nav.retryGuidance(f.g.now, null) },
            postDelayed = { task, delay -> f.scheduled += f.g.now + delay to task; true },
            remove = { task -> f.scheduled.removeAll { it.second === task } })
        f.g.now = 1_100L; schedule(1_100_000_000L)
        assertEquals(1, evaluations)
        f.g.now = 1_200L; schedule(1_100_000_000L)
        assertEquals(1, evaluations)
        assertNull(f.g.nav.retryGuidance(2_499L, null)?.instruction)
        assertNotNull(f.g.nav.retryGuidance(2_500L, null)?.instruction)
    }

    @Test fun confirmedTwentyDegreeSamplesPreserveTheSamePlayingPacketAndQueuedStart() {
        val f = Fixture(); f.startPlaying()
        val playing = f.g.sent.single(); assertTrue(playing.current())
        f.sample(1_100L, 20.0)
        assertNotNull(f.g.heading.observation)
        assertFalse(f.session.invalidationPending)
        assertEquals(1, f.ui.size)
        assertTrue(f.g.events.isEmpty())
        assertTrue("The explicit GRACE keeps the original packet available before UI delivery", playing.current())
        f.sample(1_150L, 20.0)
        assertEquals(1, f.ui.size)
        f.g.now = 1_200L; f.ui.removeAt(0).run()
        assertFalse(f.g.events.toString(), f.g.events.contains("cancel"))
        assertTrue(f.g.events.contains("fault:1100"))
        assertEquals(1, f.g.sent.size)
        assertTrue(playing === f.g.sent.single())
        assertTrue(playing.current())
        assertFalse(f.g.nav.isDirectionRecheckPending())
        assertEquals(1_000L, f.g.fix.elapsedRealtimeMs)
        assertNotNull(f.g.nav.currentAcceptedRouteMatchFor(1_000L))
        assertNull(f.g.nav.currentAcceptedRouteMatchFor(1_200L))
        playing.done()
        assertFalse(playing.current())
    }

    private class Fixture {
        val g = GuidanceFixture()
        val ui = mutableListOf<Runnable>()
        val scheduled = mutableListOf<Pair<Long, Runnable>>()
        val evaluations = mutableListOf<Long>()
        var sourceIdentity = Any()
        var feedbackGeneration = 1
        val publisher = RouteCompassObservationPublisher().apply { reset(1) }
        var listener: ((RouteCompassObservationEvent) -> Unit)? = null
        val session = RouteCompassSession(g.run, object : RouteCompassSession.Source {
            override fun captureOwner() = RouteCompassSession.CompassObservationOwner(sourceIdentity, g.epoch,
                g.requestGeneration, feedbackGeneration, g.nav.currentTactileRouteProgress().routeVersion)
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
        fun sample(at: Long, accuracy: Double, degrees: Double = 90.0) {
            g.now = at; g.heading = good(at).copy(observation = RouteFacingObservation(degrees, accuracy, at))
            listener!!.invoke(requireNotNull(publisher.sample(1, RouteCompassStream.ROTATION_VECTOR,
                at * 1_000_000L, at * 1_000_000L, g.heading)))
        }
    }
    companion object {
        private fun good(at: Long) = RouteCompassHeadingResult(RouteFacingObservation(90.0, 3.0, at), RouteCompassHeadingReason.VALID)
    }
}
