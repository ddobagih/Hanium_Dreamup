package kr.co.hanium.dreamup.walksafe.ui

import kr.co.hanium.dreamup.walksafe.navigation.DestinationGuidancePresentation
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage

/** Ordered screen output using the actual workflow page; owns no page, session or epoch state. */
internal class NativePagePresentationController(
    private val workflow: () -> NativeWorkflowController,
    private val screen: () -> FrameTarget,
    private val queries: Queries,
    private val phases: Phases,
) {
    interface FrameTarget {
        fun beginFrame(facts: NativeScreenPresenter.FrameFacts)
        fun renderDestinationConfirmationMessage(message: String)
        fun renderDestinationConfirmationActionDescription(message: String)
        fun renderDestinationStartEnabled(enabled: Boolean)
        fun renderGuidanceDestination(message: String)
        fun renderGuidanceActions(presentation: DestinationGuidancePresentation, activityForeground: Boolean)
        fun finishFrame(facts: NativeScreenPresenter.FrameFacts)
        fun renderFramePreparation(facts: NativeScreenPresenter.FrameFacts, preparing: Boolean)
    }

    interface Queries {
        fun destinationConfirmationMessage(): String
        fun currentGuidancePresentation(): DestinationGuidancePresentation
        fun walkingCalibrationStatusMessage(): String
        fun destinationStartAvailable(): Boolean
        fun runtimeActive(): Boolean
        fun activityForeground(): Boolean
    }

    interface Phases {
        fun renderGuidanceStatus(presentation: DestinationGuidancePresentation, measurements: String)
        fun renderPreview(frame: NativeScreenPresenter.FrameFacts)
        fun renderVoiceFirst(homeAvailable: Boolean)
        fun updatePaneTitle(homeAvailable: Boolean, settingsVisible: Boolean)
        fun updateHomeGeometry()
        fun clearSearchFocusAndHideKeyboard()
        fun renderMainUi()
        fun selectFocusTarget(page: NativeUiPage): NativeWorkflowController.FocusTarget?
    }

    fun displayPage(page: NativeUiPage): NativeWorkflowController.FocusTarget? {
        if (page != NativeUiPage.DESTINATION_SEARCH) phases.clearSearchFocusAndHideKeyboard()
        phases.renderMainUi()
        return phases.selectFocusTarget(page)
    }

    fun renderFrame(frame: NativeScreenPresenter.FrameFacts) {
        screen().beginFrame(frame)
        if (workflow().nativeUiPage == NativeUiPage.DESTINATION_CONFIRM) {
            screen().renderDestinationConfirmationMessage(queries.destinationConfirmationMessage())
            screen().renderDestinationConfirmationActionDescription(queries.destinationConfirmationMessage())
            screen().renderDestinationStartEnabled(
                workflow().pendingUiDestination != null && queries.destinationStartAvailable(),
            )
        }
        if (workflow().nativeUiPage == NativeUiPage.GUIDANCE) {
            val presentation = queries.currentGuidancePresentation()
            screen().renderGuidanceDestination(queries.destinationConfirmationMessage())
            phases.renderGuidanceStatus(presentation, queries.walkingCalibrationStatusMessage())
            screen().renderGuidanceActions(presentation, queries.activityForeground())
        }
        screen().finishFrame(frame)
        if (frame.homeAvailable || frame.forceSettings) {
            val preparing = workflow().pendingNativeUiPage != null && !queries.runtimeActive()
            screen().renderFramePreparation(frame, preparing)
        }
        phases.renderPreview(frame)
        phases.renderVoiceFirst(frame.homeAvailable)
        phases.updatePaneTitle(frame.homeAvailable, frame.settingsVisible)
        phases.updateHomeGeometry()
    }
}
