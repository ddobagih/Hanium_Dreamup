package kr.co.hanium.dreamup.walksafe.inference

import java.nio.ByteBuffer
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
}

internal class AndroidPrimaryTensorRemotePort(private val client: RemoteVisionClient, private val ownerId: String) : PrimaryTensorRemotePort {
    override fun acquire(deadlineMs: Long) = client.acquire(ownerId, deadlineMs)
    override fun released(lease: VisionCohortLease) = client.actualReleasedFuture(lease.cohortId)
    override fun open(lease: VisionCohortLease, spec: VisionModelSpec, deadlineMs: Long) = client.open(lease, spec, deadlineMs)
    override fun modelReleased(handle: VisionModelHandle) = client.modelReleasedFuture(handle.modelInstanceId)
    override fun invoke(handle: VisionModelHandle, input: ByteBuffer, output: ByteBuffer, width: Int, height: Int,
        purpose: VisionPurpose, deadlineMs: Long) = client.invoke(handle, input, arrayOf(output), width, height, purpose, deadlineMs)
    override fun close(handle: VisionModelHandle, deadlineMs: Long) = client.close(handle, deadlineMs)
    override fun exit(lease: VisionCohortLease, reason: String) = client.requestExit(lease, reason)
}

internal class RemotePrimaryTensorFactory(private val port: PrimaryTensorRemotePort,
    private val now: () -> Long, private val openDeadlineMs: Long) : PrimaryTensorEngineFactory {
    private val lock = Any()
    private val release = PrimaryTensorRelease()
    override val actualReleasedFuture get() = release.actualReleasedFuture
    private var lease: VisionCohortLease? = null
    private var cohortReceipt: CompletionStage<*>? = null
    private var failure: Throwable? = null
    private var closed = false
    private var opening = 0
    private val models = mutableListOf<RemoteEngine>()

    init {
        try {
            lease = port.acquire(openDeadlineMs)
            cohortReceipt = port.released(requireNotNull(lease))
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
            release.beforeOpen()
        }
        val ownedLease = requireNotNull(lease)
        try {
            val handle = port.open(ownedLease, spec, openDeadlineMs)
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
        } finally { synchronized(lock) { opening -= 1 } }
    }

    override fun close() {
        val owned = synchronized(lock) {
            if (closed) return
            closed = true
            models.toList() to (opening > 0 || failure != null)
        }
        var closeFailure: Throwable? = null
        if (owned.second) lease?.let {
            try { port.exit(it, "primary_factory_retired") } catch (error: Throwable) { closeFailure = error }
        }
        owned.first.forEach {
            try { it.close() } catch (error: Throwable) {
                if (closeFailure == null) closeFailure = error else if (closeFailure !== error) closeFailure?.addSuppressed(error)
            }
        }
        release.seal()
        closeFailure?.let { throw it }
    }

    private fun retired(error: Throwable) = if (error is DetectorCohortRetired) error else DetectorCohortRetired(error, actualReleasedFuture)
    private fun retire(error: Throwable) {
        synchronized(lock) { if (failure == null) failure = error }
        lease?.let { owned ->
            try { port.exit(owned, "primary_remote_failed") } catch (exitFailure: Throwable) {
                if (exitFailure !== error) error.addSuppressed(exitFailure)
            }
        }
    }

    private inner class RemoteEngine(private val handle: VisionModelHandle, private val spec: VisionModelSpec,
        private val slot: PrimaryTensorRelease.Slot) : PrimaryTensorEngine {
        private val options = VisionRuntimeCodec.decodeOptions(spec.runtimeJson)
        private var observed = PrimaryTensorRuntime.decode(handle.runtimeJson, handle)
        private var engineClosed = false
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
            purpose: VisionPurpose): PrimaryTensorResult {
            PrimaryTensorContract.requireBuffers(input, output, spec.inputShape[1], spec.outputShapes.single(), width, height)
            synchronized(lock) {
                if (closed || engineClosed || failure != null) throw retired(failure ?: IllegalStateException("primary model closed"))
            }
            try {
                val deadline = VisionRuntimeProtocol.deadline(Long.MAX_VALUE, now(), VisionRuntimeProtocol.operationBudget(purpose))
                val result = port.invoke(handle, input, output, width, height, purpose, deadline)
                val actual = PrimaryTensorRuntime.decode(result.runtimeJson, handle)
                requireRuntime(actual)
                check(actual.modelOwnerPid == observed.modelOwnerPid && actual.modelOwnerThreadId == observed.modelOwnerThreadId) {
                    "primary native owner differs from the opened model"
                }
                require(listOf(result.inferenceMs, result.outputReadMs).all { it.isFinite() && it >= 0.0 })
                observed = actual
                return PrimaryTensorResult(actual, result.inferenceMs.toLong(), result.outputReadMs.toLong())
            } catch (error: Throwable) {
                cohortReceipt?.let { release.follow(slot, it) }
                retire(error)
                throw retired(error)
            }
        }

        override fun close() {
            val needsClose = synchronized(lock) { if (engineClosed) false else { engineClosed = true; true } }
            if (!needsClose) return
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
