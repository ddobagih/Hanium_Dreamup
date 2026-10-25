package kr.co.hanium.dreamup.walksafe.runtime.vision.composition

import android.app.Activity
import android.content.Context
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.UnknownRuntimeServiceOwner

/** Same SDK factories; every Context/native/view query remains in its original SDK call phase. */
internal class VisionCalibrationSessionFactory(
    private val activity: () -> Activity,
    private val context: () -> Context,
    private val nativeExecutor: () -> Executor,
    private val speechActive: () -> Boolean,
    private val onUi: (Runnable) -> Unit,
) {
    fun create(spec: PrimaryCalibrationSessionSpec<TfliteAndroidFrameDetector>,
        callbacks: PrimaryCalibrationSessionCallbacks): AndroidRuntimeCalibrationSession =
        AndroidRuntimeCalibrationSession(activity = activity(), featureScope = spec.scope, bindingHash = spec.binding,
            budgetMs = spec.budgetMs, speechActive = speechActive,
            runnerFactory = { environmentProvider, progress, finished, liveDetection ->
                AndroidRuntimeCalibrationRunner(context = context(), config = spec.config,
                    baselineDetector = spec.baseline, baselineExecutor = nativeExecutor(),
                    baselineCandidate = spec.candidate, bindingHash = spec.binding, featureScope = spec.scope,
                    preprocessingStrategy = YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR,
                    environmentProvider = environmentProvider, isCurrent = callbacks.isCurrent,
                    callbackExecutor = Executor { onUi(it) }, onProgress = progress,
                    onFinished = finished, onLiveDetection = liveDetection, speechActive = speechActive,
                    baselinePreviouslyConfirmed = spec.baselinePreviouslyConfirmed,
                    restoredQueue = spec.restoredQueue, budgetMs = spec.budgetMs,
                    onSelectionReady = callbacks.onSelectionReady)
            }, onClosed = callbacks.onClosed)
}

internal class VisionPrimaryCalibrationPlatform(
    private val context: () -> Context,
    private val sessions: () -> VisionCalibrationSessionFactory,
) : PrimaryCalibrationPlatform<TfliteAndroidFrameDetector, AndroidRuntimeCalibrationSession> {
    override fun createBinding(config: TwoModelRuntimeConfig, scope: RuntimeFeatureScope) =
        AndroidDeviceTuningProfileStore.createBinding(context(), config, scope)
    override fun load(config: TwoModelRuntimeConfig): PrimaryCalibrationLoad<TfliteAndroidFrameDetector> {
        val load = TfliteAndroidFrameDetector.createWithStatus(context(), config,
            preprocessingStrategy = YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
        return PrimaryCalibrationLoad(load.detector, load.modelKey, load.fallbackUsed)
    }
    override fun createSession(spec: PrimaryCalibrationSessionSpec<TfliteAndroidFrameDetector>,
        callbacks: PrimaryCalibrationSessionCallbacks) = sessions().create(spec, callbacks)
    override fun startSession(session: AndroidRuntimeCalibrationSession) = session.start()
    override fun calibratableDetector(detector: AndroidFrameDetector) = detector as? TfliteAndroidFrameDetector
    override fun closeDetector(detector: TfliteAndroidFrameDetector) = detector.close()
}

/** Same service holder including its uninitialized null fallback; no cached service or close state. */
internal class VisionAuxiliaryCalibrationPeer(private val services: Lazy<UnknownRuntimeServiceOwner>) : PrimaryAuxiliaryCalibration<TfliteAndroidFrameDetector> {
    override fun isAbsent() = if (services.isInitialized()) services.value.currentService == null else true
    override fun releaseBeforePrimary() = services.value.releaseBeforePrimaryCalibration()
    override fun prepare(config: TwoModelRuntimeConfig, primary: TfliteAndroidFrameDetector, candidate: RuntimeCandidate,
        scope: RuntimeFeatureScope, manual: Boolean, deadlineMs: Long, current: () -> Boolean) =
        services.value.prepareComposition(config, primary, candidate, scope, manual, deadlineMs, current)
}
