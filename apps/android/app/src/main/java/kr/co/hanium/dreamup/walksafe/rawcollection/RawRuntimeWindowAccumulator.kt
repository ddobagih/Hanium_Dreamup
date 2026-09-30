package kr.co.hanium.dreamup.walksafe.rawcollection

import kr.co.hanium.dreamup.walksafe.navigation.positioning.MotionStateAggregate
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateSnapshot

/**
 * Upper bounds (exclusive, ms) of the fixed latency buckets; the last bucket is `>= 800 ms`.
 * These are measurement buckets only, not an approved latency budget or pass criterion
 * (FP-050-DETAIL-01): a budget can be applied later to the stored histogram and maximum.
 */
val RAW_RUNTIME_LATENCY_BUCKET_UPPER_BOUNDS_MS: List<Long> = listOf(33L, 66L, 100L, 200L, 400L, 800L)
val RAW_RUNTIME_LATENCY_BUCKET_COUNT: Int = RAW_RUNTIME_LATENCY_BUCKET_UPPER_BOUNDS_MS.size + 1

internal fun rawRuntimeLatencyBucket(valueMs: Long): Int {
    val index = RAW_RUNTIME_LATENCY_BUCKET_UPPER_BOUNDS_MS.indexOfFirst { valueMs < it }
    return if (index < 0) RAW_RUNTIME_LATENCY_BUCKET_COUNT - 1 else index
}

/**
 * One capture window of runtime counters for the raw PERFORMANCE/DETECTION pair.
 *
 * Counter definitions (16-17 #4):
 * - completed inference: a detector result recorded through [recordCompletedInference]; the
 *   DETECTION and PERFORMANCE samples both count this same stage.
 * - busy-dropped frame: a frame that was due while one inference was still running.
 * - frame interval: elapsed time between two consecutive completed inferences of the same walk,
 *   so a long stall shows up even when the mean inference time is unchanged (16-17 #2).
 *
 * Not thread-safe: MainActivity only touches it from the single raw collection executor.
 */
internal class RawRuntimeWindowAccumulator {
    var walkSessionId: String? = null
        private set
    var windowStartedAtElapsedMs: Long = 0L
        private set
    var modelRevision: String? = null
        private set
    var runtimeIdentity: RawDetectorRuntimeIdentity? = null
        private set
    /** Windows closed early because the detector identity changed mid-window (16-17 #6). */
    var identitySplitCount: Int = 0
        private set
    private var windowOpen = false
    private var completed = 0
    private var busyDropped = 0
    private var detections = 0
    private var inferenceTotalMs = 0L
    private var inferenceMaxMs = 0L
    private val inferenceBuckets = IntArray(RAW_RUNTIME_LATENCY_BUCKET_COUNT)
    private var lastCompletedAtElapsedMs: Long? = null
    private var intervalCount = 0
    private var intervalTotalMs = 0L
    private var intervalMaxMs = 0L
    private val intervalBuckets = IntArray(RAW_RUNTIME_LATENCY_BUCKET_COUNT)
    private val motion = MotionStateAggregate()

    val completedInferenceCount: Int
        get() = completed

    fun recordCompletedInference(
        walkSessionId: String,
        observedAtElapsedMs: Long,
        detectionCount: Int,
        inferenceMs: Long,
        busyDroppedSincePrevious: Int,
        modelRevision: String?,
        runtimeIdentity: RawDetectorRuntimeIdentity? = null,
    ) {
        if (this.walkSessionId != walkSessionId) {
            resetAll()
            this.walkSessionId = walkSessionId
        }
        if (windowOpen && completed > 0 && this.runtimeIdentity != runtimeIdentity) {
            // Never mix two detector executions in one window: the shorter-than-interval part
            // recorded under the previous identity is closed without being stored.
            identitySplitCount = saturatingAdd(identitySplitCount, 1)
            resetWindow()
        }
        if (!windowOpen) {
            windowOpen = true
            windowStartedAtElapsedMs = observedAtElapsedMs
        }
        val boundedInferenceMs = inferenceMs.coerceAtLeast(0L)
        completed = saturatingAdd(completed, 1)
        busyDropped = saturatingAdd(busyDropped, busyDroppedSincePrevious.coerceAtLeast(0))
        detections = saturatingAdd(detections, detectionCount.coerceAtLeast(0))
        inferenceTotalMs = saturatingAdd(inferenceTotalMs, boundedInferenceMs)
        inferenceMaxMs = maxOf(inferenceMaxMs, boundedInferenceMs)
        inferenceBuckets[rawRuntimeLatencyBucket(boundedInferenceMs)] += 1
        lastCompletedAtElapsedMs?.let { previous ->
            if (observedAtElapsedMs >= previous) {
                val intervalMs = observedAtElapsedMs - previous
                intervalCount = saturatingAdd(intervalCount, 1)
                intervalTotalMs = saturatingAdd(intervalTotalMs, intervalMs)
                intervalMaxMs = maxOf(intervalMaxMs, intervalMs)
                intervalBuckets[rawRuntimeLatencyBucket(intervalMs)] += 1
            }
        }
        lastCompletedAtElapsedMs = observedAtElapsedMs
        this.modelRevision = modelRevision
        this.runtimeIdentity = runtimeIdentity
    }

