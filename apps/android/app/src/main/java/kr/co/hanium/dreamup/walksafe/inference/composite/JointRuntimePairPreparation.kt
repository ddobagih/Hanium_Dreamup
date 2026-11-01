package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.device.DeviceCheckNativeModelRef
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckAuthorityBinding
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckNativeSpec
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckPairProof
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionPairSnapshot
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionCohortLease
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionModelHandle
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionPurpose
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRole
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeCodec
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeFailures
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeOptions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class JointPairSamplePhase { WARMUP, VERIFICATION }

/** Per-instance preparation evidence, not a new benchmark or a CPU-reference accuracy claim. */
data class JointPairSampleEvidence(val role: String, val phase: JointPairSamplePhase,
    val fixtureId: String, val fixtureSha256: String, val argbInputDigest: String,
    val modelInputDigest: String, val modelInstanceId: String, val cohortId: String,
    val rawValueCounts: List<Int>, val completedAtMs: Long,
    val rawFinite: Boolean, val consumerDrained: Boolean, val referenceVerifierApplied: Boolean)

/** Owned raw output is available to a strict reference/mask verifier only during this callback. */
sealed interface JointPairSampleOutput {
    val evidence: JointPairSampleEvidence
    data class Primary(override val evidence: JointPairSampleEvidence,
        val output: AndroidCalibrationDetectionResult) : JointPairSampleOutput
    data class Auxiliary(override val evidence: JointPairSampleEvidence,
        val output: FastSamResult<*>) : JointPairSampleOutput
}

/** Process-local, non-serializable and one-use. No historical choice can manufacture this proof. */
class JointPairReadyProof private constructor(
    override val measurementId: String,
    val choice: ValidatedJointRuntimeChoice,
    override val primary: DeviceCheckNativeModelRef,
    override val auxiliary: DeviceCheckNativeModelRef,
    val sourceBinding: DeviceCheckAuthorityBinding,
    val candidate: JointRuntimeCandidate,
    val snapshot: VisionPairSnapshot,
    val sampleEvidence: List<JointPairSampleEvidence>,
    val qualification: JointRuntimeFinalQualification?,
    private val nativeCurrent: () -> Boolean,
) : DeviceCheckPairProof {
    private val consumed = AtomicBoolean()
    private val revoked = AtomicBoolean()
    private val transferClaimed = AtomicBoolean()
    private val qualificationClaimed = AtomicBoolean()
    private val consumerGate = AtomicReference<(() -> Boolean)?>(null)
    /** The gate may capture only the process registry and its metadata claim, never an Activity. */
    fun bindConsumerGateOnce(gate: () -> Boolean): Boolean = !revoked.get() && consumerGate.compareAndSet(null, gate)
    override fun isCurrent(): Boolean = !revoked.get() &&
        consumerGate.get()?.let { runCatching(it).getOrDefault(false) } == true &&
        runCatching(nativeCurrent).getOrDefault(false)
    @Synchronized override fun consume(): Boolean = isCurrent() && consumed.compareAndSet(false, true)
    fun revoke() { revoked.set(true) }
    /** Commit consumption does not consume the independent, one-time consumer handoff. */
    @Synchronized fun claimForTransfer(): JointPairTransferWitness? {
        if (!isCurrent() || !transferClaimed.compareAndSet(false, true)) return null
        revoked.set(true)
        return JointPairTransferWitness.issue(this)
    }

    /** Same native instances and measurement lineage; the warm proof cannot mint strict evidence. */
    @Synchronized fun attachFinalQualification(finalChoice: ValidatedJointRuntimeChoice,
        value: JointRuntimeFinalQualification): JointPairReadyProof {
        check(ownsProof(this) && qualification == null && !revoked.get() && !consumed.get() && !transferClaimed.get())
        check(finalChoice.measurementId == measurementId && finalChoice.bindingHash == choice.bindingHash &&
            finalChoice.candidate == candidate && JointRuntimeFinalQualification.owns(value) && value.matches(finalChoice, snapshot)) {
            "Final qualification belongs to another measurement, choice or native pair"
        }
        check(runCatching(nativeCurrent).getOrDefault(false)) { "Final native pair is no longer current" }
        val gate = consumerGate.get()
        check(gate == null || runCatching(gate).getOrDefault(false)) { "Final consumer ownership is no longer current" }
        check(qualificationClaimed.compareAndSet(false, true) && revoked.compareAndSet(false, true))
        return issue(finalChoice, sourceBinding, snapshot, primary, auxiliary, sampleEvidence, nativeCurrent, value).also {
            if (gate != null) check(it.bindConsumerGateOnce(gate))
        }
    }

    companion object {
        private val issued = Collections.synchronizedMap(WeakHashMap<JointPairReadyProof, Boolean>())
        @JvmStatic fun ownsProof(proof: DeviceCheckPairProof): Boolean =
            proof is JointPairReadyProof && issued[proof] == true
        /** New production commits require strict/soak evidence; historical warm readiness is separate. */
        @JvmStatic fun ownsQualifiedProof(proof: DeviceCheckPairProof): Boolean =
            proof is JointPairReadyProof && ownsProof(proof) &&
                proof.qualification?.matches(proof.choice, proof.snapshot) == true && proof.isCurrent()
        /** The preparation transaction owns all sample/contract checks before reaching this point. */
        internal fun issue(choice: ValidatedJointRuntimeChoice, binding: DeviceCheckAuthorityBinding,
            snapshot: VisionPairSnapshot, primary: DeviceCheckNativeModelRef, auxiliary: DeviceCheckNativeModelRef,
            evidence: List<JointPairSampleEvidence>, nativeCurrent: () -> Boolean): JointPairReadyProof =
            issue(choice, binding, snapshot, primary, auxiliary, evidence, nativeCurrent, null)

        internal fun issue(choice: ValidatedJointRuntimeChoice, binding: DeviceCheckAuthorityBinding,
            snapshot: VisionPairSnapshot, primary: DeviceCheckNativeModelRef, auxiliary: DeviceCheckNativeModelRef,
            evidence: List<JointPairSampleEvidence>, nativeCurrent: () -> Boolean,
            finalQualification: JointRuntimeFinalQualification?): JointPairReadyProof {
            check(finalQualification == null || finalQualification.matches(choice, snapshot))
            return JointPairReadyProof(choice.measurementId, choice, primary, auxiliary, binding, choice.candidate,
                snapshot, evidence.toList(), finalQualification, nativeCurrent).also { issued[it] = true }
        }
    }
}

