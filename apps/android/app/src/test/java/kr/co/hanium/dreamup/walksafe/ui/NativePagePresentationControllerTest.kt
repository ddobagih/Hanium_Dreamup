package kr.co.hanium.dreamup.walksafe.ui

import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage

class NativePagePresentationControllerTest {
    @Test fun constructionDoesNotResolvePeersOrReadAnyQuery() {
        val f = Fixture()
        assertEquals(0, f.peerReads)
        assertTrue(f.trace.isEmpty())
    }

    @Test fun confirmationMessageIsQueriedTwiceAndUnavailableDestinationSkipsTheAvailabilityRead() {
        val f = Fixture()
        f.workflow.nativeUiPage = NativeUiPage.DESTINATION_CONFIRM
        f.controller.renderFrame(frame("DESTINATION_CONFIRM"))
        assertTrue(f.trace.contains("confirmation:message1"))
        assertTrue(f.trace.contains("description:message2"))
        assertTrue(f.trace.contains("enabled:false"))
        assertFalse(f.trace.contains("availability"))
        assertEquals(listOf("preview", "voiceFirst", "pane", "geometry"), f.trace.takeLast(4))
    }

    @Test fun rendererPageChangeUsesTheLiveWorkflowPageInsteadOfTheFrameName() {
        val f = Fixture()
        f.onBegin = { f.workflow.nativeUiPage = NativeUiPage.GUIDANCE }
        f.controller.renderFrame(frame("HOME"))
        assertTrue(f.trace.contains("guidanceQuery"))
        assertFalse(f.trace.any { it.startsWith("confirmation:") })
        assertTrue(f.trace.indexOf("calibration") < f.trace.indexOf("guidanceStatus"))
        assertTrue(f.trace.indexOf("guidanceStatus") < f.trace.indexOf("foreground"))
        assertTrue(f.trace.indexOf("foreground") < f.trace.indexOf("guidanceActions"))
    }

    @Test fun pendingPreparationReadsRuntimeOnlyAfterFinishAndWhenTheFrameSurfaceIsAvailable() {
        val f = Fixture()
        f.workflow.pendingNativeUiPage = NativeUiPage.VOICE_COMMAND
        f.controller.renderFrame(frame("HOME"))
        assertTrue(f.trace.indexOf("finish") < f.trace.indexOf("runtime"))
        assertTrue(f.trace.contains("preparing:true"))
        f.trace.clear()
        f.controller.renderFrame(frame("HOME").copy(homeAvailable = false, forceSettings = false))
        assertFalse(f.trace.contains("runtime"))
        assertFalse(f.trace.any { it.startsWith("preparing:") })
    }

    @Test fun searchPageSkipsKeyboardDismissalAndFocusSelectionRunsAfterRendering() {
        val f = Fixture()
        f.controller.displayPage(NativeUiPage.DESTINATION_SEARCH)
        assertEquals(listOf("renderUi", "focus:DESTINATION_SEARCH"), f.trace)
        f.trace.clear()
        f.controller.displayPage(NativeUiPage.HOME)
        assertEquals(listOf("hideKeyboard", "renderUi", "focus:HOME"), f.trace)
    }

    @Test fun actualWorkflowRetainsItsStalePostedFocusGuard() {
        val f = Fixture()
        f.workflow.showNativeUiPage(NativeUiPage.HOME)
        val posted = f.focusQueue.single()
        f.workflow.nativeUiPage = NativeUiPage.GUIDANCE
        f.trace.clear()
        posted.run()
        assertFalse(f.trace.contains("isShown"))
        assertFalse(f.trace.contains("requestFocus"))
        f.workflow.nativeUiPage = NativeUiPage.HOME
        posted.run()
        assertTrue(f.trace.contains("isShown"))
        assertTrue(f.trace.indexOf("requestFocus") < f.trace.indexOf("accessibilityFocus"))
    }

    @Test fun pausedGuidanceCarriesExplicitResumeAndPendingPresentationEvenWithoutRetry() {
        val f = Fixture()
        f.workflow.nativeUiPage = NativeUiPage.GUIDANCE
        for (action in kr.co.hanium.dreamup.walksafe.navigation.DestinationGuidanceResumeAction.entries) {
            f.resumeAction = action
            f.controller.renderFrame(frame("GUIDANCE"))
            assertEquals(action, f.renderedResumeAction)
        }
    }

