package kr.co.hanium.dreamup.walksafe.inference

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletionStage
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*

/** No local delegate fallback can repair an invalidated cohort containing other model handles. */
internal class DetectorCohortRetired(cause: Throwable, val actualReleasedFuture: CompletionStage<Unit>) :
    RuntimeException("primary remote cohort retired", cause)

/** Narrow SDK-free port for exercising OPEN/invoke/close ownership without native test doubles. */
internal interface PrimaryTensorRemotePort {
    fun acquire(deadlineMs: Long): VisionCohortLease
    fun released(lease: VisionCohortLease): CompletionStage<*>
    fun open(lease: VisionCohortLease, spec: VisionModelSpec, deadlineMs: Long): VisionModelHandle
    fun modelReleased(handle: VisionModelHandle): CompletionStage<*>
    fun invoke(handle: VisionModelHandle, input: ByteBuffer, output: ByteBuffer, width: Int, height: Int,
        purpose: VisionPurpose, deadlineMs: Long): VisionInvokeResult
    fun close(handle: VisionModelHandle, deadlineMs: Long): VisionCloseResult
    fun exit(lease: VisionCohortLease, reason: String)
    fun validateAttach(lease: VisionCohortLease, handle: VisionModelHandle)
    fun validateAdopted(lease: VisionCohortLease, handle: VisionModelHandle)
    fun reportNativeOutputFault(lease: VisionCohortLease, handle: VisionModelHandle, reason: VisionNativeFaultReason)
}

internal class AndroidPrimaryTensorRemotePort(private val client: RemoteVisionClient,
    private val scope: PrimaryTensorEngines.OwnerScope) : PrimaryTensorRemotePort {
    override fun acquire(deadlineMs: Long) = scope.diagnostics?.let {
        client.acquireForDiagnostics(scope.owner, deadlineMs, it)
    } ?: client.acquire(scope.owner, deadlineMs)
    override fun released(lease: VisionCohortLease) = client.actualReleasedFuture(lease.cohortId)
    override fun open(lease: VisionCohortLease, spec: VisionModelSpec, deadlineMs: Long) = client.open(lease, spec, deadlineMs)
    override fun modelReleased(handle: VisionModelHandle) = client.modelReleasedFuture(handle.modelInstanceId)
    override fun invoke(handle: VisionModelHandle, input: ByteBuffer, output: ByteBuffer, width: Int, height: Int,
        purpose: VisionPurpose, deadlineMs: Long) = client.invoke(handle, input, arrayOf(output), width, height, purpose, deadlineMs)
    override fun close(handle: VisionModelHandle, deadlineMs: Long) = client.close(handle, deadlineMs)
    override fun exit(lease: VisionCohortLease, reason: String) = client.requestExit(lease, reason)
    override fun validateAttach(lease: VisionCohortLease, handle: VisionModelHandle) {
        client.validateOwner(scope.owner, lease)
        client.validateAttach(lease, handle)
    }
    override fun validateAdopted(lease: VisionCohortLease, handle: VisionModelHandle) {
        client.validateOwner(scope.owner, lease)
        client.validateAdopted(lease, handle)
    }
    override fun reportNativeOutputFault(lease: VisionCohortLease, handle: VisionModelHandle, reason: VisionNativeFaultReason) {
        client.validateAttach(lease, handle)
        client.reportNativeFault(scope.owner, lease, reason)
    }
}

