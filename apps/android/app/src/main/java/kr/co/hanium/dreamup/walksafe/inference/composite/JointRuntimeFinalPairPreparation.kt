package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamModelContract
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*
import java.util.Collections
import java.util.WeakHashMap
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeInfo
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorRuntime
import java.util.concurrent.TimeUnit

/** Immutable metadata only; no image, tensor, listener, Context or executable owner is retained. */
data class JointRuntimeStrictSampleEvidence(val role: VisionRole, val fixtureId: String,
    val fixtureSha256: String, val argbInputDigest: String, val modelInputDigest: String,
    val modelInstanceId: String, val cohortId: String, val rawValueCounts: List<Int>,
    val completedAtMs: Long, val rawFinite: Boolean, val rawEquivalent: Boolean,
    val fixtureRolesMatched: Boolean, val masksEquivalent: Boolean)

/** Issued only after strict verification and a sustained measurement of these exact native models. */
class JointRuntimeFinalQualification private constructor(
    val measurementId: String, val bindingHash: String, val candidate: JointRuntimeCandidate,
    val cohortId: String, val childPid: Int, val qualifiedEpoch: Long, val currentEpoch: Long,
    val primaryModelInstanceId: String, val auxiliaryModelInstanceId: String,
    val primaryRuntimeHash: String, val auxiliaryRuntimeHash: String,
    val strictEvidence: List<JointRuntimeStrictSampleEvidence>,
    val soakStartedAtMs: Long, val soakCompletedAtMs: Long, val soakEvidence: JointFixtureRuntimeEvidence,
) {
    fun matches(choice: ValidatedJointRuntimeChoice, snapshot: VisionPairSnapshot): Boolean = owns(this) &&
        choice.measurementId == measurementId && choice.bindingHash == bindingHash && choice.candidate == candidate &&
        choice.evidence == soakEvidence && snapshot.lease.cohortId == cohortId && snapshot.childPid == childPid &&
        snapshot.lease.ownerEpoch == currentEpoch && snapshot.primaryHandle.role == VisionRole.PRIMARY &&
        snapshot.auxiliaryHandle.role == VisionRole.FASTSAM &&
        snapshot.primaryHandle.modelInstanceId == primaryModelInstanceId &&
        snapshot.auxiliaryHandle.modelInstanceId == auxiliaryModelInstanceId &&
        listOf(snapshot.primaryHandle, snapshot.auxiliaryHandle).all { it.cohortId == cohortId && it.ownerEpoch == currentEpoch } &&
        runtimeHash(snapshot.primaryHandle) == primaryRuntimeHash && runtimeHash(snapshot.auxiliaryHandle) == auxiliaryRuntimeHash

    /** The caller separately proves the canonical target lease and durable epoch+1 adoption. */
    fun rebindAfterTransfer(choice: ValidatedJointRuntimeChoice, snapshot: VisionPairSnapshot): JointRuntimeFinalQualification {
        check(owns(this) && currentEpoch < Long.MAX_VALUE && snapshot.lease.ownerEpoch == currentEpoch + 1)
        val rebound = JointRuntimeFinalQualification(measurementId, bindingHash, candidate, cohortId, childPid,
            qualifiedEpoch, snapshot.lease.ownerEpoch, primaryModelInstanceId, auxiliaryModelInstanceId,
            primaryRuntimeHash, auxiliaryRuntimeHash, strictEvidence, soakStartedAtMs, soakCompletedAtMs, soakEvidence)
        issued[rebound] = true
        check(rebound.matches(choice, snapshot)) { "Final qualification does not belong to the transferred native pair" }
        return rebound
    }

    companion object {
        private val issued = Collections.synchronizedMap(WeakHashMap<JointRuntimeFinalQualification, Boolean>())
        @JvmStatic fun owns(value: JointRuntimeFinalQualification): Boolean = issued[value] == true
        private fun runtimeHash(handle: VisionModelHandle) = runCatching { VisionRuntimeCodec.runtimeHash(handle.runtimeJson) }.getOrNull()
        internal fun issue(choice: ValidatedJointRuntimeChoice, snapshot: VisionPairSnapshot,
            strict: List<JointRuntimeStrictSampleEvidence>, startedAtMs: Long, completedAtMs: Long,
            processors: Int): JointRuntimeFinalQualification {
            check(choice.isValid(choice.bindingHash, processors) && startedAtMs >= 0 && completedAtMs > startedAtMs &&
                completedAtMs - startedAtMs == choice.evidence.durationMs && snapshot.childPid > 0 &&
                snapshot.lease.ownerEpoch > 0 && strict.size == 8) { "Final measurement is incomplete" }
            val expected = JointRuntimeChoicePolicy.REQUIRED_FIXTURE_IDS.flatMap { id -> VisionRole.entries.map { id to it } }.toSet()
            check(strict.map { it.fixtureId to it.role }.toSet() == expected && strict.all {
                JointRuntimeChoicePolicy.validHash(it.fixtureSha256) && JointRuntimeChoicePolicy.validHash(it.argbInputDigest) &&
                    JointRuntimeChoicePolicy.validHash(it.modelInputDigest) && it.completedAtMs in 0..startedAtMs &&
                    it.cohortId == snapshot.lease.cohortId && it.rawFinite && it.rawEquivalent &&
                    it.fixtureRolesMatched && it.masksEquivalent &&
                    it.modelInstanceId == (if (it.role == VisionRole.PRIMARY) snapshot.primaryHandle else snapshot.auxiliaryHandle).modelInstanceId &&
                    it.rawValueCounts == (if (it.role == VisionRole.PRIMARY) listOf(JointRuntimeChoicePolicy.PRIMARY_RAW_VALUE_COUNT)
                        else listOf(FastSamModelContract.DETECTION_FLOATS, FastSamModelContract.PROTOTYPE_FLOATS))
            }) { "Final strict fixture evidence differs" }
            val frozen = Collections.unmodifiableList(strict.map { it.copy(rawValueCounts = Collections.unmodifiableList(it.rawValueCounts.toList())) })
            val evidence = choice.evidence.copy(
                primaryTurnaroundMs = Collections.unmodifiableList(choice.evidence.primaryTurnaroundMs.toList()),
                auxiliaryTurnaroundMs = Collections.unmodifiableList(choice.evidence.auxiliaryTurnaroundMs.toList()),
                fixtureQuality = Collections.unmodifiableList(choice.evidence.fixtureQuality.toList()))
            return JointRuntimeFinalQualification(choice.measurementId, choice.bindingHash, choice.candidate,
                snapshot.lease.cohortId, snapshot.childPid, snapshot.lease.ownerEpoch, snapshot.lease.ownerEpoch,
                snapshot.primaryHandle.modelInstanceId, snapshot.auxiliaryHandle.modelInstanceId,
                requireNotNull(runtimeHash(snapshot.primaryHandle)), requireNotNull(runtimeHash(snapshot.auxiliaryHandle)),
                frozen, startedAtMs, completedAtMs, evidence).also {
                issued[it] = true
                check(it.matches(choice, snapshot)) { "Final native identity differs" }
            }
        }
    }
}

