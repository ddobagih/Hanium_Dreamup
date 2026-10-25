package kr.co.hanium.dreamup.walksafe.navigation.start

import kr.co.hanium.dreamup.walksafe.device.PhoneMountingCheckRequest
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativePhoneMountingCheckRequest
import org.junit.Assert.*
import org.junit.Test

/** Actual execution owner with a deterministic event queue, not a reimplementation of Main timers. */
class NativeGuidanceCalibrationSessionTest {
    @Test fun observationStartsOnlyAfterDoneAndRetainsTheThreeSecondWindow() {
        val f = Fixture()
        assertFalse(f.owner.advance(f.request))
        f.tick(1_000)
        assertEquals(1, f.prompts.size)
        f.now = 5_000
        f.prompts.single().done()
        assertFalse(f.owner.advance(f.request))
        f.now = 7_999
        assertFalse(f.owner.advance(f.request))
        f.now = 8_000
        assertTrue(f.owner.advance(f.request))
        assertEquals("DIRECTION_CALIBRATION_READY", f.stages.last())
    }

    @Test fun failuresKeepIntentRetryAtOneThenFiveSecondsAndOldDoneCannotCompleteRetry() {
        val f = Fixture()
        f.owner.advance(f.request)
        val old = f.prompts.single()
        old.failed()
        f.tick(999)
        assertEquals(1, f.prompts.size)
        f.tick(1_000)
        assertEquals(2, f.prompts.size)
        f.prompts.last().failed()
        assertNotNull(f.failure)
        f.tick(2_000)
        assertEquals(2, f.prompts.size)
        f.tick(6_000)
        assertEquals(2, f.prompts.size)
        f.tick(7_000)
        val retry = f.prompts.last()
        assertEquals(3, f.prompts.size)
        old.done()
        assertTrue(retry.current())
        retry.done()
        assertNull(f.failure)
        f.now = 10_000
        assertTrue(f.owner.advance(f.request))
    }

    @Test fun accessCorrectionPreservesRecoveryRegistrationAndCancelRemovesBothTimers() {
        val f = Fixture()
        f.owner.advance(f.request)
        f.owner.scheduleRecovery(f.request)
        val recovery = f.queue.entries.single { it.value == 2_000L }.key
        f.permission = false
        f.owner.cancelPrompt()
        f.owner.scheduleRecovery(f.request)
        assertEquals(1, f.queue.size)
        assertTrue(f.queue.containsKey(recovery))
        f.tick(2_000)
        assertEquals(listOf("recover", "continue"), f.effects.takeLast(2))
        f.owner.scheduleRecovery(f.request)
        val stale = f.queue.keys.single()
        f.owner.cancel()
        assertTrue(f.queue.isEmpty())
        stale.run()
        assertEquals(1, f.effects.count { it == "recover" })
    }

    @Test fun cancellationNeverCancelsAReplacementCommandAndLateCallbacksCannotReviveIt() {
        val f = Fixture()
        f.owner.advance(f.request)
        val old = f.prompts.single()
        f.generation++
        f.owner.cancel()
        old.done()
        old.failed()
        assertEquals(0, f.cancelled)
        assertTrue(f.queue.isEmpty())
        f.request = f.request.copy(checkRequest = f.request.checkRequest.copy(requestId = 2))
        f.owner.advance(f.request)
        assertFalse(old.current())
        assertTrue(f.prompts.last().current())
        f.owner.cancel()
        assertEquals(1, f.cancelled)
    }

    @Test fun rejectedOrInlineTerminalSubmissionDoesNotLeaveOwnedSpeechBehind() {
        val rejected = Fixture().apply { accepted = false }
        rejected.owner.advance(rejected.request)
        assertFalse(rejected.prompts.single().current())
        rejected.owner.cancel()
        assertEquals(0, rejected.cancelled)
        val inline = Fixture().apply { inlineDone = true }
        inline.owner.advance(inline.request)
        inline.owner.cancel()
        assertEquals(0, inline.cancelled)
    }