/** Process-local one-use lineage for the same warmed models. It contains no executable owner. */
class JointPairTransferWitness private constructor(
    val measurementId: String,
    val sourceBinding: DeviceCheckAuthorityBinding,
    val candidate: JointRuntimeCandidate,
    val sourceLease: VisionCohortLease,
    val childPid: Int,
    val primaryHandle: VisionModelHandle,
    val auxiliaryHandle: VisionModelHandle,
    val primarySpec: DeviceCheckNativeSpec,
    val auxiliarySpec: DeviceCheckNativeSpec,
    val sampleEvidence: List<JointPairSampleEvidence>,
    val qualification: JointRuntimeFinalQualification?,
) {
    val choiceBindingHash get() = sourceBinding.jointBindingHash
    private val consumed = AtomicBoolean()
    internal fun claim(): Boolean = issued[this] == true && consumed.compareAndSet(false, true)
    internal companion object {
        private val issued = Collections.synchronizedMap(WeakHashMap<JointPairTransferWitness, Boolean>())
        fun owns(witness: JointPairTransferWitness) = issued[witness] == true
        fun issue(proof: JointPairReadyProof) = JointPairTransferWitness(proof.measurementId, proof.sourceBinding,
            proof.candidate, proof.snapshot.lease, proof.snapshot.childPid, proof.snapshot.primaryHandle,
            proof.snapshot.auxiliaryHandle, proof.primary.spec, proof.auxiliary.spec, proof.sampleEvidence.toList(), proof.qualification)
            .also { issued[it] = true }
    }
}

internal data class JointPairFixture(val id: String, val sha256: String, val image: ArgbImage)
internal data class JointPairPrimarySample(val output: AndroidCalibrationDetectionResult, val inputDigest: String)

