package kr.co.hanium.dreamup.walksafe.inference.unknown

import android.content.Context
import android.os.Build
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckAuthority
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckOwnerRef
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean

/** Parent-side ARGB transport and owned raw-output copy. This class never touches a native model. */
internal class RemoteFastSamEngine internal constructor(
    private val options: FastSamRuntimeOptions,
    private val lease: VisionCohortLease,
    private val handle: VisionModelHandle,
    private val invokeRemote: (VisionModelHandle, ByteBuffer, Array<ByteBuffer>, Int, Int, VisionPurpose, Long) -> VisionInvokeResult,
    private val closeRemote: (VisionModelHandle, Long) -> VisionCloseResult,
    private val requestExit: (String) -> Unit,
    private val physical: CompletableFuture<Void>,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val validateTransfer: () -> Unit = {},
    private val buffers: ParentDirectBufferArena.Lease = acquireBuffers(),
) : FastSamEngine {
    private val owner = Thread.currentThread().id
    private val closed = AtomicBoolean()
    private var info = decodeObserved(options, handle, handle.runtimeJson)
    private val outputs = buffers.outputViews()

    override fun runtime(): FastSamRuntimeInfo = info
    override fun invoke(ownedArgb: IntArray, width: Int, height: Int): FastSamEngine.Output =
        invokeOwned(ownedArgb, width, height, VisionPurpose.LIVE, deadline(nowMs(), VisionRuntimeProtocol.operationBudget(VisionPurpose.LIVE)))
    override fun invoke(ownedArgb: IntArray, width: Int, height: Int, token: FastSamFrameToken): FastSamEngine.Output =
        invokeToken(ownedArgb, width, height, token)
    override fun invoke(ownedArgb: IntArray, width: Int, height: Int, token: FastSamFrameToken,
        request: FastSamExecutionRequest): FastSamEngine.Output {
        require(token.width == width && token.height == height &&
            (token.source == FastSamFrameToken.Source.LIVE_CAMERA) == (request.purpose == VisionPurpose.LIVE))
        return invokeOwned(ownedArgb, width, height, request.purpose, request.absoluteDeadlineElapsedNs / 1_000_000L)
    }

    internal fun invokeOwned(ownedArgb: IntArray, width: Int, height: Int, purpose: VisionPurpose,
        deadlineMs: Long): FastSamEngine.Output {
        checkOwner(); check(!closed.get()) { "FastSAM remote engine closed" }
        require(width in 1..2048 && height in 1..2048 && ownedArgb.size == Math.multiplyExact(width, height))
        val now = nowMs()
        if (deadlineMs <= now) throw FastSamExecutionRequest.Expired()
        val executionDeadline = VisionRuntimeProtocol.deadline(deadlineMs, now, VisionRuntimeProtocol.operationBudget(purpose))
        val bytes = Math.multiplyExact(ownedArgb.size, 4)
        val wire = buffers.inputView(bytes)
        wire.asIntBuffer().put(ownedArgb)
        outputs.forEach(ByteBuffer::clear)
        val result = invokeRemote(handle, wire, outputs, width, height, purpose, executionDeadline)
        val observed = decodeObserved(options, handle, result.runtimeJson)
        check(observed.modelOwnerProcessId == info.modelOwnerProcessId && observed.modelOwnerThreadId == info.modelOwnerThreadId) {
            "FastSAM native identity changed"
        }
        require(listOf(result.preprocessingMs, result.inferenceMs, result.outputReadMs).all { it.isFinite() && it >= 0 })
        val copyStarted = System.nanoTime()
        val raw = outputs.map { output ->
            val owned = FloatArray(output.capacity() / 4)
            val read = output.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            read.clear()
            read.asFloatBuffer().get(owned)
            owned
        }.toTypedArray()
        info = observed
        return FastSamEngine.Output(raw, result.preprocessingMs, result.inferenceMs,
            result.outputReadMs + (System.nanoTime() - copyStarted) / 1e6)
    }

    internal fun invokeToken(ownedArgb: IntArray, width: Int, height: Int, token: FastSamFrameToken): FastSamEngine.Output {
        require(token.width == width && token.height == height)
        val purpose = if (token.source == FastSamFrameToken.Source.LIVE_CAMERA) VisionPurpose.LIVE else VisionPurpose.BENCHMARK
        val limit = deadline(nowMs(), VisionRuntimeProtocol.operationBudget(purpose))
        return invokeOwned(ownedArgb, width, height, purpose, limit)
    }

    override fun close() {
        checkOwner()
        if (!closed.compareAndSet(false, true)) return
        try {
            val result = closeRemote(handle, deadline(nowMs(), VisionRuntimeProtocol.CLOSE_MS))
            check(result.modelInstanceId == handle.modelInstanceId && result.nativeCloseConfirmed) { "FastSAM native close unconfirmed" }
            // close(true) includes the per-handle native barrier and parent mapping/FD release.
            physical.complete(null)
        } catch (error: Throwable) {
            try { requestExit("fastsam_close_failed") } catch (control: Throwable) {
                if (control !== error) error.addSuppressed(control)
            }
            // A semantic error cannot poison the recoverable physical receipt. Late child death
            // completes it independently, after the service has drained its CPU consumer.
            throw error
        } finally { buffers.releaseAfterHostDrain() }
    }

    override fun detachForTransfer(): FastSamTransferredModel {
        checkOwner()
        check(!closed.get()) { "FastSAM proxy already retired" }
        validateTransfer()
        check(closed.compareAndSet(false, true)) { "FastSAM proxy already retired" }
        buffers.releaseAfterHostDrain()
        return FastSamTransferredModel(lease, handle, options)
    }

    private fun checkOwner() { check(Thread.currentThread().id == owner) { "FastSAM parent proxy owner changed" } }

    companion object {
        private const val MAXIMUM_WIRE_BYTES = 2048 * 2048 * Int.SIZE_BYTES
        internal fun acquireBuffers(): ParentDirectBufferArena.Lease = ParentDirectBufferArena.process().acquire(
            "fastsam-parent-argb-2048", MAXIMUM_WIRE_BYTES,
            intArrayOf(FastSamModelContract.DETECTION_FLOATS * Float.SIZE_BYTES,
                FastSamModelContract.PROTOTYPE_FLOATS * Float.SIZE_BYTES))
        @JvmStatic @JvmOverloads fun factory(context: Context, options: FastSamRuntimeOptions,
            ownerId: String = VisionRuntimeContext.ownerId(context)): FastSamEngine.Factory {
            val owner = VisionRuntimeContext.ownerRef(context)
            require(owner.ownerId == ownerId) { "FastSAM request owner differs" }
            return RemoteFactory(context.applicationContext, options, owner, VisionRuntimeContext.diagnosticAuthority(context))
        }
        @JvmStatic fun attachedFactory(context: Context, options: FastSamRuntimeOptions,
            lease: VisionCohortLease, handle: VisionModelHandle): FastSamEngine.Factory {
            val owner = VisionRuntimeContext.ownerRef(context)
            require(owner.ownerId == lease.ownerId) { "FastSAM attachment owner differs" }
            return RemoteFactory(context.applicationContext, options, owner, VisionRuntimeContext.diagnosticAuthority(context), lease, handle)
        }
        internal fun deadline(now: Long, budget: Long) = if (now > Long.MAX_VALUE - budget) Long.MAX_VALUE else now + budget

        internal fun decodeObserved(options: FastSamRuntimeOptions, handle: VisionModelHandle, json: String): FastSamRuntimeInfo {
            require(handle.role == VisionRole.FASTSAM && handle.modelKey == FastSamModelContract.MODEL_ID &&
                VisionRuntimeProtocol.TOKEN.matches(handle.cohortId) && VisionRuntimeProtocol.KEY.matches(handle.modelInstanceId))
            val value = VisionRuntimeCodec.objectJson(VisionRuntimeCodec.validateRuntimeJson(json))
            val expected = options.requestedBackend.name.lowercase()
            require(value.getString("requestedDelegate") == expected && value.getString("activeDelegate") == expected &&
                value.getInt("numThreads") == options.numThreads && !value.getBoolean("fallbackUsed"))
            val gpu = options.requestedBackend == FastSamRuntimeOptions.Backend.GPU
            require(value.getBoolean("gpuDelegateAttached") == gpu &&
                value.getBoolean("gpuPrecisionLossRequested") == options.gpuPrecisionLossAllowed &&
                value.getBoolean("gpuPrecisionLossAllowed") == (gpu && options.gpuPrecisionLossAllowed) &&
                value.getBoolean("gpuSerializationCacheRequested") == options.gpuSerializationCacheEnabled)
            val status = value.getString("gpuSerializationCacheStatus")
            require(value.getBoolean("gpuSerializationCacheConfigured") == (gpu && status == "configured"))
            fun optional(key: String) = if (value.isNull(key)) null else value.getString(key)
            return FastSamRuntimeInfo(options, FastSamRuntimeOptions.Backend.valueOf(value.getString("activeDelegate").uppercase()),
                optional("fallbackReason"), value.getLong("modelOwnerThreadId"), value.getDouble("loadMs"),
                status, optional("gpuSerializationCacheToken"), optional("gpuSerializationCacheFailureReason"),
                value.getInt("gpuInitializationAttempts"), value.getLong("modelOwnerPid"), handle.modelInstanceId, handle.cohortId)
        }
    }

    private class RemoteFactory(private val context: Context, private val options: FastSamRuntimeOptions,
        private val owner: DeviceCheckOwnerRef, private val diagnostics: DeviceCheckAuthority?,
        private val attachedLease: VisionCohortLease? = null,
        private val attachedHandle: VisionModelHandle? = null) : FastSamEngine.Factory {
        private val actual = CompletableFuture<Void>()
        private val cancelled = AtomicBoolean()
        @Volatile private var client: RemoteVisionClient? = null
        @Volatile private var lease: VisionCohortLease? = attachedLease
        @Volatile private var model: VisionModelHandle? = attachedHandle
        @Volatile private var attachmentActivated = attachedHandle == null
        private val observedCohort = AtomicBoolean()
        private val observedModel = AtomicBoolean()
        @Volatile private var ownerValidated = false
        override fun actualReleasedFuture(): CompletableFuture<Void> = actual
        override fun supportsTransfer() = true
        override fun reportNativeOutputFault(failure: FastSamModelContract.NativeOutputException) {
            val captured = lease ?: return
            if (!ownerValidated) return
            requireNotNull(client).reportNativeFault(owner, captured, VisionNativeFaultReason.valueOf(failure.reason.name))
        }
        override fun validateTransferredAttachment() {
            val owned = requireNotNull(attachedLease) { "FastSAM factory has no transferred model" }
            val captured = requireNotNull(attachedHandle)
            val remote = client ?: RemoteVisionClient.forMainProcess(context).also { client = it }
            remote.validateOwner(owner, owned)
            ownerValidated = true
            remote.validateAttach(owned, captured)
            decodeObserved(options, captured, captured.runtimeJson)
            observeCohort(remote, owned)
            observeModel(remote, owned, captured)
        }
        override fun activateTransferred() {
            val owned = requireNotNull(attachedLease)
            val captured = requireNotNull(attachedHandle)
            requireNotNull(client).validateOwner(owner, owned)
            requireNotNull(client).validateAdopted(owned, captured)
            attachmentActivated = true
        }
        override fun canAdmit(): Boolean {
            if (!attachmentActivated) return false
            val remote = client ?: return false
            val owned = lease ?: return false
            val captured = model ?: return false
            return runCatching { remote.validateOwner(owner, owned); remote.validateAdopted(owned, captured) }.isSuccess
        }
        override fun cancelPendingLoad() {
            cancelled.set(true)
            val captured = lease ?: return
            if (ownerValidated) client?.requestExit(captured, "fastsam_load_cancelled")
        }
        override fun create(): FastSamEngine {
            var buffers: ParentDirectBufferArena.Lease? = null
            var handedToEngine = false
            try {
                require(Build.VERSION.SDK_INT >= 31) { "vision_android_api_unsupported" }
                if (cancelled.get()) throw CancellationException("FastSAM creation retired")
                if (attachedHandle != null) {
                    validateTransferredAttachment()
                    if (cancelled.get()) throw CancellationException("FastSAM attachment retired")
                    buffers = acquireBuffers()
                    return proxy(requireNotNull(client), requireNotNull(attachedLease), attachedHandle, buffers).also { handedToEngine = true }
                }
                val spec = FastSamNativeBridge.spec(options).also(FastSamNativeBridge::validateSpec)
                val remote = RemoteVisionClient.forMainProcess(context.applicationContext).also { client = it }
                val deadline = options.loadDeadlineElapsedNs / 1_000_000L
                val owned = (diagnostics?.let { remote.acquireForDiagnostics(owner, deadline, it) }
                    ?: remote.acquire(owner, deadline)).also { lease = it; ownerValidated = true }
                observeCohort(remote, owned)
                if (cancelled.get()) { remote.requestExit(owned, "fastsam_load_cancelled"); throw CancellationException("FastSAM creation retired") }
                // The nonnative lease preserves typed foreign-owner Busy. Buffers are still
                // admitted/charged before OPEN can grant any new native allocation.
                buffers = acquireBuffers()
                val opened = remote.open(owned, spec, options.loadDeadlineElapsedNs / 1_000_000L).also { model = it }
                observeModel(remote, owned, opened)
                if (cancelled.get()) { remote.requestExit(owned, "fastsam_load_cancelled"); throw CancellationException("FastSAM creation retired") }
                return proxy(remote, owned, opened, buffers).also { handedToEngine = true }
            } catch (error: Throwable) {
                val owned = lease
                if (owned != null && ownerValidated) client?.requestExit(owned, "fastsam_open_failed")
                else if (error is VisionRuntimeException && error.releaseReceipt != null) observe(error.releaseReceipt)
                else actual.complete(null) // No lease/OPEN admission was ever acquired.
                throw error
            } finally { if (!handedToEngine) buffers?.releaseAfterHostDrain() }
        }
        private fun proxy(remote: RemoteVisionClient, owned: VisionCohortLease, model: VisionModelHandle,
            buffers: ParentDirectBufferArena.Lease) =
            RemoteFastSamEngine(options, owned, model, remote::invoke, remote::close,
                { reason -> remote.requestExit(owned, reason) }, actual,
                validateTransfer = { remote.validateAttach(owned, model) }, buffers = buffers)
        private fun observeCohort(remote: RemoteVisionClient, owned: VisionCohortLease) {
            if (observedCohort.compareAndSet(false, true)) observe(remote.actualReleasedFuture(owned.cohortId))
        }
        private fun observeModel(remote: RemoteVisionClient, owned: VisionCohortLease, model: VisionModelHandle) {
            if (!observedModel.compareAndSet(false, true)) return
            remote.modelReleasedFuture(model.modelInstanceId).whenComplete { receipt, error ->
                if (error == null && receipt != null && receipt.cohortId == owned.cohortId &&
                    receipt.modelInstanceId == model.modelInstanceId && receipt.childPid > 0) actual.complete(null)
            }
        }
        private fun observe(receipt: CompletionStage<VisionReleaseReceipt>) {
            receipt.whenComplete { proof, error ->
                if (error == null && proof != null &&
                    (proof.releaseProof == VisionReleaseProof.NO_NATIVE_START || proof.childPid > 0)) actual.complete(null)
            }
        }
    }
}
