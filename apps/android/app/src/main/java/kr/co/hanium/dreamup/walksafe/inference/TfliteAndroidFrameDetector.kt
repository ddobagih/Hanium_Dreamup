package kr.co.hanium.dreamup.walksafe.inference

import android.content.Context
import android.media.Image
import android.os.Build
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.UprightCameraImage
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
    private val customDetector: TfliteSingleModelDetector? = null,
    private val cocoDetector: TfliteSingleModelDetector? = null,
    private val unifiedDetector: TfliteSingleModelDetector? = null,
    private val preprocessor: YuvImagePreprocessor = YuvImagePreprocessor(),
    private val preprocessingStrategy: YuvPreprocessingStrategy = YuvPreprocessingStrategy.LEGACY_TWO_PASS,
) : AndroidFrameDetector, Closeable {
    private val runtimeLock = Any()
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

    private fun detectOnOwner(
        cameraImage: Image,
        onPartialResult: (AndroidDetectionResult) -> Unit,
    ): AndroidDetectionResult {
        val unified = unifiedDetector
        if (unified != null) {
            return detectUnified(cameraImage, unified)
        }

        val coco = requireNotNull(cocoDetector) { "coco detector is required in legacy two-model mode" }
        val custom = requireNotNull(customDetector) { "custom detector is required in legacy two-model mode" }
        val totalStartedNs = System.nanoTime()
        val decodeStartedNs = System.nanoTime()
        val image = if (preprocessingStrategy == YuvPreprocessingStrategy.LEGACY_TWO_PASS) {
            preprocessor.decode(cameraImage)
        } else {
            null
        }
        val yuvDecodeMs = image?.let { elapsedMs(decodeStartedNs) }

        val cocoInputStartedNs = System.nanoTime()
        val cocoInput = if (image != null) preprocessor.preprocess(image, coco.inputSize)
            else preprocessor.preprocess(cameraImage, coco.inputSize, preprocessingStrategy)
        val cocoPreprocessMs = elapsedMs(cocoInputStartedNs)
        val cocoResult = coco.detect(cocoInput)
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
                detections = cocoResult.detections,
                timing = partialTiming,
                partial = true,
            ),
        )

        val customInputStartedNs = System.nanoTime()
        val customInput = if (image != null) preprocessor.preprocess(image, custom.inputSize)
            else preprocessor.preprocess(cameraImage, custom.inputSize, preprocessingStrategy)
        val customPreprocessMs = elapsedMs(customInputStartedNs)
        val customResult = custom.detect(customInput)
        return AndroidDetectionResult(
            detections = cocoResult.detections + customResult.detections,
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
    ): AndroidDetectionResult {
        val totalStartedNs = System.nanoTime()
        val decodeStartedNs = System.nanoTime()
        val image = UprightCameraImage.rotate(preprocessor.decode(cameraImage), quarterTurns)
        val yuvDecodeMs = elapsedMs(decodeStartedNs)

        val inputStartedNs = System.nanoTime()
        val input = preprocessor.preprocessBilinear(image, detector.inputSize)
        val preprocessMs = elapsedMs(inputStartedNs)
        val result = detector.detect(input)
        return AndroidDetectionResult(
            detections = result.detections.map { it.copy(bboxNorm = UprightCameraImage.toSensor(it.bboxNorm, quarterTurns)) },
            timing = AndroidDetectorTiming(
                yuvDecodeMs = yuvDecodeMs,
                modelKey = UNIFIED_MODEL_KEY,
                modelPreprocessMs = preprocessMs,
                modelInferenceMs = result.inferenceMs,
                modelParseMs = result.parseMs,
                totalMs = elapsedMs(totalStartedNs),
                completedModels = listOf(UNIFIED_MODEL_KEY),
                modelRuntime = result.runtime,
                preprocessingStrategy = "UPRIGHT_RGB_BILINEAR",
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
        AndroidDetectionResult(
            detections = result.detections,
            timing = AndroidDetectorTiming(
                modelKey = UNIFIED_MODEL_KEY,
                modelPreprocessMs = preprocessMs,
                modelInferenceMs = result.inferenceMs,
                modelParseMs = result.parseMs,
                totalMs = elapsedMs(totalStartedNs),
                completedModels = listOf(UNIFIED_MODEL_KEY),
                modelRuntime = result.runtime,
                preprocessingStrategy = "FIXED_ARGB_BILINEAR",
            ),
        )
    }

    override fun close() {
        val release = {
            synchronized(runtimeLock) {
                if (!closed) {
                    closed = true
                    closeModels()
                }
            }
        }
        if (threadOwner != null) threadOwner.close(release) else release()
    }

    private fun closeModels() {
        var failure: Throwable? = null
        listOfNotNull(customDetector, cocoDetector, unifiedDetector).forEach { detector ->
            try {
                detector.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

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
        val result = unified.detect(input)
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
            val unifiedOnly = config.copy(fallbackModelKey = null, customTactile = null, cocoGeneral = null)
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
            return try {
                createWithStatus(context, TwoModelRuntimeConfig.load(context), runtimeOverride, preprocessingStrategy)
            } catch (error: Exception) {
                AndroidDetectorLoadResult(
                    detector = null,
                    configLoaded = false,
                    detectorAvailable = false,
                    modelKey = null,
                    fallbackUsed = false,
                    reason = error::class.java.simpleName,
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
            val effectiveConfig = if (runtimeOverride == null) config else config.copy(
                unifiedWalksafe = config.unifiedWalksafe?.copy(runtime = runtimeOverride),
                customTactile = config.customTactile?.copy(runtime = runtimeOverride),
                cocoGeneral = config.cocoGeneral?.copy(runtime = runtimeOverride),
            )
            val owner = if (effectiveConfig.requiresGpuThreadOwner) DetectorThreadOwner() else null
            val create = { createOnOwner(context, effectiveConfig, owner, preprocessingStrategy) }
            return try {
                val result = owner?.call(create) ?: create()
                if (result.detector == null) owner?.close {}
                result
            } catch (error: Throwable) {
                try {
                    owner?.close {}
                } catch (closeError: Throwable) {
                    error.addSuppressed(closeError)
                }
                if (error !is Exception && error !is LinkageError) throw error
                AndroidDetectorLoadResult(null, true, false, null, false, error.javaClass.simpleName)
            }
        }

        private fun createOnOwner(
            context: Context,
            config: TwoModelRuntimeConfig,
            owner: DetectorThreadOwner?,
            preprocessingStrategy: YuvPreprocessingStrategy,
        ): AndroidDetectorLoadResult {
            return try {
                if (config.primaryModelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY) {
                    val unified = createUnifiedOrNull(context, config, owner, preprocessingStrategy)
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
                        val legacy = createLegacyFallbackOrNull(context, config, owner, preprocessingStrategy)
                        AndroidDetectorLoadResult(
                            detector = legacy,
                            configLoaded = true,
                            detectorAvailable = legacy != null,
                            modelKey = if (legacy != null) TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY else null,
                            fallbackUsed = legacy != null,
                            reason = if (legacy != null) "unified_unavailable_legacy_loaded" else "detector_assets_unavailable",
                        )
                    }
                } else {
                    AndroidDetectorLoadResult(
                        detector = createLegacyTwoModel(context, config, owner, preprocessingStrategy),
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
                )
            }
        }

        fun createOrNull(context: Context): TfliteAndroidFrameDetector? {
            return createWithStatus(context).detector
        }

        private fun createUnifiedOrNull(
            context: Context,
            config: TwoModelRuntimeConfig,
            owner: DetectorThreadOwner?,
            preprocessingStrategy: YuvPreprocessingStrategy,
        ): TfliteAndroidFrameDetector? {
            val unifiedConfig = config.unifiedWalksafe ?: return null
            if (config.primaryModelKey != TwoModelRuntimeConfig.UNIFIED_MODEL_KEY || !unifiedConfig.enabled) {
                return null
            }
            return loadModelOrNull {
                require(TwoModelRuntimeConfig.assetMatches(context, unifiedConfig)) { "unified model asset hash mismatch" }
                TfliteAndroidFrameDetector(
                    threadOwner = owner,
                    unifiedDetector = createSingleModelDetector(context, unifiedConfig),
                    preprocessingStrategy = preprocessingStrategy,
                )
            }
        }

        internal fun <T> loadModelOrNull(create: () -> T): T? = try {
            create()
        } catch (error: DetectorNativeCleanupFailure) {
            // A failed native close must also prevent switching to the legacy model pair.
            throw error
        } catch (_: Exception) {
            null
        }

        private fun createLegacyFallbackOrNull(
            context: Context,
            config: TwoModelRuntimeConfig,
            owner: DetectorThreadOwner?,
            preprocessingStrategy: YuvPreprocessingStrategy,
        ): TfliteAndroidFrameDetector? {
            if (config.fallbackModelKey != TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY) {
                return null
            }
            return loadModelOrNull {
                createLegacyTwoModel(context, config, owner, preprocessingStrategy)
            }
        }

        private fun createLegacyTwoModel(
            context: Context,
            config: TwoModelRuntimeConfig,
            owner: DetectorThreadOwner?,
            preprocessingStrategy: YuvPreprocessingStrategy,
        ): TfliteAndroidFrameDetector {
            val customConfig = requireNotNull(config.customTactile) {
                "custom_tactile model config is required in legacy two-model mode"
            }
            val cocoConfig = requireNotNull(config.cocoGeneral) {
                "coco_general model config is required in legacy two-model mode"
            }
            require(TwoModelRuntimeConfig.assetMatches(context, customConfig)) {
                "custom_tactile model asset hash mismatch"
            }
            require(TwoModelRuntimeConfig.assetMatches(context, cocoConfig)) {
                "coco_general model asset hash mismatch"
            }
            val custom = createSingleModelDetector(context, customConfig)
            return try {
                TfliteAndroidFrameDetector(
                    threadOwner = owner,
                    customDetector = custom,
                    cocoDetector = createSingleModelDetector(context, cocoConfig),
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
        ): TfliteSingleModelDetector {
            val model = loadMappedAsset(context, config.asset)
            val cache = if (config.runtime.delegate == "gpu") {
                GpuSerializationCache.prepare(config.runtime.gpuSerializationCacheEnabled) {
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
                        GpuSerializationCache.privateDirectory(context.codeCacheDir),
                        contract.modelToken(model),
                    )
                }
            } else null
            return TfliteSingleModelDetector(
                model = model,
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
)

private data class SingleModelDetectionResult(
    val detections: List<DetectionCandidate>,
    val inferenceMs: Long,
    val parseMs: Long,
    val runtime: AndroidDetectorRuntime,
)

private class TfliteSingleModelDetector(
    private val model: MappedByteBuffer,
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

    private val interpreterRuntime = DelegateFallbackRuntime(runtime) { delegate ->
        if (delegate == "gpu" && gpuSerializationCache != null) {
            CompatibilityList().use { compatibility ->
                check(compatibility.isDelegateSupportedOnThisDevice) { "GPU delegate unsupported on this device" }
            }
            gpuSerializationCache.create { serialization ->
                createInterpreter(model, runtime, delegate, inputSize, outputShape, serialization)
            }
        } else createInterpreter(model, runtime, delegate, inputSize, outputShape)
    }

    fun detect(input: PreprocessedImage): SingleModelDetectionResult {
        input.inputBuffer.rewind()
        val inferenceStartedNs = System.nanoTime()
        hasCompletedOutput = false
        interpreterRuntime.run { handle ->
            input.inputBuffer.rewind()
            output.rewind()
            handle.interpreter.run(input.inputBuffer, output)
        }
        val inferenceMs = elapsedMs(inferenceStartedNs)
        val parseStartedNs = System.nanoTime()
        outputFloats.rewind()
        outputFloats.get(flatOutput)
        val parsed = rawParser?.parse(flatOutput, input.transform) ?: parser.parse(flatOutput)
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
            require(interpreter.inputTensorCount == 1) { "model must have exactly one input tensor" }
            require(interpreter.outputTensorCount == 1) { "model must have exactly one output tensor" }
            val input = interpreter.getInputTensor(0)
            val output = interpreter.getOutputTensor(0)
            require(input.dataType() == DataType.FLOAT32) { "model input tensor must be float32" }
            require(output.dataType() == DataType.FLOAT32) { "model output tensor must be float32" }
            require(input.shape().contentEquals(intArrayOf(1, inputSize, inputSize, 3))) {
                "model input tensor must be [1,$inputSize,$inputSize,3]"
            }
            require(output.shape().contentEquals(outputShape)) {
                "model output tensor must be ${outputShape.contentToString()}"
            }
        }

        fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L

        fun createInterpreter(
            model: MappedByteBuffer,
            runtime: ModelRuntimeOptions,
            delegate: String,
            inputSize: Int,
            outputShape: IntArray,
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
                    gpuDelegate = GpuDelegate(gpuOptions)
                    options.addDelegate(gpuDelegate)
                }
                interpreter = Interpreter(model, options)
                validateTensorContract(interpreter, inputSize, outputShape)
                return InterpreterHandle(interpreter, gpuDelegate)
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
