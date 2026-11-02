package kr.co.hanium.dreamup.walksafe.inference.composite

import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class JointRuntimeSampleFailure(val failure: JointRuntimeSearchFailure, cause: Throwable? = null) :
    Exception(failure.detail, cause)

/** Only the direct acquire call can prove this rejection preceded creation of a cohort record. */
internal class JointRuntimeSampleNoNativeStart internal constructor(cause: Throwable) :
    CancellationException("sample_source_retired_before_acquire") { init { initCause(cause) } }
internal object JointRuntimeSampleAcquisition {
    fun acquire(effect: () -> VisionCohortLease): VisionCohortLease = try { effect() }
        catch (error: VisionStaleAuthorityException) { throw JointRuntimeSampleNoNativeStart(error) }

    /** Busy owns no lease. Wait for its allocation receipt within this phase, never the next one. */
    fun acquireWhenAvailable(deadlineMs: Long, isCurrent: () -> Boolean, now: () -> Long,
        effect: () -> VisionCohortLease): VisionCohortLease {
        val started = now()
        var last = started
        fun current() {
            if (!isCurrent()) throw JointRuntimeSampleNoNativeStart(VisionStaleAuthorityException())
            val value = now()
            if (value < last || value < started || value >= deadlineMs)
                throw JointRuntimeSampleNoNativeStart(TimeoutException("sample_acquire_deadline"))
            last = value
        }
        while (true) {
            current()
            try { return acquire(effect) }
            catch (busy: VisionCohortBusyException) {
                val available = busy.availabilityReceipt.toCompletableFuture()
                while (true) {
                    current()
                    try {
                        available.get(minOf(50L, (deadlineMs - now()).coerceAtLeast(1L)), TimeUnit.MILLISECONDS)
                        break
                    } catch (_: TimeoutException) { /* Currentness and the original deadline are checked again. */ }
                    catch (error: ExecutionException) { throw JointRuntimeSampleNoNativeStart(error.cause ?: error) }
                    catch (error: CancellationException) { throw JointRuntimeSampleNoNativeStart(error) }
                    catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw JointRuntimeSampleNoNativeStart(error) }
                }
            }
        }
    }
}

/** Returned model registration precedes every currentness check and every independent teardown. */
class JointRuntimeSampleHooks<T>(
    val onPrimaryCreated: (AndroidDetectorLoadResult) -> Boolean = { true },
    val onPrimaryCreationFinished: (Throwable?) -> Unit = {},
    val onPrimaryCreationNotStarted: () -> Unit = {},
    val onAuxiliaryCreated: (FastSamRuntimeService<T>) -> Boolean = { true },
)

internal data class JointRuntimeSampleFixture(val definition: RuntimeCalibrationFixture, val image: ArgbImage,
    val argbDigest: String)

/** One decoded bundle per check. Closing prevents new readers and waits for existing CPU readers. */
class JointRuntimeSampleFixtures private constructor(private var values: List<JointRuntimeSampleFixture>?,
    private val release: () -> Unit) : Closeable {
    private val lock = Any()
    private var readers = 0
    private var closing = false
    internal class Borrow internal constructor(val values: List<JointRuntimeSampleFixture>, private val done: () -> Unit) : Closeable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) done() }
    }
    internal fun borrow(): Borrow = synchronized(lock) {
        check(!closing); readers++
        Borrow(requireNotNull(values)) { relinquish() }
    }
    private fun relinquish() {
        val finish = synchronized(lock) { check(readers > 0); readers--; if (closing && readers == 0 && values != null) { values = null; true } else false }
        if (finish) release()
    }
    override fun close() {
        val finish = synchronized(lock) { closing = true; if (readers == 0 && values != null) { values = null; true } else false }
        if (finish) release()
    }
    companion object {
        fun load(context: Context, ledger: JointRuntimeMemoryLedger = ParentDirectBufferArena.process().memoryLedger): JointRuntimeSampleFixtures {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Fixture decoding requires a worker" }
            val app = context.applicationContext
            val loader = RuntimeCalibrationFixtures { path -> app.assets.open(path).use { source ->
                val out = java.io.ByteArrayOutputStream(); val block = ByteArray(8192)
                while (true) { val count = source.read(block); if (count < 0) break
                    check(out.size() + count <= 2 * 1024 * 1024) { "Fixture asset exceeds its bounded bundle" }; out.write(block, 0, count) }
                out.toByteArray()
            } }
            val manifest = loader.loadManifest()
            check(manifest.version == RuntimeCalibrationFixtures.VERSION && manifest.sha256 == RuntimeCalibrationFixtures.MANIFEST_SHA256 &&
                manifest.fixtures.size == 4 && manifest.fixtures.all { it.required } &&
                manifest.fixtures.map { it.id }.toSet() == JointRuntimeChoicePolicy.REQUIRED_FIXTURE_IDS)
            // Decoded arrays plus the largest Bitmap/temporary decode. Reclaimed garbage remains
            // included in the next fresh Runtime heap reading; this is ownership accounting only.
            val pixels = manifest.fixtures.sumOf { Math.multiplyExact(it.width.toLong(), it.height.toLong()) }
            val largest = manifest.fixtures.maxOf { it.width.toLong() * it.height }
            val bytes = Math.addExact(pixels * 4, largest * 8 + 4 * 1024 * 1024L)
            val reservation = admitted(ledger.reservePersistent(JointRuntimeMemoryBytes(managedBytes = bytes), ParentDirectBufferArena.currentManagedHeap()))
            check(ledger.allocationStarted(reservation))
            try {
                val entries = manifest.fixtures.map { fixture -> val image = loader.loadArgb(fixture)
                    JointRuntimeSampleFixture(fixture, image, jointPairArgbDigest(image)) }
                check(ledger.allocationCompleted(reservation))
                return JointRuntimeSampleFixtures(Collections.unmodifiableList(entries)) { ledger.releaseAfterDrain(reservation) }
            } catch (error: Throwable) { ledger.releaseAfterDrain(reservation); throw error }
        }
        internal fun forTest(values: List<JointRuntimeSampleFixture>) = JointRuntimeSampleFixtures(values.toList()) {}
    }
}

