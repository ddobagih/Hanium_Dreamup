package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import android.content.Context
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.composite.AndroidJointRuntimeLoader
import kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeReleaseFence
import java.util.WeakHashMap

/** SDK operations at their original native/tuning call sites; obtaining Context remains lazy. */
internal class AndroidPrimaryLifecyclePlatform(
    private val context: () -> Context,
    private val restoreJoint: (TwoModelRuntimeConfig, () -> Boolean) -> AndroidJointRuntimeLoader.Prepared?,
    private val createCpu: (TwoModelRuntimeConfig?, ModelRuntimeOptions) -> AndroidDetectorLoadResult,
    private val processorCount: () -> Int,
    private val releaseFence: JointRuntimeReleaseFence = AndroidJointRuntimeLoader.releaseFence,
) : PrimaryLifecyclePlatform {
    constructor(context: () -> Context) : this(context,
        { config, current -> AndroidJointRuntimeLoader.restore(context(), config, current) },
        { config, options ->
            if (config == null) TfliteAndroidFrameDetector.createWithStatus(context(), options,
                YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
            else TfliteAndroidFrameDetector.createWithStatus(context(),
                config.copy(fallbackModelKey = null, customTactile = null, cocoGeneral = null), options,
                YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
        }, { Runtime.getRuntime().availableProcessors() })
    private data class PreparedSetting(val candidate: RuntimeCandidate, val intervalMs: Long?)
    private val prepared = WeakHashMap<TfliteAndroidFrameDetector, PreparedSetting>()
    override fun loadDefault() = prepareInitialCpu(null)
    override fun select(config: TwoModelRuntimeConfig, allowComparison: Boolean, current: () -> Boolean,
        progress: (String) -> Unit): AndroidDetectorLoadResult {
        releaseFence.ensureConfirmed()
        // Explicit rechecks must reach the isolated checker without retrying a previous GPU load
        // in the parent process. Ordinary/final application still restores the measured choice.
        val joint = if (allowComparison) null else restoreJoint(config, current)
        if (joint != null) {
            joint.load.detector?.let { synchronized(prepared) {
                prepared[it] = PreparedSetting(joint.choice.candidate.primary, joint.choice.candidate.primaryIntervalMs)
            } }
            return joint.load
        }
        if (!current()) throw java.util.concurrent.CancellationException("CPU preparation retired")
        // Cold GPU trials belong exclusively to the isolated two-model fixture worker, even when
        // an older caller requests comparison. A legacy single-model GPU cache is not that result.
        progress("객체 모델 초기 준비 중 · 샘플 이미지 성능 점검 전")
        return prepareInitialCpu(config)
    }

    private fun prepareInitialCpu(config: TwoModelRuntimeConfig?): AndroidDetectorLoadResult {
        releaseFence.ensureConfirmed()
        val candidate = RuntimeSelectionPolicy.initialCpuCandidate(processorCount(), defaultThreads = 2)
        val options = ModelRuntimeOptions(delegate = "cpu", numThreads = candidate.numThreads,
            fallbackToCpu = false, gpuPrecisionLossAllowed = false, gpuSerializationCacheEnabled = false)
        val load = try { createCpu(config, options) }
        catch (error: DetectorNativeCleanupFailure) { releaseFence.retain(error); throw error }
        releaseFence.requireCleanResult(load)
        load.detector?.let { synchronized(prepared) { prepared[it] = PreparedSetting(candidate, null) } }
        return if (load.detectorAvailable && load.configLoaded && !load.fallbackUsed)
            load.copy(reason = SAFE_CPU_PREPARATION_REASON) else load
    }
    override fun logPreparationFailure(error: Throwable) {
        android.util.Log.w("WalkSafeRuntimeChoice", "Model preparation failed", error)
    }
    override fun close(detector: TfliteAndroidFrameDetector) {
        detector.close()
        synchronized(prepared) { prepared.remove(detector) }
    }
    override fun observedCandidate(detector: TfliteAndroidFrameDetector) =
        synchronized(prepared) { prepared[detector]?.candidate }
    override fun measuredPrimaryIntervalMs(detector: TfliteAndroidFrameDetector) =
        synchronized(prepared) { prepared[detector]?.intervalMs }
    override fun firstSuccessfulDelegate(detector: TfliteAndroidFrameDetector) = detector.lifecycleTiming().phases.lastOrNull {
        it.phase == AndroidDetectorLoadPhase.FIRST_INFERENCE && it.outcome == AndroidDetectorPhaseOutcome.SUCCEEDED
    }?.delegate
    override fun availableProcessors() = processorCount()
    override fun sample(detector: TfliteAndroidFrameDetector): AndroidDetectionResult {
        val fixtures = RuntimeCalibrationFixtures(context())
        return detector.detectCalibrationImage(fixtures.loadArgb(fixtures.loadManifest().fixtures.first { it.required }))
    }
    override fun performance(detector: TfliteAndroidFrameDetector): PrimaryFixedFixtureResult {
        val fixtures = RuntimeCalibrationFixtures(context())
        val required = fixtures.loadManifest().fixtures.filter { it.required }
        return runPrimaryFixedFixtureBenchmark(required) { fixture ->
            detector.detectCalibrationImage(fixtures.loadArgb(fixture))
        }
    }

    companion object { const val SAFE_CPU_PREPARATION_REASON = "SafeCpuPreparationAwaitingSampleCheck" }
}


/** Original warm-up, three measured rounds, then an unmeasured delegate observation. */
internal fun <F> runPrimaryFixedFixtureBenchmark(required: List<F>, detect: (F) -> AndroidDetectionResult): PrimaryFixedFixtureResult {
    check(required.isNotEmpty())
    detect(required.first())
    val totalMs = mutableListOf<Long>(); val inferenceMs = mutableListOf<Long>(); val detectedObjects = mutableListOf<Int>()
    repeat(3) {
        required.forEach { fixture ->
            val detection = detect(fixture)
            totalMs += requireNotNull(detection.timing.totalMs)
            inferenceMs += requireNotNull(detection.timing.modelInferenceMs)
            detectedObjects += detection.detections.size
        }
    }
    return PrimaryFixedFixtureResult(required.size, totalMs.size, totalMs.sorted()[totalMs.size / 2],
        inferenceMs.sorted()[inferenceMs.size / 2], detect(required.first()).timing.modelRuntime?.activeDelegate ?: "unknown",
        detectedObjects.sum())
}
