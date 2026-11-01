package kr.co.hanium.dreamup.walksafe.inference.composite

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckAuthority
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckNativeModelRef
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeInfo
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.RemoteVisionClient
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionPurpose
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionPairSnapshot
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionCohortLease
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeContext
import java.lang.ref.WeakReference
import kr.co.hanium.dreamup.walksafe.inference.*
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture
import java.util.IdentityHashMap
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeException
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeFailures

/** Both HOME and the walking primary owner restore the same complete device-check choice. */
object AndroidJointRuntimeLoader {
    internal val releaseFence = JointRuntimeReleaseFence()
    data class Prepared(val load: AndroidDetectorLoadResult, val choice: ValidatedJointRuntimeChoice)
    class PreparedPair<T> internal constructor(val load: AndroidDetectorLoadResult,
        val choice: ValidatedJointRuntimeChoice, val auxiliary: kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService<T>,
        val proof: JointPairReadyProof, val evidence: List<JointPairSampleEvidence>)

    internal fun ensureNativeReleaseConfirmed() {
        releaseFence.ensureConfirmed()
    }

    /** Explicit actual-pair entry. Legacy primary-only preparation never grants a PairReadyProof.
     * Callbacks register returned instances before any caller teardown can lose their receipts.
     * NO_CREATE is used only if the primary create effect itself was never entered. */
    fun <T> preparePair(context: Context, config: TwoModelRuntimeConfig, choice: ValidatedJointRuntimeChoice,
        auxiliaryEpoch: Long, auxiliaryListener: FastSamRuntimeService.Listener<T>, absoluteDeadlineMs: Long,
        authority: DeviceCheckAuthority, isCurrent: () -> Boolean = { true },
        onPrimaryCreated: (AndroidDetectorLoadResult) -> Boolean = { true },
        onPrimaryCreationFinished: (Throwable?) -> Unit = {},
        onAuxiliaryCreated: (FastSamRuntimeService<T>) -> Boolean = { true },
        verifySample: ((JointPairSampleOutput) -> Unit)? = null,
        onPrimaryCreationNotStarted: () -> Unit = {},
    ): PreparedPair<T> {
        var enteredTransaction = false
        try {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Pair preparation requires a worker" }
            val owner = VisionRuntimeContext.ownerRef(context) // Immutable request binding before normalization.
            val diagnostics = VisionRuntimeContext.diagnosticAuthority(context)
            val scoped = if (diagnostics == null) VisionRuntimeContext(context.applicationContext, owner)
                else VisionRuntimeContext(context.applicationContext, owner, diagnostics)
            require(diagnostics == null || diagnostics === authority)
            val fixtureLoader = RuntimeCalibrationFixtures(scoped)
            val required = fixtureLoader.loadManifest().fixtures.filter { it.required }
            require(required.isNotEmpty()) { "Pair preparation fixture is missing" }
            val fixtures = listOf(required.first(), required.getOrElse(1) { required.first() }).map {
                JointPairFixture(it.id, it.sha256, fixtureLoader.loadArgb(it))
            }
            val binding = AndroidJointRuntimeChoiceStore.createBinding(scoped, config)
            val ports = AndroidPairPreparationPorts<T>(scoped, config, authority)
            enteredTransaction = true
            return JointRuntimePairPreparation.prepare(config, choice, binding, Runtime.getRuntime().availableProcessors(),
                android.os.Build.VERSION.SDK_INT >= 29, auxiliaryEpoch, auxiliaryListener, absoluteDeadlineMs,
                SystemClock::elapsedRealtime, isCurrent, fixtures, ports, releaseFence, onPrimaryCreated,
                onPrimaryCreationFinished, onAuxiliaryCreated, verifySample, onPrimaryCreationNotStarted)
        } finally {
            if (!enteredTransaction) onPrimaryCreationNotStarted()
        }
    }