    /**
     * ADD-MOTION-045: only while RAW consent and the ACTIVE walk allow collection; a disallowed
     * call drops the motion aggregate so nothing is kept across a pause, end, or withdrawal.
     */
    fun recordMotion(snapshot: UserMotionStateSnapshot?, collectionAllowed: Boolean) {
        motion.observe(snapshot, collectionAllowed)
    }

    fun windowElapsedMs(nowElapsedMs: Long): Long =
        if (windowOpen) nowElapsedMs - windowStartedAtElapsedMs else 0L

    fun detectionSample(
        windowStartedAtEpochMs: Long,
        capturedAtEpochMs: Long,
        detectorAvailable: Boolean,
    ): RawDetectionMetadataSample = RawDetectionMetadataSample(
        windowStartedAtEpochMs = windowStartedAtEpochMs,
        capturedAtEpochMs = capturedAtEpochMs,
        processedFrameCount = completed,
        detectionCount = detections,
        averageInferenceMs = if (completed == 0) 0L else inferenceTotalMs / completed,
        detectorAvailable = detectorAvailable,
        modelRevision = modelRevision,
        maxInferenceMs = inferenceMaxMs,
        inferenceMsHistogram = inferenceBuckets.toList(),
        runtimeIdentity = runtimeIdentity,
    )

    fun performanceSample(
        windowStartedAtEpochMs: Long,
        capturedAtEpochMs: Long,
        thermalThrottled: Boolean?,
    ): RawPerformanceMetadataSample = RawPerformanceMetadataSample(
        windowStartedAtEpochMs = windowStartedAtEpochMs,
        capturedAtEpochMs = capturedAtEpochMs,
        processedFrameCount = completed,
        droppedFrameCount = busyDropped,
        averageFrameDurationMs = if (intervalCount == 0) 0L else intervalTotalMs / intervalCount,
        thermalThrottled = thermalThrottled,
        maxFrameDurationMs = intervalMaxMs,
        frameDurationMsHistogram = intervalBuckets.toList(),
        frameDurationSampleCount = intervalCount,
        motion = motion.summary(),
    )

    /** Starts the next window of the same walk; the last completion still bounds the next interval. */
    fun resetWindow() {
        windowOpen = false
        windowStartedAtElapsedMs = 0L
        completed = 0
        busyDropped = 0
        detections = 0
        inferenceTotalMs = 0L
        inferenceMaxMs = 0L
        inferenceBuckets.fill(0)
        intervalCount = 0
        intervalTotalMs = 0L
        intervalMaxMs = 0L
        intervalBuckets.fill(0)
        modelRevision = null
        runtimeIdentity = null
        motion.resetWindow()
    }

    /** S1617-6-F1: hands the windows split at an identity change to the outcome counters once. */
    fun takeIdentitySplitCount(): Int = identitySplitCount.also { identitySplitCount = 0 }

    /** Forgets the walk too, so a pause/resume gap is never measured as a frame interval. */
    fun resetAll() {
        resetWindow()
        walkSessionId = null
        lastCompletedAtElapsedMs = null
        motion.reset()
    }

    private fun saturatingAdd(left: Int, right: Int): Int =
        if (Int.MAX_VALUE - left < right) Int.MAX_VALUE else left + right

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
