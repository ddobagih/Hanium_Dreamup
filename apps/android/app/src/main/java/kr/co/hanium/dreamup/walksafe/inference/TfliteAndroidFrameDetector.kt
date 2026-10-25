package kr.co.hanium.dreamup.walksafe.inference

import android.content.Context
import android.media.Image
import android.os.Build
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import org.tensorflow.lite.TensorFlowLite
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Executes either one unified model or the complete custom-tactile/COCO legacy pair described by
 * [TwoModelRuntimeConfig]. Model identity and fallback state are returned to the caller for report
 * provenance instead of being hidden inside the detector.
 */
class TfliteAndroidFrameDetector private constructor(
    private val threadOwner: DetectorThreadOwner?,
    private val loadRecorder: DetectorLoadTimingRecorder,
    private val customDetector: TfliteSingleModelDetector? = null,
    private val cocoDetector: TfliteSingleModelDetector? = null,
    private val unifiedDetector: TfliteSingleModelDetector? = null,
    private val preprocessor: YuvImagePreprocessor = YuvImagePreprocessor(),
    private val preprocessingStrategy: YuvPreprocessingStrategy = YuvPreprocessingStrategy.LEGACY_TWO_PASS,
    private val personSupplementDetector: TfliteSingleModelDetector? = null,
) : AndroidFrameDetector, Closeable {
    private val runtimeLock = Any()
    private val rgbScratch = DetectorRgbScratch(preprocessor)
    private val personSupplementMerger = PersonSupplementMerger()
    private var closed = false

    override fun detect(
        cameraImage: Image,
        timestampMs: Long,
        onPartialResult: (AndroidDetectionResult) -> Unit,
    ): AndroidDetectionResult = withRuntime {
        detectOnOwner(cameraImage, onPartialResult)
    }

    override fun detectOriented(cameraImage: Image, timestampMs: Long, quarterTurns: Int,
        onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult = withRuntime {
        require(quarterTurns in 0..3)
        val unified = unifiedDetector
        if (unified == null) detectOnOwner(cameraImage, onPartialResult)
        else detectUnified(cameraImage, unified, quarterTurns)
    }

    override fun detect(cameraFrame: OwnedYuvFrame, timestampMs: Long,
        onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult = withRuntime {
        detectOnOwner(cameraFrame, onPartialResult)
    }

    override fun detectOriented(cameraFrame: OwnedYuvFrame, timestampMs: Long, quarterTurns: Int,
        onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult = withRuntime {
        require(quarterTurns in 0..3)
        val unified = unifiedDetector
        if (unified == null) detectOnOwner(cameraFrame, onPartialResult)
        else detectUnified(cameraFrame, unified, quarterTurns)
    }

    private fun detectOnOwner(
        cameraImage: Image,
        onPartialResult: (AndroidDetectionResult) -> Unit,
    ): AndroidDetectionResult = detectOnOwner(
        decode = { if (unifiedDetector != null) rgbScratch.decode(cameraImage) else preprocessor.decode(cameraImage) },
        fusedPreprocess = { size -> preprocessor.preprocess(cameraImage, size, preprocessingStrategy) },
        cropRect = YuvCropRect.fromImage(cameraImage),
        imageWidth = cameraImage.width,
        imageHeight = cameraImage.height,
        onPartialResult = onPartialResult,
    )

    private fun detectOnOwner(
        cameraFrame: OwnedYuvFrame,
        onPartialResult: (AndroidDetectionResult) -> Unit,
    ): AndroidDetectionResult = detectOnOwner(
        decode = { if (unifiedDetector != null) rgbScratch.decode(cameraFrame) else cameraFrame.toArgb() },
        fusedPreprocess = { size -> cameraFrame.preprocessFused(preprocessor, size) },
        cropRect = cameraFrame.cropRect,
        imageWidth = cameraFrame.width,
        imageHeight = cameraFrame.height,
        onPartialResult = onPartialResult,
    )

    private fun detectOnOwner(
        decode: () -> ArgbImage,
        fusedPreprocess: (Int) -> PreprocessedImage,
        cropRect: YuvCropRect,
        imageWidth: Int,
        imageHeight: Int,
        onPartialResult: (AndroidDetectionResult) -> Unit,
    ): AndroidDetectionResult {
        val unified = unifiedDetector
        if (unified != null) {
            return detectUnified(decode, unified, cropRect, imageWidth, imageHeight)
        }

        val coco = requireNotNull(cocoDetector) { "coco detector is required in legacy two-model mode" }
        val custom = requireNotNull(customDetector) { "custom detector is required in legacy two-model mode" }
        val totalStartedNs = System.nanoTime()
        val decodeStartedNs = System.nanoTime()
        val image = if (preprocessingStrategy == YuvPreprocessingStrategy.LEGACY_TWO_PASS) {
            decode()
        } else {
            null
        }
        val yuvDecodeMs = image?.let { elapsedMs(decodeStartedNs) }

        val cocoInputStartedNs = System.nanoTime()
        val cocoInput = if (image != null) preprocessor.preprocess(image, coco.inputSize)
            else fusedPreprocess(coco.inputSize)
        val cocoPreprocessMs = elapsedMs(cocoInputStartedNs)
        val cocoResult = coco.detect(cocoInput)
        val cocoDetections = cocoResult.detections.mapNotNull {
            cropRect.toValidSensorDetection(it, imageWidth, imageHeight)
        }
        val partialTiming = AndroidDetectorTiming(
            yuvDecodeMs = yuvDecodeMs,
            cocoPreprocessMs = cocoPreprocessMs,
            cocoInferenceMs = cocoResult.inferenceMs,
            cocoParseMs = cocoResult.parseMs,
            totalMs = elapsedMs(totalStartedNs),
            completedModels = listOf(COCO_MODEL_KEY),
            skippedModels = listOf(CUSTOM_MODEL_KEY),
            cocoRuntime = cocoResult.runtime,
            preprocessingStrategy = preprocessingStrategy.name,
        )
        // Publish COCO early to reduce warning latency; the returned result is the authoritative
        // merged legacy result after the slower tactile model completes.
        onPartialResult(
            AndroidDetectionResult(
                detections = cocoDetections,
                timing = partialTiming,
                partial = true,
            ),
        )

        val customInputStartedNs = System.nanoTime()
        val customInput = if (image != null) preprocessor.preprocess(image, custom.inputSize)
            else fusedPreprocess(custom.inputSize)
        val customPreprocessMs = elapsedMs(customInputStartedNs)
        val customResult = custom.detect(customInput)
        return AndroidDetectionResult(
            detections = cocoDetections + customResult.detections.mapNotNull {
                cropRect.toValidSensorDetection(it, imageWidth, imageHeight)
            },
            timing = AndroidDetectorTiming(
                yuvDecodeMs = yuvDecodeMs,
                cocoPreprocessMs = cocoPreprocessMs,
                cocoInferenceMs = cocoResult.inferenceMs,
                cocoParseMs = cocoResult.parseMs,
                customPreprocessMs = customPreprocessMs,
                customInferenceMs = customResult.inferenceMs,
                customParseMs = customResult.parseMs,
                totalMs = elapsedMs(totalStartedNs),
                completedModels = listOf(COCO_MODEL_KEY, CUSTOM_MODEL_KEY),
                cocoRuntime = cocoResult.runtime,
                customRuntime = customResult.runtime,
                preprocessingStrategy = preprocessingStrategy.name,
            ),
        )
    }

    private fun detectUnified(
        cameraImage: Image,
        detector: TfliteSingleModelDetector,
        quarterTurns: Int = 0,
    ): AndroidDetectionResult = detectUnified(
        { rgbScratch.decode(cameraImage) }, detector, YuvCropRect.fromImage(cameraImage),
        cameraImage.width, cameraImage.height, quarterTurns,
    )

    private fun detectUnified(
        cameraFrame: OwnedYuvFrame,
        detector: TfliteSingleModelDetector,
        quarterTurns: Int = 0,
    ): AndroidDetectionResult = detectUnified(
        { rgbScratch.decode(cameraFrame) }, detector, cameraFrame.cropRect,
        cameraFrame.width, cameraFrame.height, quarterTurns,
    )

    private fun detectUnified(
        decode: () -> ArgbImage,
        detector: TfliteSingleModelDetector,
        cropRect: YuvCropRect,
        imageWidth: Int,
        imageHeight: Int,
        quarterTurns: Int = 0,
    ): AndroidDetectionResult {
        val totalStartedNs = System.nanoTime()
        val decodeStartedNs = System.nanoTime()
        val image = rgbScratch.rotate(decode(), quarterTurns)
        val yuvDecodeMs = elapsedMs(decodeStartedNs)

        val inputStartedNs = System.nanoTime()
        val input = preprocessor.preprocessBilinear(image, detector.inputSize)
        val preprocessMs = elapsedMs(inputStartedNs)
        val uprightCrop = cropRect.takeUnless { it.isFull(imageWidth, imageHeight) }
            ?.toUpright(imageWidth, imageHeight, quarterTurns)
        val result = detector.detect(input, uprightCrop)
        // Same prepared pixels and owner transaction; each runner rewinds its borrowed input buffer.
        val personResult = personSupplementDetector?.detect(input, uprightCrop)
        val detections = personResult?.let { personSupplementMerger.merge(result.detections, it.detections) }
            ?: result.detections
        return AndroidDetectionResult(
            detections = detections.mapNotNull {
                cropRect.toValidSensorDetection(it, imageWidth, imageHeight, quarterTurns)
            },
            timing = AndroidDetectorTiming(
                yuvDecodeMs = yuvDecodeMs,
                modelKey = UNIFIED_MODEL_KEY,
                modelPreprocessMs = preprocessMs,
                modelInferenceMs = result.inferenceMs,
                modelParseMs = result.parseMs,
                totalMs = elapsedMs(totalStartedNs),
                completedModels = completedUnifiedModels(personResult),
                modelRuntime = result.runtime,
                preprocessingStrategy = "UPRIGHT_RGB_BILINEAR",
                personSupplementInferenceMs = personResult?.inferenceMs,
                personSupplementParseMs = personResult?.parseMs,
                personSupplementRuntime = personResult?.runtime,
            ),
        )
    }

    /** Runs the production unified detector against an in-app fixture; it never opens a camera. */
    fun detectCalibrationImage(image: ArgbImage): AndroidDetectionResult = withRuntime {
        val detector = requireNotNull(unifiedDetector) {
            "Fixed calibration requires the unified WalkMate detector"
        }
        val totalStartedNs = System.nanoTime()
        val inputStartedNs = System.nanoTime()
        val input = preprocessor.preprocessBilinear(image, detector.inputSize)
        val preprocessMs = elapsedMs(inputStartedNs)
        val result = detector.detect(input)
        val personResult = personSupplementDetector?.detect(input)
        AndroidDetectionResult(
            detections = personResult?.let { personSupplementMerger.merge(result.detections, it.detections) }
                ?: result.detections,
            timing = AndroidDetectorTiming(
                modelKey = UNIFIED_MODEL_KEY,
                modelPreprocessMs = preprocessMs,
                modelInferenceMs = result.inferenceMs,
                modelParseMs = result.parseMs,
                totalMs = elapsedMs(totalStartedNs),
                completedModels = completedUnifiedModels(personResult),
                modelRuntime = result.runtime,
                preprocessingStrategy = "FIXED_ARGB_BILINEAR",
                personSupplementInferenceMs = personResult?.inferenceMs,
                personSupplementParseMs = personResult?.parseMs,
                personSupplementRuntime = personResult?.runtime,
            ),
        )
    }

    override fun close() {
        val queuedNs = loadRecorder.now()
        val release = {
            synchronized(runtimeLock) {
                if (!closed) {
                    closed = true
                    loadRecorder.record(AndroidDetectorLoadPhase.CLOSE_QUEUE, queuedNs)
                    try {
                        loadRecorder.measure(AndroidDetectorLoadPhase.NATIVE_CLOSE) { closeModels() }
                    } finally {
                        rgbScratch.clear()
                    }
                }
            }
        }
        if (threadOwner != null) threadOwner.close(release) else release()
    }

    /** Includes later first-inference and native-close events from this load generation. */
    fun lifecycleTiming(): AndroidDetectorLoadTiming = loadRecorder.snapshot()

    private fun closeModels() {
        var failure: Throwable? = null
        listOfNotNull(customDetector, cocoDetector, unifiedDetector, personSupplementDetector).forEach { detector ->
            try {
                detector.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun completedUnifiedModels(personResult: SingleModelDetectionResult?): List<String> =
        if (personResult == null) listOf(UNIFIED_MODEL_KEY)
        else listOf(UNIFIED_MODEL_KEY, TwoModelRuntimeConfig.PERSON_SUPPLEMENT_MODEL_KEY)

    private fun <T> withRuntime(block: () -> T): T {
        val work = {
            synchronized(runtimeLock) {
                check(!closed) { "detector is closed" }
                block()
            }
        }
        return if (threadOwner != null) threadOwner.call(work) else work()
    }

    /** Benchmark-only copy; no extra output copy is performed in normal inference. */
    internal fun copyLastUnifiedOutputForTest(): FloatArray? = withRuntime {
        unifiedDetector?.copyLastOutput()
    }

    /**
     * Unified calibration only. Invoke and optional raw copy share the same owner transaction so
     * another frame cannot replace the output in between. The caller owns the prepared tensor
     * until this call returns. No partial, navigation, speech or report callback is invoked.
     */
    fun inferPreparedUnifiedForCalibration(
        input: PreprocessedImage,
        preprocessingMs: Long,
        strategy: YuvPreprocessingStrategy,
        copyRawOutput: Boolean = true,
    ): AndroidCalibrationDetectionResult = withRuntime {
        require(preprocessingMs >= 0L)
        val unified = requireNotNull(unifiedDetector) { "calibration requires the unified model" }
        require(input.transform.modelSize == unified.inputSize) { "calibration input size mismatch" }
        val startedNs = System.nanoTime()
        val result = unified.detect(input)
        AndroidCalibrationDetectionResult(
            result = AndroidDetectionResult(
                detections = result.detections,
                timing = AndroidDetectorTiming(
                    modelKey = UNIFIED_MODEL_KEY,
                    modelPreprocessMs = preprocessingMs,
                    modelInferenceMs = result.inferenceMs,
                    modelParseMs = result.parseMs,
                    totalMs = preprocessingMs + elapsedMs(startedNs),
                    completedModels = listOf(UNIFIED_MODEL_KEY),
                    modelRuntime = result.runtime,
                    preprocessingStrategy = strategy.name,
                ),
            ),
            rawOutput = if (copyRawOutput) unified.copyLastOutput() else null,
        )
    }

    internal fun inferPreparedUnifiedForTest(
        input: PreprocessedImage,
        preparation: OwnedTensorPreparation,
    ): AndroidDetectionResult = withRuntime {
        val unified = requireNotNull(unifiedDetector) { "prepared inference requires the unified model" }
        require(input.transform.modelSize == unified.inputSize) { "prepared input size does not match model" }
        val startedNs = System.nanoTime()
        val result = unified.detect(input, preparation.validImageCrop())
        AndroidDetectionResult(
            detections = result.detections,
            timing = AndroidDetectorTiming(
                modelKey = UNIFIED_MODEL_KEY,
                modelPreprocessMs = preparation.preprocessingMs,
                modelInferenceMs = result.inferenceMs,
                modelParseMs = result.parseMs,
                // Component work time; capture-to-completion latency is reported by the experiment.
                totalMs = preparation.preparationMs + elapsedMs(startedNs),
                completedModels = listOf(UNIFIED_MODEL_KEY),
                modelRuntime = result.runtime,
                preprocessingStrategy = preparation.strategy.name,
                ownedTensorCopyMs = preparation.tensorCopyMs,
            ),
        )
    }

    companion object {
        /** Experimental unified-only path: a legacy fallback must never masquerade as this model. */
        internal fun createUnifiedOverlapForTest(
            context: Context,
            config: TwoModelRuntimeConfig,
            runtimeOverride: ModelRuntimeOptions,
            preprocessingStrategy: YuvPreprocessingStrategy,
            sourceSessionId: Long,
            maximumSourceAgeMs: Long = 800L,
        ): UnifiedFrameOverlapExperiment {
            require(sourceSessionId > 0L && maximumSourceAgeMs in 1L..800L)
            val unified = requireNotNull(config.unifiedWalksafe) { "unified model is required" }
            require(config.primaryModelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY && unified.enabled) {
                "overlap experiment requires an enabled unified primary model; legacy is not supported"
            }
            require(TwoModelRuntimeConfig.assetMatches(context, unified)) { "unified model asset hash mismatch" }
            val unifiedOnly = config.copy(fallbackModelKey = null, customTactile = null, cocoGeneral = null,
                personSupplement = null)
            val load = createWithStatus(context, unifiedOnly, runtimeOverride, preprocessingStrategy)
            val detector = checkNotNull(load.detector) { "unified overlap model unavailable: ${load.reason}" }
            return try {
                UnifiedFrameOverlapExperiment(
                    detector, unified.inputSize, sourceSessionId, preprocessingStrategy, maximumSourceAgeMs,
                )
            } catch (error: Throwable) {
                try {
                    detector.close()
                } catch (closeError: Throwable) {
                    error.addSuppressed(closeError)
                }
                throw error
            }
        }

        fun createWithStatus(
            context: Context,
            runtimeOverride: ModelRuntimeOptions? = null,
            preprocessingStrategy: YuvPreprocessingStrategy = YuvPreprocessingStrategy.LEGACY_TWO_PASS,
        ): AndroidDetectorLoadResult {
            val recorder = DetectorLoadTimingRecorder()
            return try {
                // Config loading already performs its own integrity checks; do not add another read here.
                val config = recorder.measure(AndroidDetectorLoadPhase.CONFIG_LOAD) { TwoModelRuntimeConfig.load(context) }
                createRecorded(context, config, runtimeOverride, preprocessingStrategy, recorder)
            } catch (error: Exception) {
                AndroidDetectorLoadResult(
                    detector = null,
                    configLoaded = false,
                    detectorAvailable = false,
                    modelKey = null,
                    fallbackUsed = false,
                    reason = error::class.java.simpleName,
                    loadTiming = recorder.finishLoad(false),
                )
            }
        }

        /** Unified failure falls back only when the config names a complete legacy pair. */
        fun createWithStatus(
            context: Context,
            config: TwoModelRuntimeConfig,
            runtimeOverride: ModelRuntimeOptions? = null,
            preprocessingStrategy: YuvPreprocessingStrategy = YuvPreprocessingStrategy.LEGACY_TWO_PASS,
        ): AndroidDetectorLoadResult {
            return createRecorded(context, config, runtimeOverride, preprocessingStrategy, DetectorLoadTimingRecorder())
        }

        private fun createRecorded(
            context: Context,
            config: TwoModelRuntimeConfig,
            runtimeOverride: ModelRuntimeOptions?,
            preprocessingStrategy: YuvPreprocessingStrategy,
            recorder: DetectorLoadTimingRecorder,
        ): AndroidDetectorLoadResult {
            val effectiveConfig = if (runtimeOverride == null) config else config.copy(
                unifiedWalksafe = config.unifiedWalksafe?.copy(runtime = runtimeOverride),
                customTactile = config.customTactile?.copy(runtime = runtimeOverride),
                cocoGeneral = config.cocoGeneral?.copy(runtime = runtimeOverride),
                personSupplement = config.personSupplement?.copy(runtime = runtimeOverride),
            )
            val owner = if (effectiveConfig.requiresGpuThreadOwner) DetectorThreadOwner() else null
            val queuedNs = recorder.now()
            var createEntered = false
            val create = {
                createEntered = true
                if (owner != null) recorder.record(AndroidDetectorLoadPhase.OWNER_QUEUE, queuedNs)
                createOnOwner(context, effectiveConfig, owner, preprocessingStrategy, recorder)
            }
            val result = try {
                val result = owner?.call(create) ?: create()
                if (result.detector == null) owner?.close {}
                result
            } catch (error: Throwable) {
                if (owner != null && !createEntered) recorder.record(AndroidDetectorLoadPhase.OWNER_QUEUE, queuedNs, failure = error)
                try {
                    owner?.close {}
                } catch (closeError: Throwable) {
                    error.addSuppressed(closeError)
                }
                if (error !is Exception && error !is LinkageError) throw error
                AndroidDetectorLoadResult(null, true, false, null, false, error.javaClass.simpleName)
            }
            return result.copy(loadTiming = recorder.finishLoad(result.detectorAvailable))
        }

        private fun createOnOwner(
            context: Context,
            config: TwoModelRuntimeConfig,
            owner: DetectorThreadOwner?,
            preprocessingStrategy: YuvPreprocessingStrategy,
            recorder: DetectorLoadTimingRecorder,
        ): AndroidDetectorLoadResult {
            return try {
                if (config.primaryModelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY) {
                    val unifiedAttempt = createUnified(context, config, owner, preprocessingStrategy, recorder)
                    val unified = unifiedAttempt.value
                    if (unified != null) {
                        AndroidDetectorLoadResult(
                            detector = unified,
                            configLoaded = true,
                            detectorAvailable = true,
                            modelKey = TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,
                            fallbackUsed = false,
                            reason = "unified_loaded",
                        )
                    } else {
                        val legacy = createLegacyFallbackOrNull(context, config, owner, preprocessingStrategy, recorder)
                        AndroidDetectorLoadResult(
                            detector = legacy,
                            configLoaded = true,
                            detectorAvailable = legacy != null,
                            modelKey = if (legacy != null) TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY else null,
                            fallbackUsed = legacy != null,
                            reason = unavailableReason(legacy != null, unifiedAttempt.failureCode),
                            failureCode = unifiedAttempt.failureCode,
                        )
                    }
                } else {
                    AndroidDetectorLoadResult(
                        detector = createLegacyTwoModel(context, config, owner, preprocessingStrategy, recorder),
                        configLoaded = true,
                        detectorAvailable = true,
                        modelKey = TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY,
                        fallbackUsed = false,
                        reason = "legacy_loaded",
                    )
                }
            } catch (error: Exception) {
                AndroidDetectorLoadResult(
                    detector = null,
                    configLoaded = true,
                    detectorAvailable = false,
                    modelKey = null,
                    fallbackUsed = false,
                    reason = error::class.java.simpleName,
                    failureCode = ModelAssetIntegrity.classify(error),
                )
            }
        }

        /** Keeps the historical prefix while exposing the preserved primary-model cause. */
        internal fun unavailableReason(legacyLoaded: Boolean, failureCode: DetectorLoadFailureCode?): String = when {
            legacyLoaded -> "unified_unavailable_legacy_loaded"
            failureCode != null -> "detector_assets_unavailable:${failureCode.name}"
            else -> "detector_assets_unavailable"
        }

        fun createOrNull(context: Context): TfliteAndroidFrameDetector? {
            return createWithStatus(context).detector
        }

        private fun createUnified(
            context: Context,
            config: TwoModelRuntimeConfig,
            owner: DetectorThreadOwner?,
            preprocessingStrategy: YuvPreprocessingStrategy,
            recorder: DetectorLoadTimingRecorder,
        ): ModelLoadAttempt<TfliteAndroidFrameDetector> {
            val unifiedConfig = config.unifiedWalksafe ?: return ModelLoadAttempt(null, null)
            if (config.primaryModelKey != TwoModelRuntimeConfig.UNIFIED_MODEL_KEY || !unifiedConfig.enabled) {
                // A config-load integrity failure disables the entry; keep that cause, not just "disabled".
                return ModelLoadAttempt(null, unifiedConfig.integrityFailure ?: DetectorLoadFailureCode.MODEL_DISABLED)
            }
            return loadModel {
                recorder.measure(AndroidDetectorLoadPhase.ASSET_INTEGRITY, UNIFIED_MODEL_KEY) {
                    requireAssetIntegrity(context, unifiedConfig, "unified")
                }
                val unified = createSingleModelDetector(context, unifiedConfig, recorder, UNIFIED_MODEL_KEY)
                var person: TfliteSingleModelDetector? = null
                try {
                    config.personSupplement?.takeIf { it.enabled }?.let { personConfig ->
                        recorder.measure(AndroidDetectorLoadPhase.ASSET_INTEGRITY,
                            TwoModelRuntimeConfig.PERSON_SUPPLEMENT_MODEL_KEY) {
                            requireAssetIntegrity(context, personConfig, "person_supplement")
                        }
                        person = createSingleModelDetector(context, personConfig, recorder,
                            TwoModelRuntimeConfig.PERSON_SUPPLEMENT_MODEL_KEY)
                    }
                    TfliteAndroidFrameDetector(
                        threadOwner = owner,
                        loadRecorder = recorder,
                        unifiedDetector = unified,
                        personSupplementDetector = person,
                        preprocessingStrategy = preprocessingStrategy,
                    )
                } catch (error: Throwable) {
                    var cleanupFailed = false
                    for (model in listOfNotNull(person, unified)) {
                        try { model.close() } catch (closeError: Throwable) {
                            cleanupFailed = true
                            error.addSuppressed(closeError)
                        }
                    }
                    if (cleanupFailed) throw DetectorNativeCleanupFailure(error)
                    throw error
                }
            }
        }

        internal fun <T> loadModelOrNull(create: () -> T): T? = loadModel(create).value

        /** Like [loadModelOrNull], but the specific failure cause survives for diagnostics. */
        internal fun <T> loadModel(create: () -> T): ModelLoadAttempt<T> = try {
            ModelLoadAttempt(create(), null)
        } catch (error: DetectorNativeCleanupFailure) {
            // A failed native close must also prevent switching to the legacy model pair.
            throw error
        } catch (error: Exception) {
            ModelLoadAttempt(null, ModelAssetIntegrity.classify(error))
        }

        private fun requireAssetIntegrity(context: Context, model: ModelRuntimeConfig, label: String) {
            val failure = TwoModelRuntimeConfig.assetIntegrityFailure(context, model) ?: return
            throw DetectorAssetIntegrityFailure(failure, "$label model asset ${failure.name.lowercase()}")
        }

        private fun createLegacyFallbackOrNull(
            context: Context,
            config: TwoModelRuntimeConfig,
            owner: DetectorThreadOwner?,
            preprocessingStrategy: YuvPreprocessingStrategy,
            recorder: DetectorLoadTimingRecorder,
        ): TfliteAndroidFrameDetector? {
            if (config.fallbackModelKey != TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY) {
                return null
            }
            return loadModelOrNull {
                createLegacyTwoModel(context, config, owner, preprocessingStrategy, recorder)
            }
        }

        private fun createLegacyTwoModel(
            context: Context,
            config: TwoModelRuntimeConfig,
            owner: DetectorThreadOwner?,
            preprocessingStrategy: YuvPreprocessingStrategy,
            recorder: DetectorLoadTimingRecorder,
        ): TfliteAndroidFrameDetector {
            val customConfig = requireNotNull(config.customTactile) {
                "custom_tactile model config is required in legacy two-model mode"
            }
            val cocoConfig = requireNotNull(config.cocoGeneral) {
                "coco_general model config is required in legacy two-model mode"
            }
            recorder.measure(AndroidDetectorLoadPhase.ASSET_INTEGRITY, CUSTOM_MODEL_KEY) {
                requireAssetIntegrity(context, customConfig, "custom_tactile")
            }
            recorder.measure(AndroidDetectorLoadPhase.ASSET_INTEGRITY, COCO_MODEL_KEY) {
                requireAssetIntegrity(context, cocoConfig, "coco_general")
            }
            val custom = createSingleModelDetector(context, customConfig, recorder, CUSTOM_MODEL_KEY)
            return try {
                TfliteAndroidFrameDetector(
                    threadOwner = owner,
                    loadRecorder = recorder,
                    customDetector = custom,
                    cocoDetector = createSingleModelDetector(context, cocoConfig, recorder, COCO_MODEL_KEY),
                    preprocessingStrategy = preprocessingStrategy,
                )
            } catch (error: Throwable) {
                try {
                    custom.close()
                } catch (closeError: Throwable) {
                    error.addSuppressed(closeError)
                }
                throw error
            }
        }

        private fun createSingleModelDetector(
            context: Context,
            config: ModelRuntimeConfig,
            recorder: DetectorLoadTimingRecorder,
            modelKey: String,
        ): TfliteSingleModelDetector {
            val model = recorder.measure(AndroidDetectorLoadPhase.ASSET_MAP, modelKey) { loadMappedAsset(context, config.asset) }
            val cache = if (config.runtime.delegate == "gpu") {
                GpuSerializationCache.prepare(config.runtime.gpuSerializationCacheEnabled) {
                    recorder.measure(AndroidDetectorLoadPhase.GPU_CACHE_PREPARE, modelKey, "gpu") {
                        val contract = GpuSerializationContract(
                            inputSize = config.inputSize,
                            numThreads = config.runtime.numThreads,
                            precisionLossAllowed = config.runtime.gpuPrecisionLossAllowed,
                            nativeRuntimeVersion = TensorFlowLite.runtimeVersion(),
                            deviceFingerprint = Build.FINGERPRINT,
                            supportedAbis = Build.SUPPORTED_ABIS.toList(),
                            sdkInt = Build.VERSION.SDK_INT,
                        )
                        GpuSerializationParameters(
                            GpuSerializationCache.privateDirectory(context.noBackupFilesDir),
                            contract.modelToken(model),
                        )
                    }
                }
            } else null
            return TfliteSingleModelDetector(
                model = model,
                loadRecorder = recorder,
                modelKey = modelKey,
                inputSize = config.inputSize,
                runtime = config.runtime,
                gpuSerializationCache = cache,
                outputFormat = config.outputFormat,
                outputShape = config.outputTensorShape(),
                classCount = config.classes.size,
                nmsIouThreshold = config.nmsIouThreshold,
                maxDetections = config.maxDetections,
                classNameForId = config::classNameForId,
                thresholdForClass = config::thresholdForClass,
                allowClass = config::isAllowedClass,
            )
        }

        private fun loadMappedAsset(context: Context, assetPath: String): MappedByteBuffer {
            context.assets.openFd(assetPath).use { descriptor ->
                descriptor.createInputStream().channel.use { channel ->
                    return channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.length)
                }
            }
        }

        private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L

        private const val COCO_MODEL_KEY = "coco_general"
        private const val CUSTOM_MODEL_KEY = "custom_tactile"
        private const val UNIFIED_MODEL_KEY = "unified_walksafe"
    }
}

data class AndroidDetectorLoadResult(
    val detector: TfliteAndroidFrameDetector?,
    val configLoaded: Boolean,
    val detectorAvailable: Boolean,
    val modelKey: String?,
    val fallbackUsed: Boolean,
    val reason: String,
    /** Primary-model load cause (hash/asset/tensor/runtime); null when the primary model loaded. */
    val failureCode: DetectorLoadFailureCode? = null,
    /** Snapshot at load completion; ask the detector for later inference/close phases. */
    val loadTiming: AndroidDetectorLoadTiming? = null,
)

/** Exactly one of [value] or [failureCode] is set, except an unconfigured model (both null). */
internal class ModelLoadAttempt<T>(val value: T?, val failureCode: DetectorLoadFailureCode?)

private data class SingleModelDetectionResult(
    val detections: List<DetectionCandidate>,
    val inferenceMs: Long,
    val parseMs: Long,
    val runtime: AndroidDetectorRuntime,
)

private class TfliteSingleModelDetector(
    private val model: MappedByteBuffer,
    private val loadRecorder: DetectorLoadTimingRecorder,
    private val modelKey: String,
    val inputSize: Int,
    private val runtime: ModelRuntimeOptions,
    private val gpuSerializationCache: GpuSerializationCache?,
    private val outputFormat: YoloOutputFormat,
    private val outputShape: IntArray,
    classCount: Int,
    nmsIouThreshold: Float,
    maxDetections: Int,
    classNameForId: (Int) -> String?,
    thresholdForClass: (String) -> Float,
    allowClass: (String) -> Boolean = { true },
) : Closeable {
    // Allocate host buffers and parsers before native handles so allocation failure cannot leak a delegate.
    private val flatOutput = FloatArray(outputShape.fold(1) { size, axis -> size * axis })
    private val output = ByteBuffer.allocateDirect(flatOutput.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    private val outputFloats = output.asFloatBuffer()
    private val parser = YoloEndToEndOutputParser(
        inputSize = inputSize,
        classNameForId = classNameForId,
        thresholdForClass = thresholdForClass,
        allowClass = allowClass,
    )

    private val rawParser = if (outputFormat.isRaw) {
        YoloRawOutputParser(inputSize, classCount, classNameForId, thresholdForClass, allowClass,
            nmsIouThreshold, maxDetections,
            normalizedCoordinates = outputFormat == YoloOutputFormat.RAW_XYWH_NORMALIZED)
    } else null

    private var hasCompletedOutput = false
    private var firstInferenceAttempted = false

    private val interpreterRuntime = DelegateFallbackRuntime(runtime) { delegate ->
        if (delegate == "gpu" && gpuSerializationCache != null) {
            loadRecorder.measure(AndroidDetectorLoadPhase.GPU_COMPATIBILITY, modelKey, delegate) {
                CompatibilityList().use { compatibility ->
                    check(compatibility.isDelegateSupportedOnThisDevice) { "GPU delegate unsupported on this device" }
                }
            }
            gpuSerializationCache.create { serialization ->
                createInterpreter(model, runtime, delegate, inputSize, outputShape, loadRecorder, modelKey, serialization)
            }
        } else createInterpreter(model, runtime, delegate, inputSize, outputShape, loadRecorder, modelKey)
    }

    fun detect(input: PreprocessedImage, validImageCrop: YuvCropRect? = null): SingleModelDetectionResult {
        // Checked before the delegate runs, so a wrong tensor never triggers a CPU replacement.
        DetectorTensorContract.requireModelInput(input, inputSize)
        input.inputBuffer.rewind()
        val inferenceStartedNs = System.nanoTime()
        hasCompletedOutput = false
        val recordFirstInference = !firstInferenceAttempted
        firstInferenceAttempted = true
        interpreterRuntime.run { handle ->
            input.inputBuffer.rewind()
            output.rewind()
            if (recordFirstInference) {
                // Each native attempt keeps its actual delegate: failed GPU and retried CPU are separate.
                loadRecorder.measure(AndroidDetectorLoadPhase.FIRST_INFERENCE, modelKey,
                    interpreterRuntime.runtime.activeDelegate) { handle.interpreter.run(input.inputBuffer, output) }
            } else {
                handle.interpreter.run(input.inputBuffer, output)
            }
        }
        val inferenceMs = elapsedMs(inferenceStartedNs)
        val parseStartedNs = System.nanoTime()
        outputFloats.rewind()
        outputFloats.get(flatOutput)
        val parsed = rawParser?.parse(flatOutput, input.transform, validImageCrop) ?: parser.parse(flatOutput)
        val detections = parsed.mapNotNull(input.transform::modelDetectionToImageDetection)
        hasCompletedOutput = true
        return SingleModelDetectionResult(
            detections = detections,
            inferenceMs = inferenceMs,
            parseMs = elapsedMs(parseStartedNs),
            runtime = interpreterRuntime.runtime.copy(
                gpuSerializationCacheStatus = gpuSerializationCache?.status,
                gpuSerializationCacheToken = gpuSerializationCache?.modelToken,
                gpuSerializationCacheFailureReason = gpuSerializationCache?.failureReason,
            ),
        )
    }

    override fun close() {
        interpreterRuntime.close()
    }

    fun copyLastOutput(): FloatArray? = if (hasCompletedOutput) flatOutput.copyOf() else null

    private companion object {
        fun validateTensorContract(interpreter: Interpreter, inputSize: Int, outputShape: IntArray) {
            val inputCount = interpreter.inputTensorCount
            val outputCount = interpreter.outputTensorCount
            val input = if (inputCount == 1) interpreter.getInputTensor(0) else null
            val output = if (outputCount == 1) interpreter.getOutputTensor(0) else null
            DetectorTensorContract.requireInterpreterContract(
                inputTensorCount = inputCount,
                outputTensorCount = outputCount,
                inputFloat32 = input?.dataType() == DataType.FLOAT32,
                outputFloat32 = output?.dataType() == DataType.FLOAT32,
                inputShape = input?.shape() ?: IntArray(0),
                outputShape = output?.shape() ?: IntArray(0),
                inputSize = inputSize,
                expectedOutputShape = outputShape,
            )
        }

        fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L

        fun createInterpreter(
            model: MappedByteBuffer,
            runtime: ModelRuntimeOptions,
            delegate: String,
            inputSize: Int,
            outputShape: IntArray,
            recorder: DetectorLoadTimingRecorder,
            modelKey: String,
            serialization: GpuSerializationParameters? = null,
        ): InterpreterHandle {
            var gpuDelegate: GpuDelegate? = null
            var interpreter: Interpreter? = null
            try {
                val options = createOptions(runtime, useNnapi = delegate == "nnapi")
                if (delegate == "gpu") {
                    val gpuOptions = GpuDelegateFactory.Options()
                        .setPrecisionLossAllowed(runtime.gpuPrecisionLossAllowed)
                        .setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                        .setQuantizedModelsAllowed(true)
                    serialization?.let { gpuOptions.setSerializationParams(it.directory.absolutePath, it.modelToken) }
                    gpuDelegate = recorder.measure(AndroidDetectorLoadPhase.DELEGATE_INIT, modelKey, delegate) {
                        GpuDelegate(gpuOptions)
                    }
                    options.addDelegate(gpuDelegate)
                }
                val createdInterpreter = recorder.measure(AndroidDetectorLoadPhase.INTERPRETER_INIT, modelKey, delegate) {
                    Interpreter(model, options)
                }
                interpreter = createdInterpreter
                recorder.measure(AndroidDetectorLoadPhase.TENSOR_CHECK, modelKey, delegate) {
                    validateTensorContract(createdInterpreter, inputSize, outputShape)
                }
                return InterpreterHandle(createdInterpreter, gpuDelegate)
            } catch (error: Throwable) {
                var cleanupFailed = false
                try {
                    interpreter?.close()
                } catch (closeError: Throwable) {
                    cleanupFailed = true
                    error.addSuppressed(closeError)
                }
                try {
                    gpuDelegate?.close()
                } catch (closeError: Throwable) {
                    cleanupFailed = true
                    error.addSuppressed(closeError)
                }
                if (cleanupFailed) throw DetectorNativeCleanupFailure(error)
                throw error
            }
        }

        @Suppress("DEPRECATION")
        fun createOptions(runtime: ModelRuntimeOptions, useNnapi: Boolean): Interpreter.Options {
            return Interpreter.Options()
                .setNumThreads(runtime.numThreads)
                .setUseNNAPI(useNnapi)
        }
    }
}

private class InterpreterHandle(
    val interpreter: Interpreter,
    private val gpuDelegate: GpuDelegate?,
) : Closeable {
    override fun close() {
        try {
            interpreter.close()
        } finally {
            gpuDelegate?.close()
        }
    }
}
