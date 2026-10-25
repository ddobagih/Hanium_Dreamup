package kr.co.hanium.dreamup.walksafe.feedback.delivery

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeSafetyDecision
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

internal data class FeedbackRejectionProducerScope(val epoch: WalkRuntimeEpoch, val detectorGeneration: Int, val arSessionGeneration: Long)

/** Original lifetime and sensor owners are queried at their original branch, never in a snapshot. */
internal interface RiskFeedbackAdmission {
    fun generation(): Int
    fun lifecycleCurrent(generation: Int): Boolean
    fun runtimeEpochOrNull(): WalkRuntimeEpoch?
    fun runtimeEpochCurrent(epoch: WalkRuntimeEpoch): Boolean
    fun evaluateSafety(nowMs: Long): WalkRuntimeSafetyDecision
    fun qualitativeAllowsWork(nowMs: Long): Boolean
    fun deviceGateAllowsAlerts(): Boolean
    fun freshUnknownFeedbackBatches(nowMs: Long): List<UnknownObjectFeedbackBatch>
    fun unknownFeedbackTrackIsCurrent(trackId: String): Boolean
    fun phoneMountingOutputsAllowed(): Boolean
}

internal interface RiskFeedbackOutput {
    fun currentActor(): AndroidFeedbackActuator?
    fun ensureActor(): AndroidFeedbackActuator
    fun reconcileTalkBackRiskTracks(trackIds: Set<String>)
    fun guidanceVoiceInputActive(): Boolean
    fun recognitionActive(): Boolean
    fun cancelGatewaySpeechInteraction(reason: String)
    fun cancelVoiceCommandRecognition()
}

internal interface RiskFeedbackScheduler {
    fun nowMs(): Long
    fun isMainThread(): Boolean
    fun runOnUiThread(action: () -> Unit)
    fun hasTerminalView(): Boolean
    fun postTerminal(task: Runnable, delayMs: Long)
    fun removeTerminal(task: Runnable)
}

internal interface RiskFeedbackLog {
    fun initialized(): Boolean
    fun recordEvent(name: String, fields: Map<String, Any?>)
}

/** The original frame owner still checks the exact consumed receipt, under its frame lock. */
internal interface RiskFeedbackFrameDelivery<Receipt> {
    fun deliver(dispatch: TactileFrameFeedbackDispatch)
    fun dispatchTactileFrameFeedback(frame: TactileSnapshotFrameResult, stale: Boolean, deviceGateAllowsAlerts: Boolean,
        nowMs: Long, feedbackActuatorOverride: TactileFrameFeedbackActuator?, independentFreshOutputs: List<UnknownObjectFeedbackBatch>, warningContext: WarningRegionContext?): TactileFrameFeedbackDispatch
    fun dispatchConsumedTactileFrameFeedback(receipt: Receipt, frame: TactileSnapshotFrameResult, stale: Boolean,
        deviceGateAllowsAlerts: Boolean, nowMs: Long, feedbackActuatorOverride: TactileFrameFeedbackActuator?,
        independentFreshOutputs: List<UnknownObjectFeedbackBatch>, warningContext: WarningRegionContext?): TactileFrameFeedbackDispatch?
}