internal interface JointRuntimeSamplePorts<T> {
    fun begin(absoluteDeadlineMs: Long): CompletionStage<Unit>
    fun begin(absoluteDeadlineMs: Long, isCurrent: () -> Boolean): CompletionStage<Unit> = begin(absoluteDeadlineMs)
    fun ownerBinding(): DeviceCheckAuthorityBinding
    fun createPrimary(config: TwoModelRuntimeConfig, options: ModelRuntimeOptions, deadlineMs: Long): AndroidDetectorLoadResult
    fun preparePrimaryScratch()
    fun primarySample(model: TfliteAndroidFrameDetector, fixture: JointRuntimeSampleFixture,
        purpose: VisionPurpose, deadlineMs: Long, raw: Boolean): JointPairPrimarySample
    fun createAuxiliary(epoch: Long, options: FastSamRuntimeOptions, listener: FastSamRuntimeService.Listener<T>): FastSamRuntimeService<T>
    fun closePrimary(model: TfliteAndroidFrameDetector)
    fun retire(reason: String)
    fun samplingDrained()
    fun thermal(): Int
    fun validatePair(primary: AndroidDetectorRuntime, auxiliary: FastSamRuntimeInfo): VisionPairSnapshot
    fun modelRef(snapshot: VisionPairSnapshot, primary: Boolean): DeviceCheckNativeModelRef
    fun validator(model: TfliteAndroidFrameDetector, auxiliary: FastSamRuntimeService<T>, snapshot: VisionPairSnapshot,
        primaryRef: DeviceCheckNativeModelRef, auxiliaryRef: DeviceCheckNativeModelRef): () -> Boolean
}

internal data class JointRuntimeSampleWindow(val startedAtMs: Long, val completedAtMs: Long,
    val primarySubmitted: Int, val auxiliarySubmitted: Int, val primaryTimes: List<Double>, val auxiliaryTimes: List<Double>,
    val primaryGapMs: Double, val auxiliaryGapMs: Double, val thermalStatus: Int)

