package kr.co.hanium.dreamup.walksafe.inference

import android.media.Image
import android.os.SystemClock
import java.io.Closeable

internal data class OwnedTensorPreparation(
    val transform: LetterboxTransform,
    val preprocessingMs: Long,
    val tensorCopyMs: Long,
    val preparationMs: Long,
    val preparedAtElapsedRealtimeMs: Long,
    val strategy: YuvPreprocessingStrategy,
)

internal data class OverlapPreparationResult(
    val status: OverlapFrameStatus,
    val key: OverlapFrameKey,
    val capturedAtElapsedRealtimeMs: Long,
    val preprocessingMs: Long? = null,
    val tensorCopyMs: Long? = null,
    val preparationMs: Long? = null,
    val replacedReadyKey: OverlapFrameKey? = null,
    val failureReason: String? = null,
)

internal data class OverlapInferenceResult(
    val status: OverlapFrameStatus,
    val key: OverlapFrameKey? = null,
    val capturedAtElapsedRealtimeMs: Long? = null,
    val completedAtElapsedRealtimeMs: Long,
    val sourceAgeMs: Long? = null,
    val readyWaitMs: Long? = null,
    val invocationWallMs: Long? = null,
    val outputCopyMs: Long? = null,
    val observedTiming: AndroidDetectorTiming? = null,
    // Rejected/stale/closed results have no output that a caller could publish as fresh evidence.
    val result: AndroidDetectionResult? = null,
    val rawOutput: FloatArray? = null,
    val failureReason: String? = null,
)

/**
 * Unified-model benchmark path: one CPU producer and one inference consumer can overlap.
 * The caller owns each Image and MUST close it after tryPrepare returns, including rejection.
 * No Image, ARCore Frame, or borrowed preprocessing buffer is retained by a ready slot.
 */