/** SDK effects only. The transaction, actual sample checks and rollback are shared with unit tests. */
internal interface JointPairPreparationPorts<T> {
    fun ownerBinding(): DeviceCheckAuthorityBinding
    fun createPrimary(config: TwoModelRuntimeConfig, options: ModelRuntimeOptions): AndroidDetectorLoadResult
    fun primarySample(detector: TfliteAndroidFrameDetector, fixture: JointPairFixture,
        purpose: VisionPurpose, absoluteDeadlineMs: Long): JointPairPrimarySample
    fun closePrimary(detector: TfliteAndroidFrameDetector)
    fun createAuxiliary(epoch: Long, options: FastSamRuntimeOptions,
        listener: FastSamRuntimeService.Listener<T>): FastSamRuntimeService<T>
    fun validatePair(primary: AndroidDetectorRuntime, auxiliary: FastSamRuntimeInfo): VisionPairSnapshot
    fun modelRef(cohortId: String, epoch: Long, modelId: String): DeviceCheckNativeModelRef?
    /** Must capture only application services, IDs and weak facades, never an Activity/listener. */
    fun currentValidator(primary: TfliteAndroidFrameDetector, auxiliary: FastSamRuntimeService<T>,
        snapshot: VisionPairSnapshot, primaryRef: DeviceCheckNativeModelRef,
        auxiliaryRef: DeviceCheckNativeModelRef): () -> Boolean
}

