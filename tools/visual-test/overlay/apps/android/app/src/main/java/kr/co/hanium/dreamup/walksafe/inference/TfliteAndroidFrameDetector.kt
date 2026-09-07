package kr.co.hanium.dreamup.walksafe.inference

import android.content.Context
import android.media.Image
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import java.io.Closeable
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Executes either one unified model or the complete custom-tactile/COCO legacy pair described by
 * [TwoModelRuntimeConfig]. Model identity and fallback state are returned to the caller for report
 * provenance instead of being hidden inside the detector.
 */
class TfliteAndroidFrameDetector private constructor(
    private val customDetector: TfliteSingleModelDetector? = null,
    private val cocoDetector: TfliteSingleModelDetector? = null,
    private val unifiedDetector: TfliteSingleModelDetector? = null,
    private val preprocessor: YuvImagePreprocessor = YuvImagePreprocessor(),
) : AndroidFrameDetector, Closeable {
    private var invocationCount = 0

    override fun detect(
        cameraImage: Image,
        timestampMs: Long,
        onPartialResult: (AndroidDetectionResult) -> Unit,
    ): AndroidDetectionResult {
        invocationCount += 1
        val unified = unifiedDetector
        if (unified != null) {
            return detectUnified(cameraImage, unified)
        }

        val coco = requireNotNull(cocoDetector) { "coco detector is required in legacy two-model mode" }
        val custom = requireNotNull(customDetector) { "custom detector is required in legacy two-model mode" }
        val totalStartedNs = System.nanoTime()
        val decodeStartedNs = System.nanoTime()
        val image = preprocessor.decode(cameraImage)
        val yuvDecodeMs = elapsedMs(decodeStartedNs)

        val cocoInputStartedNs = System.nanoTime()
        val cocoInput = preprocessor.preprocess(image, coco.inputSize)
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
        val customInput = preprocessor.preprocess(image, custom.inputSize)
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
            ),
        )
    }

    private fun detectUnified(
        cameraImage: Image,
        detector: TfliteSingleModelDetector,
    ): AndroidDetectionResult {
        val totalStartedNs = System.nanoTime()
        val decodeStartedNs = System.nanoTime()
        val image = preprocessor.decode(cameraImage)
        val yuvDecodeMs = elapsedMs(decodeStartedNs)

        val inputStartedNs = System.nanoTime()
        val input = preprocessor.preprocess(image, detector.inputSize)
        val preprocessMs = elapsedMs(inputStartedNs)
        val result = detector.detect(input)
        return AndroidDetectionResult(
            detections = result.detections,
            timing = AndroidDetectorTiming(
                yuvDecodeMs = yuvDecodeMs,
                modelKey = UNIFIED_MODEL_KEY,
                modelPreprocessMs = preprocessMs,
                modelInferenceMs = result.inferenceMs,
                modelParseMs = result.parseMs,
                totalMs = elapsedMs(totalStartedNs),
                completedModels = listOf(UNIFIED_MODEL_KEY),
                modelRuntime = result.runtime,
            ),
        )
    }

    override fun close() {
        customDetector?.close()
        cocoDetector?.close()
        unifiedDetector?.close()
    }

    companion object {
        fun createWithStatus(context: Context): AndroidDetectorLoadResult {
            return try {
                createWithStatus(context, TwoModelRuntimeConfig.load(context))
            } catch (error: Exception) {
                DetectorLoadDiagnostics.record("load_failed", "stage=config config_loaded=false", error)
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
        fun createWithStatus(context: Context, config: TwoModelRuntimeConfig): AndroidDetectorLoadResult {
            DetectorLoadDiagnostics.record(
                "selection",
                "primary=${config.primaryModelKey} fallback=${config.fallbackModelKey ?: "none"}",
            )
            val result = try {
                if (config.primaryModelKey == TwoModelRuntimeConfig.UNIFIED_MODEL_KEY) {
                    val unified = createUnifiedOrNull(context, config)
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
                        val legacy = createLegacyFallbackOrNull(context, config)
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
                        detector = createLegacyTwoModel(context, config),
                        configLoaded = true,
                        detectorAvailable = true,
                        modelKey = TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY,
                        fallbackUsed = false,
                        reason = "legacy_loaded",
                    )
                }
            } catch (error: Exception) {
                DetectorLoadDiagnostics.record("load_failed", "stage=model_selection config_loaded=true", error)
                AndroidDetectorLoadResult(
                    detector = null,
                    configLoaded = true,
                    detectorAvailable = false,
                    modelKey = null,
                    fallbackUsed = false,
                    reason = error::class.java.simpleName,
                )
            }
            DetectorLoadDiagnostics.record(
                "load_result",
                "config_loaded=${result.configLoaded} available=${result.detectorAvailable}" +
                    " model=${result.modelKey ?: "none"} fallback_used=${result.fallbackUsed}" +
                    " reason=${result.reason}",
            )
            return result
        }

        fun createOrNull(context: Context): TfliteAndroidFrameDetector? {
            return createWithStatus(context).detector
        }

        private fun createUnifiedOrNull(
            context: Context,
            config: TwoModelRuntimeConfig,
        ): TfliteAndroidFrameDetector? {
            val unifiedConfig = config.unifiedWalksafe ?: run {
                DetectorLoadDiagnostics.record("model_skipped", "model=$UNIFIED_MODEL_KEY reason=config_missing")
                return null
            }
            if (config.primaryModelKey != TwoModelRuntimeConfig.UNIFIED_MODEL_KEY || !unifiedConfig.enabled) {
                DetectorLoadDiagnostics.record(
                    "model_skipped",
                    "model=$UNIFIED_MODEL_KEY reason=not_selected_or_disabled enabled=${unifiedConfig.enabled}",
                )
                return null
            }
            return try {
                TfliteAndroidFrameDetector(
                    unifiedDetector = createSingleModelDetector(context, unifiedConfig),
                )
            } catch (error: Exception) {
                DetectorLoadDiagnostics.record("primary_failed", "model=$UNIFIED_MODEL_KEY", error)
                null
            }
        }

        private fun createLegacyFallbackOrNull(
            context: Context,
            config: TwoModelRuntimeConfig,
        ): TfliteAndroidFrameDetector? {
            if (config.fallbackModelKey != TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY) {
                DetectorLoadDiagnostics.record("fallback_skipped", "reason=not_configured")
                return null
            }
            DetectorLoadDiagnostics.record("fallback_start", "model=${TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY}")
            return try {
                createLegacyTwoModel(context, config)
            } catch (error: Exception) {
                DetectorLoadDiagnostics.record("fallback_failed", "model=${TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY}", error)
                null
            }
        }

        private fun createLegacyTwoModel(
            context: Context,
            config: TwoModelRuntimeConfig,
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
            try {
                return TfliteAndroidFrameDetector(
                    customDetector = custom,
                    cocoDetector = createSingleModelDetector(context, cocoConfig),
                )
            } catch (error: Throwable) {
                // Argument evaluation used to leave the first interpreter open if COCO failed.
                runCatching { custom.close() }.onFailure { closeError ->
                    DetectorLoadDiagnostics.record("cleanup_failed", "model=$CUSTOM_MODEL_KEY", closeError)
                }
                throw error
            }
        }

        private fun createSingleModelDetector(
            context: Context,
            config: ModelRuntimeConfig,
        ): TfliteSingleModelDetector {
            DetectorLoadDiagnostics.record(
                "model_start",
                "model=${config.key} input_size=${config.inputSize}" +
                    " classes=${config.classes.size} delegate=${config.runtime.delegate}" +
                    " threads=${config.runtime.numThreads}",
            )
            val model = try {
                loadMappedAsset(context, config.asset).also {
                    DetectorLoadDiagnostics.record("asset_mmap_ready", "model=${config.key} bytes=${it.capacity()}")
                }
            } catch (error: Exception) {
                DetectorLoadDiagnostics.record("asset_mmap_failed", "model=${config.key}", error)
                throw error
            }
            return TfliteSingleModelDetector(
                model = model,
                modelKey = config.key,
                inputSize = config.inputSize,
                runtime = config.runtime,
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
    private val modelKey: String,
    val inputSize: Int,
    private val runtime: ModelRuntimeOptions,
    classNameForId: (Int) -> String?,
    thresholdForClass: (String) -> Float,
    allowClass: (String) -> Boolean = { true },
) : Closeable {
    private var interpreterHandle = try {
        DetectorLoadDiagnostics.record("interpreter_start", "model=$modelKey delegate=${runtime.delegate}")
        createInterpreter(model, runtime).also {
            DetectorLoadDiagnostics.record(
                "interpreter_ready",
                "model=$modelKey active_delegate=${it.runtime.activeDelegate}",
            )
        }
    } catch (error: Exception) {
        DetectorLoadDiagnostics.record("interpreter_failed", "model=$modelKey", error)
        throw error
    }
    private val output = Array(1) { Array(OUTPUT_ROWS) { FloatArray(OUTPUT_COLUMNS) } }
    private val flatOutput = FloatArray(OUTPUT_ROWS * OUTPUT_COLUMNS)
    private val parser = YoloEndToEndOutputParser(
        inputSize = inputSize,
        classNameForId = classNameForId,
        thresholdForClass = thresholdForClass,
        allowClass = allowClass,
    )

    init {
        try {
            validateTensorContract(interpreterHandle.interpreter, inputSize, modelKey)
            DetectorLoadDiagnostics.record("model_ready", "model=$modelKey")
        } catch (error: RuntimeException) {
            DetectorLoadDiagnostics.record("tensor_contract_failed", "model=$modelKey", error)
            interpreterHandle.interpreter.close()
            throw error
        }
    }

    fun detect(input: PreprocessedImage): SingleModelDetectionResult {
        input.inputBuffer.rewind()
        val inferenceStartedNs = System.nanoTime()
        runWithFallback(input)
        val inferenceMs = elapsedMs(inferenceStartedNs)
        val parseStartedNs = System.nanoTime()
        var index = 0
        for (row in 0 until OUTPUT_ROWS) {
            for (column in 0 until OUTPUT_COLUMNS) {
                flatOutput[index++] = output[0][row][column]
            }
        }
        val detections = parser.parse(flatOutput).map { candidate ->
            candidate.copy(bboxNorm = input.transform.modelRectToImageRect(candidate.bboxNorm))
        }
        return SingleModelDetectionResult(
            detections = detections,
            inferenceMs = inferenceMs,
            parseMs = elapsedMs(parseStartedNs),
            runtime = interpreterHandle.runtime,
        )
    }

    override fun close() {
        interpreterHandle.interpreter.close()
    }

    /** A delegate failure may rebuild the interpreter once on CPU; model selection never changes here. */
    private fun runWithFallback(input: PreprocessedImage) {
        try {
            interpreterHandle.interpreter.run(input.inputBuffer, output)
        } catch (error: RuntimeException) {
            if (interpreterHandle.runtime.activeDelegate == "cpu" || !runtime.fallbackToCpu) throw error
            interpreterHandle.interpreter.close()
            interpreterHandle = createCpuInterpreter(model, runtime, fallbackUsed = true)
            input.inputBuffer.rewind()
            interpreterHandle.interpreter.run(input.inputBuffer, output)
        }
    }

    private companion object {
        const val OUTPUT_ROWS = 300
        const val OUTPUT_COLUMNS = 6

        fun validateTensorContract(interpreter: Interpreter, inputSize: Int, modelKey: String) {
            DetectorLoadDiagnostics.record(
                "tensor_counts",
                "model=$modelKey input_count=${interpreter.inputTensorCount}" +
                    " output_count=${interpreter.outputTensorCount}",
            )
            require(interpreter.inputTensorCount == 1) { "model must have exactly one input tensor" }
            require(interpreter.outputTensorCount == 1) { "model must have exactly one output tensor" }
            val input = interpreter.getInputTensor(0)
            val output = interpreter.getOutputTensor(0)
            DetectorLoadDiagnostics.record(
                "tensor_contract",
                "model=$modelKey input_type=${input.dataType()} input_shape=${input.shape().joinToString("x")}" +
                    " output_type=${output.dataType()} output_shape=${output.shape().joinToString("x")}" +
                    " expected_input=1x${inputSize}x${inputSize}x3 expected_output=1x${OUTPUT_ROWS}x$OUTPUT_COLUMNS",
            )
            require(input.dataType() == DataType.FLOAT32) { "model input tensor must be float32" }
            require(output.dataType() == DataType.FLOAT32) { "model output tensor must be float32" }
            require(input.shape().contentEquals(intArrayOf(1, inputSize, inputSize, 3))) {
                "model input tensor must be [1,$inputSize,$inputSize,3]"
            }
            require(output.shape().contentEquals(intArrayOf(1, OUTPUT_ROWS, OUTPUT_COLUMNS))) {
                "model output tensor must be [1,$OUTPUT_ROWS,$OUTPUT_COLUMNS]"
            }
        }

        fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L

        fun createInterpreter(model: MappedByteBuffer, runtime: ModelRuntimeOptions): InterpreterHandle {
            if (runtime.delegate == "nnapi") {
                try {
                    return InterpreterHandle(
                        interpreter = Interpreter(model, createOptions(runtime, useNnapi = true)),
                        runtime = AndroidDetectorRuntime(
                            requestedDelegate = runtime.delegate,
                            activeDelegate = "nnapi",
                            numThreads = runtime.numThreads,
                        ),
                    )
                } catch (error: RuntimeException) {
                    if (!runtime.fallbackToCpu) throw error
                }
            }
            return createCpuInterpreter(model, runtime, fallbackUsed = runtime.delegate != "cpu")
        }

        fun createCpuInterpreter(
            model: MappedByteBuffer,
            runtime: ModelRuntimeOptions,
            fallbackUsed: Boolean,
        ): InterpreterHandle {
            return InterpreterHandle(
                interpreter = Interpreter(model, createOptions(runtime, useNnapi = false)),
                runtime = AndroidDetectorRuntime(
                    requestedDelegate = runtime.delegate,
                    activeDelegate = "cpu",
                    numThreads = runtime.numThreads,
                    fallbackUsed = fallbackUsed,
                ),
            )
        }

        @Suppress("DEPRECATION")
        fun createOptions(runtime: ModelRuntimeOptions, useNnapi: Boolean): Interpreter.Options {
            return Interpreter.Options()
                .setNumThreads(runtime.numThreads)
                .setUseNNAPI(useNnapi)
        }
    }
}

private data class InterpreterHandle(
    val interpreter: Interpreter,
    val runtime: AndroidDetectorRuntime,
)