/** Parent-only orchestration. Every native operation goes through the production remote proxies. */
class JointRuntimeSampleHost<T> internal constructor(
    internal val config: TwoModelRuntimeConfig, internal val bindingHash: String, internal val processors: Int,
    internal val fixtures: JointRuntimeSampleFixtures, internal val ledger: JointRuntimeMemoryLedger,
    private val epoch: Long, listener: FastSamRuntimeService.Listener<T>, private val ports: JointRuntimeSamplePorts<T>,
    internal val nowMs: () -> Long, private val pause: (Long) -> Unit = Thread::sleep,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "WalkSafeSamplePrimary") },
    private val thermalSupported: Boolean = false,
    private val heapSnapshot: () -> JointRuntimeManagedHeapSnapshot = ParentDirectBufferArena::currentManagedHeap,
) {
    private val begun = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val bodyFinished = CompletableFuture<Unit>()
    private val nativeReleased = CompletableFuture<Unit>()
    private val primaryReleased = CompletableFuture<Unit>()
    private val auxiliaryReleased = CompletableFuture<Unit>()
    private val resourcesReleased = CompletableFuture.allOf(bodyFinished, nativeReleased, primaryReleased, auxiliaryReleased).thenApply { Unit }
    private val memoryDrained = CompletableFuture<Unit>()
    val actualReleasedFuture: CompletionStage<Unit> = CompletableFuture.allOf(resourcesReleased, memoryDrained).thenApply { Unit }
    private val samples = SampleListener(listener)
    private val ids = AtomicLong()
    private var startedAt = Long.MIN_VALUE
    private var lastNow = Long.MIN_VALUE
    private val clockLock = Any()
    private lateinit var budget: JointRuntimePhaseBudget
    private var current: () -> Boolean = { false }
    private var borrowed: JointRuntimeSampleFixtures.Borrow? = null
    private var memoryCandidate: JointRuntimeMemoryCandidate? = null
    private var load: AndroidDetectorLoadResult? = null
    internal var primary: TfliteAndroidFrameDetector? = null; private set
    internal var auxiliary: FastSamRuntimeService<T>? = null; private set
    internal lateinit var candidate: JointRuntimeCandidate; private set
    internal val warmEvidence = mutableListOf<JointPairSampleEvidence>()
    private var creationEntered = false
    private var hooks = JointRuntimeSampleHooks<T>()
    private var cleanupStarted = false
    private var beginEntered = false
    private var beginReturned = false
    private val frameMemory = ConcurrentHashMap<Long, JointRuntimeMemoryReservation>()
    private val frameReceipts = ConcurrentHashMap<JointRuntimeMemoryReservation, CompletableFuture<Unit>>()
    private val frameDrains = ConcurrentLinkedQueue<CompletableFuture<Unit>>()
    private val primaryScratchLock = Any()
    private var primaryScratchMemory: JointRuntimeMemoryReservation? = null
    private var primaryScratchPrepared = false
    private var primaryIdentity: AndroidDetectorRuntime? = null
    private var auxiliaryIdentity: FastSamRuntimeInfo? = null
    internal val samplingDrainedFuture: CompletionStage<Unit> get() = bodyFinished

    fun cancel(reason: String = "sample_attempt_cancelled") {
        cancelled.set(true); samples.retire(CancellationException(reason))
        runCatching { auxiliary?.closeAsync() }
        runCatching { ports.retire(reason) }
    }

    internal fun admit(): Long {
        val now = synchronized(clockLock) {
            val observed = nowMs()
            if (observed < startedAt || observed < lastNow || observed >= budget.deadlineMs) throw TimeoutException("sample_phase_deadline")
            lastNow = observed; observed
        }
        if (cancelled.get() || !current()) throw CancellationException("sample_attempt_retired")
        return now
    }
    internal fun <V> await(stage: CompletionStage<V>): V {
        admit()
        val value = try { stage.toCompletableFuture().get((budget.deadlineMs - nowMs()).coerceAtLeast(1), TimeUnit.MILLISECONDS) }
            catch (error: ExecutionException) { throw error.cause ?: error }
        admit(); return value
    }
    internal fun entries(): List<JointRuntimeSampleFixture> = requireNotNull(borrowed).values
    internal fun freshManagedHeap(): JointRuntimeManagedHeapSnapshot = heapSnapshot()

    internal fun open(value: JointRuntimeCandidate, phaseBudget: JointRuntimePhaseBudget, isCurrent: () -> Boolean,
        callbacks: JointRuntimeSampleHooks<T> = JointRuntimeSampleHooks()) {
        check(begun.compareAndSet(false, true)) { "Each sample host owns exactly one operation" }
        candidate = value; budget = phaseBudget; current = isCurrent; hooks = callbacks
        startedAt = nowMs(); lastNow = startedAt
        try {
            if (startedAt < phaseBudget.startedAtMs) throw TimeoutException("sample_phase_clock_rollback")
            admit()
            require(phaseBudget.startedAtMs >= 0 && phaseBudget.deadlineMs > phaseBudget.startedAtMs &&
                phaseBudget.deadlineMs - phaseBudget.startedAtMs <= JointRuntimeSearchPolicy.TOTAL_MAXIMUM_MS &&
                phaseBudget.deadlineMs <= Long.MAX_VALUE / 1_000_000L)
            require(epoch > 0 && bindingHash == ports.ownerBinding().jointBindingHash)
            require(config.primaryModelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY && requireNotNull(config.unifiedWalksafe).enabled)
            require(listOf(value.primary, value.auxiliary).all { it.numThreads in 1..minOf(4, processors) } &&
                value.primaryIntervalMs in 100L..600L && value.auxiliaryIntervalMs in 100L..600L)
            require(requireNotNull(config.unifiedWalksafe).inputSize == 768 &&
                requireNotNull(config.unifiedWalksafe).outputTensorShape().fold(1L) { size, axis -> size * axis } == JointRuntimeChoicePolicy.PRIMARY_RAW_VALUE_COUNT.toLong())
            borrowed = fixtures.borrow()
            require(entries().all { jointPairArgbDigest(it.image) == it.argbDigest }) { "sample_fixture_input_changed" }
            memoryCandidate = ledger.beginCandidate("${phaseBudget.phase}-${epoch}") ?: resource("sample_candidate_memory_busy")
            beginEntered = true
            val nativeDone = nativeReleased
            val nativeReceipt = ports.begin(budget.deadlineMs) { !cancelled.get() && current() }
            beginReturned = true
            nativeReceipt.whenComplete { _, error -> if (error == null) nativeDone.complete(Unit) else nativeDone.completeExceptionally(error) }
            admit()
            val options = requireNotNull(config.unifiedWalksafe).runtime.copy(delegate = value.primary.backend.delegate,
                numThreads = value.primary.numThreads, fallbackToCpu = false, gpuPrecisionLossAllowed = false, gpuSerializationCacheEnabled = true)
            var error: Throwable? = null
            try {
                creationEntered = true
                val created = ports.createPrimary(config.copy(fallbackModelKey = null, customTactile = null,
                    cocoGeneral = null, personSupplement = null), options, budget.deadlineMs)
                load = created; primary = created.detector
                val primaryDone = primaryReleased
                created.actualReleasedFuture?.whenComplete { _, failure ->
                    if (failure == null) primaryDone.complete(Unit) else primaryDone.completeExceptionally(failure) }
                if (!hooks.onPrimaryCreated(created)) throw CancellationException("Primary reservation retired")
            } catch (failure: Throwable) { error = failure; throw failure }
            finally { hooks.onPrimaryCreationFinished(error) }
            val loaded = requireNotNull(load)
            check(loaded.detectorAvailable && loaded.configLoaded && !loaded.fallbackUsed && loaded.detector != null &&
                loaded.actualReleasedFuture != null && !loaded.actualReleasedFuture.toCompletableFuture().isDone) { "sample_primary_unavailable_${loaded.reason}" }
            admit()
            val optionsAux = FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.valueOf(value.auxiliary.backend.name),
                value.auxiliary.numThreads, false, Math.multiplyExact(budget.deadlineMs, 1_000_000L), 800,
                true, false, true, Math.multiplyExact(budget.deadlineMs, 1_000_000L))
            val service = ports.createAuxiliary(epoch, optionsAux, samples)
            auxiliary = service
            val auxiliaryDone = auxiliaryReleased
            service.releasedFuture().whenComplete { _, failure -> if (failure == null) auxiliaryDone.complete(Unit) else auxiliaryDone.completeExceptionally(failure) }
            if (!hooks.onAuxiliaryCreated(service)) throw CancellationException("Auxiliary reservation retired")
            requireAuxiliary(await(service.start()))
            val first = entries().first()
            val p = primarySample(first, VisionPurpose.WARMUP, true)
            warmEvidence += primaryEvidence(first, p, JointPairSamplePhase.WARMUP, false)
            val a = auxiliarySample(first, VisionPurpose.WARMUP, true)
            warmEvidence += auxiliaryEvidence(first, a, JointPairSamplePhase.WARMUP, false)
            snapshot(requireNotNull(p.output.result.timing.modelRuntime), a.runtime)
        } catch (error: Throwable) {
            if (!beginEntered || !beginReturned && (error is JointRuntimeSampleNoNativeStart || VisionRuntimeFailures.busy(error) != null))
                nativeReleased.complete(Unit)
            val nativeDone = nativeReleased
            if (!beginReturned) (error as? VisionRuntimeException)?.releaseReceipt?.whenComplete { _, failure ->
                if (failure == null) nativeDone.complete(Unit) else nativeDone.completeExceptionally(failure) }
            if (!creationEntered) hooks.onPrimaryCreationNotStarted()
            throw error
        } finally { hooks = JointRuntimeSampleHooks() }
    }

    internal fun primarySample(fixture: JointRuntimeSampleFixture, purpose: VisionPurpose, raw: Boolean): JointPairPrimarySample =
        consumePrimary(fixture, purpose, raw) { it }

    internal fun <V> consumePrimary(fixture: JointRuntimeSampleFixture, purpose: VisionPurpose, raw: Boolean,
        consume: (JointPairPrimarySample) -> V): V {
        admit()
        check(jointPairArgbDigest(fixture.image) == fixture.argbDigest) { "sample_fixture_input_changed" }
        val reservation = primaryFrameReservation(fixture)
        var success = false
        try {
        preparePrimaryScratch()
        admit()
        val output = ports.primarySample(requireNotNull(primary), fixture, purpose, budget.deadlineMs, raw)
        check(ledger.allocationCompleted(reservation))
        admit(); requirePrimary(requireNotNull(output.output.result.timing.modelRuntime))
        check(output.output.result.inferenceCompleted && !output.output.result.partial &&
            output.output.result.timing.modelKey == config.primaryModelKey &&
            output.output.result.timing.completedModels == listOf(config.primaryModelKey)) { "sample_primary_incomplete" }
        if (raw) check(output.output.rawOutput?.let { it.size == JointRuntimeChoicePolicy.PRIMARY_RAW_VALUE_COUNT && it.all(Float::isFinite) } == true) { "sample_primary_raw_invalid" }
        check(JointRuntimeChoicePolicy.validHash(output.inputDigest)) { "sample_primary_input_digest" }
        success = true; return consume(output)
        } finally { releaseFrame(reservation, success) }
    }
    private fun submitAuxiliary(fixture: JointRuntimeSampleFixture, purpose: VisionPurpose, raw: Boolean): Pair<FastSamFrameToken, CompletableFuture<FastSamResult<T>>> {
        val at = admit(); val id = ids.incrementAndGet()
        check(jointPairArgbDigest(fixture.image) == fixture.argbDigest) { "sample_fixture_input_changed" }
        val reservation = frameReservation(JointRuntimeMemoryRole.AUXILIARY, fixture)
        frameMemory[id] = reservation
        val image = fixture.image
        val token = FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE, epoch, id, 0, 0,
            Math.multiplyExact(at, 1_000_000L), "joint-sample-${fixture.definition.id}", image.width, image.height)
        val pending = samples.reserve(id)
        try {
            val request = FastSamExecutionRequest(purpose, Math.multiplyExact(budget.deadlineMs, 1_000_000L), raw)
            check(requireNotNull(auxiliary).submitArgb(image.width, image.height, image.pixels, token, null, request) ==
                FastSamRuntimeService.Admission.ACCEPTED) { "sample_auxiliary_admission" }
            return token to pending
        } catch (error: Throwable) { samples.forget(id); frameMemory.remove(id)?.let { releaseFrame(it, false) }; throw error }
    }
    internal fun auxiliarySample(fixture: JointRuntimeSampleFixture, purpose: VisionPurpose, raw: Boolean): FastSamResult<T> =
        consumeAuxiliary(fixture, purpose, raw) { it }

    internal fun <V> consumeAuxiliary(fixture: JointRuntimeSampleFixture, purpose: VisionPurpose, raw: Boolean,
        consume: (FastSamResult<T>) -> V): V {
        val (token, promise) = submitAuxiliary(fixture, purpose, raw)
        var drained = false
        try {
            val result = await(promise); await(requireNotNull(auxiliary).consumerIdleFuture())
            requireAuxiliaryResult(token, result, raw)
            check(ledger.allocationCompleted(requireNotNull(frameMemory[token.frameId])))
            drained = true
            return consume(result)
        } finally {
            frameMemory.remove(token.frameId)?.let { releaseFrame(it, drained) }
            samples.forget(token.frameId)
        }
    }
    private fun primaryFrameReservation(fixture: JointRuntimeSampleFixture): JointRuntimeMemoryReservation = synchronized(primaryScratchLock) {
        val heap = freshManagedHeap()
        val scratch = if (primaryScratchMemory == null) admitted(ledger.reserveCandidate(requireNotNull(memoryCandidate),
            JointRuntimeMemoryBytes(managedBytes = PRIMARY_SCRATCH_BYTES), heap)).also { primaryScratchMemory = it } else null
        try {
            // Both new allocations are pending against the same fresh observation before either starts.
            frameReservation(JointRuntimeMemoryRole.PRIMARY, fixture, heap)
        } catch (error: Throwable) {
            if (scratch != null) {
                check(ledger.abortBeforeAllocation(scratch))
                primaryScratchMemory = null
            }
            throw error
        }
    }
    private fun preparePrimaryScratch() = synchronized(primaryScratchLock) {
        if (!primaryScratchPrepared) {
            val reservation = requireNotNull(primaryScratchMemory)
            check(ledger.allocationStarted(reservation))
            ports.preparePrimaryScratch()
            check(ledger.allocationCompleted(reservation))
            primaryScratchPrepared = true
        }
    }
    private fun releasePrimaryScratchAfterDrain() = synchronized(primaryScratchLock) {
        primaryScratchMemory?.let { reservation ->
            check(ledger.releaseAfterDrain(reservation))
            primaryScratchMemory = null
            primaryScratchPrepared = false
        }
    }
    private fun frameReservation(role: JointRuntimeMemoryRole, fixture: JointRuntimeSampleFixture,
        heap: JointRuntimeManagedHeapSnapshot = freshManagedHeap()): JointRuntimeMemoryReservation {
        val pixels = fixture.image.width.toLong() * fixture.image.height
        val bytes = if (role == JointRuntimeMemoryRole.PRIMARY) JointRuntimeMemoryBytes(
            managedBytes = 128_000L, sharedBytes = 7_077_888L + 7_200L)
        else JointRuntimeMemoryBytes(managedBytes = pixels * 4 + 6_508_800L + ((pixels + 63) / 64) * 8 * 100 + 512_000L,
            sharedBytes = pixels * 4 + 6_508_800L)
        return admitted(ledger.beginFrame(requireNotNull(memoryCandidate), role, bytes, heap)).also {
            check(ledger.allocationStarted(it))
            val drained = CompletableFuture<Unit>(); frameReceipts[it] = drained; frameDrains += drained
        }
    }
    private fun releaseFrame(reservation: JointRuntimeMemoryReservation, success: Boolean) {
        val ledgerRef = ledger
        val receipt = frameReceipts.remove(reservation) ?: return
        fun release() {
            try { check(ledgerRef.releaseAfterDrain(reservation)); receipt.complete(Unit) }
            catch (error: Throwable) { receipt.completeExceptionally(error) }
        }
        if (success) release()
        else resourcesReleased.whenComplete { _, error -> if (error == null) release() else receipt.completeExceptionally(error) }
    }
    private fun requireAuxiliaryResult(token: FastSamFrameToken, result: FastSamResult<T>, raw: Boolean) {
        admit(); requireAuxiliary(result.runtime)
        check(result.token === token && result.completedElapsedNs >= token.capturedElapsedNs &&
            requireNotNull(auxiliary).isReady && !requireNotNull(auxiliary).stats().inflight) { "sample_auxiliary_not_drained" }
        if (raw) { val outputs = requireNotNull(result.rawOutputs)
            check(outputs.detections().remaining() == FastSamModelContract.DETECTION_FLOATS && outputs.prototypes().remaining() == FastSamModelContract.PROTOTYPE_FLOATS &&
                finite(outputs.detections()) && finite(outputs.prototypes())) { "sample_auxiliary_raw_invalid" } }
    }

    internal fun primaryEvidence(fixture: JointRuntimeSampleFixture, sample: JointPairPrimarySample,
        phase: JointPairSamplePhase, verified: Boolean): JointPairSampleEvidence {
        val runtime = requireNotNull(sample.output.result.timing.modelRuntime)
        return JointPairSampleEvidence(VisionRole.PRIMARY.name, phase, fixture.definition.id, fixture.definition.sha256,
            fixture.argbDigest, sample.inputDigest, requireNotNull(runtime.modelInstanceId), requireNotNull(runtime.cohortId),
            listOf(JointRuntimeChoicePolicy.PRIMARY_RAW_VALUE_COUNT), admit(), true, true, verified)
    }
    internal fun auxiliaryEvidence(fixture: JointRuntimeSampleFixture, sample: FastSamResult<T>,
        phase: JointPairSamplePhase, verified: Boolean) = JointPairSampleEvidence(VisionRole.FASTSAM.name, phase,
        fixture.definition.id, fixture.definition.sha256, fixture.argbDigest, fixture.argbDigest,
        requireNotNull(sample.runtime.modelInstanceId), requireNotNull(sample.runtime.cohortId),
        listOf(FastSamModelContract.DETECTION_FLOATS, FastSamModelContract.PROTOTYPE_FLOATS), admit(), true, true, verified)

    internal fun requirePrimary(value: AndroidDetectorRuntime) {
        check(!value.fallbackUsed && value.requestedDelegate == candidate.primary.backend.delegate && value.activeDelegate == candidate.primary.backend.delegate &&
            value.numThreads == candidate.primary.numThreads && value.gpuPrecisionLossAllowed != true &&
            (value.modelOwnerPid ?: 0) > 0 && (value.modelOwnerThreadId ?: 0) > 0 && !value.modelInstanceId.isNullOrBlank() && !value.cohortId.isNullOrBlank()) { "sample_primary_actual_runtime" }
        primaryIdentity?.let { previous -> check(previous.modelInstanceId == value.modelInstanceId && previous.cohortId == value.cohortId &&
            previous.modelOwnerPid == value.modelOwnerPid && previous.modelOwnerThreadId == value.modelOwnerThreadId) { "sample_primary_instance_changed" } }
        if (primaryIdentity == null) primaryIdentity = value
    }
    internal fun requireAuxiliary(value: FastSamRuntimeInfo) {
        check(!value.fallbackUsed && value.requestedBackend.name == candidate.auxiliary.backend.name && value.actualBackend.name == candidate.auxiliary.backend.name &&
            value.numThreads == candidate.auxiliary.numThreads && !value.gpuPrecisionLossRequested && !value.gpuPrecisionLossAllowed && value.gpuSerializationCacheRequested &&
            value.modelSha256 == FastSamModelContract.SHA256 && value.modelOwnerProcessId > 0 && value.modelOwnerThreadId > 0 &&
            !value.modelInstanceId.isNullOrBlank() && !value.cohortId.isNullOrBlank()) { "sample_auxiliary_actual_runtime" }
        auxiliaryIdentity?.let { previous -> check(previous.modelInstanceId == value.modelInstanceId && previous.cohortId == value.cohortId &&
            previous.modelOwnerProcessId == value.modelOwnerProcessId && previous.modelOwnerThreadId == value.modelOwnerThreadId) { "sample_auxiliary_instance_changed" } }
        if (auxiliaryIdentity == null) auxiliaryIdentity = value
    }

    internal fun snapshot(primaryRuntime: AndroidDetectorRuntime, auxiliaryRuntime: FastSamRuntimeInfo): VisionPairSnapshot {
        admit(); requirePrimary(primaryRuntime); requireAuxiliary(auxiliaryRuntime)
        return ports.validatePair(primaryRuntime, auxiliaryRuntime).also { snapshot ->
            check(snapshot.childPid == primaryRuntime.modelOwnerPid && snapshot.childPid.toLong() == auxiliaryRuntime.modelOwnerProcessId &&
                primaryRuntime.modelOwnerThreadId != auxiliaryRuntime.modelOwnerThreadId &&
                snapshot.lease.cohortId == primaryRuntime.cohortId && snapshot.lease.cohortId == auxiliaryRuntime.cohortId &&
                snapshot.primaryHandle.modelInstanceId == primaryRuntime.modelInstanceId && snapshot.auxiliaryHandle.modelInstanceId == auxiliaryRuntime.modelInstanceId &&
                snapshot.primaryHandle.role == VisionRole.PRIMARY && snapshot.auxiliaryHandle.role == VisionRole.FASTSAM &&
                snapshot.primaryHandle.modelKey == config.primaryModelKey && snapshot.auxiliaryHandle.modelKey == FastSamModelContract.MODEL_ID &&
                snapshot.primaryHandle.modelInstanceId != snapshot.auxiliaryHandle.modelInstanceId &&
                listOf(snapshot.primaryHandle, snapshot.auxiliaryHandle).all { it.cohortId == snapshot.lease.cohortId && it.ownerEpoch == snapshot.lease.ownerEpoch }) { "sample_pair_identity" }
            for ((handle, expected) in listOf(snapshot.primaryHandle to candidate.primary, snapshot.auxiliaryHandle to candidate.auxiliary)) {
                val actual = VisionRuntimeCodec.objectJson(VisionRuntimeCodec.validateRuntimeJson(handle.runtimeJson))
                check(actual.getString("requestedDelegate") == expected.backend.delegate && actual.getString("activeDelegate") == expected.backend.delegate &&
                    actual.getInt("numThreads") == expected.numThreads && !actual.getBoolean("fallbackUsed") &&
                    !actual.getBoolean("gpuPrecisionLossRequested") && !actual.getBoolean("gpuPrecisionLossAllowed") && actual.getBoolean("gpuSerializationCacheRequested") &&
                    actual.getBoolean("gpuDelegateAttached") == (expected.backend == RuntimeBackend.GPU) && actual.getInt("modelOwnerPid") == snapshot.childPid &&
                    actual.getLong("modelOwnerThreadId") == (if (handle.role == VisionRole.PRIMARY) primaryRuntime.modelOwnerThreadId else auxiliaryRuntime.modelOwnerThreadId)) {
                    "sample_pair_handle_options"
                }
            }
        }
    }
    internal fun references(snapshot: VisionPairSnapshot): Pair<DeviceCheckNativeModelRef, DeviceCheckNativeModelRef> {
        val p = ports.modelRef(snapshot, true); val a = ports.modelRef(snapshot, false)
        for ((ref, handle) in listOf(p to snapshot.primaryHandle, a to snapshot.auxiliaryHandle)) {
            val runtime = if (handle.role == VisionRole.PRIMARY) candidate.primary else candidate.auxiliary
            val sha = if (handle.role == VisionRole.PRIMARY) requireNotNull(config.unifiedWalksafe?.artifactSha256) else FastSamModelContract.SHA256
            check(ref.modelId == handle.modelInstanceId && ref.lease.cohortId == snapshot.lease.cohortId && ref.lease.ownerId == snapshot.lease.ownerId &&
                ref.lease.ownerEpoch == snapshot.lease.ownerEpoch && ref.spec.role == handle.role.name && ref.spec.modelKey == handle.modelKey && ref.spec.modelSha256 == sha &&
                ref.spec.delegate == runtime.backend.delegate && ref.spec.numThreads == runtime.numThreads &&
                ref.spec.optionsHash == VisionRuntimeCodec.optionsHash(VisionRuntimeCodec.encodeOptions(VisionRuntimeOptions(runtime.backend.delegate, runtime.numThreads, false, false, true))) &&
                ref.actualRuntimeHash == VisionRuntimeCodec.runtimeHash(handle.runtimeJson)) { "sample_durable_model_ref" }
        }
        check(p !== a && p.loadId != a.loadId && p.authorityId == a.authorityId)
        return p to a
    }
    internal fun validator(snapshot: VisionPairSnapshot, refs: Pair<DeviceCheckNativeModelRef, DeviceCheckNativeModelRef>) =
        ports.validator(requireNotNull(primary), requireNotNull(auxiliary), snapshot, refs.first, refs.second)
    internal fun ownerBinding() = ports.ownerBinding()
    internal fun loadedPrimary() = requireNotNull(load)

    internal fun measure(minimumMs: Long, maximumMs: Long): JointRuntimeSampleWindow {
        val start = admit(); val stop = minOf(budget.deadlineMs, Math.addExact(start, maximumMs))
        check(stop - start >= minimumMs) { "sample_measurement_budget" }
        var p: CompletableFuture<JointPairPrimarySample>? = null
        var a: Pair<FastSamFrameToken, CompletableFuture<FastSamResult<T>>>? = null
        var pAt = 0L; var aAt = 0L; var pNext = start; var aNext = start; var pi = 0; var ai = 0
        var pCount = 0; var aCount = 0; var heat = observedThermal()
        val pTimes = mutableListOf<Double>(); val aTimes = mutableListOf<Double>()
        val pDone = mutableListOf<Long>(); val aDone = mutableListOf<Long>()
        val pPacing = AdaptiveInferencePacingPolicy().apply { configureMinimumInterval(candidate.primaryIntervalMs) }
        val aPacing = AdaptiveInferencePacingPolicy().apply { configureMinimumInterval(candidate.auxiliaryIntervalMs) }
        var pTicket: InferencePacingTicket? = null; var aTicket: InferencePacingTicket? = null
        try {
            while (true) {
                val at = admit(); heat = maxOf(heat, observedThermal()); samples.failure()?.let { throw it }
                p?.takeIf { it.isDone }?.let { future ->
                    val sample = await(future); val end = admit(); pTimes += (end - pAt).toDouble(); pDone += end
                    pTicket?.let { pPacing.complete(it, end, sample.output.result.timing.modelInferenceMs, heat >= 3) }
                    pTicket = null; p = null
                }
                a?.takeIf { it.second.isDone && requireNotNull(auxiliary).consumerIdleFuture().isDone }?.let { pending ->
                    val result = await(pending.second); requireAuxiliaryResult(pending.first, result, false)
                    check(ledger.allocationCompleted(requireNotNull(frameMemory[pending.first.frameId])))
                    val end = admit(); aTimes += (end - aAt).toDouble(); aDone += end
                    aTicket?.let { aPacing.complete(it, end, result.inferenceMs.toLong(), heat >= 3) }
                    aTicket = null; samples.forget(pending.first.frameId)
                    frameMemory.remove(pending.first.frameId)?.let { releaseFrame(it, true) }; a = null
                }
                val needed = if (minimumMs < 10_000) 2 else 10
                if (at >= stop || at - start >= minimumMs && pTimes.size >= needed && aTimes.size >= needed) break
                if (p == null && at >= pNext) pPacing.tryStart(at)?.let { ticket ->
                    pTicket = ticket; pAt = at; pNext = at + candidate.primaryIntervalMs
                    val fixture = entries()[pi++ % entries().size]
                    p = CompletableFuture.supplyAsync({ primarySample(fixture, VisionPurpose.BENCHMARK, false) }, executor); pCount++
                }
                if (a == null && at >= aNext) aPacing.tryStart(at)?.let { ticket ->
                    aTicket = ticket; aAt = at; aNext = at + candidate.auxiliaryIntervalMs
                    a = submitAuxiliary(entries()[ai++ % entries().size], VisionPurpose.BENCHMARK, false); aCount++
                }
                pause(5)
            }
            p?.let { future -> val output = await(future); val end = admit(); pTimes += (end - pAt).toDouble(); pDone += end
                pTicket?.let { pPacing.complete(it, end, output.output.result.timing.modelInferenceMs, heat >= 3) }; pTicket = null }
            a?.let { pending -> val result = await(pending.second); await(requireNotNull(auxiliary).consumerIdleFuture())
                requireAuxiliaryResult(pending.first, result, false)
                check(ledger.allocationCompleted(requireNotNull(frameMemory[pending.first.frameId])))
                val end = admit(); aTimes += (end - aAt).toDouble(); aDone += end
                aTicket?.let { aPacing.complete(it, end, result.inferenceMs.toLong(), heat >= 3) }; aTicket = null; samples.forget(pending.first.frameId) }
            a?.let { frameMemory.remove(it.first.frameId)?.let { reservation -> releaseFrame(reservation, true) } }
            val end = admit()
            return JointRuntimeSampleWindow(start, end, pCount, aCount, pTimes.toList(), aTimes.toList(), gap(pDone, start, end), gap(aDone, start, end), heat)
        } finally { pTicket?.let(pPacing::cancel); aTicket?.let(aPacing::cancel) }
    }

    private fun observedThermal(): Int {
        val status = ports.thermal()
        if (status !in -1..6 || thermalSupported && status < 0) accuracy("sample_thermal_unobserved")
        return status
    }

    fun measureProvisional(value: JointRuntimeCandidate, phaseBudget: JointRuntimePhaseBudget,
        isCurrent: () -> Boolean): JointRuntimeProvisionalMeasurement {
        var result: JointRuntimeProvisionalMeasurement? = null
        try {
            require(phaseBudget.phase == JointRuntimeSearchPhase.PROVISIONAL)
            open(value, phaseBudget, isCurrent)
            val window = measure(JointRuntimeSearchPolicy.MINIMUM_PROVISIONAL_DURATION_MS, JointRuntimeSearchPolicy.MAXIMUM_PROVISIONAL_DURATION_MS)
            result = JointRuntimeProvisionalMeasurement(value, window.completedAtMs - window.startedAtMs, window.primarySubmitted,
                window.auxiliarySubmitted, window.primaryTimes, window.auxiliaryTimes, value.primary, value.auxiliary, false, window.thermalStatus)
        } finally { finish(false) }
        awaitRelease(isCurrent)
        return requireNotNull(result)
    }

    internal fun awaitRelease(isCurrent: () -> Boolean) {
        val at = nowMs()
        if (at < startedAt || at >= budget.deadlineMs || !isCurrent()) throw TimeoutException("sample_cleanup_deadline")
        actualReleasedFuture.toCompletableFuture().get(budget.deadlineMs - at, TimeUnit.MILLISECONDS)
        val end = nowMs()
        if (end < at || end >= budget.deadlineMs || !isCurrent()) throw TimeoutException("sample_cleanup_deadline")
    }
    internal fun finish(keep: Boolean) {
        if (cleanupStarted) return
        if (keep) samples.prepared()
        cleanupStarted = true
        if (!keep) {
            samples.retire(CancellationException("sample_work_finished"))
            runCatching { auxiliary?.closeAsync() }
            runCatching { ports.retire("sample_work_finished") }
        }
        val noPrimary = load == null && !creationEntered
        if (noPrimary) primaryReleased.complete(Unit)
        if (auxiliary == null) auxiliaryReleased.complete(Unit)
        // A failed create without a result remains owned by the exact captured cohort receipt.
        if (creationEntered && load?.actualReleasedFuture == null)
            primaryReleased.let { primaryDone -> nativeReleased.whenComplete { _, error -> if (error == null) primaryDone.complete(Unit) else primaryDone.completeExceptionally(error) } }
        if (!beginEntered) nativeReleased.complete(Unit)
        frameMemory.values.toList().also { frameMemory.clear() }.forEach { releaseFrame(it, false) }
        val ownedPorts = ports; val ownedPrimary = primary; val ownedBorrow = borrowed; val finished = bodyFinished
        borrowed = null
        current = { false }
        try {
            executor.execute {
                try {
                    if (!keep) try { ownedPrimary?.let(ownedPorts::closePrimary) } catch (error: Throwable) {
                        if (!knownRemoteRelease(error)) throw error
                    }
                    ownedPorts.samplingDrained(); releasePrimaryScratchAfterDrain(); ownedBorrow?.close(); finished.complete(Unit)
                } catch (error: Throwable) { finished.completeExceptionally(error) }
            }
        } catch (error: RejectedExecutionException) { finished.completeExceptionally(error) }
        executor.shutdown()
        val token = memoryCandidate; val ledgerRef = ledger; val memoryDone = memoryDrained
        val pendingFrames = frameDrains; val releasedResources = resourcesReleased
        bodyFinished.whenComplete { _, bodyError ->
            if (bodyError != null) memoryDone.completeExceptionally(bodyError)
            else CompletableFuture.allOf(releasedResources, *pendingFrames.toTypedArray()).whenComplete { _, error ->
                if (error != null) memoryDone.completeExceptionally(error)
                else if (token == null || ledgerRef.endCandidate(token)) memoryDone.complete(Unit)
                else memoryDone.completeExceptionally(IllegalStateException("Sample candidate memory did not drain"))
            }
        }
    }

    internal fun discardKeptAfterFailure() {
        cancel("final_pair_rejected")
        try { primary?.let(ports::closePrimary) } catch (error: Throwable) { if (!knownRemoteRelease(error)) throw error }
    }

    companion object {
        private const val PRIMARY_SCRATCH_BYTES = 7_077_888L
        fun <T> create(context: Context, config: TwoModelRuntimeConfig, authority: DeviceCheckAuthority,
            fixtures: JointRuntimeSampleFixtures, auxiliaryEpoch: Long, listener: FastSamRuntimeService.Listener<T>): JointRuntimeSampleHost<T> {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Sample host creation requires a worker" }
            val owner = VisionRuntimeContext.ownerRef(context); val diagnostic = VisionRuntimeContext.diagnosticAuthority(context)
            val scoped = if (diagnostic == null) VisionRuntimeContext(context.applicationContext, owner)
                else VisionRuntimeContext(context.applicationContext, owner, diagnostic)
            require(diagnostic == null || diagnostic === authority)
            val binding = requireNotNull(AndroidJointRuntimeChoiceStore.createBinding(scoped, config))
            return JointRuntimeSampleHost(config, binding, Runtime.getRuntime().availableProcessors(), fixtures,
                ParentDirectBufferArena.process().memoryLedger, auxiliaryEpoch, listener,
                AndroidJointRuntimeSamplePorts(scoped, owner, diagnostic, authority), SystemClock::elapsedRealtime,
                thermalSupported = Build.VERSION.SDK_INT >= 29)
        }
        private fun finite(values: java.nio.FloatBuffer): Boolean { while (values.hasRemaining()) if (!values.get().isFinite()) return false; return true }
        private fun knownRemoteRelease(error: Throwable): Boolean {
            var cause: Throwable? = error
            val visited = Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            while (cause != null && visited.add(cause)) {
                if (cause is DetectorCohortRetired || cause is DetectorNativeCleanupFailure && cause.actualReleasedFuture != null) return true
                cause = cause.cause
            }
            return false
        }
        private fun gap(completed: List<Long>, start: Long, end: Long) = (listOf(start) + completed + end)
            .zipWithNext { a, b -> (b - a).toDouble() }.maxOrNull() ?: (end - start).toDouble()
    }
}

