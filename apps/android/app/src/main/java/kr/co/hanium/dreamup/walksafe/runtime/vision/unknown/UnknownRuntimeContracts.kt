package kr.co.hanium.dreamup.walksafe.runtime.vision.unknown

import kr.co.hanium.dreamup.walksafe.device.DeviceTuningRecheckReason
import kr.co.hanium.dreamup.walksafe.device.RuntimeCalibrationLiveEvidence
import kr.co.hanium.dreamup.walksafe.device.RuntimeCalibrationResult
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCandidate
import kr.co.hanium.dreamup.walksafe.inference.RuntimeFeatureScope
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.composite.ValidatedJointRuntimeChoice
import kr.co.hanium.dreamup.walksafe.inference.composite.JointCameraMode
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamResult
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeAttachment
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeBinding
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeProfile
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeSelection

internal interface UnknownRuntimeProfileRepository {
    val requiresJointChoice: Boolean get() = false
    fun loadJoint(config: TwoModelRuntimeConfig, primary: RuntimeCandidate,
        scope: RuntimeFeatureScope, mode: JointCameraMode?): ValidatedJointRuntimeChoice? = null
    fun invalidateJointChoice(choice: ValidatedJointRuntimeChoice): Boolean = false
    fun createBinding(config: TwoModelRuntimeConfig, primary: RuntimeCandidate, scope: RuntimeFeatureScope): UnknownRuntimeBinding?
    fun load(binding: UnknownRuntimeBinding): UnknownRuntimeProfile?
    fun needsRecheck(binding: UnknownRuntimeBinding, manual: Boolean): Boolean
    fun markPending(binding: UnknownRuntimeBinding, reason: DeviceTuningRecheckReason): Boolean
}

internal interface UnknownCalibrationSession {
    val isRunning: Boolean
    val isDraining: Boolean
    fun start(): Boolean
    fun cancel(reason: String)
}

internal data class UnknownCalibrationRequest(
    val binding: UnknownRuntimeBinding,
    val primary: TfliteAndroidFrameDetector,
    val serviceEpoch: Long,
    val deadlineMs: Long,
    val isCurrent: () -> Boolean,
    val speechActive: () -> Boolean,
    val onSelected: (UnknownRuntimeSelection) -> Unit,
    val onClosed: (RuntimeCalibrationResult?) -> Unit,
    val onProductionResult: (FastSamResult<UnknownRuntimeAttachment>) -> Unit,
    val onCalibrationResult: (FastSamResult<UnknownRuntimeAttachment>, RuntimeCalibrationLiveEvidence) -> Boolean,
    val onProductionError: (Throwable) -> Unit,
    val onProductionDiscard: (FastSamFrameToken, String) -> Unit,
)

/** Native implementations preserve the original model, options, executor and Activity SDK owner. */
internal interface UnknownRuntimeFactory {
    fun createService(epoch: Long, options: FastSamRuntimeOptions,
        listener: FastSamRuntimeService.Listener<UnknownRuntimeAttachment>): FastSamRuntimeService<UnknownRuntimeAttachment>
    fun createCalibration(request: UnknownCalibrationRequest): UnknownCalibrationSession
}

/** Resource ownership only; transient tracking/depth quality remains a capture/output decision. */
internal class UnknownWalkingSource(
    private val session: Any,
    private val provider: Any,
    private val purpose: Any,
    private val generation: Long,
    private val epoch: kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch,
    val isCurrent: () -> Boolean,
) {
    fun sameOwner(other: UnknownWalkingSource): Boolean = session === other.session &&
        provider === other.provider && purpose == other.purpose && generation == other.generation && epoch == other.epoch
}

internal interface UnknownRuntimeServiceAuthority {
    fun destroyedOrDetectorClosing(): Boolean
    fun mayStartWalkingService(): Boolean
    fun calibrationCameraAvailable(): Boolean
    fun speechActive(): Boolean
}

internal sealed interface UnknownRuntimeServiceEvent {
    data object PreparationFinished : UnknownRuntimeServiceEvent
    data object CalibrationWillStart : UnknownRuntimeServiceEvent
    data class CalibrationClosed(val result: RuntimeCalibrationResult?) : UnknownRuntimeServiceEvent
}