internal object JointRuntimePairPreparation {
    fun <T> prepare(config: TwoModelRuntimeConfig, choice: ValidatedJointRuntimeChoice,
        bindingHash: String?, processors: Int, thermalStatusSupported: Boolean,
        epoch: Long, listener: FastSamRuntimeService.Listener<T>, absoluteDeadlineMs: Long,
        nowMs: () -> Long, isCurrent: () -> Boolean, fixtures: List<JointPairFixture>,
        ports: JointPairPreparationPorts<T>, fence: JointRuntimeReleaseFence,
        onPrimaryCreated: (AndroidDetectorLoadResult) -> Boolean = { true },
        onPrimaryCreationFinished: (Throwable?) -> Unit = {},
        onAuxiliaryCreated: (FastSamRuntimeService<T>) -> Boolean = { true },
        verifySample: ((JointPairSampleOutput) -> Unit)? = null,
        onPrimaryCreationNotStarted: () -> Unit = {},
    ): AndroidJointRuntimeLoader.PreparedPair<T> {
        var primaryLoad: AndroidDetectorLoadResult? = null
        var primary: TfliteAndroidFrameDetector? = null
        var auxiliary: FastSamRuntimeService<T>? = null
        var creationEntered = false
        var creationAcknowledged = false
        var startedAt = Long.MIN_VALUE
        val samples = mutableListOf<JointPairSampleEvidence>()
        val sampleListener = PairSampleListener(listener)
        fun admit() {
            val now = nowMs()
            if (now < startedAt || now >= absoluteDeadlineMs) throw TimeoutException("Pair preparation deadline expired")
            val active = try { isCurrent() } catch (error: Throwable) {
                throw CancellationException("Pair caller observation failed").also { it.initCause(error) }
            }
            if (!active) throw CancellationException("Pair preparation retired")
        }
        fun <V> await(stage: CompletionStage<V>): V {
            admit()
            return try { stage.toCompletableFuture().get(absoluteDeadlineMs - nowMs(), TimeUnit.MILLISECONDS) }
            catch (error: ExecutionException) { throw error.cause ?: error }
        }
        fun primarySample(model: TfliteAndroidFrameDetector, fixture: JointPairFixture,
            phase: JointPairSamplePhase): AndroidDetectorRuntime {
            admit()
            val purpose = if (phase == JointPairSamplePhase.WARMUP) VisionPurpose.WARMUP else VisionPurpose.BENCHMARK
            val observed = ports.primarySample(model, fixture, purpose, absoluteDeadlineMs)
            admit()
            val result = observed.output.result
            val runtime = requireNotNull(result.timing.modelRuntime)
            check(result.inferenceCompleted && !result.partial && result.timing.modelKey == config.primaryModelKey &&
                result.timing.completedModels == listOf(config.primaryModelKey) &&
                result.timing.modelInferenceMs?.let { it >= 0 } == true &&
                result.timing.modelParseMs?.let { it >= 0 } == true) { "Primary pair sample did not complete" }
            requirePrimaryRuntime(runtime, choice)
            val raw = observed.output.rawOutput
            check(raw != null && raw.size == JointRuntimeChoicePolicy.PRIMARY_RAW_VALUE_COUNT && raw.all(Float::isFinite)) {
                "Primary pair raw output contract differs"
            }
            check(JointRuntimeChoicePolicy.validHash(observed.inputDigest)) { "Primary input digest missing" }
            val evidence = JointPairSampleEvidence(VisionRole.PRIMARY.name, phase, fixture.id, fixture.sha256,
                jointPairArgbDigest(fixture.image), observed.inputDigest, requireNotNull(runtime.modelInstanceId),
                requireNotNull(runtime.cohortId), listOf(raw.size), nowMs(), true, true, verifySample != null)
            verifySample?.invoke(JointPairSampleOutput.Primary(evidence, observed.output))
            admit()
            samples += evidence
            return runtime
        }
        fun auxiliarySample(service: FastSamRuntimeService<T>, fixture: JointPairFixture,
            phase: JointPairSamplePhase, frameId: Long): FastSamRuntimeInfo {
            admit()
            val token = FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE, epoch, frameId,
                0, 0, Math.multiplyExact(nowMs(), 1_000_000L), "joint-pair-${choice.measurementId}",
                fixture.image.width, fixture.image.height)
            val promise = sampleListener.reserve(frameId)
            try {
                val purpose = if (phase == JointPairSamplePhase.WARMUP) VisionPurpose.WARMUP else VisionPurpose.BENCHMARK
                val execution = FastSamExecutionRequest(purpose, Math.multiplyExact(absoluteDeadlineMs, 1_000_000L), true)
                check(service.submitArgb(fixture.image.width, fixture.image.height, fixture.image.pixels, token, null, execution) ==
                    FastSamRuntimeService.Admission.ACCEPTED) { "Auxiliary pair sample admission rejected" }
                val result = await(promise)
                await(service.consumerIdleFuture())
                admit()
                check(result.token === token && result.completedElapsedNs >= token.capturedElapsedNs &&
                    service.isReady && !service.stats().inflight) { "Auxiliary pair consumer did not complete" }
                requireAuxiliaryRuntime(result.runtime, choice)
                val raw = requireNotNull(result.rawOutputs) { "Auxiliary pair raw output missing" }
                val detections = raw.detections()
                val prototypes = raw.prototypes()
                check(detections.remaining() == FastSamModelContract.DETECTION_FLOATS &&
                    prototypes.remaining() == FastSamModelContract.PROTOTYPE_FLOATS &&
                    allFinite(detections) && allFinite(prototypes)) { "Auxiliary pair raw output contract differs" }
                val inputDigest = jointPairArgbDigest(fixture.image)
                val evidence = JointPairSampleEvidence(VisionRole.FASTSAM.name, phase, fixture.id, fixture.sha256,
                    inputDigest, inputDigest, requireNotNull(result.runtime.modelInstanceId),
                    requireNotNull(result.runtime.cohortId), listOf(FastSamModelContract.DETECTION_FLOATS,
                        FastSamModelContract.PROTOTYPE_FLOATS), nowMs(), true, true, verifySample != null)
                verifySample?.invoke(JointPairSampleOutput.Auxiliary(evidence, result))
                admit()
                samples += evidence
                return result.runtime
            } finally { sampleListener.forget(frameId) }
        }
        try {
            startedAt = nowMs()
            fence.ensureConfirmed()
            admit()
            require(absoluteDeadlineMs - startedAt in 1..300_000 && epoch > 0)
            require(bindingHash != null && choice.isValid(bindingHash, processors)) { "Pair choice binding differs" }
            require(!thermalStatusSupported || choice.evidence.thermalStatus >= 0) { "Thermal evidence missing" }
            require(fixtures.size == 2 && fixtures.all { it.id.isNotBlank() && JointRuntimeChoicePolicy.validHash(it.sha256) })
            val selectedModel = requireNotNull(config.unifiedWalksafe)
            val options = selectedModel.runtime.copy(delegate = choice.candidate.primary.backend.delegate,
                numThreads = choice.candidate.primary.numThreads, fallbackToCpu = false,
                gpuPrecisionLossAllowed = false, gpuSerializationCacheEnabled = true)
            var creationError: Throwable? = null
            try {
                creationEntered = true
                val load = ports.createPrimary(config.copy(fallbackModelKey = null, customTactile = null,
                    cocoGeneral = null, personSupplement = null), options)
                primaryLoad = load
                primary = load.detector
                if (!onPrimaryCreated(load)) throw CancellationException("Primary pair reservation retired")
            } catch (error: Throwable) { creationError = error; throw error }
            finally {
                creationAcknowledged = true
                try { onPrimaryCreationFinished(creationError) }
                catch (acknowledgement: Throwable) {
                    val original = creationError
                    if (original == null) throw acknowledgement
                    if (acknowledgement !== original) original.addSuppressed(acknowledgement)
                }
            }
            val load = requireNotNull(primaryLoad)
            fence.requireCleanResult(load)
            val model = requireNotNull(primary)
            check(load.detectorAvailable && load.configLoaded && !load.fallbackUsed &&
                load.modelKey == config.primaryModelKey && load.actualReleasedFuture != null &&
                !load.actualReleasedFuture.toCompletableFuture().isDone) { "Primary pair instance unavailable" }
            val warmPrimary = primarySample(model, fixtures[0], JointPairSamplePhase.WARMUP)
            admit()
            val auxiliaryOptions = FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.valueOf(choice.candidate.auxiliary.backend.name),
                choice.candidate.auxiliary.numThreads, false, Math.multiplyExact(absoluteDeadlineMs, 1_000_000L),
                800, true, false, true, Math.multiplyExact(absoluteDeadlineMs, 1_000_000L))
            val service = ports.createAuxiliary(epoch, auxiliaryOptions, sampleListener)
            auxiliary = service
            if (!onAuxiliaryCreated(service)) throw CancellationException("Auxiliary pair reservation retired")
            admit()
            val loadedAuxiliary = await(service.start())
            requireAuxiliaryRuntime(loadedAuxiliary, choice)
            admit()
            val warmAuxiliary = auxiliarySample(service, fixtures[0], JointPairSamplePhase.WARMUP, 1)
            val verifiedPrimary = primarySample(model, fixtures[1], JointPairSamplePhase.VERIFICATION)
            val verifiedAuxiliary = auxiliarySample(service, fixtures[1], JointPairSamplePhase.VERIFICATION, 2)
            check(samePrimary(warmPrimary, verifiedPrimary) && sameAuxiliary(loadedAuxiliary, warmAuxiliary) &&
                sameAuxiliary(warmAuxiliary, verifiedAuxiliary)) { "Pair native identity changed during preparation" }
            admit()
            val snapshot = ports.validatePair(verifiedPrimary, verifiedAuxiliary)
            validatePairIdentity(snapshot, verifiedPrimary, verifiedAuxiliary, config, choice)
            val primaryRef = checkNotNull(ports.modelRef(snapshot.lease.cohortId, snapshot.lease.ownerEpoch,
                snapshot.primaryHandle.modelInstanceId)) { "Primary OPEN acknowledgement missing" }
            val auxiliaryRef = checkNotNull(ports.modelRef(snapshot.lease.cohortId, snapshot.lease.ownerEpoch,
                snapshot.auxiliaryHandle.modelInstanceId)) { "Auxiliary OPEN acknowledgement missing" }
            requireRef(primaryRef, snapshot, true, config, choice)
            requireRef(auxiliaryRef, snapshot, false, config, choice)
            check(primaryRef !== auxiliaryRef && primaryRef.loadId != auxiliaryRef.loadId &&
                primaryRef.authorityId == auxiliaryRef.authorityId) { "Pair OPEN authorities differ" }
            val validator = ports.currentValidator(model, service, snapshot, primaryRef, auxiliaryRef)
            check(validator()) { "Pair retired before issuing readiness proof" }
            admit()
            val ownerBinding = ports.ownerBinding()
            check(ownerBinding.jointBindingHash == choice.bindingHash) { "Pair source binding differs" }
            val proof = JointPairReadyProof.issue(choice, ownerBinding, snapshot, primaryRef, auxiliaryRef, samples, validator)
            sampleListener.prepared()
            return AndroidJointRuntimeLoader.PreparedPair(load.copy(reason = "DeviceCheckActualJointPair"),
                choice, service, proof, samples.toList())
        } catch (error: Throwable) {
            sampleListener.failed(error)
            val releases = listOfNotNull(primaryLoad?.actualReleasedFuture, auxiliary?.releasedFuture())
            val unknownCreate = creationEntered && primaryLoad == null && VisionRuntimeFailures.busy(error) == null
            try {
                if (unknownCreate || primary != null && primaryLoad?.actualReleasedFuture == null) fence.retain(error, primary)
                else if (releases.isNotEmpty()) fence.retain(error, primary,
                    CompletableFuture.allOf(*releases.map { it.toCompletableFuture() }.toTypedArray()))
            } catch (retention: Throwable) {
                if (retention !== error) error.addSuppressed(retention)
                fence.retain(error, primary)
            }
            try { auxiliary?.closeAsync() } catch (release: Throwable) { if (release !== error) error.addSuppressed(release) }
            try { primary?.let(ports::closePrimary) } catch (release: Throwable) {
                if (release !== error) error.addSuppressed(release)
            }
            throw error
        } finally {
            // NO_CREATE is explicit. A thrown/unknown create can never be acknowledged as absent.
            if (!creationEntered) onPrimaryCreationNotStarted()
            else check(creationAcknowledged) { "Primary creation completion was not acknowledged" }
        }
    }

    /** A transfer changes Java ownership/epoch, never its native IDs or its measurement lineage. */
    fun reissueAfterTransfer(config: TwoModelRuntimeConfig, choice: ValidatedJointRuntimeChoice,
        witness: JointPairTransferWitness, targetBinding: DeviceCheckAuthorityBinding, snapshot: VisionPairSnapshot,
        primary: DeviceCheckNativeModelRef, auxiliary: DeviceCheckNativeModelRef,
        nativeCurrent: () -> Boolean): JointPairReadyProof {
        check(JointPairTransferWitness.owns(witness)) { "Unknown pair transfer witness" }
        check(choice.measurementId == witness.measurementId && choice.bindingHash == witness.choiceBindingHash &&
            choice.candidate == witness.candidate && targetBinding == witness.sourceBinding) { "Pair transfer binding differs" }
        val old = witness.sourceLease
        check(old.ownerEpoch < Long.MAX_VALUE && snapshot.lease.ownerEpoch == old.ownerEpoch + 1 &&
            snapshot.lease.cohortId == old.cohortId && snapshot.childPid == witness.childPid &&
            snapshot.primaryHandle.modelInstanceId == witness.primaryHandle.modelInstanceId &&
            snapshot.auxiliaryHandle.modelInstanceId == witness.auxiliaryHandle.modelInstanceId &&
            snapshot.primaryHandle.role == VisionRole.PRIMARY && snapshot.auxiliaryHandle.role == VisionRole.FASTSAM &&
            listOf(snapshot.primaryHandle, snapshot.auxiliaryHandle).all {
                it.cohortId == old.cohortId && it.ownerEpoch == snapshot.lease.ownerEpoch
            } && witness.sampleEvidence.size == 4 && witness.sampleEvidence.all { it.rawFinite && it.consumerDrained }) {
            "Pair transfer native identity/epoch differs"
        }
        requireRef(primary, snapshot, true, config, choice)
        requireRef(auxiliary, snapshot, false, config, choice)
        check(primary.spec == witness.primarySpec && auxiliary.spec == witness.auxiliarySpec &&
            VisionRuntimeCodec.runtimeHash(snapshot.primaryHandle.runtimeJson) == VisionRuntimeCodec.runtimeHash(witness.primaryHandle.runtimeJson) &&
            VisionRuntimeCodec.runtimeHash(snapshot.auxiliaryHandle.runtimeJson) == VisionRuntimeCodec.runtimeHash(witness.auxiliaryHandle.runtimeJson) &&
            primary !== auxiliary && primary.loadId != auxiliary.loadId && primary.authorityId == auxiliary.authorityId &&
            nativeCurrent()) { "Transferred pair is not the current activated pair" }
        val qualification = witness.qualification?.let { original ->
            check(original.matches(choice, VisionPairSnapshot(witness.sourceLease, witness.primaryHandle,
                witness.auxiliaryHandle, witness.childPid))) { "Transferred qualification lineage differs" }
            original.rebindAfterTransfer(choice, snapshot)
        }
        check(witness.claim()) { "Pair transfer witness already consumed" }
        return JointPairReadyProof.issue(choice, targetBinding, snapshot, primary, auxiliary, witness.sampleEvidence, nativeCurrent,
            qualification)
    }

    private fun requirePrimaryRuntime(value: AndroidDetectorRuntime, choice: ValidatedJointRuntimeChoice) {
        val expected = choice.candidate.primary
        check(!value.fallbackUsed && value.activeDelegate == expected.backend.delegate &&
            value.requestedDelegate == expected.backend.delegate && value.numThreads == expected.numThreads &&
            value.gpuPrecisionLossAllowed != true && (value.modelOwnerPid ?: 0) > 0 &&
            (value.modelOwnerThreadId ?: 0) > 0 && !value.modelInstanceId.isNullOrBlank() && !value.cohortId.isNullOrBlank()) {
            "Primary actual runtime differs from the pair choice"
        }
    }
    private fun requireAuxiliaryRuntime(value: FastSamRuntimeInfo, choice: ValidatedJointRuntimeChoice) {
        val expected = choice.candidate.auxiliary
        check(!value.fallbackUsed && value.actualBackend.name == expected.backend.name &&
            value.requestedBackend.name == expected.backend.name && value.numThreads == expected.numThreads &&
            !value.gpuPrecisionLossRequested && !value.gpuPrecisionLossAllowed && value.gpuSerializationCacheRequested &&
            value.modelOwnerProcessId > 0 && value.modelOwnerThreadId > 0 && !value.modelInstanceId.isNullOrBlank() &&
            !value.cohortId.isNullOrBlank() && value.modelSha256 == FastSamModelContract.SHA256) {
            "Auxiliary actual runtime differs from the pair choice"
        }
    }
    private fun samePrimary(a: AndroidDetectorRuntime, b: AndroidDetectorRuntime) =
        a.modelInstanceId == b.modelInstanceId && a.cohortId == b.cohortId &&
            a.modelOwnerPid == b.modelOwnerPid && a.modelOwnerThreadId == b.modelOwnerThreadId
    private fun sameAuxiliary(a: FastSamRuntimeInfo, b: FastSamRuntimeInfo) =
        a.modelInstanceId == b.modelInstanceId && a.cohortId == b.cohortId &&
            a.modelOwnerProcessId == b.modelOwnerProcessId && a.modelOwnerThreadId == b.modelOwnerThreadId
    private fun allFinite(values: java.nio.FloatBuffer): Boolean {
        while (values.hasRemaining()) if (!values.get().isFinite()) return false
        return true
    }
    private fun validatePairIdentity(snapshot: VisionPairSnapshot, primary: AndroidDetectorRuntime,
        auxiliary: FastSamRuntimeInfo, config: TwoModelRuntimeConfig, choice: ValidatedJointRuntimeChoice) {
        val lease = snapshot.lease
        check(snapshot.childPid > 0 && primary.modelOwnerPid == snapshot.childPid &&
            auxiliary.modelOwnerProcessId == snapshot.childPid.toLong() &&
            primary.modelOwnerThreadId != auxiliary.modelOwnerThreadId &&
            primary.cohortId == lease.cohortId && auxiliary.cohortId == lease.cohortId &&
            snapshot.primaryHandle.modelInstanceId == primary.modelInstanceId &&
            snapshot.auxiliaryHandle.modelInstanceId == auxiliary.modelInstanceId &&
            snapshot.primaryHandle.modelInstanceId != snapshot.auxiliaryHandle.modelInstanceId &&
            snapshot.primaryHandle.role == VisionRole.PRIMARY && snapshot.auxiliaryHandle.role == VisionRole.FASTSAM &&
            snapshot.primaryHandle.modelKey == config.primaryModelKey && snapshot.auxiliaryHandle.modelKey == FastSamModelContract.MODEL_ID &&
            listOf(snapshot.primaryHandle, snapshot.auxiliaryHandle).all { it.cohortId == lease.cohortId && it.ownerEpoch == lease.ownerEpoch }) {
            "Actual pair owner/cohort/instance identity differs"
        }
        for ((handle, expected) in listOf(snapshot.primaryHandle to choice.candidate.primary,
                snapshot.auxiliaryHandle to choice.candidate.auxiliary)) {
            val actual = VisionRuntimeCodec.objectJson(VisionRuntimeCodec.validateRuntimeJson(handle.runtimeJson))
            check(actual.getString("requestedDelegate") == expected.backend.delegate &&
                actual.getString("activeDelegate") == expected.backend.delegate && actual.getInt("numThreads") == expected.numThreads &&
                !actual.getBoolean("fallbackUsed") && !actual.getBoolean("gpuPrecisionLossRequested") &&
                !actual.getBoolean("gpuPrecisionLossAllowed") && actual.getBoolean("gpuSerializationCacheRequested") &&
                actual.getBoolean("gpuDelegateAttached") == (expected.backend == RuntimeBackend.GPU) &&
                actual.getInt("modelOwnerPid") == snapshot.childPid && actual.getLong("modelOwnerThreadId") ==
                    (if (handle.role == VisionRole.PRIMARY) primary.modelOwnerThreadId else auxiliary.modelOwnerThreadId)) {
                "Pair handle runtime options differ"
            }
        }
    }
    private fun requireRef(ref: DeviceCheckNativeModelRef, snapshot: VisionPairSnapshot, primary: Boolean,
        config: TwoModelRuntimeConfig, choice: ValidatedJointRuntimeChoice) {
        val handle = if (primary) snapshot.primaryHandle else snapshot.auxiliaryHandle
        val candidate = if (primary) choice.candidate.primary else choice.candidate.auxiliary
        val sha = if (primary) requireNotNull(config.unifiedWalksafe?.artifactSha256) else FastSamModelContract.SHA256
        val options = VisionRuntimeOptions(candidate.backend.delegate, candidate.numThreads, false, false, true)
        check(ref.modelId == handle.modelInstanceId && ref.lease.cohortId == snapshot.lease.cohortId &&
            ref.lease.ownerId == snapshot.lease.ownerId && ref.lease.ownerEpoch == snapshot.lease.ownerEpoch &&
            ref.spec.role == handle.role.name && ref.spec.modelKey == handle.modelKey && ref.spec.modelSha256 == sha &&
            ref.spec.delegate == candidate.backend.delegate && ref.spec.numThreads == candidate.numThreads &&
            ref.spec.optionsHash == VisionRuntimeCodec.optionsHash(VisionRuntimeCodec.encodeOptions(options)) &&
            ref.actualRuntimeHash == VisionRuntimeCodec.runtimeHash(handle.runtimeJson)) { "Pair durable OPEN reference differs" }
    }
}