private class SampleListener<T>(listener: FastSamRuntimeService.Listener<T>) : FastSamRuntimeService.Listener<T> {
    private val pending = ConcurrentHashMap<Long, CompletableFuture<FastSamResult<T>>>()
    @Volatile private var live: FastSamRuntimeService.Listener<T>? = listener
    @Volatile private var active = false
    @Volatile private var error: Throwable? = null
    fun reserve(id: Long) = CompletableFuture<FastSamResult<T>>().also { check(pending.putIfAbsent(id, it) == null); error?.let(it::completeExceptionally) }
    fun forget(id: Long) { pending.remove(id) }
    fun failure() = error
    fun prepared() { error?.let { throw it }; active = true }
    fun retire(failure: Throwable) { live = null; active = false; if (error == null) error = failure
        val waiting = pending.values.toList(); pending.clear(); waiting.forEach { it.completeExceptionally(failure) } }
    override fun onResult(result: FastSamResult<T>) {
        if (result.token.source == FastSamFrameToken.Source.CALIBRATION_FIXTURE) pending[result.token.frameId]?.complete(result)
        else if (active) live?.onResult(result)
    }
    override fun onError(failure: Throwable) { if (error == null) error = failure; pending.values.forEach { it.completeExceptionally(failure) }; if (active) live?.onError(failure) }
    override fun onDiscard(token: FastSamFrameToken, reason: String) {
        if (token.source == FastSamFrameToken.Source.CALIBRATION_FIXTURE) pending[token.frameId]?.completeExceptionally(IllegalStateException(reason))
        else if (active) live?.onDiscard(token, reason)
    }
}