internal class RemotePrimaryTensorFactory(private val port: PrimaryTensorRemotePort,
    private val now: () -> Long, private val openDeadlineMs: Long,
    private val attachment: PrimaryTransferredAttachment? = null,
    private val arena: ParentDirectBufferArena? = null,
) : PrimaryTensorEngineFactory {
    private val lock = Any()
    private val release = PrimaryTensorRelease()
    override val actualReleasedFuture get() = release.actualReleasedFuture
    private var lease: VisionCohortLease? = null
    private var ownsLease = false
    private var cohortReceipt: CompletionStage<*>? = null
    private var failure: Throwable? = null
    private var closed = false
    private var opening = 0
    private var inFlight = 0
    private var attachmentActivated = attachment == null
    private val attachedSlots = mutableMapOf<String, PrimaryTensorRelease.Slot>()
    private val models = mutableListOf<RemoteEngine>()
    private val hostBuffers = mutableMapOf<String, ParentDirectBufferArena.Lease>()

    override fun inputBuffer(modelSize: Int): ByteBuffer = arenaBuffer("primary-input-$modelSize", modelSize * modelSize * 3 * 4, true)
    override fun outputBuffer(modelKey: String, bytes: Int): ByteBuffer = arenaBuffer("primary-output-$modelKey-$bytes", bytes, false)
    private fun arenaBuffer(key: String, bytes: Int, input: Boolean): ByteBuffer {
        val previous = synchronized(lock) {
            VisionRuntimeFailures.busy(failure)?.let { throw it }
            if (closed || failure != null) throw retired(failure ?: IllegalStateException("primary host backing retired"))
            hostBuffers[key]
        }
        val shared = arena ?: return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        if (previous != null) return if (input) previous.inputView() else previous.outputViews().single()
        val acquired = shared.acquire(key, if (input) bytes else 0, if (input) intArrayOf() else intArrayOf(bytes))
        val accepted = synchronized(lock) {
            if (closed || hostBuffers.containsKey(key)) false else { hostBuffers[key] = acquired; true }
        }
        if (!accepted) { acquired.releaseAfterHostDrain(); throw IllegalStateException("primary host backing retired") }
        return if (input) acquired.inputView() else acquired.outputViews().single()
    }
    private fun releaseHostBuffersIfDrained() {
        val drained = synchronized(lock) {
            if (!closed || opening != 0 || inFlight != 0) emptyList()
            else hostBuffers.values.toList().also { hostBuffers.clear() }
        }
        drained.forEach { check(it.releaseAfterHostDrain()) }
    }

    init {
        try {
            lease = attachment?.lease ?: port.acquire(openDeadlineMs)
            if (attachment == null) ownsLease = true
            cohortReceipt = port.released(requireNotNull(lease))
            attachment?.handles?.forEach { handle ->
                val slot = release.beforeOpen()
                attachedSlots[handle.modelKey] = slot
                cohortReceipt?.let { release.follow(slot, it) }
            }
            attachment?.handles?.forEach { handle ->
                release.follow(attachedSlots.getValue(handle.modelKey), port.modelReleased(handle))
                port.validateAttach(requireNotNull(lease), handle)
            }
            ownsLease = true
        } catch (error: Throwable) {
            failure = error
            (error as? VisionRuntimeException)?.releaseReceipt?.let { release.follow(release.beforeOpen(), it) }
        }
    }

    override fun open(model: ModelRuntimeConfig, delegate: String, recorder: DetectorLoadTimingRecorder,
        modelKey: String): PrimaryTensorEngine {
        val spec = PrimaryTensorEngines.spec(model, delegate, modelKey)
        val slot = synchronized(lock) {
            VisionRuntimeFailures.busy(failure)?.let { throw it }
            if (closed || failure != null) throw retired(failure ?: IllegalStateException("primary factory closed"))
            opening += 1
            attachedSlots[modelKey] ?: release.beforeOpen()
        }
        val ownedLease = requireNotNull(lease)
        try {
            val handle = if (attachment == null) port.open(ownedLease, spec, openDeadlineMs)
            else {
                val pinned = attachment.specs.single { it.modelKey == modelKey }
                require(spec.assetPath == pinned.assetPath && spec.assetSha256 == pinned.assetSha256 &&
                    spec.inputShape.contentEquals(pinned.inputShape) && spec.outputShapes.size == pinned.outputShapes.size &&
                    spec.outputShapes.indices.all { spec.outputShapes[it].contentEquals(pinned.outputShapes[it]) } &&
                    VisionRuntimeCodec.decodeOptions(spec.runtimeJson) == VisionRuntimeCodec.decodeOptions(pinned.runtimeJson))
                check(synchronized(lock) { models.none { it.handle.modelKey == modelKey } }) { "duplicate primary attachment" }
                attachment.handles.single { it.modelKey == modelKey }.also { port.validateAttach(ownedLease, it) }
            }
            release.follow(slot, port.modelReleased(handle))
            val engine = RemoteEngine(handle, spec, slot)
            val accepted = synchronized(lock) {
                if (closed || failure != null) false else { models += engine; true }
            }
            if (!accepted) {
                engine.close()
                throw retired(IllegalStateException("primary OPEN completed after retirement"))
            }
            return engine
        } catch (error: Throwable) {
            cohortReceipt?.let { release.follow(slot, it) }
            retire(error)
            throw retired(error)
        } finally { synchronized(lock) { opening -= 1 }; releaseHostBuffersIfDrained() }
    }

    override fun close() {
        val owned = synchronized(lock) {
            if (closed) return
            closed = true
            models.toList() to (opening > 0 || failure != null || !attachmentActivated ||
                (attachment != null && models.size != attachment.handles.size))
        }
        var closeFailure: Throwable? = null
        if (owned.second && ownsLease) lease?.let {
            try { port.exit(it, "primary_factory_retired") } catch (error: Throwable) { closeFailure = error }
        }
        owned.first.filter { attachmentActivated }.forEach {
            try { it.close() } catch (error: Throwable) {
                if (closeFailure == null) closeFailure = error else if (closeFailure !== error) closeFailure?.addSuppressed(error)
            }
        }
        release.seal()
        releaseHostBuffersIfDrained()
        closeFailure?.let { throw it }
    }

    override fun activateTransferred() {
        val captured = synchronized(lock) {
            check(attachment != null && !closed && failure == null && opening == 0 &&
                models.size == attachment.handles.size && models.none { it.engineClosed })
            requireNotNull(lease) to models.toList()
        }
        captured.second.forEach { port.validateAdopted(captured.first, it.handle) }
        synchronized(lock) {
            check(!closed && failure == null && lease === captured.first && opening == 0 &&
                models.size == captured.second.size && models.indices.all { models[it] === captured.second[it] && !models[it].engineClosed })
            attachmentActivated = true
        }
    }

    override fun canAdmit(): Boolean {
        val captured = synchronized(lock) {
            if (closed || failure != null || !attachmentActivated || models.isEmpty() || models.any { it.engineClosed }) return false
            requireNotNull(lease) to models.toList()
        }
        if (!captured.second.all { runCatching { port.validateAdopted(captured.first, it.handle) }.isSuccess }) return false
        return synchronized(lock) {
            !closed && failure == null && attachmentActivated && lease === captured.first &&
                models.size == captured.second.size && models.indices.all { models[it] === captured.second[it] && !models[it].engineClosed }
        }
    }

    override fun detachForTransfer(): PrimaryTransferredDetector {
        val captured = synchronized(lock) {
            check(!closed && failure == null && opening == 0 && inFlight == 0 && models.isNotEmpty() &&
                models.none { it.engineClosed } && attachmentActivated) {
                "primary consumers have not drained or are not adopted"
            }
            requireNotNull(lease) to models.toList()
        }
        val token = try {
            captured.second.forEach { port.validateAttach(captured.first, it.handle) }
            PrimaryTransferredDetector(captured.first, captured.second.map { it.spec }, captured.second.map { it.handle })
        } catch (error: Throwable) {
            synchronized(lock) {
                closed = true
                captured.second.forEach { it.engineClosed = true }
            }
            captured.second.forEach { model -> cohortReceipt?.let { release.follow(model.slot, it) } }
            retire(error)
            release.seal()
            releaseHostBuffersIfDrained()
            throw retired(error)
        }
        synchronized(lock) {
            check(!closed && failure == null && opening == 0 && inFlight == 0 && lease === captured.first &&
                models.size == captured.second.size && models.indices.all { models[it] === captured.second[it] && !models[it].engineClosed })
            closed = true
            captured.second.forEach { it.engineClosed = true }
        }
        captured.second.forEach { model -> cohortReceipt?.let { release.follow(model.slot, it) } }
        release.seal()
        releaseHostBuffersIfDrained()
        return token
    }

    private fun retired(error: Throwable) = if (error is DetectorCohortRetired) error else DetectorCohortRetired(error, actualReleasedFuture)
    private fun retire(error: Throwable) {
        synchronized(lock) { if (failure == null) failure = error }
        lease?.takeIf { ownsLease }?.let { owned ->
            try { port.exit(owned, "primary_remote_failed") } catch (exitFailure: Throwable) {
                if (exitFailure !== error) error.addSuppressed(exitFailure)
            }
        }
    }

    private inner class RemoteEngine(val handle: VisionModelHandle, val spec: VisionModelSpec,
        val slot: PrimaryTensorRelease.Slot) : PrimaryTensorEngine {
        private val options = VisionRuntimeCodec.decodeOptions(spec.runtimeJson)
        private var observed = PrimaryTensorRuntime.decode(handle.runtimeJson, handle)
        var engineClosed = false
        override val runtime get() = observed

        init {
            require(handle.cohortId == lease?.cohortId && handle.ownerEpoch == lease?.ownerEpoch &&
                handle.role == VisionRole.PRIMARY && handle.modelKey == spec.modelKey) { "primary OPEN handle binding differs" }
            requireRuntime(observed)
        }

        private fun requireRuntime(actual: AndroidDetectorRuntime) {
            PrimaryTensorContract.observedRuntime(actual, AndroidDetectorRuntime(options.delegate, options.delegate,
                options.numThreads, gpuPrecisionLossAllowed = options.gpuPrecisionLossAllowed.takeIf { options.delegate == "gpu" }))
        }

        override fun invoke(input: ByteBuffer, output: ByteBuffer, width: Int, height: Int,
            purpose: VisionPurpose): PrimaryTensorResult = invoke(input, output, width, height, purpose, Long.MAX_VALUE)

        override fun invoke(input: ByteBuffer, output: ByteBuffer, width: Int, height: Int,
            purpose: VisionPurpose, absoluteDeadlineMs: Long): PrimaryTensorResult {
            PrimaryTensorContract.requireBuffers(input, output, spec.inputShape[1], spec.outputShapes.single(), width, height)
            val startedAt = now()
            if (absoluteDeadlineMs <= startedAt) throw java.util.concurrent.TimeoutException("primary caller deadline expired")
            synchronized(lock) {
                if (closed || engineClosed || failure != null) throw retired(failure ?: IllegalStateException("primary model closed"))
                if (!attachmentActivated) throw DetectorAttachmentInactive()
                inFlight += 1
            }
            try {
                port.validateAdopted(requireNotNull(lease), handle)
                val deadline = VisionRuntimeProtocol.deadline(absoluteDeadlineMs, startedAt, VisionRuntimeProtocol.operationBudget(purpose))
                val result = port.invoke(handle, input, output, width, height, purpose, deadline)
                val completedAt = now()
                if (completedAt < startedAt || completedAt >= deadline)
                    throw java.util.concurrent.TimeoutException("primary result arrived after its operation deadline")
                val actual = PrimaryTensorRuntime.decode(result.runtimeJson, handle)
                requireRuntime(actual)
                check(actual.modelOwnerPid == observed.modelOwnerPid && actual.modelOwnerThreadId == observed.modelOwnerThreadId) {
                    "primary native owner differs from the opened model"
                }
                require(listOf(result.inferenceMs, result.outputReadMs).all { it.isFinite() && it >= 0.0 })
                requireFiniteNativeOutput(input, output)
                observed = actual
                return PrimaryTensorResult(actual, result.inferenceMs.toLong(), result.outputReadMs.toLong())
            } catch (error: Throwable) {
                cohortReceipt?.let { release.follow(slot, it) }
                retire(error)
                throw retired(error)
            } finally { synchronized(lock) { inFlight -= 1 }; releaseHostBuffersIfDrained() }
        }

        private fun requireFiniteNativeOutput(input: ByteBuffer, output: ByteBuffer) {
            val values = output.duplicate().order(ByteOrder.nativeOrder()).apply { clear() }.asFloatBuffer()
            while (values.hasRemaining()) {
                if (values.get().isFinite()) continue
                // Caller-created malformed tensors are not evidence against this native model.
                // The ordinary preprocessor emits normalized finite RGB. Scan it only on the
                // exceptional output path, avoiding another full input pass on every frame.
                val source = input.duplicate().order(ByteOrder.nativeOrder()).apply { clear() }.asFloatBuffer()
                while (source.hasRemaining()) {
                    val value = source.get()
                    if (!value.isFinite() || value < 0f || value > 1f) throw DetectorTensorContractViolation(
                        DetectorLoadFailureCode.INPUT_CONTRACT_MISMATCH, "primary input contains invalid normalized RGB")
                }
                val failure = DetectorTensorContractViolation(DetectorLoadFailureCode.OUTPUT_CONTRACT_MISMATCH,
                    "primary native output contains a non-finite value")
                try { port.reportNativeOutputFault(requireNotNull(lease), handle, VisionNativeFaultReason.NON_FINITE_OUTPUT) }
                catch (reportFailure: Throwable) { if (reportFailure !== failure) failure.addSuppressed(reportFailure) }
                throw failure
            }
        }

        override fun close() {
            val needsClose = synchronized(lock) { if (engineClosed) false else { engineClosed = true; true } }
            if (!needsClose) return
            if (!attachmentActivated) {
                cohortReceipt?.let { release.follow(slot, it) }
                lease?.takeIf { ownsLease }?.let { port.exit(it, "primary_dormant_attachment_closed") }
                return
            }
            try {
                val result = port.close(handle, VisionRuntimeProtocol.deadline(Long.MAX_VALUE, now(), VisionRuntimeProtocol.CLOSE_MS))
                check(result.modelInstanceId == handle.modelInstanceId && result.nativeCloseConfirmed) { "primary close unconfirmed" }
            } catch (error: Throwable) {
                cohortReceipt?.let { release.follow(slot, it) }
                retire(error)
                throw retired(error)
            }
        }
    }
}