private class PairSampleListener<T>(private val live: FastSamRuntimeService.Listener<T>) : FastSamRuntimeService.Listener<T> {
    private val pending = ConcurrentHashMap<Long, CompletableFuture<FastSamResult<T>>>()
    private val firstError = AtomicReference<Throwable?>()
    private val preparing = AtomicBoolean(true)
    fun reserve(frameId: Long) = CompletableFuture<FastSamResult<T>>().also {
        check(pending.putIfAbsent(frameId, it) == null)
        firstError.get()?.let(it::completeExceptionally)
    }
    fun forget(frameId: Long) { pending.remove(frameId) }
    fun prepared() { preparing.set(false); firstError.get()?.let { throw it } }
    fun failed(error: Throwable) { firstError.compareAndSet(null, error); pending.values.forEach { it.completeExceptionally(error) } }
    override fun onResult(result: FastSamResult<T>) {
        if (result.token.source == FastSamFrameToken.Source.CALIBRATION_FIXTURE) {
            pending[result.token.frameId]?.complete(result)
        } else if (!preparing.get()) live.onResult(result)
    }
    override fun onError(error: Throwable) { failed(error); if (!preparing.get()) live.onError(error) }
    override fun onDiscard(token: FastSamFrameToken, reason: String) {
        if (token.source == FastSamFrameToken.Source.CALIBRATION_FIXTURE)
            pending[token.frameId]?.completeExceptionally(IllegalStateException("Pair sample discarded: $reason"))
        else if (!preparing.get()) live.onDiscard(token, reason)
    }
}

internal fun jointPairArgbDigest(image: ArgbImage): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val chunk = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
    for (pixel in image.pixels) {
        if (chunk.remaining() < 4) { digest.update(chunk.array(), 0, chunk.position()); chunk.clear() }
        chunk.putInt(pixel)
    }
    digest.update(chunk.array(), 0, chunk.position())
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun jointPairTensorDigest(buffer: ByteBuffer): String = MessageDigest.getInstance("SHA-256")
    .apply { update(buffer.duplicate().apply { clear() }) }.digest().joinToString("") { "%02x".format(it) }