private class AndroidJointRuntimeSamplePorts<T>(context: Context, private val owner: DeviceCheckOwnerRef,
    private val diagnostic: DeviceCheckAuthority?, private val authority: DeviceCheckAuthority) : JointRuntimeSamplePorts<T> {
    private val context = context
    private val client = RemoteVisionClient.forMainProcess(context)
    @Volatile private var lease: VisionCohortLease? = null
    private var inputLease: ParentDirectBufferArena.Lease? = null
    private val preprocessor = YuvImagePreprocessor { size ->
        check(size == FastSamModelContract.INPUT_SIZE)
        (inputLease ?: ParentDirectBufferArena.process().acquire("joint-sample-primary-input-768", size * size * 3 * 4, intArrayOf()).also { inputLease = it }).inputView()
    }
    override fun begin(absoluteDeadlineMs: Long): CompletionStage<Unit> = begin(absoluteDeadlineMs) { true }
    override fun begin(absoluteDeadlineMs: Long, isCurrent: () -> Boolean): CompletionStage<Unit> {
        val acquired = JointRuntimeSampleAcquisition.acquireWhenAvailable(absoluteDeadlineMs,
            { isCurrent() && authority.ownerRef(owner.ownerId) === owner }, SystemClock::elapsedRealtime) {
            if (diagnostic == null) client.acquire(owner, absoluteDeadlineMs)
            else client.acquireForDiagnostics(owner, absoluteDeadlineMs, diagnostic)
        }
        lease = acquired
        return client.actualReleasedFuture(acquired.cohortId).thenApply { Unit }
    }
    override fun ownerBinding() = owner.source.binding
    override fun createPrimary(config: TwoModelRuntimeConfig, options: ModelRuntimeOptions, deadlineMs: Long) =
        TfliteAndroidFrameDetector.createWithStatus(context, config, options, YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR, deadlineMs)
    override fun preparePrimaryScratch() = preprocessor.prepareBilinearScratch(FastSamModelContract.INPUT_SIZE)
    override fun primarySample(model: TfliteAndroidFrameDetector, fixture: JointRuntimeSampleFixture,
        purpose: VisionPurpose, deadlineMs: Long, raw: Boolean): JointPairPrimarySample {
        val start = SystemClock.elapsedRealtime(); val prepared = preprocessor.preprocessBilinear(fixture.image, 768)
        val digest = jointPairTensorDigest(prepared.inputBuffer)
        return JointPairPrimarySample(model.inferPreparedUnifiedForCalibration(prepared, SystemClock.elapsedRealtime() - start,
            YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR, raw, purpose, deadlineMs), digest)
    }
    override fun createAuxiliary(epoch: Long, options: FastSamRuntimeOptions, listener: FastSamRuntimeService.Listener<T>) = FastSamRuntimeService(context, epoch, options, listener)
    override fun closePrimary(model: TfliteAndroidFrameDetector) = model.close()
    override fun retire(reason: String) { lease?.let { client.requestExit(it, reason) } }
    override fun samplingDrained() {
        preprocessor.clearAfterDrain()
        inputLease?.releaseAfterHostDrain(); inputLease = null
    }
    override fun thermal(): Int = if (Build.VERSION.SDK_INT < 29) -1 else requireNotNull(context.getSystemService(PowerManager::class.java)).currentThermalStatus
    override fun validatePair(primary: AndroidDetectorRuntime, auxiliary: FastSamRuntimeInfo) = client.validatePair(requireNotNull(lease), requireNotNull(primary.modelInstanceId), requireNotNull(auxiliary.modelInstanceId))
    override fun modelRef(snapshot: VisionPairSnapshot, primary: Boolean) = requireNotNull(authority.modelRef(snapshot.lease.cohortId,
        snapshot.lease.ownerEpoch, (if (primary) snapshot.primaryHandle else snapshot.auxiliaryHandle).modelInstanceId))
    override fun validator(model: TfliteAndroidFrameDetector, auxiliary: FastSamRuntimeService<T>, snapshot: VisionPairSnapshot,
        primaryRef: DeviceCheckNativeModelRef, auxiliaryRef: DeviceCheckNativeModelRef): () -> Boolean {
        val p = WeakReference(model); val a = WeakReference(auxiliary); val remote = client; val captured = owner; val journal = authority
        return {
            p.get()?.isRuntimeAdmissionCurrent() == true && a.get()?.isReady == true && runCatching {
                remote.validateOwner(captured, snapshot.lease)
                val actual = remote.validatePair(snapshot.lease, primaryRef.modelId, auxiliaryRef.modelId)
                actual.primaryHandle === snapshot.primaryHandle && actual.auxiliaryHandle === snapshot.auxiliaryHandle &&
                    journal.modelRef(snapshot.lease.cohortId, snapshot.lease.ownerEpoch, primaryRef.modelId) === primaryRef &&
                    journal.modelRef(snapshot.lease.cohortId, snapshot.lease.ownerEpoch, auxiliaryRef.modelId) === auxiliaryRef
            }.getOrDefault(false)
        }
    }
}

internal fun admitted(value: JointRuntimeMemoryAdmission): JointRuntimeMemoryReservation =
    (value as? JointRuntimeMemoryAdmission.Granted)?.reservation ?: resource((value as JointRuntimeMemoryAdmission.Rejected).reason)
internal fun resource(reason: String): Nothing = throw JointRuntimeSampleFailure(JointRuntimeSearchFailure(JointRuntimeSearchFailureKind.RESOURCE_BUDGET, reason))
