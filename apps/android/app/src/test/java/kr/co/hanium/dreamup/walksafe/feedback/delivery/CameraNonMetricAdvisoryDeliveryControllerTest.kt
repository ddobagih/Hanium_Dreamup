package kr.co.hanium.dreamup.walksafe.feedback.delivery

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.feedback.AndroidNonMetricObstacleAdvisoryPolicy
import kr.co.hanium.dreamup.walksafe.feedback.NonMetricAdvisoryGate
import kr.co.hanium.dreamup.walksafe.feedback.NonMetricObstacleAdvisoryAction
import kr.co.hanium.dreamup.walksafe.feedback.NonMetricObstacleAdvisoryConfig
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Actual reservation/claim/terminal policy; only live observations and speech/platform effects vary. */
class CameraNonMetricAdvisoryDeliveryControllerTest {
    @Test fun constructionQueriesNoClockAuthoritySpeechLockOrUi() {
        val f = Fixture()
        assertTrue(f.trace.isEmpty())
        assertEquals(0, f.speechCalls)
    }

    @Test fun dispatchClaimsAndRechecksBeforeSpeechAndActualDonePostsToUiBeforeConfirmation() {
        val f = Fixture(); val action = f.offer()
        f.dispatch(action)
        assertEquals(listOf("clock", "start", "owner:false", "clock", "start", "owner:false", "speech"), f.trace)
        assertTrue(f.policy.hasClaimedDelivery(action))
        assertTrue(f.current())
        f.delivered()
        assertEquals(0, f.records.size)
        assertTrue(f.policy.hasClaimedDelivery(action))
        f.runUi()
        assertEquals(listOf(action to f.now), f.records)
        assertEquals(listOf("ui", "clock", "lock", "owner:true", "mount:true", "record"), f.trace.takeLast(6))
        assertFalse(f.policy.hasClaimedDelivery(action))
    }

    @Test fun startExpiryStopsBeforeAnyAuthorityReadAndDoesNotConsumeRepeatInterval() {
        val f = Fixture(); val action = f.offer(); f.now = action.validUntilMs + 1
        f.dispatch(action)
        assertEquals(listOf("clock"), f.trace)
        assertEquals(0, f.speechCalls)
        assertFalse(f.policy.isDeliveryCurrent(action))
        assertNotNull(f.offer())
    }

    @Test fun voiceOrRawStartRejectionShortCircuitsOwnerBeforeClaim() {
        val f = Fixture(); val action = f.offer(); f.startAllowed = false
        f.dispatch(action)
        assertEquals(listOf("clock", "start"), f.trace)
        assertFalse(f.policy.hasClaimedDelivery(action))
        assertFalse(f.policy.isDeliveryCurrent(action))
    }

    @Test fun ownerLossBeforeClaimOrAfterClaimRejectsWithoutSpeech() {
        listOf(1, 2).forEach { failAt ->
            val f = Fixture(); val action = f.offer()
            f.ownerHook = { count -> count != failAt }
            f.dispatch(action)
            assertEquals(failAt, f.ownerCalls)
            assertEquals(0, f.speechCalls)
            assertFalse(f.policy.hasClaimedDelivery(action))
            assertFalse(f.policy.isDeliveryCurrent(action))
            assertNotNull(f.offer())
        }
    }

    @Test fun sourceLossDuringPostClaimRecheckRejectsBeforeSpeech() {
        val f = Fixture(); val action = f.offer()
        f.ownerHook = { count -> if (count == 2) f.observe(emptyList()); true }
        f.dispatch(action)
        assertEquals(0, f.speechCalls)
        assertFalse(f.policy.hasClaimedDelivery(action))
    }

    @Test fun nativeStartCurrentnessRemainsLiveAndReturningSourceCannotReviveOldStart() {
        val f = Fixture(); val action = f.offer(); f.dispatch(action)
        assertTrue(f.current())
        f.observe(emptyList())
        assertFalse(f.current())
        assertNull(f.observe())
        assertFalse(f.current())
        f.failed()
        assertNotNull(f.offer())
    }

    @Test fun actualDoneAfterExpiryAndSourceLossStillConfirmsOnceAndConsumesCooldown() {
        val f = Fixture(); val action = f.offer(); f.dispatch(action)
        f.observe(emptyList()); f.now = action.validUntilMs + 2_000
        assertFalse(f.current())
        f.delivered(); f.runUi()
        assertEquals(1, f.records.size)
        assertNull(f.observe())
        f.delivered(); f.failed(); f.runUi()
        assertEquals(1, f.records.size)
        f.now += 7_999L
        assertNull(f.observe())
        f.now += 1L
        assertNotNull(f.offer())
    }

    @Test fun queuedDoneRechecksEpochFallbackAndLifecycleAtUiExecution() {
        val mutations: List<(Fixture) -> Unit> = listOf(
            { it.epoch = WalkRuntimeEpoch("replacement", 2L) },
            { it.fallbackGeneration += 1 },
            { it.lifecycleGeneration += 1 },
        )
        mutations.forEach { mutate ->
            val f = Fixture(); val action = f.offer(); f.dispatch(action); f.delivered()
            mutate(f); f.runUi()
            assertTrue(f.records.isEmpty())
            assertFalse(f.policy.hasClaimedDelivery(action))
            assertFalse(f.trace.contains("mount:true"))
        }
    }

