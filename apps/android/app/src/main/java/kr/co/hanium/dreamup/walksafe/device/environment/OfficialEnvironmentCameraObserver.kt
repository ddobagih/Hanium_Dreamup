package kr.co.hanium.dreamup.walksafe.device.environment

import kr.co.hanium.dreamup.walksafe.device.ApprovedCameraFrameQualityProfile
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityReason
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityPolicy
import kr.co.hanium.dreamup.walksafe.device.CameraPreflightStabilityDecision
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Commits quality and mounting facts in the original shared observation transaction. */
internal class OfficialEnvironmentCameraObserver(
    private val evidence: OfficialEnvironmentEvidenceStore,
    private val preflight: OfficialEnvironmentPreflightController,
    private val runtime: OfficialEnvironmentRuntimeController,
    private val facts: OfficialEnvironmentCameraObservationFacts,
    private val mounting: OfficialEnvironmentMountingObservationPort,
    private val effects: OfficialEnvironmentCameraObservationEffects,
    private val nowMs: () -> Long,
    private val runOnUiThread: (() -> Unit) -> Unit,
    private val bypassEnabled: () -> Boolean,
) {
    fun observe(
        observation: CameraFrameQualityObservation,
        expectedCameraPreflightGeneration: Long? = null,
        diagnosticReasonOverride: String? = null,
    ): CameraFrameQualityObservation? {
        if (bypassEnabled()) return observation
        val epoch = observation.epoch
        val observedAtMs = observation.observedAtElapsedRealtimeMs
        val observationGeneration = mounting.observationGeneration()
        if (expectedCameraPreflightGeneration == null && facts.resumeActivationPending(epoch)) effects.postResumeActivation(epoch)
        if (facts.currentEpoch() != epoch || expectedCameraPreflightGeneration != null &&
            !preflight.isCameraCurrent(epoch, expectedCameraPreflightGeneration)
        ) return null
        var assessment = CameraFrameQualityPolicy.assess(epoch, nowMs(), observation, facts.cameraProfile())
        if (expectedCameraPreflightGeneration == null) {
            val handoffStartedAt = synchronized(evidence.observationLock) { mounting.cameraHandoffStartedAt(epoch) }
            if (handoffStartedAt != null) {
                if (observedAtMs < handoffStartedAt || assessment.status != EnvironmentEvidenceStatus.PASS) return observation
                synchronized(evidence.observationLock) { mounting.completeCameraHandoffIfCurrent(epoch) }
            }
        }
        if (expectedCameraPreflightGeneration != null) effects.preflightDiagnostic(observation, assessment, expectedCameraPreflightGeneration)
        val mountingProfileId = facts.mountingCameraProfileId()
        var mountingFault = false
        val raw = assessment.asMeasuredEnvironmentEvidence()
        val commit = synchronized(evidence.observationLock) {
            if (observationGeneration != mounting.observationGeneration() || expectedCameraPreflightGeneration != null &&
                !preflight.isCameraCurrent(epoch, expectedCameraPreflightGeneration)
            ) null
            else {
                val previous = evidence.cameraEvidence
                val previousAt = previous?.observedAtElapsedRealtimeMs
                val candidateAt = raw.observedAtElapsedRealtimeMs
                if (previousAt != null && (candidateAt == null || candidateAt < previousAt)) return@synchronized null
                val stability = if (expectedCameraPreflightGeneration != null) preflight.observeCameraStability(assessment)
                    else CameraPreflightStabilityDecision.ACCEPT
                val checked = when (stability) {
                    CameraPreflightStabilityDecision.STABILIZING -> raw.copy(status = EnvironmentEvidenceStatus.UNKNOWN, detail = "CAMERA_STABILIZING")
                    CameraPreflightStabilityDecision.RETAIN_PREVIOUS_PASS -> previous?.takeIf { it.status == EnvironmentEvidenceStatus.PASS } ?: raw
                    CameraPreflightStabilityDecision.ACCEPT -> raw
                }
                val admitted = if (expectedCameraPreflightGeneration == null && facts.isRuntimeEpochCurrent(epoch)) {
                    val now = nowMs()
                    evidence.observeRuntimeRawCameraAssessment(assessment, now)
                    requireNotNull(evidence.cameraQualityGate.current(now))
                } else checked
                if (admitted !== raw && admitted.status == EnvironmentEvidenceStatus.PASS) {
                    mounting.latestCameraAssessment()?.takeIf {
                        it.status == EnvironmentEvidenceStatus.PASS && it.epoch == admitted.epoch &&
                            it.profileId == admitted.measurementProfileId && it.observedAtElapsedRealtimeMs == admitted.observedAtElapsedRealtimeMs
                    }?.let { assessment = it }
                }
                if (admitted.status != EnvironmentEvidenceStatus.PASS && admitted.detail != assessment.reason.name) {
                    CameraFrameQualityReason.entries.firstOrNull { it.name == admitted.detail }?.let {
                        assessment = assessment.copy(status = admitted.status, reason = it)
                    }
                }
                mountingFault = assessment.status != EnvironmentEvidenceStatus.PASS || mountingProfileId == null || assessment.profileId != mountingProfileId
                val sequence = mounting.commitCameraAssessment(epoch, assessment, stability, mountingFault)
                evidence.commitCameraEvidence(admitted)
                if (stability != CameraPreflightStabilityDecision.RETAIN_PREVIOUS_PASS) evidence.recordCameraDiagnostic(observation, admitted, diagnosticReasonOverride)
                Triple(sequence, previous, admitted)
            }
        }
        val sequence = commit?.first
        val previous = commit?.second
        val admitted = commit?.third ?: return null
        if (sequence == null || facts.currentEpoch() != epoch) return null
        val reassess = admitted.status == EnvironmentEvidenceStatus.PASS && previous?.status != EnvironmentEvidenceStatus.PASS ||
            runtime.outputsAllowed && admitted.status != EnvironmentEvidenceStatus.PASS
        runOnUiThread {
            if (observationGeneration != mounting.observationGeneration() || expectedCameraPreflightGeneration != null &&
                expectedCameraPreflightGeneration != preflight.cameraGeneration || facts.currentEpoch() != epoch
            ) return@runOnUiThread
            if (facts.walkRuntimeActive()) {
                val armedAt = mounting.runtimeRetryArmedAt()
                val retry = armedAt != null && assessment.observedAtElapsedRealtimeMs?.let { it > armedAt } == true
                val applied = mounting.applyRuntimeCameraAssessment(retry, assessment, sequence, observationGeneration, mountingFault)
                if (retry && applied) {
                    synchronized(evidence.observationLock) { mounting.finishRuntimeRetryIfCurrent(armedAt) }
                    effects.updateMountingUi()
                }
                if (mountingFault && applied) synchronized(evidence.observationLock) {
                    mounting.recordAppliedFaultIfCurrent(observationGeneration, sequence)
                }
                if ((reassess || retry && applied) && facts.isRuntimeEpochCurrent(epoch)) runtime.applyCurrentAssessment()
                else if (evidence.cameraQualityGate.pendingFailureDeadlineElapsedRealtimeMs() != null) runtime.scheduleWatchdog(epoch)
            } else {
                effects.updateMountingUi()
                effects.refreshReadinessUi()
            }
        }
        return observation
    }
}

