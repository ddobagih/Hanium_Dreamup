package kr.co.hanium.dreamup.walksafe.inference

import android.content.Context
import android.media.Image
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionPurpose
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeFailures

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
    private var tensorFactory: PrimaryTensorEngineFactory? = null

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

    fun actualReleasedFuture(): CompletionStage<Unit> =
        tensorFactory?.actualReleasedFuture ?: CompletableFuture.completedFuture(Unit)

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
        try { tensorFactory?.close() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
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
                VisionRuntimeFailures.busy(error)?.let { throw it }
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

        /** Explicit local entry for the disposable fixture child; never selected by a parent caller. */
        internal fun createWithStatusLocal(
            context: Context,
            config: TwoModelRuntimeConfig,
            runtimeOverride: ModelRuntimeOptions? = null,
            preprocessingStrategy: YuvPreprocessingStrategy = YuvPreprocessingStrategy.LEGACY_TWO_PASS,
        ): AndroidDetectorLoadResult = createRecorded(context, config, runtimeOverride, preprocessingStrategy,
            DetectorLoadTimingRecorder(), local = true)

        private fun createRecorded(
            context: Context,
            config: TwoModelRuntimeConfig,
            runtimeOverride: ModelRuntimeOptions?,
            preprocessingStrategy: YuvPreprocessingStrategy,
            recorder: DetectorLoadTimingRecorder,
            local: Boolean = false,
        ): AndroidDetectorLoadResult {
            val effectiveConfig = if (runtimeOverride == null) config else config.copy(
                unifiedWalksafe = config.unifiedWalksafe?.copy(runtime = runtimeOverride),
                customTactile = config.customTactile?.copy(runtime = runtimeOverride),
                cocoGeneral = config.cocoGeneral?.copy(runtime = runtimeOverride),
                personSupplement = config.personSupplement?.copy(runtime = runtimeOverride),
            )
            val factory = try {
                if (local) PrimaryTensorEngines.local(context)
                else PrimaryTensorEngines.remote(context, SystemClock.elapsedRealtime() + 60_000L)
            } catch (error: Exception) {
                VisionRuntimeFailures.busy(error)?.let { throw it }
                return AndroidDetectorLoadResult(null, true, false, null, false, error.javaClass.simpleName,
                    loadTiming = recorder.finishLoad(false), actualReleasedFuture = CompletableFuture.completedFuture(Unit))
            }
            val owner = if (factory.requiresOwnerThread || effectiveConfig.requiresGpuThreadOwner) DetectorThreadOwner() else null
            val queuedNs = recorder.now()
            var createEntered = false
            val create = {
                createEntered = true
                if (owner != null) recorder.record(AndroidDetectorLoadPhase.OWNER_QUEUE, queuedNs)
                createOnOwner(context, effectiveConfig, owner, preprocessingStrategy, recorder, factory)
            }
            val result = try {
                val result = owner?.call(create) ?: create()
                if (result.detector == null) {
                    if (owner != null) owner.close { factory.close() } else factory.close()
                }
                result
            } catch (error: Throwable) {
                if (owner != null && !createEntered) recorder.record(AndroidDetectorLoadPhase.OWNER_QUEUE, queuedNs, failure = error)
                try {
                    if (owner != null) owner.close { factory.close() } else factory.close()
                } catch (closeError: Throwable) {
                    if (closeError !== error) error.addSuppressed(closeError)
                }
                VisionRuntimeFailures.busy(error)?.let { throw it }
                if (error !is Exception && error !is LinkageError) throw error
                AndroidDetectorLoadResult(null, true, false, null, false, error.javaClass.simpleName)
            }
            return result.copy(loadTiming = recorder.finishLoad(result.detectorAvailable), actualReleasedFuture = factory.actualReleasedFuture)
        }

        private fun createOnOwner(
            context: Context,
            config: TwoModelRuntimeConfig,
            owner: DetectorThreadOwner?,
            preprocessingStrategy: YuvPreprocessingStrategy,
            recorder: DetectorLoadTimingRecorder,
            factory: PrimaryTensorEngineFactory,
        ): AndroidDetectorLoadResult {
            val loaded = try {
                if (config.primaryModelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY) {
                    val unifiedAttempt = createUnified(context, config, owner, preprocessingStrategy, recorder, factory)
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
                        val legacy = createLegacyFallbackOrNull(context, config, owner, preprocessingStrategy, recorder, factory)
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
                        detector = createLegacyTwoModel(context, config, owner, preprocessingStrategy, recorder, factory),
                        configLoaded = true,
                        detectorAvailable = true,
                        modelKey = TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY,
                        fallbackUsed = false,
                        reason = "legacy_loaded",
                    )
                }
            } catch (error: Exception) {
                VisionRuntimeFailures.busy(error)?.let { throw it }
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
            loaded.detector?.tensorFactory = factory
            return loaded
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
            factory: PrimaryTensorEngineFactory,
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
                val unified = createSingleModelDetector(unifiedConfig, recorder, UNIFIED_MODEL_KEY, factory)
                var person: TfliteSingleModelDetector? = null
                try {
                    config.personSupplement?.takeIf { it.enabled }?.let { personConfig ->
                        recorder.measure(AndroidDetectorLoadPhase.ASSET_INTEGRITY,
                            TwoModelRuntimeConfig.PERSON_SUPPLEMENT_MODEL_KEY) {
                            requireAssetIntegrity(context, personConfig, "person_supplement")
                        }
                        person = createSingleModelDetector(personConfig, recorder,
                            TwoModelRuntimeConfig.PERSON_SUPPLEMENT_MODEL_KEY, factory)
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
        } catch (error: DetectorCohortRetired) {
            // A failed remote cohort cannot be combined with another model's old handle.
            throw error
        } catch (error: Exception) {
            VisionRuntimeFailures.busy(error)?.let { throw it }
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
            factory: PrimaryTensorEngineFactory,
        ): TfliteAndroidFrameDetector? {
            if (config.fallbackModelKey != TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY) {
                return null
            }
            return loadModelOrNull {
                createLegacyTwoModel(context, config, owner, preprocessingStrategy, recorder, factory)
            }
        }

        private fun createLegacyTwoModel(
            context: Context,
            config: TwoModelRuntimeConfig,
            owner: DetectorThreadOwner?,
            preprocessingStrategy: YuvPreprocessingStrategy,
            recorder: DetectorLoadTimingRecorder,
            factory: PrimaryTensorEngineFactory,
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
            val custom = createSingleModelDetector(customConfig, recorder, CUSTOM_MODEL_KEY, factory)
            return try {
                TfliteAndroidFrameDetector(
                    threadOwner = owner,
                    loadRecorder = recorder,
                    customDetector = custom,
                    cocoDetector = createSingleModelDetector(cocoConfig, recorder, COCO_MODEL_KEY, factory),
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
            config: ModelRuntimeConfig,
            recorder: DetectorLoadTimingRecorder,
            modelKey: String,
            factory: PrimaryTensorEngineFactory,
        ): TfliteSingleModelDetector = TfliteSingleModelDetector(config, recorder, modelKey, factory)

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
    /** Independent physical proof, retained even when remote OPEN returns no detector. */
    val actualReleasedFuture: CompletionStage<Unit>? = null,
)

/** Exactly one of [value] or [failureCode] is set, except an unconfigured model (both null). */
internal class ModelLoadAttempt<T>(val value: T?, val failureCode: DetectorLoadFailureCode?)

internal data class SingleModelDetectionResult(
    val detections: List<DetectionCandidate>,
    val inferenceMs: Long,
    val parseMs: Long,
    val runtime: AndroidDetectorRuntime,
)

/** Host-only tensor preparation/output parsing; this class never creates a native interpreter. */
internal class TfliteSingleModelDetector(
    private val config: ModelRuntimeConfig,
    private val loadRecorder: DetectorLoadTimingRecorder,
    private val modelKey: String,
    private val factory: PrimaryTensorEngineFactory,
) : Closeable {
    val inputSize = config.inputSize
    private val flatOutput = FloatArray(config.outputTensorShape().fold(1) { size, axis -> size * axis })
    private val output = ByteBuffer.allocateDirect(flatOutput.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    private val outputFloats = output.asFloatBuffer()
    private val parser = YoloEndToEndOutputParser(inputSize = inputSize,
        classNameForId = config::classNameForId, thresholdForClass = config::thresholdForClass,
        thresholdForClassId = config::thresholdForClassId,
        pixelCoordinates = config.outputFormat == YoloOutputFormat.END_TO_END_XYXY_PIXELS,
        allowClass = config::isAllowedClass)
    private val rawParser = if (config.outputFormat.isRaw) {
        YoloRawOutputParser(inputSize, config.classes.size, config::classNameForId, config::thresholdForClass,
            config::isAllowedClass, config.nmsIouThreshold, config.maxDetections,
            normalizedCoordinates = config.outputFormat == YoloOutputFormat.RAW_XYWH_NORMALIZED)
    } else null
    private var hasCompletedOutput = false
    private var firstInferenceAttempted = false
    private val interpreterRuntime = DelegateFallbackRuntime(config.runtime) { delegate ->
        factory.open(config, delegate, loadRecorder, modelKey)
    }

    fun detect(input: PreprocessedImage, validImageCrop: YuvCropRect? = null): SingleModelDetectionResult {
        DetectorTensorContract.requireModelInput(input, inputSize)
        input.inputBuffer.rewind()
        hasCompletedOutput = false
        val first = !firstInferenceAttempted
        firstInferenceAttempted = true
        val startedNs = System.nanoTime()
        val observed = interpreterRuntime.run { engine ->
            input.inputBuffer.rewind(); output.rewind()
            val invoke = { engine.invoke(input.inputBuffer, output, inputSize, inputSize,
                if (first) VisionPurpose.WARMUP else VisionPurpose.LIVE) }
            val result = if (first) loadRecorder.measure(AndroidDetectorLoadPhase.FIRST_INFERENCE, modelKey,
                interpreterRuntime.runtime.activeDelegate, invoke) else invoke()
            PrimaryTensorContract.observedRuntime(result.runtime, interpreterRuntime.runtime)
        }
        val inferenceMs = (System.nanoTime() - startedNs) / 1_000_000L
        val parseStartedNs = System.nanoTime()
        outputFloats.rewind()
        outputFloats.get(flatOutput)
        val parsed = rawParser?.parse(flatOutput, input.transform, validImageCrop) ?: parser.parse(flatOutput)
        val detections = parsed.mapNotNull(input.transform::modelDetectionToImageDetection)
        hasCompletedOutput = true
        return SingleModelDetectionResult(detections, inferenceMs,
            (System.nanoTime() - parseStartedNs) / 1_000_000L, observed)
    }

    override fun close() = interpreterRuntime.close()
    fun copyLastOutput(): FloatArray? = if (hasCompletedOutput) flatOutput.copyOf() else null
}
