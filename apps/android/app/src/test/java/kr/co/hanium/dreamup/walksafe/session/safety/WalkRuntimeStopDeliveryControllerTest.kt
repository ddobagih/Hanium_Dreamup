package kr.co.hanium.dreamup.walksafe.session.safety

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.navigation.location.FusedLocationCancellationRegistry
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class WalkRuntimeStopDeliveryControllerTest {
    @Test fun constructorDoesNotReadLifecycleLatchOrPostAnyWork() {
        val f = Fixture()
        assertTrue(f.events.isEmpty())
        assertTrue(f.queued.isEmpty())
        assertTrue(f.retryTasks.isEmpty())
    }

    @Test fun realStoppedSuccessorWaitsForBothAsyncReleasesAndAcknowledgesExactlyOnce() {
        val f = Fixture()
        val ack = mutableListOf<Boolean>()
        f.delivery.onWalkRuntimeSafetyStop(f.stop) { ack += it }
        assertEquals(WalkSessionState.SAFE_STOP, f.lifecycle.snapshot().state)
        assertNotEquals(f.stop.epoch, f.lifecycle.snapshot().epoch)
        assertTrue(ack.isEmpty())
        f.camera(true)
        assertTrue(ack.isEmpty())
        f.location(true)
        assertEquals(listOf(true), ack)
        f.camera(false)
        f.location(false)
        assertEquals(listOf(true), ack)
    }

    @Test fun synchronousFailureCannotBeHiddenBySuccessfulNativeRelease() {
        val f = Fixture()
        f.synchronousResult = false
        val ack = mutableListOf<Boolean>()
        f.delivery.onWalkRuntimeSafetyStop(f.stop) { ack += it }
        f.camera(true)
        f.location(true)
        assertEquals(listOf(false), ack)
    }

    @Test fun inlineReleaseBeforeCleanupReturnsStillWaitsForItsSynchronousResult() {
        val f = Fixture()
        f.inline = true
        val ack = mutableListOf<Boolean>()
        f.delivery.onWalkRuntimeSafetyStop(f.stop) {
            assertEquals("cleanup_return", f.events.last())
            ack += it
        }
        assertEquals(listOf(true), ack)
    }

    @Test fun retryReusesTheActualStoppedCheckpointAndNeverReplaysAnUncertainStage() {
        val f = Fixture()
        f.synchronousResult = false
        val first = mutableListOf<Boolean>()
        f.delivery.onWalkRuntimeSafetyStop(f.stop) { first += it }
        val original = f.checkpoints
        f.camera(true); f.location(true)
        f.synchronousResult = true
        val second = mutableListOf<Boolean>()
        f.delivery.onWalkRuntimeSafetyStop(f.stop) { second += it }
        assertSame(original, f.checkpoints)
        f.camera(true); f.location(true)
        assertEquals(listOf(false), first)
        assertEquals(listOf(true), second)
        assertEquals(1, f.transitions)
        var uncertain = 0
        assertFalse(original!!.runStage("non_idempotent") { uncertain++; throw IllegalStateException() })
        assertFalse(original.runStage("non_idempotent") { uncertain++ })
        assertEquals(1, uncertain)
    }

    @Test fun oldSourceCannotClaimANewWalkAndClosedOwnerRejectsQueuedDelivery() {
        val f = Fixture()
        f.lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
        f.lifecycle.handle(WalkSessionEvent.NewWalkRequested)
        assertNotEquals(f.stop.epoch, f.lifecycle.snapshot().epoch)
        val ack = mutableListOf<Boolean>()
        f.delivery.onWalkRuntimeSafetyStop(f.stop) { ack += it }
        assertEquals(listOf(false), ack)
        assertNull(f.checkpoints)
        f.delivery.close()
        f.delivery.onWalkRuntimeSafetyStop(f.stop) { ack += it }
        assertEquals(listOf(false, false), ack)
        assertNull(f.delivery.currentOwnedWalkRuntimeSafetyStopStatus())
    }

    @Test fun onlyTheCurrentRetryWrapperRunsAndCloseRemovesItBeforeAnyLateExecution() {
        val f = Fixture()
        var invoked = 0
        assertTrue(f.delivery.scheduleWalkRuntimeSafetyStopRetry(Runnable { invoked++ }))
        val old = f.retryTasks.last()
        assertTrue(f.delivery.scheduleWalkRuntimeSafetyStopRetry(Runnable { invoked += 10 }))
        old.run()
        assertEquals(0, invoked)
        val current = f.retryTasks.last()
        f.delivery.close()
        assertSame(current, f.removed.single())
        current.run()
        assertEquals(0, invoked)
        assertFalse(f.delivery.scheduleWalkRuntimeSafetyStopRetry(Runnable { invoked++ }))
    }

    @Test fun exceptionAfterActualStopTransitionAcknowledgesFailureAndKeepsTheStoppedOwner() {
        val f = Fixture()
        f.throwAfterTransition = true
        val ack = mutableListOf<Boolean>()
        f.delivery.onWalkRuntimeSafetyStop(f.stop) { ack += it }
        assertEquals(listOf(false), ack)
        assertEquals(WalkSessionState.SAFE_STOP, f.lifecycle.snapshot().state)
        f.throwAfterTransition = false
        f.delivery.onWalkRuntimeSafetyStop(f.stop) { ack += it }
        f.camera(true); f.location(true)
        assertEquals(listOf(false, true), ack)
        assertEquals(1, f.transitions)
    }

    @Test fun queuedCauseUpdateReadsTheLatestRealLatchAndNeverRerunsCleanup() {
        val f = Fixture()
        f.safety.beginEpoch(f.stop.epoch)
        f.safety.observe(WalkRuntimeSafetyObservation(f.stop.epoch, 1_000L, gpsTrusted = false))
        f.delivery.onWalkRuntimeSafetyStop(f.stop) {}
        assertEquals(1, f.cleanupCalls)
        f.delivery.onWalkRuntimeSafetyStopCausesChanged(f.stop)
        f.safety.observe(WalkRuntimeSafetyObservation(f.stop.epoch, 1_001L, cameraTrusted = false))
        assertEquals(1, f.queued.size)
        f.queued.single()()
        assertTrue(f.events.last().contains("camera_trust_lost+gps_trust_lost"))
        assertEquals(1, f.cleanupCalls)
    }

    @Test fun causesAreAlwaysQueuedAndNeverRerunCleanup() {
        val f = Fixture()
        f.delivery.onWalkRuntimeSafetyStopCausesChanged(f.stop)
        assertEquals(1, f.queued.size)
        assertEquals(0, f.cleanupCalls)
        f.queued.single()()
        assertEquals(0, f.cleanupCalls)
    }

    private class Fixture : RuntimeStopScheduler, RuntimeStopEffects {
        val events = mutableListOf<String>()
        val queued = mutableListOf<() -> Unit>()
        val retryTasks = mutableListOf<Runnable>()
        val removed = mutableListOf<Runnable>()
        private var nextWalkId = 0
        val lifecycle = WalkSessionLifecycle({ "walk-${nextWalkId++}" }, { 1_000L }, { 1_000L })
        val stop: WalkRuntimeSafetyStop
        val safety = WalkRuntimeSafetyCoordinator(thresholdProfile = null, onSafeStop = {})
        val registry = FusedLocationCancellationRegistry()
        lateinit var camera: (Boolean) -> Unit
        lateinit var location: (Boolean) -> Unit
        var checkpoints: WalkRuntimeStopCleanupCheckpoints? = null
        var transitions = 0
        var cleanupCalls = 0
        var inline = false
        var synchronousResult = true
        var throwAfterTransition = false
        val delivery: WalkRuntimeStopDeliveryController
        init {
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val p = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val c = WalkSessionReadinessCollector(lifecycle.snapshot().epoch, p)
            p.requiredRequirements.forEach { c.record(WalkSessionReadinessObservation(c.epoch, it, WalkSessionReadinessStatus.READY)) }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(c.build(1_000L)))
            lifecycle.handle(WalkSessionEvent.StartRequested(lifecycle.snapshot().confirmationToken!!))
            stop = WalkRuntimeSafetyStop(lifecycle.snapshot().epoch, setOf(WalkRuntimeSafetyStopCause.GPS_TRUST_LOST))
            delivery = WalkRuntimeStopDeliveryController(safety, registry::failLocationStopCleanupCheckpoint, { lifecycle }, this,
                RuntimeStopCleanupRunner { _, persist, cp, cam, loc ->
                    assertTrue(persist)
                    cleanupCalls++
                    checkpoints = cp
                    cp.runStage("session_transition") {
                        transitions++
                        val transition = lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
                        delivery.recordSafetyStopTransitionIfOwned(transition)
                    }
                    if (throwAfterTransition) throw IllegalStateException("controlled post-transition failure")
                    camera = cam
                    location = loc
                    if (inline) { cam(true); loc(true) }
                    events += "cleanup_return"
                    synchronousResult
                }, this)
        }
        override fun dispatchOnMain(action: () -> Unit) = action()
        override fun enqueueOnMain(action: () -> Unit): Boolean { queued += action; return true }
        override fun postRetry(action: Runnable, delayMs: Long): Boolean { assertEquals(250L, delayMs); retryTasks += action; return true }
        override fun removeRetry(action: Runnable) { removed += action }
        override fun showRestartNotice() { events += "restart" }
        override fun showCauseStatus(navigationStatus: String) { events += navigationStatus }
        override fun refreshOwnedStop() { events += "refresh" }
    }
}
