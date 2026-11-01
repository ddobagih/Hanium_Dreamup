package kr.co.hanium.dreamup.walksafe.inference.composite

import android.os.Looper
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckAuthority
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorLoadResult
import kr.co.hanium.dreamup.walksafe.inference.PrimaryTransferredDetector
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamTransferredModel
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.RemoteVisionClient
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRole
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeContext
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionTransferResult
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Target-local host wrappers. Never put this object in the process handoff mailbox. */
internal class TransferredJointRuntimeAttachment<T> private constructor(
    private val context: VisionRuntimeContext,
    private val config: TwoModelRuntimeConfig,
    private val choice: ValidatedJointRuntimeChoice,
    private val authority: DeviceCheckAuthority,
    val runtime: VisionTransferResult,
    val load: AndroidDetectorLoadResult,
    val auxiliary: FastSamRuntimeService<T>,
    private val deadlineMs: Long,
    private val current: () -> Boolean,
) {
    val modelIds = runtime.handles.map { it.modelInstanceId }.toSet()
    private var activated = false

    /** Registry adoption must precede this call. No OPEN, warmup or sample inference occurs. */
    fun activateAndProve(witness: JointPairTransferWitness): AndroidJointRuntimeLoader.PreparedPair<T> {
        requireWorker()
        admit(deadlineMs, current)
        check(!activated) { "Pair attachment already activated" }
        val client = RemoteVisionClient.forMainProcess(context.applicationContext)
        client.confirmAdoption(runtime.lease, deadlineMs)
        admit(deadlineMs, current)
        val primary = requireNotNull(load.detector)
        primary.activateTransferred()
        check(auxiliary.activateTransferred()) { "Transferred auxiliary activation rejected" }
        admit(deadlineMs, current)
        val proof = AndroidJointRuntimeLoader.reissueTransferredPairProof(context, config, choice, witness,
            runtime.lease, primary, auxiliary, authority)
        activated = true
        return AndroidJointRuntimeLoader.PreparedPair(load, choice, auxiliary, proof, proof.sampleEvidence)
    }

    /** A failed target closes its own wrappers; only the independent receipt proves native release. */
    fun closeHost(): CompletableFuture<Unit> {
        val auxiliaryDrained = try { auxiliary.closeAsync().handle { _, _ -> Unit } }
        catch (error: Throwable) { CompletableFuture<Unit>().also { it.completeExceptionally(error) } }
        runCatching { load.detector?.close() }
        // close() has returned from the primary's host lock. The auxiliary close callback is
        // after its copy/invoke/post/listener barrier even when the operation itself failed.
        // Neither is the independent native-release receipt; that belongs to the coordinator.
        return auxiliaryDrained
    }

    companion object {
        fun <T> attach(context: VisionRuntimeContext, config: TwoModelRuntimeConfig,
            choice: ValidatedJointRuntimeChoice, authority: DeviceCheckAuthority,
            primaryToken: PrimaryTransferredDetector, auxiliaryToken: FastSamTransferredModel,
            runtime: VisionTransferResult, auxiliaryEpoch: Long,
            listener: FastSamRuntimeService.Listener<T>, deadlineMs: Long, current: () -> Boolean,
            onPrimaryCreated: (AndroidDetectorLoadResult) -> Boolean = { true },
            onPrimaryCreationFinished: (Throwable?) -> Unit = {},
            onPrimaryCreationNotStarted: () -> Unit = {},
            onAuxiliaryCreated: (FastSamRuntimeService<T>) -> Boolean = { true },
        ): TransferredJointRuntimeAttachment<T> {
            requireWorker()
            var load: AndroidDetectorLoadResult? = null
            var auxiliary: FastSamRuntimeService<T>? = null
            var createEntered = false
            val client = RemoteVisionClient.forMainProcess(context.applicationContext)
            try {
                admit(deadlineMs, current)
                client.validateOwner(VisionRuntimeContext.ownerRef(context), runtime.lease)
                check(runtime.handles.size == 2 && runtime.handles.map { it.role }.toSet() ==
                    setOf(VisionRole.PRIMARY, VisionRole.FASTSAM)) { "Transferred pair roles differ" }
                var creationError: Throwable? = null
                try {
                    createEntered = true
                    load = TfliteAndroidFrameDetector.attachTransferred(context, config, primaryToken,
                        runtime.lease, runtime.handles)
                    if (!onPrimaryCreated(requireNotNull(load))) throw CancellationException("Primary target reservation retired")
                } catch (error: Throwable) { creationError = error; throw error }
                finally { onPrimaryCreationFinished(creationError) }
                admit(deadlineMs, current)
                val options = auxiliaryToken.options
                val targetOptions = FastSamRuntimeOptions(options.requestedBackend, options.numThreads,
                    options.allowCpuFallback, Math.multiplyExact(deadlineMs, 1_000_000L), options.maximumResultAgeMs,
                    options.captureRawOutputs, options.gpuPrecisionLossAllowed, options.gpuSerializationCacheEnabled)
                auxiliary = FastSamRuntimeService.attachTransferred(context, auxiliaryEpoch, targetOptions, listener,
                    auxiliaryToken, runtime.lease, runtime.handles.single { it.role == VisionRole.FASTSAM })
                if (!onAuxiliaryCreated(auxiliary)) throw CancellationException("Auxiliary target reservation retired")
                admit(deadlineMs, current)
                auxiliary.start().get(deadlineMs - SystemClock.elapsedRealtime(), TimeUnit.MILLISECONDS)
                admit(deadlineMs, current)
                return TransferredJointRuntimeAttachment(context, config, choice, authority, runtime,
                    requireNotNull(load), auxiliary, deadlineMs, current)
            } catch (error: Throwable) {
                runCatching { auxiliary?.closeAsync() }.exceptionOrNull()?.let { if (it !== error) error.addSuppressed(it) }
                runCatching { load?.detector?.close() }.exceptionOrNull()?.let { if (it !== error) error.addSuppressed(it) }
                client.requestExit(runtime.lease, "pair_target_attachment_failed")
                throw error
            } finally { if (!createEntered) onPrimaryCreationNotStarted() }
        }

        private fun requireWorker() = check(Looper.myLooper() != Looper.getMainLooper()) { "Pair attachment requires a worker" }
        private fun admit(deadlineMs: Long, current: () -> Boolean) {
            if (SystemClock.elapsedRealtime() >= deadlineMs || !current()) throw CancellationException("Pair transfer retired")
        }
    }
}
