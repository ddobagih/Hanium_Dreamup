package kr.co.hanium.dreamup.walksafe.inference.composite

import android.content.Context
import android.os.Looper
import kr.co.hanium.dreamup.walksafe.inference.*
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionStage
import java.util.IdentityHashMap
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeException
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeFailures

/** Both HOME and the walking primary owner restore the same complete device-check choice. */
object AndroidJointRuntimeLoader {
    internal val releaseFence = JointRuntimeReleaseFence()
    data class Prepared(val load: AndroidDetectorLoadResult, val choice: ValidatedJointRuntimeChoice)

    internal fun ensureNativeReleaseConfirmed() {
        releaseFence.ensureConfirmed()
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
        try {
            val options = requireNotNull(config.unifiedWalksafe).runtime.copy(
                delegate = choice.candidate.primary.backend.delegate,
                numThreads = choice.candidate.primary.numThreads, fallbackToCpu = false,
                gpuPrecisionLossAllowed = false, gpuSerializationCacheEnabled = true)
            val load = create(config.copy(fallbackModelKey = null, customTactile = null, cocoGeneral = null), options)
            detector = load.detector
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
                fence.retain(release, detector)
                if (release !== error) error.addSuppressed(release)
                // A cancellation is only logically retired. Failed native destruction must still
                // reach the physical owner's fence and must not be mistaken for a clean drain.
                throw if (error is DetectorNativeCleanupFailure) error else DetectorNativeCleanupFailure(error)
            }
            throw error
        }
    }

}

/** A failed native cleanup blocks every subsequent parent-process model allocation. */
internal class JointRuntimeReleaseFence {
    private class Pending(val error: Throwable, val detector: TfliteAndroidFrameDetector?)
    private class ReceiptCause(cause: Throwable, val receipt: CompletionStage<*>?) :
        RuntimeException("Native retirement is awaiting its physical receipt", cause)
    private val pending = IdentityHashMap<Throwable, Pending>()

    @Synchronized fun ensureConfirmed() {
        pending.values.firstOrNull()?.let { throw DetectorNativeCleanupFailure(it.error) }
    }

    fun requireCleanResult(load: AndroidDetectorLoadResult) {
        if (load.reason !in setOf("DetectorNativeCleanupFailure", "DetectorCohortRetired")) return
        // The same exception crosses prepare's catch and can be retained again after a fast ACK.
        // Keep its proof in the exception rather than only in an already-removed map entry.
        val error = DetectorNativeCleanupFailure(ReceiptCause(
            IllegalStateException("Native retirement reported as a failed load result"), load.actualReleasedFuture))
        retain(error, load.detector, load.actualReleasedFuture)
        throw error
    }

    fun retain(error: Throwable, detector: TfliteAndroidFrameDetector? = null,
        physicalRelease: CompletionStage<*>? = physicalReceipt(error)) {
        val retained = synchronized(this) {
            pending.getOrPut(error) { Pending(error, detector) }
        }
        // Operation failure is never release evidence. A late successful physical receipt may
        // release this exact failure; it cannot release another unknown/local native owner.
        physicalRelease?.whenComplete { _, releaseError ->
            if (releaseError == null) synchronized(this) {
                if (pending[error] === retained) pending.remove(error)
            }
        }
    }

    private fun physicalReceipt(error: Throwable): CompletionStage<*>? {
        val visited = java.util.Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = error
        while (cause != null && visited.add(cause)) {
            (cause as? ReceiptCause)?.receipt?.let { return it }
            (cause as? DetectorCohortRetired)?.actualReleasedFuture?.let { return it }
            (cause as? VisionRuntimeException)?.releaseReceipt?.let { return it }
            cause = cause.cause
        }
        return null
    }
}
