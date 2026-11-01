package kr.co.hanium.dreamup.walksafe.inference

import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** Plane bytes are private copies, so native inference never borrows a CameraX or ARCore image. */
class OwnedYuvFrame private constructor(
    val width: Int,
    val height: Int,
    private val y: ByteArray,
    private val u: ByteArray,
    private val v: ByteArray,
    private val yRowStride: Int,
    private val yPixelStride: Int,
    private val uvRowStride: Int,
    private val uvPixelStride: Int,
    internal val cropRect: YuvCropRect,
    private val colorConversion: YuvColorConversion,
) {
    internal fun toArgb(): ArgbImage = ArgbImage(
        width,
        height,
        yuv420ToArgbPixels(
            width, height, yBuffer(), uBuffer(), vBuffer(),
            yRowStride, yPixelStride, uvRowStride, uvPixelStride, cropRect,
            colorConversion = colorConversion,
        ),
    )

    /** Writes only into an explicitly supplied owner scratch; [toArgb] remains an owned copy. */
    internal fun toArgbInto(destination: IntArray): ArgbImage = ArgbImage(width, height,
        yuv420ToArgbPixels(width, height, yBuffer(), uBuffer(), vBuffer(),
            yRowStride, yPixelStride, uvRowStride, uvPixelStride, cropRect, destination, colorConversion))

    internal fun preprocessFused(preprocessor: YuvImagePreprocessor, modelSize: Int): PreprocessedImage =
        preprocessor.preprocessYuv420(
            width, height, yBuffer(), uBuffer(), vBuffer(),
            yRowStride, yPixelStride, uvRowStride, uvPixelStride, modelSize, cropRect, colorConversion,
        )

    private fun yBuffer(): ByteBuffer = ByteBuffer.wrap(y).asReadOnlyBuffer()
    private fun uBuffer(): ByteBuffer = ByteBuffer.wrap(u).asReadOnlyBuffer()
    private fun vBuffer(): ByteBuffer = ByteBuffer.wrap(v).asReadOnlyBuffer()

    companion object {
        fun copyOf(image: Image): OwnedYuvFrame = copyOf(image, image.cropRect)

        /** CameraX can expose a crop on ImageProxy that differs from the wrapped Image crop. */
        fun copyOf(image: Image, cropRect: Rect): OwnedYuvFrame {
            require(image.format == ImageFormat.YUV_420_888) { "YUV_420_888 frame required" }
            val planes = image.planes
            require(planes.size >= 3) { "three YUV planes required" }
            return copyOf(
                image.width, image.height,
                planes[0].buffer, planes[1].buffer, planes[2].buffer,
                planes[0].rowStride, planes[0].pixelStride,
                planes[1].rowStride, planes[1].pixelStride,
                YuvCropRect.fromRect(cropRect),
                YuvColorConversion.fromImage(image),
            )
        }

        internal fun copyOf(
            width: Int,
            height: Int,
            yBuffer: ByteBuffer,
            uBuffer: ByteBuffer,
            vBuffer: ByteBuffer,
            yRowStride: Int,
            yPixelStride: Int,
            uvRowStride: Int,
            uvPixelStride: Int,
            cropRect: YuvCropRect = YuvCropRect.full(width, height),
        ): OwnedYuvFrame = copyOf(width, height, yBuffer, uBuffer, vBuffer,
            yRowStride, yPixelStride, uvRowStride, uvPixelStride, cropRect,
            YuvColorConversion.BT601_FULL)

        private fun copyOf(
            width: Int,
            height: Int,
            yBuffer: ByteBuffer,
            uBuffer: ByteBuffer,
            vBuffer: ByteBuffer,
            yRowStride: Int,
            yPixelStride: Int,
            uvRowStride: Int,
            uvPixelStride: Int,
            cropRect: YuvCropRect,
            colorConversion: YuvColorConversion,
        ): OwnedYuvFrame {
            require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE)
            cropRect.requireWithin(width, height)
            return OwnedYuvFrame(
                width, height,
                copyPlane(yBuffer), copyPlane(uBuffer), copyPlane(vBuffer),
                yRowStride, yPixelStride, uvRowStride, uvPixelStride, cropRect, colorConversion,
            )
        }

        private fun copyPlane(buffer: ByteBuffer): ByteArray {
            val source = buffer.duplicate()
            source.position(0)
            return ByteArray(source.remaining()).also { source.get(it) }
        }
    }
}

/**
 * Walk-time callers must use [detectOriented]: every implementation handles the upright rotation
 * explicitly, so there is no default that silently forwards to the unrotated [detect] path.
 * [detect] is only for input that is already upright or for execution/latency checks whose boxes
 * are not used as walking guidance.
 */
interface AndroidFrameDetector {
    /** Output boxes remain in original sensor coordinates, regardless of model input rotation. */
    fun detectOriented(
        cameraImage: Image,
        timestampMs: Long,
        quarterTurns: Int,
        onPartialResult: (AndroidDetectionResult) -> Unit = {},
    ): AndroidDetectionResult

