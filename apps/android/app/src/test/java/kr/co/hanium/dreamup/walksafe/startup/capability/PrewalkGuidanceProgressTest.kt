package kr.co.hanium.dreamup.walksafe.startup.capability

import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Actual progress/post/retirement state; View drawing and TalkBack are controlled. */
class PrewalkGuidanceProgressTest {
    @Test fun nativeContinuationRunsBeforeInactiveCheckAndNoFocusIsInvented() {
        val f = Fixture(); f.active = false
        f.progress.updatePrewalkGuidance(f.epoch, true, true)
        assertEquals(listOf("continue"), f.events)
        assertTrue(f.mount.posted.isEmpty()); assertTrue(f.start.posted.isEmpty())
    }
    @Test fun actualReadyClaimSchedulesOriginalGraceOnceAndFocusAdvancesOnlyOnce() {
        val f = Fixture()
        f.progress.updatePrewalkGuidance(f.epoch, true, false)
        f.progress.updatePrewalkGuidance(f.epoch, true, false)
        assertEquals(1, f.mount.posted.size)
        f.mount.posted.removeAt(0)(); assertEquals(1, f.mount.focused)
        f.progress.updatePrewalkGuidance(f.epoch, true, true)
        f.progress.updatePrewalkGuidance(f.epoch, true, true)
        assertEquals(listOf(Triple(f.epoch, 7L, 2_000L)), f.timeouts)
        assertEquals(1, f.start.posted.size)
        f.start.posted.removeAt(0)(); assertEquals(1, f.start.focused)
    }
    @Test fun laterStageResetEpochAndHiddenTargetEachRetirePostedFocus() {
        val f = Fixture()
        f.progress.updatePrewalkGuidance(f.epoch, true, false)
        f.progress.updatePrewalkGuidance(f.epoch, true, true)
        f.mount.posted.removeAt(0)(); assertEquals(0, f.mount.focused)
        f.progress.reset(); f.start.posted.removeAt(0)(); assertEquals(0, f.start.focused)
        f.progress.updatePrewalkGuidance(f.epoch, true, false)
        f.lifecycle.handle(WalkSessionEvent.EnteredBackground)
        f.mount.posted.removeAt(0)(); assertEquals(0, f.mount.focused)
        f.lifecycle.handle(WalkSessionEvent.EnteredForeground); f.progress.reset()
        f.progress.updatePrewalkGuidance(f.epoch, true, false)
        f.mount.shown = false; f.mount.posted.removeAt(0)(); assertEquals(0, f.mount.focused)
    }
    private class Target : PrewalkGuidanceTarget {
        override var shown = true; override var enabled = true
        val posted = mutableListOf<() -> Unit>(); var focused = 0; var description = ""
        override fun describe(message: String) { description = message }
        override fun post(action: () -> Unit) { posted += action }
        override fun focus() { focused++ }
    }
    private class Fixture {
        val lifecycle = WalkSessionLifecycle({ "prewalk" }, { 1_000L }, { 1_000L }).apply { handle(WalkSessionEvent.EnteredForeground) }
        val epoch get() = lifecycle.snapshot().epoch
        var active = true; var claimed = false
        val events = mutableListOf<String>(); val timeouts = mutableListOf<Triple<WalkRuntimeEpoch, Long, Long>>()
        val mount = Target(); val start = Target()
        val progress = PrewalkGuidanceProgress(object : PrewalkGuidancePort {
            override val nativeStartPending = true
            override val preflightActive get() = active
            override val preflightGeneration = 7L
            override fun continueNativeStart() { events += "continue" }
            override fun snapshot() = lifecycle.snapshot()
            override fun claimReadyGrace(): Boolean = !claimed.also { claimed = true }
            override fun scheduleReadyTimeout(epoch: WalkRuntimeEpoch, generation: Long, delayMs: Long) { timeouts += Triple(epoch, generation, delayMs) }
            override fun mountingTarget() = mount
            override fun startupTarget() = start
        }, 2_000L)
    }
}
