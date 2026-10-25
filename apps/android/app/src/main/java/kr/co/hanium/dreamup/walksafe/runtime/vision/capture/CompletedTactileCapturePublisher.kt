package kr.co.hanium.dreamup.walksafe.runtime.vision.capture

import kr.co.hanium.dreamup.walksafe.AndroidRiskSelectionPolicy
import kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.UnknownTactilePublication
import kr.co.hanium.dreamup.walksafe.feedback.delivery.FeedbackRejectionProducerScope
import kr.co.hanium.dreamup.walksafe.navigation.capture.TactileCaptureSourceFacts
import kr.co.hanium.dreamup.walksafe.navigation.capture.TactileCaptureReceiptFacts
import kr.co.hanium.dreamup.walksafe.navigation.WarningRegionContext
import kr.co.hanium.dreamup.walksafe.navigation.TactileSnapshotFrameResult
import kr.co.hanium.dreamup.walksafe.depth.isCurrentQualitativeCaution
import kr.co.hanium.dreamup.walksafe.feedback.TactileDotReferenceDeliveryOwner
import kr.co.hanium.dreamup.walksafe.navigation.TactileFrameFeedbackActuator

internal fun interface CompletedTactileReferencePublication<R> {
    fun publish(frame: TactileSnapshotFrameResult, owner: TactileDotReferenceDeliveryOwner, receipt: R, current: () -> Boolean): Boolean
}

/** Policy and effects for a completed mask; receipt/currentness/consume/DONE remain in actual peers. */
internal class CompletedTactileCapturePublisher<L : Any, S : kr.co.hanium.dreamup.walksafe.navigation.capture.TactileCaptureSourceFacts, R : kr.co.hanium.dreamup.walksafe.navigation.capture.TactileCaptureReceiptFacts<S>>(
    private val tactile: kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameTactileResultPeer<L, S, R>,
    private val risk: kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameRiskOutputPeer<R>,
    private val reference: CompletedTactileReferencePublication<R>,
    private val lease: (Any) -> L,
    private val minimumDepthConfidence: Float,
) {
    fun publish(
        publication: UnknownTactilePublication,
        frame: TactileSnapshotFrameResult, receipt: R, decisionNs: Long, outputIsCurrent: () -> Boolean,
    ) {
        val owner = receipt.source.owner
        val sourceIsCurrent = { tactile.resultIsCurrent(receipt) &&
            outputIsCurrent() }
        if (!reference.publish(frame, owner, receipt, sourceIsCurrent)) return
        val nowMs = decisionNs / 1_000_000L
        val outputs = AndroidRiskSelectionPolicy.resolveConflictingStateFeedback(frame.guidance.outputs,
            nowElapsedRealtimeMs = nowMs)
        val gate = risk.deviceGate(
            bestOutput = AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(outputs).firstOrNull {
                it.source.metric && it.confidence.finalScore >= minimumDepthConfidence
            },
            staleReason = null,
            arGeneration = owner.arGeneration,
            qualitativeOutput = outputs.firstOrNull { it.isCurrentQualitativeCaution(nowMs) },
        )
        val warningContext = kr.co.hanium.dreamup.walksafe.navigation.WarningRegionContext(
            owner.epoch, publication.geometryId, owner.frameGeneration)
        val delivery = TactileFrameFeedbackActuator { dispatch ->
            risk.emitFeedback(dispatch,
                scope = FeedbackRejectionProducerScope(owner.epoch, owner.frameGeneration, owner.arGeneration),
                startIsCurrent = sourceIsCurrent, producerIsCurrent = sourceIsCurrent)
        }
        tactile.consumeCurrent(receipt) { risk.rememberFrame(frame, stale = false, qualitative = false,
            producerIsCurrent = sourceIsCurrent, alertsAllowed = { risk.currentAlertsAllowed() },
            actuator = delivery, warningContext = warningContext, receipt = receipt,
            delivery = { dispatch, current ->
                risk.emitFeedback(dispatch,
                    scope = FeedbackRejectionProducerScope(owner.epoch, owner.frameGeneration, owner.arGeneration),
                    startIsCurrent = { sourceIsCurrent() && current() }, producerIsCurrent = sourceIsCurrent)
            }) }
        tactile.dispatchFeedback(receipt, frame, stale = false, deviceGateAllowsAlerts = gate.alertsAllowed,
                nowMs = nowMs, actuator = delivery,
                independentOutputs = risk.freshIndependentOutputs(nowMs), warningContext = warningContext)
        tactile.crosswalkReference(owner, lease(publication.processingLease.arLease.originalLease), sourceIsCurrent)
        tactile.dotReference(owner, lease(publication.processingLease.arLease.originalLease), sourceIsCurrent)
    }
}