internal interface OfficialEnvironmentCameraObservationFacts {
    fun currentEpoch(): WalkRuntimeEpoch
    fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch): Boolean
    fun walkRuntimeActive(): Boolean
    fun resumeActivationPending(epoch: WalkRuntimeEpoch): Boolean
    fun cameraProfile(): ApprovedCameraFrameQualityProfile?
    fun mountingCameraProfileId(): String?
}

/** These operations retain mounting's own state and are called under the shared lock where noted. */
internal interface OfficialEnvironmentMountingObservationPort {
    fun observationGeneration(): Long
    fun cameraHandoffStartedAt(epoch: WalkRuntimeEpoch): Long?
    fun completeCameraHandoffIfCurrent(epoch: WalkRuntimeEpoch)
    fun latestCameraAssessment(): CameraFrameQualityAssessment?
    fun commitCameraAssessment(epoch: WalkRuntimeEpoch, assessment: CameraFrameQualityAssessment,
        stability: CameraPreflightStabilityDecision, mountingFault: Boolean): Long
    fun runtimeRetryArmedAt(): Long?
    fun applyRuntimeCameraAssessment(retryRequested: Boolean, assessment: CameraFrameQualityAssessment,
        sequence: Long, generation: Long, isFault: Boolean): Boolean
    fun finishRuntimeRetryIfCurrent(armedAtMs: Long?)
    fun recordAppliedFaultIfCurrent(generation: Long, sequence: Long)
}

internal interface OfficialEnvironmentCameraObservationEffects {
    fun postResumeActivation(epoch: WalkRuntimeEpoch)
    fun preflightDiagnostic(observation: CameraFrameQualityObservation, assessment: CameraFrameQualityAssessment, generation: Long)
    fun updateMountingUi()
    fun refreshReadinessUi()
}