    /** The source Image must already be closed before this native call begins. */
    fun detectOriented(
        cameraFrame: OwnedYuvFrame,
        timestampMs: Long,
        quarterTurns: Int,
        onPartialResult: (AndroidDetectionResult) -> Unit = {},
    ): AndroidDetectionResult

    fun detect(
        cameraImage: Image,
        timestampMs: Long,
        onPartialResult: (AndroidDetectionResult) -> Unit = {},
    ): AndroidDetectionResult

    fun detect(
        cameraFrame: OwnedYuvFrame,
        timestampMs: Long,
        onPartialResult: (AndroidDetectionResult) -> Unit = {},
    ): AndroidDetectionResult
}

/** Stands in while no model is loaded. It never reports a completed empty inference. */
class NoopAndroidFrameDetector : AndroidFrameDetector {
    override fun detectOriented(
        cameraImage: Image,
        timestampMs: Long,
        quarterTurns: Int,
        onPartialResult: (AndroidDetectionResult) -> Unit,
    ): AndroidDetectionResult = AndroidDetectionResult.unavailable()

    override fun detectOriented(
        cameraFrame: OwnedYuvFrame,
        timestampMs: Long,
        quarterTurns: Int,
        onPartialResult: (AndroidDetectionResult) -> Unit,
    ): AndroidDetectionResult = AndroidDetectionResult.unavailable()

    override fun detect(
        cameraImage: Image,
        timestampMs: Long,
        onPartialResult: (AndroidDetectionResult) -> Unit,
    ): AndroidDetectionResult = AndroidDetectionResult.unavailable()

    override fun detect(
        cameraFrame: OwnedYuvFrame,
        timestampMs: Long,
        onPartialResult: (AndroidDetectionResult) -> Unit,
    ): AndroidDetectionResult = AndroidDetectionResult.unavailable()
}

/**
 * Only [SUCCESS] means a model inference completed; an empty SUCCESS is "nothing detected in this
 * frame", never "safe". UNAVAILABLE/FAILED carry no detections and must not replace a newer or
 * still-fresh result as if the scene were empty. Staleness is judged by the caller from capture time.
 */
enum class AndroidDetectionStatus { SUCCESS, UNAVAILABLE, FAILED }

data class AndroidDetectionResult(
    val detections: List<DetectionCandidate>,
    val timing: AndroidDetectorTiming = AndroidDetectorTiming(),
    val partial: Boolean = false,
    val status: AndroidDetectionStatus = AndroidDetectionStatus.SUCCESS,
) {
    init {
        require(status == AndroidDetectionStatus.SUCCESS || (detections.isEmpty() && !partial)) {
            "only a completed inference can carry detections or partial output"
        }
    }

    val inferenceCompleted: Boolean get() = status == AndroidDetectionStatus.SUCCESS

    companion object {
        /** A completed inference with no detections. */
        fun empty(): AndroidDetectionResult = AndroidDetectionResult(emptyList())

        /** No detector is loaded or it was closed; no inference ran. */
        fun unavailable(): AndroidDetectionResult =
            AndroidDetectionResult(emptyList(), status = AndroidDetectionStatus.UNAVAILABLE)

        /** The inference call failed; its absence of boxes is not evidence of an empty scene. */
        fun failed(): AndroidDetectionResult =
            AndroidDetectionResult(emptyList(), status = AndroidDetectionStatus.FAILED)
    }
}

/** Calibration owns the optional raw copy; normal inference never allocates this snapshot. */
data class AndroidCalibrationDetectionResult(
    val result: AndroidDetectionResult,
    val rawOutput: FloatArray? = null,
)

data class AndroidDetectorTiming(
    val yuvDecodeMs: Long? = null,
    val modelKey: String? = null,
    val modelPreprocessMs: Long? = null,
    val modelInferenceMs: Long? = null,
    val modelParseMs: Long? = null,
    val cocoPreprocessMs: Long? = null,
    val cocoInferenceMs: Long? = null,
    val cocoParseMs: Long? = null,
    val customPreprocessMs: Long? = null,
    val customInferenceMs: Long? = null,
    val customParseMs: Long? = null,
    val totalMs: Long? = null,
    val completedModels: List<String> = emptyList(),
    val skippedModels: List<String> = emptyList(),
    val modelRuntime: AndroidDetectorRuntime? = null,
    val cocoRuntime: AndroidDetectorRuntime? = null,
    val customRuntime: AndroidDetectorRuntime? = null,
    val preprocessingStrategy: String? = null,
    val ownedTensorCopyMs: Long? = null,
    /** Separate provenance/cost: supplementary people must not be reported as primary inference work. */
    val personSupplementInferenceMs: Long? = null,
    val personSupplementParseMs: Long? = null,
    val personSupplementRuntime: AndroidDetectorRuntime? = null,
)