internal class UnifiedFrameOverlapExperiment internal constructor(
    private val detector: TfliteAndroidFrameDetector,
    private val inputSize: Int,
    sourceSessionId: Long,
    private val preprocessingStrategy: YuvPreprocessingStrategy,
    maximumSourceAgeMs: Long = 800L,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) : Closeable {
    private val preprocessor = YuvImagePreprocessor()
    private val slots = OwnedTensorFrameSlots<OwnedTensorPreparation>(
        sessionId = sourceSessionId,
        bytesPerTensor = Math.multiplyExact(Math.multiplyExact(inputSize, inputSize), 3 * Float.SIZE_BYTES),
        maximumSourceAgeMs = maximumSourceAgeMs,
    )
    @Volatile
    private var closed = false

    val tensorBufferCount: Int get() = slots.tensorBufferCount
    val ownedTensorBytes: Long get() = slots.ownedTensorBytes
    // The current preprocessor retains one more borrowed tensor; the owned copy is measured separately.
    val preprocessingTensorBytes: Long get() = slots.bytesPerTensor.toLong()

    fun tryPrepare(
        image: Image,
        key: OverlapFrameKey,
        capturedAtElapsedRealtimeMs: Long,
    ): OverlapPreparationResult {
        if (closed) return OverlapPreparationResult(OverlapFrameStatus.CLOSED, key, capturedAtElapsedRealtimeMs)
        val sourceTimestampNs = try {
            image.timestamp
        } catch (error: RuntimeException) {
            return OverlapPreparationResult(
                OverlapFrameStatus.FAILED, key, capturedAtElapsedRealtimeMs,
                failureReason = error.javaClass.simpleName,
            )
        }
        val admission = slots.beginPreparation(
            key, capturedAtElapsedRealtimeMs, elapsedRealtimeMs(),
            actualCameraImageTimestampNs = sourceTimestampNs,
        )
        val lease = admission.value ?: return OverlapPreparationResult(admission.status, key, capturedAtElapsedRealtimeMs)
        val startedNs = System.nanoTime()
        var completedPreparation = false
        try {
            val preprocessStartedNs = System.nanoTime()
            val borrowed = preprocessor.preprocess(image, inputSize, preprocessingStrategy)
            val preprocessingMs = elapsedMs(preprocessStartedNs)
            val copyStartedNs = System.nanoTime()
            val source = borrowed.inputBuffer.duplicate().apply { rewind() }
            check(source.remaining() == slots.bytesPerTensor) { "prepared tensor byte count does not match model input" }
            lease.inputBuffer.put(source)
            val tensorCopyMs = elapsedMs(copyStartedNs)
            val preparedAtMs = elapsedRealtimeMs()
            val preparationMs = elapsedMs(startedNs)
            val status = slots.completePreparation(
                lease,
                OwnedTensorPreparation(
                    borrowed.transform, preprocessingMs, tensorCopyMs, preparationMs,
                    preparedAtMs, preprocessingStrategy,
                ),
                preparedAtMs,
            )
            completedPreparation = true
            return OverlapPreparationResult(
                status, key, capturedAtElapsedRealtimeMs,
                preprocessingMs, tensorCopyMs, preparationMs, admission.replacedReadyKey,
            )
        } catch (error: Throwable) {
            if (error !is Exception && error !is LinkageError) throw error
            return OverlapPreparationResult(
                if (closed) OverlapFrameStatus.CLOSED else OverlapFrameStatus.FAILED,
                key, capturedAtElapsedRealtimeMs,
                preparationMs = elapsedMs(startedNs),
                replacedReadyKey = admission.replacedReadyKey,
                failureReason = error.javaClass.simpleName,
            )
        } finally {
            if (!completedPreparation) slots.cancelPreparation(lease)
        }
    }

    /** Synchronous consumer call. Invoke on a worker; never queue one call per camera frame. */
    fun inferLatest(
        copyRawOutput: Boolean = false,
        onClaimed: (OverlapFrameKey) -> Unit = {},
    ): OverlapInferenceResult {
        val admission = slots.claimLatest(elapsedRealtimeMs)
        val claimedAtMs = elapsedRealtimeMs()
        val lease = admission.value ?: return OverlapInferenceResult(
            admission.status, key = admission.discardedKey, completedAtElapsedRealtimeMs = claimedAtMs,
        )
        val invocationStartedNs = System.nanoTime()
        var inferenceCompleted = false
        try {
            onClaimed(lease.key)
            val output = detector.inferPreparedUnifiedForTest(
                PreprocessedImage(lease.inputBuffer, lease.metadata.transform),
                lease.metadata,
            )
            val invocationWallMs = elapsedMs(invocationStartedNs)
            val copyStartedNs = System.nanoTime()
            val raw = if (copyRawOutput) detector.copyLastUnifiedOutputForTest() else null
            val outputCopyMs = if (copyRawOutput) elapsedMs(copyStartedNs) else null
            val completedAtMs = elapsedRealtimeMs()
            val status = slots.completeInference(lease, completedAtMs)
            inferenceCompleted = true
            return OverlapInferenceResult(
                status = status,
                key = lease.key,
                capturedAtElapsedRealtimeMs = lease.capturedAtElapsedRealtimeMs,
                completedAtElapsedRealtimeMs = completedAtMs,
                sourceAgeMs = completedAtMs - lease.capturedAtElapsedRealtimeMs,
                readyWaitMs = claimedAtMs - lease.metadata.preparedAtElapsedRealtimeMs,
                invocationWallMs = invocationWallMs,
                outputCopyMs = outputCopyMs,
                observedTiming = output.timing,
                result = output.takeIf { status == OverlapFrameStatus.ACCEPTED },
                rawOutput = raw.takeIf { status == OverlapFrameStatus.ACCEPTED },
            )
        } catch (error: Throwable) {
            if (error !is Exception && error !is LinkageError) throw error
            val completedAtMs = elapsedRealtimeMs()
            val status = slots.completeInference(lease, completedAtMs, succeeded = false)
            inferenceCompleted = true
            return OverlapInferenceResult(
                status, lease.key, lease.capturedAtElapsedRealtimeMs, completedAtMs,
                sourceAgeMs = completedAtMs - lease.capturedAtElapsedRealtimeMs,
                invocationWallMs = elapsedMs(invocationStartedNs),
                failureReason = error.javaClass.simpleName,
            )
        } finally {
            if (!inferenceCompleted) slots.completeInference(lease, elapsedRealtimeMs(), succeeded = false)
        }
    }

    override fun close() {
        closed = true
        slots.close()
        detector.close()
    }

    private fun elapsedMs(startedNs: Long): Long = (System.nanoTime() - startedNs) / 1_000_000L
}
