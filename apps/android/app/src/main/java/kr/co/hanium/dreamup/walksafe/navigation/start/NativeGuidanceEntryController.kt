package kr.co.hanium.dreamup.walksafe.navigation.start

import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage

/** Ordered start-button effects; workflow, prewalk and route transactions retain their actual owners. */
internal class NativeGuidanceEntryController(
    private val workflow: NativeWorkflowController,
    private val prewalk: NativePrewalkController,
    private val preparation: GuidancePreparationController,
    private val scope: Scope,
    private val actions: Actions,
) {
    fun start() {
        val destination = workflow.pendingUiDestination ?: return
        if (!workflow.isDestinationStartDispatching) workflow.clearPendingNativeDestinationStart()
        val newPreparation = !scope.runtimeActive() && (workflow.nativePrewalkStartEpoch == null ||
            scope.preflightFailedOrTimedOut() || scope.startupRetryRequired())
        if (newPreparation) preparation.recordRequestedAt()
        actions.recordPreparationStage("GUIDANCE_REQUESTED")
        preparation.resetStartFailure()
        actions.clearDestinationVoiceInput()
        workflow.nativeUiPage = NativeUiPage.GUIDANCE
        actions.ensureFeedbackAndHideKeyboard()
        if (!scope.runtimeActive()) {
            if (workflow.nativePrewalkStartEpoch != null && (scope.preflightFailedOrTimedOut() || scope.startupRetryRequired())) prewalk.cancel(false)
            workflow.pendingNativeUiPage = NativeUiPage.DESTINATION_CONFIRM
            prewalk.beginFromHome()
            if (newPreparation && workflow.nativeUiPage == NativeUiPage.GUIDANCE) preparation.begin(destination)
            if (workflow.nativePrewalkStartEpoch == null && !scope.runtimeActive()) actions.speakCurrentStatusDetail()
            actions.renderMain()
            return
        }
        actions.handOffDestinationStart(destination)
    }

    interface Scope {
        fun runtimeActive(): Boolean
        fun preflightFailedOrTimedOut(): Boolean
        fun startupRetryRequired(): Boolean
    }
    interface Actions {
        fun recordPreparationStage(stage: String)
        fun clearDestinationVoiceInput()
        fun ensureFeedbackAndHideKeyboard()
        fun speakCurrentStatusDetail()
        fun renderMain()
        fun handOffDestinationStart(destination: DestinationSearchResult)
    }
}
