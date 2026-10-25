package kr.co.hanium.dreamup.walksafe.voice.composition

import kr.co.hanium.dreamup.walksafe.diagnostics.*
import kr.co.hanium.dreamup.walksafe.voice.*
import kr.co.hanium.dreamup.walksafe.voice.output.*
import org.junit.Assert.*
import org.junit.Test

class VoicePresentationCallbacksTest {
    @Test fun constructionDoesNotReadViewsClockThreadOrReadinessFactory() {
        val f = Fixture(); assertTrue(f.events.isEmpty()); assertEquals(0, f.readinessCreated)
    }

    @Test fun readinessHandlerFactoryIsLazyAndSharedByOriginalPostRemovePoints() {
        val f = Fixture(); val task = Runnable {}
        f.callbacks.postDeferred(task, 2); assertEquals(0, f.readinessCreated)
        f.callbacks.postReadiness(task, 3); f.callbacks.removeReadiness(task); f.callbacks.postReadiness(task, 4)
        assertEquals(1, f.readinessCreated)
        assertEquals(listOf("retired.delayed:2", "readiness.delayed:3", "readiness.remove", "readiness.delayed:4"), f.events)
    }

    @Test fun readinessRemovalBeforeFirstPostRetainsOriginalLazyCreation() {
        val f = Fixture(); f.callbacks.removeReadiness(Runnable {})
        assertEquals(1, f.readinessCreated); assertEquals(listOf("readiness.remove"), f.events)
    }

    @Test fun outputRetirementUsesImmediateQueuePostWhileDialogAndInputUseDelayedPost() {
        val f = Fixture(); val done = mutableListOf<String>()
        f.callbacks.postRetired { done += "retired" }
        f.callbacks.postDialogExpiry({ done += "dialog" }, 10)
        f.callbacks.postDeferred(Runnable { done += "input" }, 20)
        assertEquals(listOf("retired.post", "retired.delayed:10", "retired.delayed:20"), f.events)
        assertTrue(done.isEmpty()); f.retired.tasks.forEach(Runnable::run)
        assertEquals(listOf("retired", "dialog", "input"), done)
    }

    @Test fun interactionRemovalChecksPublicationButRestartAndTerminalKeepOriginalUnguardedRemove() {
        val f = Fixture(); f.initialized = false; val task = Runnable {}
        f.callbacks.removeInteraction(task)
        assertEquals(listOf("view.initialized"), f.events)
        f.events.clear(); f.callbacks.removeRestart(task); f.callbacks.removeTerminal(task)
        assertEquals(listOf("view.remove", "view.remove"), f.events)
    }

    @Test fun taskAndDelayIdentityIsPreservedAcrossAllStatusViewProducers() {
        val f = Fixture(); val task = Runnable {}
        f.callbacks.postRestart(task, 7); f.callbacks.postTerminal(task, 8); f.callbacks.postInteraction(task, 9)
        assertEquals(listOf(7L, 8L, 9L), f.statusDelays)
        assertTrue(f.statusTasks.all { it === task })
        assertEquals(0, f.readinessCreated)
    }

    @Test fun sameThreadDispatcherAndClockAreReadOnlyWhenRequested() {
        val f = Fixture(); var ran = false
        assertEquals(42L, f.callbacks.nowMs()); assertEquals(42L, f.callbacks.elapsedRealtimeMs())
        assertTrue(f.callbacks.isMainThread()); f.callbacks.runOnMain { ran = true }
        assertTrue(ran); assertEquals(listOf("clock", "clock", "main", "ui"), f.events)
    }

    @Test fun replacedAccessibilityViewRejectsLateAnnouncementBeforeWritingEitherTarget() {
        val f = Fixture(); val first = f.target; val lease = f.presentation.accessibilityTargetOrNull()!!
        f.target = TextTarget(); assertFalse(lease.presentIfCurrent("늦은 출력", SpeechAnnouncementPriority.RISK))
        assertNull(first.text); assertNull(f.target.text)
    }

    @Test fun hiddenAccessibilityViewRejectsSameIdentityWithoutChangingItsText() {
        val f = Fixture(); val lease = f.presentation.accessibilityTargetOrNull()!!
        f.target.visible = false; assertFalse(lease.presentIfCurrent("안내", SpeechAnnouncementPriority.INTERACTION))
        assertNull(f.target.text)
    }

    @Test fun exactCurrentAccessibilityTargetReceivesOriginalPriorityAndText() {
        val f = Fixture(); val lease = f.presentation.accessibilityTargetOrNull()!!
        assertTrue(lease.presentIfCurrent("위험", SpeechAnnouncementPriority.RISK))
        assertEquals("위험", f.target.text); assertTrue(f.target.assertive)
        assertTrue(lease.presentIfCurrent("안내", SpeechAnnouncementPriority.INTERACTION)); assertFalse(f.target.assertive)
    }

    @Test fun lastResultPublishesDescriptionOnlyWhenDisplayedLineChanges() {
        val f = Fixture()
        f.presentation.showLastResult("안내")
        assertEquals("마지막 안내: 안내", f.target.text); assertEquals(f.target.text, f.target.contentDescription)
        f.target.contentDescription = "다른 설명"
        f.presentation.showLastResult("안내")
        assertEquals("다른 설명", f.target.contentDescription)
    }

    @Test fun terminalDiagnosticsKeepDoneSeparateFromFailedOutput() {
        val f = Fixture(); f.presentation.commandTerminalDiagnostic(true); f.presentation.commandTerminalDiagnostic(false)
        assertEquals(listOf(VoiceInputDiagnosticEvent.OUTPUT_DONE, VoiceInputDiagnosticEvent.OUTPUT_FAILED), f.diagnostics)
    }

    private class Fixture {
        val events = mutableListOf<String>(); val diagnostics = mutableListOf<VoiceInputDiagnosticEvent>()
        var initialized = true; var readinessCreated = 0; var target = TextTarget()
        val statusTasks = mutableListOf<Runnable>(); val statusDelays = mutableListOf<Long>()
        val retired = Queue("retired", events); val readiness = Queue("readiness", events)
        val callbacks = AndroidVoiceCallbacks(object : VoiceViewCallbackTarget {
            override fun initialized(): Boolean { events += "view.initialized"; return initialized }
            override fun post(task: Runnable, delayMs: Long) { events += "view.post"; statusTasks += task; statusDelays += delayMs }
            override fun remove(task: Runnable) { events += "view.remove" }
        }, retired, { events += "ui"; it() }, { events += "clock"; 42 }, { events += "main"; true },
            readinessFactory = { readinessCreated++; readiness })
        val presentation = AndroidSpeechPresentation(noop(), { error("input not requested") },
            { target }, { target }, callbacks, { diagnostics += it })
    }
    private class Queue(private val name: String, private val events: MutableList<String>) : VoiceCallbackQueue {
        val tasks = mutableListOf<Runnable>()
        override fun post(task: Runnable) { events += "$name.post"; tasks += task }
        override fun postDelayed(task: Runnable, delayMs: Long) { events += "$name.delayed:$delayMs"; tasks += task }
        override fun remove(task: Runnable) { events += "$name.remove"; tasks.removeAll { it === task } }
    }
    private class TextTarget : VoiceTextTarget {
        override val identity = Any()
        override var text: CharSequence? = null
        override var contentDescription: CharSequence? = null
        override var visible = true
        override var assertive = false
    }
}
