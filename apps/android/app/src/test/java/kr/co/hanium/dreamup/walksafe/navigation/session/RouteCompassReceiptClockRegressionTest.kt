package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.*
import org.junit.Assert.*
import org.junit.Test

/** Actual publisher/session/controller/Navigator; receipt, sample and evaluation clocks remain distinct. */
class RouteCompassReceiptClockRegressionTest {
    @Test fun delayedGoodReceiptBehindGpsMustKeepThePlayingCue() {
        val f = Fixture(); f.startPlaying()
        val playing = f.g.sent.single(); assertTrue(playing.current())
        val event = f.event(1_499L, 1_500L, 90.0)
        f.g.now = 1_501L; f.g.heading = good(1_501L)
        f.g.controller.updateRouteGuidance(f.g.fix.copy(elapsedRealtimeMs = 1_501L))
        assertNotNull(f.g.nav.currentAcceptedRouteMatchFor(1_501L))
        assertTrue(playing.current())
        f.g.now = 1_502L
        assertFalse("The same original sample is not a reversal at the actual current query time",
            f.g.nav.hasHardInvalidatedDirectionClaim(f.g.now, event.heading.observation))
        val oldReceiptHard = f.g.nav.hasHardInvalidatedDirectionClaim(event.receivedAtMs, event.heading.observation)
        f.listener!!.invoke(event)
        val invalidationPending = f.session.invalidationPending
        while (f.ui.isNotEmpty()) f.ui.removeAt(0).run()
        println("delayed-good sample=${requireNotNull(event.sensorTimestampNanos)/1_000_000} receipt=${event.receivedAtMs} gps=1501 query=${f.g.now} oldReceiptHard=$oldReceiptHard pending=$invalidationPending events=${f.g.events} current=${playing.current()}")
        assertEquals(1_499L, event.heading.observation!!.observedAtElapsedRealtimeMs)
        assertEquals(1_500L, event.receivedAtMs)
        assertFalse("A delayed GOOD receipt must not cancel playing speech merely because GPS advanced by 1ms", f.g.events.contains("cancel"))
        assertFalse(invalidationPending)
        assertTrue(playing.current())
        playing.done(); assertEquals(listOf(playing.message), f.g.lastResults)
    }

    @Test fun sameGoodReceiptBeforeGpsAdvanceKeepsPlayingAndDone() {
        val f = Fixture(); f.startPlaying(); val playing = f.g.sent.single()
        val event = f.event(1_499L, 1_500L, 90.0)
        f.g.now = 1_502L; f.g.heading = event.heading
        f.listener!!.invoke(event)
        assertFalse(f.session.invalidationPending); assertTrue(f.ui.isEmpty())
        assertFalse(f.g.events.contains("cancel")); assertTrue(playing.current())
        playing.done(); assertEquals(listOf(playing.message), f.g.lastResults)
    }

    @Test fun freshMeasuredReversalStillCancelsAndRejectsOldDone() {
        val f = Fixture(); f.startPlaying(); val playing = f.g.sent.single()
        val event = f.event(1_499L, 1_500L, 270.0)
        f.g.now = 1_502L; f.g.heading = event.heading
        f.listener!!.invoke(event)
        assertTrue(f.session.invalidationPending)
        assertFalse(playing.current())
        while (f.ui.isNotEmpty()) f.ui.removeAt(0).run()
        assertTrue("A genuine fresh 180 degree reversal must still cancel", f.g.events.contains("cancel"))
        playing.done(); assertTrue(f.g.lastResults.isEmpty())
        assertEquals(1_499L, event.heading.observation!!.observedAtElapsedRealtimeMs)
    }

    @Test fun futureRawSampleCannotBeAdmittedByAReceiptOrQueryClock() {
        val f = Fixture(); f.startPlaying(); val playing = f.g.sent.single()
        f.g.now = 1_502L
        val priorGood = f.event(1_499L, 1_500L, 90.0)
        f.g.heading = priorGood.heading
        f.listener!!.invoke(priorGood)
        assertTrue("A separate admitted past GOOD supports the existing cue before the future sample", playing.current())
        val invalid = f.publisher.sample(1, RouteCompassStream.ROTATION_VECTOR,
            1_501_000_000L, 1_500_000_000L,
            RouteCompassHeadingResult(RouteFacingObservation(270.0, 3.0, 1_501L), RouteCompassHeadingReason.VALID))
        assertNull("Original sample later than receipt stays invalid even when current query is later", invalid)
        assertFalse(f.session.invalidationPending); assertTrue(f.ui.isEmpty())
        assertTrue(playing.current()); playing.done(); assertEquals(listOf(playing.message), f.g.lastResults)
    }


