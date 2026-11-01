package kr.co.hanium.dreamup.walksafe.inference.composite

import android.content.Context
import android.os.Looper
import kr.co.hanium.dreamup.walksafe.inference.*
import java.util.concurrent.CancellationException

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
        if (!isCurrent()) throw CancellationException("Joint runtime restoration retired")
        var detector: TfliteAndroidFrameDetector? = null
        try {
            val options = requireNotNull(config.unifiedWalksafe).runtime.copy(
                delegate = choice.candidate.primary.backend.delegate,
                numThreads = choice.candidate.primary.numThreads, fallbackToCpu = false,
                gpuPrecisionLossAllowed = false, gpuSerializationCacheEnabled = true)
            val load = TfliteAndroidFrameDetector.createWithStatus(context,
                config.copy(fallbackModelKey = null, customTactile = null, cocoGeneral = null), options,
                YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
            detector = load.detector
            releaseFence.requireCleanResult(load)
            check(load.detectorAvailable && load.configLoaded && !load.fallbackUsed && detector != null)
            val fixtures = RuntimeCalibrationFixtures(context)
            val sample = detector.detectCalibrationImage(fixtures.loadArgb(fixtures.loadManifest().fixtures.first { it.required }))
            val runtime = requireNotNull(sample.timing.modelRuntime)
            check(sample.inferenceCompleted && !sample.partial && !runtime.fallbackUsed &&
                runtime.activeDelegate == choice.candidate.primary.backend.delegate &&
                runtime.requestedDelegate == choice.candidate.primary.backend.delegate &&
                runtime.numThreads == choice.candidate.primary.numThreads && runtime.gpuPrecisionLossAllowed != true) {
                "Joint runtime differs from measured choice"
            }
            if (!isCurrent()) throw CancellationException("Joint runtime restoration retired")
            return Prepared(load.copy(reason = "DeviceCheckJointRuntimeChoice"), choice)
        } catch (error: Throwable) {
            if (error is DetectorNativeCleanupFailure) releaseFence.retain(error, detector)
            // A cancellation does not invalidate a good choice; an execution mismatch does.
            try { detector?.close() } catch (close: Throwable) {
                releaseFence.retain(close, detector)
                error.addSuppressed(close)
                throw error
            }
            if (error !is CancellationException) store.invalidateIfMatches(choice)
            throw error
        }
    }
}

/** A failed native cleanup blocks every subsequent parent-process model allocation. */
internal class JointRuntimeReleaseFence {
    @Volatile private var failure: Throwable? = null
    private var retainedDetector: TfliteAndroidFrameDetector? = null

    fun ensureConfirmed() {
        failure?.let { throw DetectorNativeCleanupFailure(it) }
    }

    fun requireCleanResult(load: AndroidDetectorLoadResult) {
        if (load.reason != "DetectorNativeCleanupFailure") return
        val error = DetectorNativeCleanupFailure(IllegalStateException("Native cleanup reported as a failed load result"))
        retain(error, load.detector)
        throw error
    }

    @Synchronized fun retain(error: Throwable, detector: TfliteAndroidFrameDetector? = null) {
        if (failure == null) failure = error
        if (retainedDetector == null) retainedDetector = detector
    }
}
