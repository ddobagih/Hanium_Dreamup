package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import android.content.Context
import kr.co.hanium.dreamup.walksafe.device.AndroidFixedFixtureRuntimeSelector
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.composite.AndroidJointRuntimeLoader
import java.util.WeakHashMap

/** SDK operations at their original native/tuning call sites; obtaining Context remains lazy. */
internal class AndroidPrimaryLifecyclePlatform(private val context: () -> Context) : PrimaryLifecyclePlatform {
    private val prepared = WeakHashMap<TfliteAndroidFrameDetector, Pair<RuntimeCandidate, Long>>()
    override fun loadDefault() = TfliteAndroidFrameDetector.createWithStatus(context(),
        preprocessingStrategy = YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
    override fun select(config: TwoModelRuntimeConfig, allowComparison: Boolean, current: () -> Boolean,
        progress: (String) -> Unit): AndroidDetectorLoadResult {
        val joint = AndroidJointRuntimeLoader.restore(context(), config, isCurrent = current)
        if (joint != null) {
            joint.load.detector?.let { synchronized(prepared) {
                prepared[it] = joint.choice.candidate.primary to joint.choice.candidate.primaryIntervalMs
            } }
            return joint.load
        }
        return AndroidFixedFixtureRuntimeSelector.load(context(), config,
            allowComparison = allowComparison, isCurrent = current, onProgress = progress)
    }
    override fun logPreparationFailure(error: Throwable) {
        android.util.Log.w("WalkSafeRuntimeChoice", "Model preparation failed", error)
    }
    override fun close(detector: TfliteAndroidFrameDetector) {
        detector.close()
        synchronized(prepared) { prepared.remove(detector) }
    }
    override fun observedCandidate(detector: TfliteAndroidFrameDetector) =
        synchronized(prepared) { prepared[detector]?.first }
    override fun measuredPrimaryIntervalMs(detector: TfliteAndroidFrameDetector) =
        synchronized(prepared) { prepared[detector]?.second }
    override fun firstSuccessfulDelegate(detector: TfliteAndroidFrameDetector) = detector.lifecycleTiming().phases.lastOrNull {
        it.phase == AndroidDetectorLoadPhase.FIRST_INFERENCE && it.outcome == AndroidDetectorPhaseOutcome.SUCCEEDED
    }?.delegate
    override fun availableProcessors() = Runtime.getRuntime().availableProcessors()
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