    @Test fun completionUsesExactMountingLockAndShortCircuitsMountingAfterStaleOwner() {
        val f = Fixture(); val action = f.offer(); assertTrue(f.policy.claimDelivery(action))
        assertFalse(f.controller.complete(action, f.now) {
            assertTrue(Thread.holdsLock(f.lock)); false
        })
        assertEquals(listOf("lock"), f.trace)
        val replacement = f.offer(); assertTrue(f.policy.claimDelivery(replacement))
        f.mountingAllowed = false
        assertFalse(f.controller.complete(replacement, f.now) {
            assertTrue(Thread.holdsLock(f.lock)); true
        })
        assertEquals("mount:true", f.trace.last())
        assertFalse(f.policy.hasClaimedDelivery(replacement))
    }

    @Test fun lateOldTerminalCannotRetireOrConfirmReplacementReservation() {
        val f = Fixture(); val old = f.offer(); f.dispatch(old)
        val oldDone = f.delivered; val oldFailure = f.failed
        f.policy.rejectDelivery(old)
        val replacement = f.offer(); f.dispatch(replacement)
        oldFailure(); oldDone(); f.runUi()
        assertTrue(f.policy.isDeliveryCurrent(replacement))
        assertTrue(f.records.isEmpty())
        f.delivered(); f.runUi()
        assertEquals(listOf(replacement to f.now), f.records)
    }

    @Test fun synchronousFailureAndFalseAcceptanceLeaveNoClaimOrCooldown() {
        listOf(false, true).forEach { callback ->
            val f = Fixture(); val action = f.offer(); f.acceptSpeech = false
            f.onSpeak = { if (callback) f.failed() }
            f.dispatch(action)
            assertFalse(f.policy.hasClaimedDelivery(action))
            assertTrue(f.records.isEmpty())
            assertNotNull(f.offer())
        }
    }

    @Test fun policyResetRetiresDoneAndFailedCallbacksWithoutNewStateInDelivery() {
        val f = Fixture(); val action = f.offer(); f.dispatch(action)
        f.policy.reset(); f.delivered(); f.failed(); f.runUi()
        assertTrue(f.records.isEmpty())
        assertFalse(f.policy.hasClaimedDelivery(action))
        assertNotNull(f.offer())
    }

    private class Fixture {
        val trace = mutableListOf<String>()
        val lock = Any()
        val policy = AndroidNonMetricObstacleAdvisoryPolicy(NonMetricObstacleAdvisoryConfig(minStableFrames = 1, minStableMs = 0))
        private val gate = NonMetricAdvisoryGate(true, true, true, true, false)
        private val obstacle = DetectionCandidate("person", 0.9f, RectNorm(0.4f, 0.4f, 0.2f, 0.5f))
        private var frame = 0L
        var now = 1_000L
        var epoch = WalkRuntimeEpoch("walk", 1L)
        var fallbackGeneration = 2
        var lifecycleGeneration = 3
        var startAllowed = true
        var mountingAllowed = true
        var ownerCalls = 0
        var ownerHook: (Int) -> Boolean = { true }
        var acceptSpeech = true
        var speechCalls = 0
        var onSpeak: () -> Unit = {}
        lateinit var current: () -> Boolean
        lateinit var delivered: () -> Unit
        lateinit var failed: () -> Unit
        val records = mutableListOf<Pair<NonMetricObstacleAdvisoryAction, Long>>()
        private val ui = mutableListOf<() -> Unit>()
        val controller = CameraNonMetricAdvisoryDeliveryController(
            policy,
            object : CameraAdvisoryDeliveryAuthority {
                override fun newOutputAllowed(nowMs: Long): Boolean { trace += "start"; assertEquals(now, nowMs); return startAllowed }
                override fun ownerCurrent(epoch: WalkRuntimeEpoch, fallbackGeneration: Int, lifecycleGeneration: Int, nowMs: Long): Boolean {
                    trace += "owner:${Thread.holdsLock(lock)}"
                    ownerCalls += 1
                    return ownerHook(ownerCalls) && epoch == this@Fixture.epoch &&
                        fallbackGeneration == this@Fixture.fallbackGeneration && lifecycleGeneration == this@Fixture.lifecycleGeneration
                }
                override fun mountingAllowed(): Boolean { trace += "mount:${Thread.holdsLock(lock)}"; return this@Fixture.mountingAllowed }
            },
            CameraAdvisorySpeech { _, _, current, delivered, failed ->
                trace += "speech"; speechCalls += 1
                this.current = current; this.delivered = delivered; this.failed = failed
                onSpeak(); acceptSpeech
            },
            mountingLock = { trace += "lock"; lock },
            elapsedRealtimeMs = { trace += "clock"; now },
            onUiThread = { trace += "ui"; ui += it },
            recordDelivered = { action, now -> trace += "record"; assertFalse(policy.hasClaimedDelivery(action)); records += action to now },
        )
        fun observe(detections: List<DetectionCandidate> = listOf(obstacle)) = policy.evaluate(detections, gate, ++frame, now)
        fun offer() = requireNotNull(observe())
        fun dispatch(action: NonMetricObstacleAdvisoryAction) = controller.dispatch(action, epoch, fallbackGeneration, lifecycleGeneration)
        fun runUi() { while (ui.isNotEmpty()) ui.removeAt(0).invoke() }
    }
}