class JointRuntimeFinalQualifiedPair<T> internal constructor(val prepared: AndroidJointRuntimeLoader.PreparedPair<T>,
    val qualification: JointRuntimeFinalQualification) {
    init { require(qualification.matches(prepared.choice, prepared.proof.snapshot)) }
}

/** The candidate is deliberately unvalidated until strict checks and this same pair's soak finish. */
object JointRuntimeFinalPairPreparation {
    fun <T> prepare(host: JointRuntimeSampleHost<T>, candidate: JointRuntimeCandidate,
        references: JointRuntimeReferenceSet, budget: JointRuntimePhaseBudget, isCurrent: () -> Boolean,
        hooks: JointRuntimeSampleHooks<T> = JointRuntimeSampleHooks(), diagnostic: (String) -> Unit = {}): JointRuntimeFinalQualifiedPair<T> {
        var stage = JointFinalStage.HOST_OPEN
        var fixtureId: String? = null
        fun event(outcome: String, error: Throwable? = null) = JointRuntimeFinalDiagnostic.emit(diagnostic,
            JointRuntimeFinalDiagnostic.line(stage, outcome, error, fixtureId))
        fun begin(next: JointFinalStage) { stage = next; event("STARTED") }
        var entered = false
        var kept = false
        var returned = false
        var verifier: JointRuntimeReferenceSet.Verifier? = null
        try {
            require(budget.phase == JointRuntimeSearchPhase.FINAL)
            require(host.bindingHash == references.bindingHash)
            entered = true
            begin(JointFinalStage.HOST_OPEN)
            host.open(candidate, budget, isCurrent, hooks)
            event("SUCCEEDED")
            begin(JointFinalStage.REFERENCE_CLAIM)
            verifier = references.claimForFinal(host.bindingHash, diagnostic)
            event("SUCCEEDED")
            val strict = mutableListOf<JointRuntimeStrictSampleEvidence>()
            val quality = mutableListOf<JointFixtureQuality>()
            var lastPrimary: AndroidDetectorRuntime? = null
            var lastAuxiliary: FastSamRuntimeInfo? = null
            var finalPrimary: JointPairSampleEvidence? = null
            var finalAuxiliary: JointPairSampleEvidence? = null
            for (fixture in host.entries()) {
                fixtureId = fixture.definition.id
                begin(JointFinalStage.PRIMARY_STRICT)
                val strictVerifier = requireNotNull(verifier)
                val primary = host.consumePrimary(fixture, VisionPurpose.BENCHMARK, true) { sample ->
                    if (!strictVerifier.primary(fixture, sample)) accuracy("final_primary_strict_${fixture.definition.id}")
                    PrimaryStrict(requireNotNull(sample.output.result.timing.modelRuntime),
                        host.primaryEvidence(fixture, sample, JointPairSamplePhase.VERIFICATION, true), requireNotNull(sample.output.rawOutput).size)
                }
                event("SUCCEEDED")
                begin(JointFinalStage.AUXILIARY_STRICT)
                val auxiliary = host.consumeAuxiliary(fixture, VisionPurpose.BENCHMARK, true) { sample ->
                    val (rawMatches, masksMatch) = strictVerifier.auxiliary(fixture, sample)
                    if (!rawMatches || !masksMatch) accuracy("final_auxiliary_strict_${fixture.definition.id}")
                    AuxiliaryStrict(sample.runtime, host.auxiliaryEvidence(fixture, sample, JointPairSamplePhase.VERIFICATION, true),
                        requireNotNull(sample.rawOutputs).detections().remaining(), requireNotNull(sample.rawOutputs).prototypes().remaining())
                }
                event("SUCCEEDED")
                lastPrimary = primary.runtime
                lastAuxiliary = auxiliary.runtime
                host.snapshot(lastPrimary, lastAuxiliary)
                strict += strictRecord(primary.evidence, VisionRole.PRIMARY)
                strict += strictRecord(auxiliary.evidence, VisionRole.FASTSAM)
                quality += JointFixtureQuality(fixture.definition.id, primary.rawCount, auxiliary.detectionCount,
                    auxiliary.prototypeCount, true, true, true, true)
                finalPrimary = primary.evidence; finalAuxiliary = auxiliary.evidence
            }
            // Reference arrays are no longer needed during sustained measurement or live ownership.
            verifier.close(); verifier = null
            fixtureId = null
            begin(JointFinalStage.SOAK)
            val window = host.measure(JointRuntimeChoicePolicy.MINIMUM_DURATION_MS, 24_000L)
            val evidence = JointFixtureRuntimeEvidence(window.completedAtMs - window.startedAtMs, window.primarySubmitted,
                window.auxiliarySubmitted, window.primaryTimes, window.auxiliaryTimes, window.primaryGapMs, window.auxiliaryGapMs,
                quality, candidate.primary, candidate.auxiliary, false, window.thermalStatus)
            val choice = JointRuntimeChoicePolicy.evaluate(host.bindingHash, candidate, evidence, host.processors)
                ?: throw JointRuntimeSampleFailure(JointRuntimeSearchFailure(JointRuntimeSearchFailureKind.PERFORMANCE_FAILED,
                    "final_${JointRuntimeChoicePolicy.rejectionReason(candidate, evidence, host.processors)}"))
            event("SUCCEEDED")
            begin(JointFinalStage.QUALIFICATION)
            host.admit()
            val snapshot = host.snapshot(requireNotNull(lastPrimary), requireNotNull(lastAuxiliary))
            val refs = host.references(snapshot)
            val current = host.validator(snapshot, refs)
            check(current()) { "final_pair_retired_before_qualification" }
            val qualification = JointRuntimeFinalQualification.issue(choice, snapshot, strict,
                window.startedAtMs, window.completedAtMs, host.processors)
            val minimal = host.warmEvidence.toList() + listOf(requireNotNull(finalPrimary), requireNotNull(finalAuxiliary))
            check(minimal.size == 4)
            val proof = JointPairReadyProof.issue(choice, host.ownerBinding(), snapshot, refs.first, refs.second,
                minimal, current, finalQualification = qualification)
            host.admit()
            event("SUCCEEDED")
            begin(JointFinalStage.DRAIN)
            host.finish(true); kept = true
            val beforeDrain = host.nowMs()
            if (beforeDrain < budget.startedAtMs || beforeDrain >= budget.deadlineMs || !isCurrent())
                throw java.util.concurrent.TimeoutException("final_host_drain_deadline")
            host.samplingDrainedFuture.toCompletableFuture().get(budget.deadlineMs - beforeDrain, TimeUnit.MILLISECONDS)
            val completed = host.nowMs()
            if (completed < beforeDrain || completed >= budget.deadlineMs || !isCurrent() || !current())
                throw java.util.concurrent.TimeoutException("final_ready_deadline")
            val prepared = AndroidJointRuntimeLoader.PreparedPair(host.loadedPrimary().copy(reason = "DeviceCheckStrictJointPair"),
                choice, requireNotNull(host.auxiliary), proof, minimal)
            event("SUCCEEDED")
            begin(JointFinalStage.PREPARED)
            return JointRuntimeFinalQualifiedPair(prepared, qualification).also { returned = true; event("SUCCEEDED") }
        } catch (error: Throwable) {
            event("FAILED", error)
            throw error
        } finally {
            verifier?.close()
            if (!entered) hooks.onPrimaryCreationNotStarted()
            if (!returned) {
                host.cancel("final_pair_failed")
                if (kept) host.discardKeptAfterFailure() else host.finish(false)
            }
        }
    }

    private data class PrimaryStrict(val runtime: AndroidDetectorRuntime, val evidence: JointPairSampleEvidence, val rawCount: Int)
    private data class AuxiliaryStrict(val runtime: FastSamRuntimeInfo, val evidence: JointPairSampleEvidence,
        val detectionCount: Int, val prototypeCount: Int)

    private fun strictRecord(sample: JointPairSampleEvidence, role: VisionRole) = JointRuntimeStrictSampleEvidence(role,
        sample.fixtureId, sample.fixtureSha256, sample.argbInputDigest, sample.modelInputDigest, sample.modelInstanceId,
        sample.cohortId, sample.rawValueCounts.toList(), sample.completedAtMs, sample.rawFinite, true, true, true)
}
