package kr.co.hanium.dreamup.walksafe.inference.composite

import android.content.Context
import android.os.Looper
import kr.co.hanium.dreamup.walksafe.inference.*
import java.util.concurrent.CancellationException

/** Both HOME and the walking primary owner restore the same complete device-check choice. */
object AndroidJointRuntimeLoader {
    @Volatile private var failedRelease: TfliteAndroidFrameDetector? = null
    data class Prepared(val load: AndroidDetectorLoadResult, val choice: ValidatedJointRuntimeChoice)

    fun restore(context: Context, config: TwoModelRuntimeConfig, scope: RuntimeFeatureScope? = null,
        mode: JointCameraMode? = null, isCurrent: () -> Boolean = { true }): Prepared? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Joint runtime loading requires a worker" }
        check(failedRelease == null) { "Previous joint runtime native release is unconfirmed" }
        val store = AndroidJointRuntimeChoiceStore(context)
        val choice = store.load(config, scope, mode) ?: return null
        if (!isCurrent()) throw CancellationException("Joint runtime restoration retired")
        var detector: TfliteAndroidFrameDetector? = null
        try {
            val options = requireNotNull(config.unifiedWalksafe).runtime.copy(
                delegate = choice.candidate.primary.backend.delegate,
                numThreads = choice.candidate.primary.numThreads, fallbackToCpu = false)
            val load = TfliteAndroidFrameDetector.createWithStatus(context, config, options,
                YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
            detector = load.detector
            check(load.detectorAvailable && load.configLoaded && !load.fallbackUsed && detector != null)
            val fixtures = RuntimeCalibrationFixtures(context)
            val sample = detector.detectCalibrationImage(fixtures.loadArgb(fixtures.loadManifest().fixtures.first { it.required }))
            val runtime = requireNotNull(sample.timing.modelRuntime)
            check(sample.inferenceCompleted && !sample.partial && !runtime.fallbackUsed &&
                runtime.activeDelegate == choice.candidate.primary.backend.delegate &&
                runtime.numThreads == choice.candidate.primary.numThreads) { "Joint runtime differs from measured choice" }
            if (!isCurrent()) throw CancellationException("Joint runtime restoration retired")
            return Prepared(load.copy(reason = "DeviceCheckJointRuntimeChoice"), choice)
        } catch (error: Throwable) {
            // A cancellation does not invalidate a good choice; an execution mismatch does.
            try { detector?.close() } catch (close: Throwable) { failedRelease = detector; error.addSuppressed(close); throw error }
            if (error !is CancellationException) store.invalidateIfMatches(choice)
            throw error
        }
    }
}
