package kr.co.hanium.dreamup.walksafe.runtime.metric

import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricPreflightSession
import kr.co.hanium.dreamup.walksafe.feedback.delivery.RiskFeedbackDeliveryController
import kr.co.hanium.dreamup.walksafe.runtime.vision.ar.RuntimeArSessionOwner
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputController

/** Orders retirement of metric evidence and its dependent outputs; owns no source/native state. */
internal class RuntimeMetricInvalidationController<L, Q, S : Any, P : Any>(
    private val stateLock: Any,
    private val metric: () -> RuntimeMetricSessionController<L, Q, S>,
    private val native: () -> RuntimeArSessionOwner<S, P, Q>,
    private val lifecycleOrNull: () -> WalkSessionLifecycle?,
    private val speech: () -> SpeechOutputController,
    private val feedback: () -> RiskFeedbackDeliveryController<*>,
    private val startup: RuntimeMetricInvalidationStartup,
    private val fallback: RuntimeMetricInvalidationFallback,
    private val navigation: RuntimeMetricInvalidationNavigation,
    private val voice: RuntimeMetricInvalidationVoice,
    private val reports: RuntimeMetricInvalidationReports,
    private val renderer: RuntimeMetricInvalidationRenderer,
    private val preflightPurpose: Q,
    private val runtimePurpose: Q,
    private val closeProvider: (P) -> Unit,
    private val pauseSession: (S) -> Unit,
    private val elapsedRealtimeMs: () -> Long,
    private val debug: Boolean,
    private val debugDiagnostic: (String) -> Unit,
    private val sourceChanged: () -> Unit = {},
) {
    /** Only this call's borrowed references; no new close or capability owner is introduced. */
    private data class Invalidation<S, P>(
        val wasPreflight: Boolean,
        val wasRuntime: Boolean,
        val hadCameraFallback: Boolean,
        val hadAvailableEvidence: Boolean,
        val evaluator: RuntimeMetricPreflightSession?,
        val sessionToClose: S?,
        val providerToClose: P?,
        val closeAction: (() -> Unit)?,
    )

    fun invalidate(reason: String) {
        if (debug) runCatching { debugDiagnostic(reason) }
        val safetyStop = reason.startsWith("runtime_") &&
            lifecycleOrNull()?.snapshot()?.state == WalkSessionState.ACTIVE
        val retainedBinding = startup.currentBinding()
        val previousCapability = metric().capabilityOverride
        val invalidated = synchronized(stateLock) {
            val captured = native().captureInvalidation {
                it == preflightPurpose || it == runtimePurpose
            }
            val wasPreflight = captured.observation.purpose == preflightPurpose
            val wasRuntime = captured.observation.purpose == runtimePurpose
            val ownedSession = captured.observation.session.takeIf { wasPreflight || wasRuntime }
            val snapshot = Invalidation(
                wasPreflight, wasRuntime,
                fallback.hasActivity(), metric().capabilityOverride == true,
                metric().preflightEvaluator,
                ownedSession.takeIf { captured.closeOwnerRetained },
                captured.providerToClose, captured.closeAction.takeIf { captured.closeOwnerRetained },
            )
            metric().invalidateEvidence()
            native().detachInvalidated(captured)
            snapshot
        }
        var resumeRenderer = false
        native().completeInvalidationCleanup(
            closeAction = invalidated.closeAction,
            beforeClose = {
                runMetricInvalidationCleanupStages(
                    sourceChanged,
                    { invalidated.providerToClose?.let(closeProvider) },
                    { if (safetyStop) navigation.safetyStopAndPersist() },
                    { invalidated.evaluator?.cancelForLifecycle(elapsedRealtimeMs()) },
                    {
                        fallback.clearContinuationIfInitialized()
                        if (invalidated.wasRuntime || invalidated.hadCameraFallback ||
                            invalidated.hadAvailableEvidence || safetyStop
                        ) {
                            voice.advanceFeedbackGeneration()
                            // A current Depth API observation still allows the next real measurement.
                            metric().restoreCapability(metric().retainDeviceDepthApiCapability(
                                reason, retainedBinding, previousCapability,
                            ))
                            startup.clearConfirmation()
                            fallback.clearPendingStart()
                            val talkBackToCancel = speech().detachPendingTalkBackInteraction()
                            feedback().resetDeliveryState()
                            runMetricInvalidationCleanupStages(
                                { startup.invalidateAvailabilityRecheck() },
                                { voice.cancelRecognition() },
                                { reports.cancelPrivacyCalls() },
                                { navigation.cancelAndClearRequests() },
                                { navigation.resetRoute() },
                                { navigation.invalidateFrame() },
                                { reports.clearExplicitFrame("runtime_metric_unavailable") },
                                { fallback.stop() },
                                { navigation.stopLocation() },
                                { navigation.stopSteps() },
                                { navigation.stopOrientationIfInitialized() },
                                { feedback().cancelPendingFeedbackDeliveries() },
                                { voice.removeTalkBackIfPublished(talkBackToCancel) },
                                { voice.cancelTerminalResolution() },
                                { speech().closeActuator() },
                                { if (safetyStop) fallback.moveToCreatedIfInitialized() },
                                { if (safetyStop) renderer.pauseForSafetyStopIfInitialized() },
                                { reports.recordInvalidationIfInitialized(reason, invalidated.wasPreflight) },
                            )
                        }
                    },
                    { if (invalidated.sessionToClose != null) resumeRenderer = renderer.pauseForSessionClose() },
                    { runCatching { invalidated.sessionToClose?.let(pauseSession) }; Unit },
                )
            },
            afterClose = {
                runMetricInvalidationCleanupStages(
                    { if (invalidated.sessionToClose != null) renderer.clearNativePresentation() },
                    { renderer.resumeAfterSessionClose(resumeRenderer) },
                    { startup.refreshPublishedForegroundUi() },
                )
            },
        )
    }
}

/** Preserve first RuntimeException identity and original independent cleanup tails. */
internal fun runMetricInvalidationCleanupStages(vararg actions: () -> Unit) {
    var firstFailure: RuntimeException? = null
    actions.forEach { action ->
        try {
            action()
        } catch (failure: RuntimeException) {
            val first = firstFailure
            if (first == null) firstFailure = failure
            else if (first !== failure) first.addSuppressed(failure)
        }
    }
    firstFailure?.let { throw it }
}