data class AndroidDetectorRuntime(
    val requestedDelegate: String,
    val activeDelegate: String,
    val numThreads: Int,
    val fallbackUsed: Boolean = false,
    val fallbackReason: String? = null,
    // A GPU delegate being attached does not imply that every graph operation ran on the GPU.
    val gpuPrecisionLossAllowed: Boolean? = null,
    // "configured" records serialization parameters being supplied, not a native cache hit.
    val gpuSerializationCacheStatus: String? = null,
    val gpuSerializationCacheToken: String? = null,
    val gpuSerializationCacheFailureReason: String? = null,
    /** Observed native owner, distinct from the parent's preprocessing/parser thread. */
    val modelOwnerPid: Int? = null,
    val modelOwnerThreadId: Long? = null,
    val modelInstanceId: String? = null,
    val cohortId: String? = null,
    val nativeLoadMs: Double? = null,
    val nativeRuntimeVersion: String? = null,
)

/** Only executed phases are present. A missing phase is not a successful zero-duration phase. */
enum class AndroidDetectorLoadPhase {
    CONFIG_LOAD, OWNER_QUEUE, ASSET_INTEGRITY, ASSET_MAP, GPU_CACHE_PREPARE,
    GPU_COMPATIBILITY, DELEGATE_INIT, INTERPRETER_INIT, TENSOR_CHECK,
    FIRST_INFERENCE, CLOSE_QUEUE, NATIVE_CLOSE,
}

enum class AndroidDetectorPhaseOutcome { SUCCEEDED, FAILED }

data class AndroidDetectorPhaseTiming(
    val phase: AndroidDetectorLoadPhase,
    val startedNs: Long,
    val finishedNs: Long,
    val threadId: Long,
    val threadName: String,
    val outcome: AndroidDetectorPhaseOutcome,
    val modelKey: String? = null,
    val delegate: String? = null,
    val failureType: String? = null,
) {
    val elapsedNs: Long get() = finishedNs - startedNs
    val elapsedMs: Double get() = elapsedNs / 1_000_000.0
}

/** Monotonic process-local times. Phase intervals can overlap during an inference fallback. */
data class AndroidDetectorLoadTiming(
    val loadGeneration: Long,
    val requestedNs: Long,
    val requestThreadId: Long,
    val requestThreadName: String,
    val loadFinishedNs: Long?,
    val detectorAvailable: Boolean?,
    val phases: List<AndroidDetectorPhaseTiming>,
) {
    val loadElapsedMs: Double? get() = loadFinishedNs?.let { (it - requestedNs) / 1_000_000.0 }
}

/** Small per-load record; snapshots are copied only when a caller requests diagnostics. */
internal class DetectorLoadTimingRecorder(private val nowNs: () -> Long = System::nanoTime) {
    private val generation = nextGeneration.incrementAndGet()
    private val requestedNs = nowNs()
    private val requestThreadId = Thread.currentThread().id
    private val requestThreadName = Thread.currentThread().name
    private val phases = mutableListOf<AndroidDetectorPhaseTiming>()
    private var loadFinishedNs: Long? = null
    private var available: Boolean? = null

    fun now(): Long = nowNs()

    fun <T> measure(phase: AndroidDetectorLoadPhase, modelKey: String? = null,
        delegate: String? = null, block: () -> T): T {
        val startedNs = nowNs()
        var failure: Throwable? = null
        try {
            return block()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            record(phase, startedNs, modelKey, delegate, failure)
        }
    }

    /** Ends a queue interval when its accepted task starts, or when admission fails. */
    fun record(phase: AndroidDetectorLoadPhase, startedNs: Long, modelKey: String? = null,
        delegate: String? = null, failure: Throwable? = null) {
        val finishedNs = nowNs()
        val thread = Thread.currentThread()
        val timing = AndroidDetectorPhaseTiming(phase, startedNs, finishedNs, thread.id, thread.name,
            if (failure == null) AndroidDetectorPhaseOutcome.SUCCEEDED else AndroidDetectorPhaseOutcome.FAILED,
            modelKey, delegate, failure?.javaClass?.simpleName)
        synchronized(this) { phases.add(timing) }
    }

    @Synchronized fun finishLoad(detectorAvailable: Boolean): AndroidDetectorLoadTiming {
        if (loadFinishedNs == null) {
            loadFinishedNs = nowNs()
            available = detectorAvailable
        }
        return snapshot()
    }

    @Synchronized fun snapshot(): AndroidDetectorLoadTiming = AndroidDetectorLoadTiming(
        generation, requestedNs, requestThreadId, requestThreadName, loadFinishedNs, available, phases.toList(),
    )

    private companion object { val nextGeneration = AtomicLong() }
}

fun AndroidDetectorTiming.totalModelMs(): Long? {
    val genericValues = listOfNotNull(
        modelPreprocessMs,
        modelInferenceMs,
        modelParseMs,
    )
    if (genericValues.isNotEmpty()) return genericValues.sum()

    val values = listOfNotNull(
        cocoPreprocessMs,
        cocoInferenceMs,
        cocoParseMs,
        customPreprocessMs,
        customInferenceMs,
        customParseMs,
    )
    if (values.isEmpty()) return null
    return values.sum()
}
