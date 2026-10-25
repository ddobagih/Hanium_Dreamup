package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import android.content.Context
import kr.co.hanium.dreamup.walksafe.device.AndroidFixedFixtureRuntimeSelector
import kr.co.hanium.dreamup.walksafe.inference.*

/** SDK operations at their original native/tuning call sites; obtaining Context remains lazy. */
internal class AndroidPrimaryLifecyclePlatform(private val context: () -> Context) : PrimaryLifecyclePlatform {
    override fun loadDefault() = TfliteAndroidFrameDetector.createWithStatus(context(),
        preprocessingStrategy = YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR)
    override fun select(config: TwoModelRuntimeConfig, allowComparison: Boolean, current: () -> Boolean,
        progress: (String) -> Unit) = AndroidFixedFixtureRuntimeSelector.load(context(), config,
            allowComparison = allowComparison, isCurrent = current, onProgress = progress)
    override fun logPreparationFailure(error: Throwable) {
        android.util.Log.w("WalkSafeRuntimeChoice", "Model preparation failed", error)
    }
    override fun close(detector: TfliteAndroidFrameDetector) = detector.close()
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