    /** Rebinds only the same native instances after exact epoch+1 transfer and target activation.
     * This performs metadata validation only. It does not LOAD, warm, sample or create a consumer. */
    fun <T> reissueTransferredPairProof(context: Context, config: TwoModelRuntimeConfig,
        choice: ValidatedJointRuntimeChoice, witness: JointPairTransferWitness, targetLease: VisionCohortLease,
        primary: TfliteAndroidFrameDetector, auxiliary: FastSamRuntimeService<T>,
        authority: DeviceCheckAuthority): JointPairReadyProof {
        val owner = VisionRuntimeContext.ownerRef(context)
        val diagnostics = VisionRuntimeContext.diagnosticAuthority(context)
        val scoped = if (diagnostics == null) VisionRuntimeContext(context.applicationContext, owner)
            else VisionRuntimeContext(context.applicationContext, owner, diagnostics)
        require(diagnostics == null || diagnostics === authority)
        val ports = AndroidPairPreparationPorts<T>(scoped, config, authority)
        val snapshot = ports.transferredSnapshot(targetLease, witness.primaryHandle.modelInstanceId,
            witness.auxiliaryHandle.modelInstanceId)
        val primaryRef = checkNotNull(ports.modelRef(targetLease.cohortId, targetLease.ownerEpoch,
            snapshot.primaryHandle.modelInstanceId)) { "Target primary mapping acknowledgement missing" }
        val auxiliaryRef = checkNotNull(ports.modelRef(targetLease.cohortId, targetLease.ownerEpoch,
            snapshot.auxiliaryHandle.modelInstanceId)) { "Target auxiliary mapping acknowledgement missing" }
        return JointRuntimePairPreparation.reissueAfterTransfer(config, choice, witness, ports.ownerBinding(),
            snapshot, primaryRef, auxiliaryRef, ports.currentValidator(primary, auxiliary, snapshot, primaryRef, auxiliaryRef))
    }

    fun restore(context: Context, config: TwoModelRuntimeConfig, isCurrent: () -> Boolean = { true }): Prepared? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Joint runtime loading requires a worker" }
        ensureNativeReleaseConfirmed()
        val store = AndroidJointRuntimeChoiceStore(context)
        val choice = store.load(config) ?: return null
        try {
            return prepare(context, config, choice, isCurrent)
        } catch (error: Throwable) {
            // Only restoration of this captured durable record may retire it. A memory-only trial
            // must never invalidate an unrelated previously applied choice.
            if (error !is CancellationException && VisionRuntimeFailures.busy(error) == null && isCurrent())
                store.invalidateIfMatches(choice)
            throw error
        }
    }

    /** Prepares one explicit in-memory choice without reading, saving or invalidating preferences. */
    fun prepare(context: Context, config: TwoModelRuntimeConfig, choice: ValidatedJointRuntimeChoice,
        isCurrent: () -> Boolean = { true }): Prepared {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Joint runtime loading requires a worker" }
        ensureNativeReleaseConfirmed()
        if (!isCurrent()) throw CancellationException("Joint runtime preparation retired")
        return prepare(config, choice, isCurrent,
            AndroidJointRuntimeChoiceStore.createBinding(context, config), Runtime.getRuntime().availableProcessors(),
            android.os.Build.VERSION.SDK_INT >= 29,
            create = { selectedConfig, options -> TfliteAndroidFrameDetector.createWithStatus(context,
                selectedConfig, options, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR) },
            sample = { detector ->
                val fixtures = RuntimeCalibrationFixtures(context)
                detector.detectCalibrationImage(fixtures.loadArgb(fixtures.loadManifest().fixtures.first { it.required }))
            }, close = TfliteAndroidFrameDetector::close, fence = releaseFence)
    }

    /** Same preparation transaction with SDK effects supplied by the Android entry point. */
    internal fun prepare(config: TwoModelRuntimeConfig, choice: ValidatedJointRuntimeChoice, isCurrent: () -> Boolean,
        bindingHash: String?, processors: Int, thermalStatusSupported: Boolean,
        create: (TwoModelRuntimeConfig, ModelRuntimeOptions) -> AndroidDetectorLoadResult,
        sample: (TfliteAndroidFrameDetector) -> AndroidDetectionResult,
        close: (TfliteAndroidFrameDetector) -> Unit, fence: JointRuntimeReleaseFence): Prepared {
        fence.ensureConfirmed()
        if (!isCurrent()) throw CancellationException("Joint runtime preparation retired")
        require(bindingHash != null && choice.isValid(bindingHash, processors)) { "Joint runtime model/device binding differs" }
        require(!thermalStatusSupported || choice.evidence.thermalStatus >= 0) { "Thermal observation is missing" }
        var detector: TfliteAndroidFrameDetector? = null
        var physicalRelease: CompletionStage<*>? = null
        try {
            val options = requireNotNull(config.unifiedWalksafe).runtime.copy(
                delegate = choice.candidate.primary.backend.delegate,
                numThreads = choice.candidate.primary.numThreads, fallbackToCpu = false,
                gpuPrecisionLossAllowed = false, gpuSerializationCacheEnabled = true)
            val load = create(config.copy(fallbackModelKey = null, customTactile = null, cocoGeneral = null), options)
            detector = load.detector
            physicalRelease = load.actualReleasedFuture
            fence.requireCleanResult(load)
            check(load.detectorAvailable && load.configLoaded && !load.fallbackUsed && detector != null &&
                load.modelKey == config.primaryModelKey)
            if (!isCurrent()) throw CancellationException("Joint runtime preparation retired")
            val output = sample(detector)
            if (!isCurrent()) throw CancellationException("Joint runtime preparation retired")
            val runtime = requireNotNull(output.timing.modelRuntime)
            check(output.inferenceCompleted && !output.partial && output.timing.modelKey == config.primaryModelKey &&
                output.timing.completedModels == listOf(config.primaryModelKey) && !runtime.fallbackUsed &&
                runtime.activeDelegate == choice.candidate.primary.backend.delegate &&
                runtime.requestedDelegate == choice.candidate.primary.backend.delegate &&
                runtime.numThreads == choice.candidate.primary.numThreads && runtime.gpuPrecisionLossAllowed != true) {
                "Joint runtime differs from measured choice"
            }
            return Prepared(load.copy(reason = "DeviceCheckJointRuntimeChoice"), choice)
        } catch (error: Throwable) {
            if (error is DetectorNativeCleanupFailure) fence.retain(error, detector)
            try { detector?.let(close) } catch (release: Throwable) {
                if (physicalRelease != null) fence.retain(release, detector, physicalRelease)
                else fence.retain(release, detector)
                if (release !== error) error.addSuppressed(release)
                // A cancellation is only logically retired. Failed native destruction must still
                // reach the physical owner's fence and must not be mistaken for a clean drain.
                throw if (error is DetectorNativeCleanupFailure && error.actualReleasedFuture != null) error
                    else DetectorNativeCleanupFailure(error, physicalRelease)
            }
            throw error
        }
    }

}