    @Test fun aGpsUpdateWhileTheSensorWaitsForNavigatorCannotMakeItsQueryRegress() {
        val f = Fixture(); f.startPlaying(); val playing = f.g.sent.single()
        val event = f.event(1_499L, 1_500L, 90.0)
        val admitted = java.util.concurrent.CountDownLatch(1)
        val classify = java.util.concurrent.CountDownLatch(1)
        val queryTimes = mutableListOf<Long>()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        f.queryClock = {
            assertTrue("The query clock must be read after acquiring the Navigator monitor", Thread.holdsLock(f.g.nav))
            f.g.now.also { queryTimes += it }
        }
        val sensor = Thread {
            try {
                f.session.onRouteCompassObservation(event, trackerCurrent = {
                    admitted.countDown()
                    assertTrue(classify.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    true
                })
            } catch (error: Throwable) { failure.set(error) }
        }
        sensor.start()
        assertTrue("The event passed the current owner check", admitted.await(2, java.util.concurrent.TimeUnit.SECONDS))
        synchronized(f.g.nav) {
            classify.countDown()
            val waitUntil = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (sensor.state != Thread.State.BLOCKED && sensor.isAlive && System.nanoTime() < waitUntil) Thread.yield()
            assertEquals("The sensor is waiting for the actual Navigator monitor", Thread.State.BLOCKED, sensor.state)
            f.g.now = 1_501L; f.g.heading = good(1_501L)
            f.g.controller.updateRouteGuidance(f.g.fix.copy(elapsedRealtimeMs = 1_501L))
            assertNotNull(f.g.nav.currentAcceptedRouteMatchFor(1_501L))
            f.g.now = 1_502L
        }
        sensor.join(2_000L)
        assertFalse("The classifier must leave both monitors", sensor.isAlive)
        failure.get()?.let { throw it }
        assertEquals(listOf(1_502L), queryTimes)
        assertFalse(f.session.invalidationPending); assertTrue(f.ui.isEmpty())
        assertFalse(f.g.events.contains("cancel")); assertTrue(playing.current())
        playing.done(); assertEquals(listOf(playing.message), f.g.lastResults)
    }

    @Test fun delayedFreshReversalBehindGpsStillCancelsTheOriginalCue() {
        val f = Fixture(); f.startPlaying(); val playing = f.g.sent.single()
        val event = f.event(1_499L, 1_500L, 270.0)
        f.g.now = 1_501L; f.g.heading = good(1_501L)
        f.g.controller.updateRouteGuidance(f.g.fix.copy(elapsedRealtimeMs = 1_501L))
        f.g.now = 1_502L
        f.listener!!.invoke(event)
        assertTrue(f.session.invalidationPending)
        while (f.ui.isNotEmpty()) f.ui.removeAt(0).run()
        assertTrue("A fresh measured reversal cannot be discarded merely because its delivery was late", f.g.events.contains("cancel"))
        assertFalse(playing.current()); playing.done(); assertTrue(f.g.lastResults.isEmpty())
        assertEquals(1_499L, event.heading.observation!!.observedAtElapsedRealtimeMs)
        assertEquals(1_500L, event.receivedAtMs)
    }

    @Test fun delayedSoftFaultKeepsItsOriginalReceiptAndGraceDeadline() {
        val f = Fixture(); f.startPlaying(); val playing = f.g.sent.single()
        f.g.now = 1_400L; f.g.heading = good(1_400L)
        f.g.controller.updateRouteGuidance(f.g.fix.copy(elapsedRealtimeMs = 1_400L))
        val bad = RouteCompassHeadingResult(RouteFacingObservation(90.0, 31.0, 1_499L), RouteCompassHeadingReason.VALID)
        val event = requireNotNull(f.publisher.sample(1, RouteCompassStream.ROTATION_VECTOR,
            1_499_000_000L, 1_500_000_000L, bad))
        f.g.now = 1_600L; f.g.heading = bad
        f.listener!!.invoke(event)
        assertFalse(f.session.invalidationPending)
        f.g.now = 1_700L
        while (f.ui.isNotEmpty()) f.ui.removeAt(0).run()
        val grace = requireNotNull(f.g.nav.currentDirectionGraceSnapshot())
        assertEquals("Queue delay cannot renew the original BAD receipt", 1_500L, grace.onsetElapsedRealtimeMs)
        assertEquals(3_000L, grace.deadlineElapsedRealtimeMs)
        assertEquals(1_499L, event.heading.observation!!.observedAtElapsedRealtimeMs)
        assertEquals(1_500L, event.receivedAtMs)
        assertTrue(f.g.events.contains("fault:1500")); assertFalse(f.g.events.contains("cancel"))
        assertTrue(playing.current())
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
        var queryClock: () -> Long = { g.now }
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
        }, g.diagnostics, { queryClock() })
        init { g.compassPeer = session }
        fun event(sampleAt: Long, receivedAt: Long, degrees: Double): RouteCompassObservationEvent =
            requireNotNull(publisher.sample(1, RouteCompassStream.ROTATION_VECTOR,
                sampleAt * 1_000_000L, receivedAt * 1_000_000L,
                RouteCompassHeadingResult(RouteFacingObservation(degrees, 3.0, sampleAt), RouteCompassHeadingReason.VALID)))
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