    private fun frame(page: String) = NativeScreenPresenter.FrameFacts(page, true, true, false, false, false, false)
    private class Fixture : NativePagePresentationController.Queries, NativePagePresentationController.Phases,
        NativePagePresentationController.FrameTarget {
        val trace = mutableListOf<String>()
        val focusQueue = mutableListOf<Runnable>()
        var peerReads = 0
        var messageReads = 0
        var onBegin: (() -> Unit)? = null
        var resumeAction = kr.co.hanium.dreamup.walksafe.navigation.DestinationGuidanceResumeAction.NONE
        var renderedResumeAction: kr.co.hanium.dreamup.walksafe.navigation.DestinationGuidanceResumeAction? = null
        private fun <T> proxy(type: Class<T>, answer: (String, Array<out Any?>) -> Any?): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> answer(method.name, args ?: emptyArray()) })!!
        val workflow: NativeWorkflowController = NativeWorkflowController(
            proxy(NativeWorkflowController.EntryQueries::class.java) { name, _ -> when (name) {
                "shouldShowNativeHome" -> true
                "nativeFeatureAvailable" -> true
                else -> false
            } },
            proxy(NativeWorkflowController.PageUi::class.java) { name, args -> when (name) {
                "displayPage" -> controller.displayPage(args[0] as NativeUiPage)
                "readyForNativeRendering" -> true
                else -> null
            } },
            proxy(NativeWorkflowController.PreparationActions::class.java) { name, _ -> if (name == "hasPreparationDialog" || name == "beginNativeWalkFromHome") false else null },
            proxy(NativeWorkflowController.DestinationTransactions::class.java) { _, _ -> null },
        )
        val controller: NativePagePresentationController = NativePagePresentationController({ peerReads++; workflow }, { peerReads++; this }, this, this)
        override fun destinationConfirmationMessage(): String { trace += "message"; return "message${++messageReads}" }
        override fun currentGuidancePresentation(): DestinationGuidancePresentation { trace += "guidanceQuery"; return DestinationGuidancePresentation(DestinationGuidanceDisplayStage.ACTIVE, "title", "route", "camera", false, resumeAction) }
        override fun walkingCalibrationStatusMessage(): String { trace += "calibration"; return "measurement" }
        override fun destinationStartAvailable(): Boolean { trace += "availability"; return true }
        override fun runtimeActive(): Boolean { trace += "runtime"; return false }
        override fun activityForeground(): Boolean { trace += "foreground"; return true }
        override fun renderGuidanceStatus(presentation: DestinationGuidancePresentation, measurements: String) { trace += "guidanceStatus" }
        override fun renderPreview(frame: NativeScreenPresenter.FrameFacts) { trace += "preview" }
        override fun renderVoiceFirst(homeAvailable: Boolean) { trace += "voiceFirst" }
        override fun updatePaneTitle(homeAvailable: Boolean, settingsVisible: Boolean) { trace += "pane" }
        override fun updateHomeGeometry() { trace += "geometry" }
        override fun clearSearchFocusAndHideKeyboard() { trace += "hideKeyboard" }
        override fun renderMainUi() { trace += "renderUi" }
        override fun selectFocusTarget(page: NativeUiPage): NativeWorkflowController.FocusTarget {
            trace += "focus:$page"
            return object : NativeWorkflowController.FocusTarget {
                override val isShown: Boolean get() { trace += "isShown"; return true }
                override fun post(action: Runnable) { focusQueue += action }
                override fun requestFocus() { trace += "requestFocus" }
                override fun requestAccessibilityFocus() { trace += "accessibilityFocus" }
            }
        }
        override fun beginFrame(facts: NativeScreenPresenter.FrameFacts) { trace += "begin"; onBegin?.invoke() }
        override fun renderDestinationConfirmationMessage(message: String) { trace += "confirmation:$message" }
        override fun renderDestinationConfirmationActionDescription(message: String) { trace += "description:$message" }
        override fun renderDestinationStartEnabled(enabled: Boolean) { trace += "enabled:$enabled" }
        override fun renderGuidanceDestination(message: String) { trace += "guidanceDestination" }
        override fun renderGuidanceActions(presentation: DestinationGuidancePresentation, activityForeground: Boolean) { trace += "guidanceActions"; renderedResumeAction = presentation.resumeAction }
        override fun finishFrame(facts: NativeScreenPresenter.FrameFacts) { trace += "finish" }
        override fun renderFramePreparation(facts: NativeScreenPresenter.FrameFacts, preparing: Boolean) { trace += "preparing:$preparing" }
    }
}