    @Test fun staleRequestOrPermissionBlocksQueuedContinuation() {
        for (revoke in listOf<(Fixture) -> Unit>({ it.permission = false }, { it.request = it.request.copy(actorId = "new") })) {
            val f = Fixture()
            f.owner.advance(f.request)
            f.owner.scheduleRecovery(f.request)
            revoke(f)
            f.tick(1_000)
            assertFalse(f.effects.contains("continue"))
            assertFalse(f.prompts.single().current())
        }
    }

    @Test fun readinessQueryNeverAdvancesObservationAndRejectsRetiredOwnerRequestOrClock() {
        val f = Fixture()
        assertFalse(f.owner.isReady(f.request))
        assertFalse(f.owner.advance(f.request))
        f.prompts.single().done()
        f.now = 3_000L
        repeat(3) { assertFalse(f.owner.isReady(f.request)) }
        assertEquals(1, f.prompts.size)
        assertTrue(f.owner.advance(f.request))
        assertTrue(f.owner.isReady(f.request))
        assertFalse(f.owner.isReady(f.request.copy()))
        f.permission = false
        assertFalse(f.owner.isReady(f.request))
        f.permission = true
        f.now = 2_999L
        assertFalse(f.owner.isReady(f.request))
        f.now = 3_000L
        f.owner.cancel()
        assertFalse(f.owner.isReady(f.request))
    }

    private data class Prompt(val current: () -> Boolean, val done: () -> Unit, val failed: () -> Unit)
    private class Fixture {
        var now = 0L
        var request = NativePhoneMountingCheckRequest("actor", 1L, null, PhoneMountingCheckRequest(WalkRuntimeEpoch("walk", 1L), 1L, 0L))
        var permission = true
        var generation = 0L
        var accepted = true
        var inlineDone = false
        var cancelled = 0
        var failure: String? = null
        val queue = linkedMapOf<Runnable, Long>()
        val prompts = mutableListOf<Prompt>()
        val stages = mutableListOf<String>()
        val effects = mutableListOf<String>()
        val owner = NativeGuidanceCalibrationSession(
            object : NativeGuidanceCalibrationSession.Scope {
                override fun requestCurrent(request: NativePhoneMountingCheckRequest) = request === this@Fixture.request
                override fun ownerCurrent(request: NativePhoneMountingCheckRequest) = permission && requestCurrent(request)
            }, object : NativeGuidanceCalibrationSession.Speech {
                override fun pending() = false
                override fun commandGeneration() = generation
                override fun submit(message: String, current: () -> Boolean, completed: () -> Unit, failed: () -> Unit): Boolean {
                    assertEquals(GuidanceStartCalibration.PROMPT_KO, message)
                    generation++
                    prompts += Prompt(current, completed, failed)
                    if (inlineDone) completed()
                    return accepted
                }
                override fun cancel() { cancelled++ }
            }, object : NativeGuidanceCalibrationSession.Callbacks {
                override fun postDelayed(action: Runnable, delayMs: Long) { queue[action] = now + delayMs }
                override fun remove(action: Runnable) { queue.remove(action) }
            }, object : NativeGuidanceCalibrationSession.Effects {
                override fun recordStage(stage: String) { stages += stage }
                override fun reportVisibleFailure(message: String) { failure = message }
                override fun visibleFailure() = failure
                override fun clearVisibleFailure() { failure = null }
                override fun render() { effects += "render" }
                override fun continuePrewalk() { effects += "continue"; ownerAdvance() }
                override fun recoverPreparation(request: NativePhoneMountingCheckRequest) { effects += "recover" }
            }, { now },
        )
        private fun ownerAdvance() { owner.advance(request) }
        fun tick(at: Long) {
            now = at
            val due = queue.filterValues { it <= now }.keys.toList()
            due.forEach { queue.remove(it); it.run() }
        }
    }
}