private class AndroidPairPreparationPorts<T>(private val context: VisionRuntimeContext,
    private val config: TwoModelRuntimeConfig, private val authority: DeviceCheckAuthority) : JointPairPreparationPorts<T> {
    private val client = RemoteVisionClient.forMainProcess(context.applicationContext)
    private val owner = VisionRuntimeContext.ownerRef(context)
    private val preprocessor = YuvImagePreprocessor()

    override fun ownerBinding() = owner.source.binding

    override fun createPrimary(config: TwoModelRuntimeConfig, options: ModelRuntimeOptions) =
        TfliteAndroidFrameDetector.createWithStatus(context, config, options, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
    override fun primarySample(detector: TfliteAndroidFrameDetector, fixture: JointPairFixture,
        purpose: VisionPurpose, absoluteDeadlineMs: Long): JointPairPrimarySample {
        val started = SystemClock.elapsedRealtime()
        val prepared = preprocessor.preprocessBilinear(fixture.image, requireNotNull(config.unifiedWalksafe).inputSize)
        val digest = jointPairTensorDigest(prepared.inputBuffer)
        val result = detector.inferPreparedUnifiedForCalibration(prepared,
            (SystemClock.elapsedRealtime() - started).coerceAtLeast(0), YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR,
            copyRawOutput = true, purpose = purpose, absoluteDeadlineMs = absoluteDeadlineMs)
        return JointPairPrimarySample(result, digest)
    }
    override fun closePrimary(detector: TfliteAndroidFrameDetector) = detector.close()
    override fun createAuxiliary(epoch: Long, options: FastSamRuntimeOptions,
        listener: FastSamRuntimeService.Listener<T>) = FastSamRuntimeService(context, epoch, options, listener)
    override fun validatePair(primary: AndroidDetectorRuntime, auxiliary: FastSamRuntimeInfo): VisionPairSnapshot {
        val lease = checkNotNull(client.peekLease(owner.ownerId)) { "Pair owner no longer holds the native cohort" }
        client.validateOwner(owner, lease)
        return client.validatePair(lease, requireNotNull(primary.modelInstanceId), requireNotNull(auxiliary.modelInstanceId))
    }
    fun transferredSnapshot(lease: VisionCohortLease, primaryId: String, auxiliaryId: String): VisionPairSnapshot {
        client.validateOwner(owner, lease)
        return client.validatePair(lease, primaryId, auxiliaryId)
    }
    override fun modelRef(cohortId: String, epoch: Long, modelId: String): DeviceCheckNativeModelRef? =
        authority.modelRef(cohortId, epoch, modelId)
    override fun currentValidator(primary: TfliteAndroidFrameDetector, auxiliary: FastSamRuntimeService<T>,
        snapshot: VisionPairSnapshot, primaryRef: DeviceCheckNativeModelRef,
        auxiliaryRef: DeviceCheckNativeModelRef): () -> Boolean {
        val primaryRefWeak = WeakReference(primary)
        val auxiliaryRefWeak = WeakReference(auxiliary)
        val remote = client
        val cachedAuthority = authority
        val capturedOwner = owner
        val lease = snapshot.lease
        val primaryId = snapshot.primaryHandle.modelInstanceId
        val auxiliaryId = snapshot.auxiliaryHandle.modelInstanceId
        return {
            primaryRefWeak.get()?.isRuntimeAdmissionCurrent() == true && auxiliaryRefWeak.get()?.isReady == true &&
                runCatching {
                    remote.validateOwner(capturedOwner, lease)
                    remote.validatePair(lease, primaryId, auxiliaryId)
                    cachedAuthority.modelRef(lease.cohortId, lease.ownerEpoch, primaryId) === primaryRef &&
                        cachedAuthority.modelRef(lease.cohortId, lease.ownerEpoch, auxiliaryId) === auxiliaryRef
                }.getOrDefault(false)
        }
    }
}

/** A failed native cleanup blocks every subsequent parent-process model allocation. */
internal class JointRuntimeReleaseFence {
    private class Pending(val error: Throwable, val detector: TfliteAndroidFrameDetector?, var physical: CompletionStage<*>?)
    private class ReceiptCause(cause: Throwable, val receipt: CompletionStage<*>?) :
        RuntimeException("Native retirement is awaiting its physical receipt", cause)
    private val pending = IdentityHashMap<Throwable, Pending>()

    @Synchronized fun ensureConfirmed() {
        pending.values.firstOrNull()?.let { throw DetectorNativeCleanupFailure(it.error, it.physical) }
    }

    fun requireCleanResult(load: AndroidDetectorLoadResult) {
        if (load.reason !in setOf("DetectorNativeCleanupFailure", "DetectorCohortRetired")) return
        // The same exception crosses prepare's catch and can be retained again after a fast ACK.
        // Keep its proof in the exception rather than only in an already-removed map entry.
        val error = DetectorNativeCleanupFailure(ReceiptCause(
            IllegalStateException("Native retirement reported as a failed load result"), load.actualReleasedFuture), load.actualReleasedFuture)
        retain(error, load.detector, load.actualReleasedFuture)
        throw error
    }

    fun retain(error: Throwable, detector: TfliteAndroidFrameDetector? = null,
        physicalRelease: CompletionStage<*>? = physicalReceipt(error)) {
        val (retained, proof) = synchronized(this) {
            val retained = pending.getOrPut(error) { Pending(error, detector, physicalRelease) }
            if (physicalRelease != null && retained.physical !== physicalRelease) {
                retained.physical = retained.physical?.let { previous ->
                    CompletableFuture.allOf(previous.toCompletableFuture(), physicalRelease.toCompletableFuture())
                } ?: physicalRelease
            }
            retained to retained.physical
        }
        // Operation failure is never release evidence. A late successful physical receipt may
        // release this exact failure; it cannot release another unknown/local native owner.
        proof?.whenComplete { _, releaseError ->
            if (releaseError == null) synchronized(this) {
                if (pending[error] === retained && retained.physical === proof) pending.remove(error)
            }
        }
    }

    private fun physicalReceipt(error: Throwable): CompletionStage<*>? {
        val visited = java.util.Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = error
        while (cause != null && visited.add(cause)) {
            (cause as? ReceiptCause)?.receipt?.let { return it }
            (cause as? DetectorNativeCleanupFailure)?.actualReleasedFuture?.let { return it }
            (cause as? DetectorCohortRetired)?.actualReleasedFuture?.let { return it }
            (cause as? VisionRuntimeException)?.releaseReceipt?.let { return it }
            cause = cause.cause
        }
        return null
    }
}
