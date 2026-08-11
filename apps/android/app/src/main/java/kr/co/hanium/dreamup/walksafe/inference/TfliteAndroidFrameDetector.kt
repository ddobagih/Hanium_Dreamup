package kr.co.hanium.dreamup.walksafe.inference

import android.content.Context
import android.media.Image
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

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

        fun createWithStatus(context: Context, config: TwoModelRuntimeConfig): AndroidDetectorLoadResult {
            return try {
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
        ): TfliteAndroidFrameDetector? {
            val unifiedConfig = config.unifiedWalksafe ?: return null
            if (config.primaryModelKey != TwoModelRuntimeConfig.UNIFIED_MODEL_KEY || !unifiedConfig.enabled) {
                return null
            }
            return try {
                TfliteAndroidFrameDetector(
                    unifiedDetector = createSingleModelDetector(context, unifiedConfig),
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun createLegacyFallbackOrNull(
            context: Context,
            config: TwoModelRuntimeConfig,
        ): TfliteAndroidFrameDetector? {
            if (config.fallbackModelKey != TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY) {
                return null
            }
            return try {
                createLegacyTwoModel(context, config)
            } catch (_: Exception) {
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
            return TfliteAndroidFrameDetector(
                customDetector = createSingleModelDetector(context, customConfig),
                cocoDetector = createSingleModelDetector(context, cocoConfig),
            )
        }

        private fun createSingleModelDetector(
            context: Context,
            config: ModelRuntimeConfig,
        ): TfliteSingleModelDetector {
            return TfliteSingleModelDetector(
                model = loadMappedAsset(context, config.asset),
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
    val inputSize: Int,
    private val runtime: ModelRuntimeOptions,
    classNameForId: (Int) -> String?,
    thresholdForClass: (String) -> Float,
    allowClass: (String) -> Boolean = { true },
) : Closeable {
    private var interpreterHandle = createInterpreter(model, runtime)
    private val output = Array(1) { Array(OUTPUT_ROWS) { FloatArray(OUTPUT_COLUMNS) } }
    private val flatOutput = FloatArray(OUTPUT_ROWS * OUTPUT_COLUMNS)
    private val parser = YoloEndToEndOutputParser(
        inputSize = inputSize,
        classNameForId = classNameForId,
        thresholdForClass = thresholdForClass,
        allowClass = allowClass,
    )

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
