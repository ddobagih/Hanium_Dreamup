package kr.co.hanium.dreamup.walksafe.navigation.start

import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage
import org.junit.Assert.*
import org.junit.Test

class NativeGuidanceEntryControllerTest {
    @Test fun startButtonOpensGuidanceBeforeKeyboardCleanupAndBeginsActualPreparation() {
        val f = NativeGuidanceTestFixture()
        f.workflow.pendingUiDestination = f.destination
        val entry = entry(f)
        assertEquals(0, f.reads)
        entry.start()
        assertEquals(NativeUiPage.GUIDANCE, f.workflow.nativeUiPage)
        assertTrue(f.events.indexOf("clear_voice") < f.events.indexOf("feedback_keyboard"))
        assertTrue(f.events.indexOf("feedback_keyboard") < f.events.indexOf("confirmEnvironment"))
        assertEquals(f.epoch, f.workflow.nativePrewalkStartEpoch)
        assertNotNull(f.preparation.pending)
    }

    @Test fun activeStartHandsOffTheSameDestinationWithoutRestartingPreparation() {
        val f = NativeGuidanceTestFixture()
        f.runtimeActive = true
        f.workflow.pendingUiDestination = f.destination
        entry(f).start()
        assertTrue(f.events.contains("handoff"))
        assertFalse(f.events.contains("confirmEnvironment"))
        assertNull(f.workflow.nativePrewalkStartEpoch)
    }

    @Test fun noDestinationMakesNoEntryEffectsAndDoesNotReadAnyScope() {
        val f = NativeGuidanceTestFixture()
        entry(f).start()
        assertTrue(f.events.isEmpty())
        assertEquals(0, f.reads)
    }

    private fun entry(f: NativeGuidanceTestFixture) = NativeGuidanceEntryController(f.workflow, f.prewalk, f.preparation,
        object : NativeGuidanceEntryController.Scope {
            override fun runtimeActive() = f.runtimeActive
            override fun preflightFailedOrTimedOut() = f.preflightFailed
            override fun startupRetryRequired() = f.retryRequired
        }, object : NativeGuidanceEntryController.Actions {
            override fun recordPreparationStage(stage: String) { f.events += "stage:$stage" }
            override fun clearDestinationVoiceInput() { f.events += "clear_voice" }
            override fun ensureFeedbackAndHideKeyboard() { assertEquals(NativeUiPage.GUIDANCE, f.workflow.nativeUiPage); f.events += "feedback_keyboard" }
            override fun speakCurrentStatusDetail() { f.events += "detail_speech" }
            override fun renderMain() { f.events += "renderMain" }
            override fun handOffDestinationStart(destination: kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult) { assertSame(f.destination, destination); f.events += "handoff" }
        })
}
