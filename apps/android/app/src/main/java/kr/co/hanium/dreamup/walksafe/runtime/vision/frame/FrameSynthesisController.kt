package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.depth.ObjectTracker
import kr.co.hanium.dreamup.walksafe.inference.tracking.GrayTrackingFrame
import kr.co.hanium.dreamup.walksafe.inference.tracking.InterFrameDetectionTracker
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingBackend
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingConfig
import kr.co.hanium.dreamup.walksafe.inference.tracking.AdaptiveVisualTrackingBudget
import com.google.ar.core.Session
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.ObjectDepthRuntimePipeline
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult
import kr.co.hanium.dreamup.walksafe.depth.CurrentTrackedFrameObservation

internal class FrameSynthesisController(
    private val frameStateLock: Any,
    private val authority: FrameSynthesisAuthority,
    private val elapsedRealtimeMs: () -> Long,
    private val visualFrameTracker: InterFrameDetectionTracker = InterFrameDetectionTracker(
        VisualTrackingConfig(backend = VisualTrackingBackend.OPENCV_PYRAMIDAL_LK,
            maxFeaturesPerObject = 40, allowSimilarityTransform = true)),
) {
    private var objectDepthPipeline = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true)
    private var visualGeometryVersion = 0L
    private val visualTrackingBudget = AdaptiveVisualTrackingBudget()
    private var currentVisualTrackingBudgetNs = 5_000_000L
    private var lastVisualFrameKey: VisualFrameKey? = null
    private var lastRuntimeCameraFrameId = 0L
    private var currentObjectDepthPipeline = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true,
        tracker = ObjectTracker(trackIdPrefix = "visual-0-track-"))
    private var latestVisualTrackingStatus = "visual=waiting"
    @Volatile private var latestDetectionSnapshot = DetectionSnapshot.empty()
    val geometryVersion get() = visualGeometryVersion
    val trackingBudgetNs get() = currentVisualTrackingBudgetNs
    val trackingStatus get() = latestVisualTrackingStatus
    val detectionSnapshot get() = latestDetectionSnapshot

    fun setUserStepLength(value: Float) {
        objectDepthPipeline.setUserStepLength(value)
        currentObjectDepthPipeline.setUserStepLength(value)
    }
    fun resetFrozenDepthPipeline(stepLength: () -> Float) {
        objectDepthPipeline = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true).also {
            it.setUserStepLength(stepLength())
        }
    }
    fun initializeTracker() { if (!visualFrameTracker.initialize()) latestVisualTrackingStatus = "visual=native_initialization_failed" }
    fun clearTrackerHistory() = visualFrameTracker.clear()
    fun resetTrackingBudget() = visualTrackingBudget.reset()
    fun restoreDefaultTrackingAllowance() { currentVisualTrackingBudgetNs = 5_000_000L }
    fun advanceGeometry() { visualGeometryVersion += 1L }
    fun resetCurrentCaptureHistory(afterFrameIdentityReset: () -> Unit = {}, stepLength: () -> Float) {
        lastVisualFrameKey = null
        lastRuntimeCameraFrameId = 0L
        afterFrameIdentityReset()
        latestVisualTrackingStatus = "visual=waiting_for_new_capture"
        currentObjectDepthPipeline = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true,
            tracker = ObjectTracker(trackIdPrefix = "visual-$visualGeometryVersion-track-"))
            .also { it.setUserStepLength(stepLength()) }
    }
    fun clearDetectionSnapshot() { latestDetectionSnapshot = DetectionSnapshot.empty() }

    // These methods consume the original source reservations; no copied tracker or lease owner exists.
    fun reservePendingSourceFrame(key: VisualFrameKey) = visualFrameTracker.reservePendingSourceFrame(key)
    fun releasePendingSourceFrame(lease: InterFrameDetectionTracker.PendingSourceLease) = visualFrameTracker.releasePendingSourceFrame(lease)
    fun publishSourceSnapshot(snapshot: DetectionSnapshot, sourceKey: VisualFrameKey?,
        sourceLease: InterFrameDetectionTracker.PendingSourceLease?): Boolean {
        if (!snapshot.partial && sourceKey != null && (sourceLease == null || sourceLease.sourceKey != sourceKey ||
                !visualFrameTracker.publishPendingSourceFrame(sourceLease))) return false
        latestDetectionSnapshot = snapshot
        return true
    }
    fun admitRuntimeFrame(generation: Int, epoch: WalkRuntimeEpoch, frameId: () -> Long): Boolean = synchronized(frameStateLock) {
        if (!authority.ownsFrame(generation, epoch)) return@synchronized false
        if (frameId() <= lastRuntimeCameraFrameId) return@synchronized false
        lastRuntimeCameraFrameId = frameId()
        true
    }
    fun trackSource(snapshot: DetectionSnapshot, grayFrame: GrayTrackingFrame?, generation: Int,
        epoch: WalkRuntimeEpoch, priorityIndices: List<Int>): FrameSourceTracking = synchronized(frameStateLock) {
        val sourceKey = snapshot.frameEvidence?.visualFrameKey
        if (!authority.ownsFrame(generation, epoch)) return@synchronized FrameSourceTracking.Retired
        val result = if (latestDetectionSnapshot !== snapshot || grayFrame == null || sourceKey == null || snapshot.partial) null
        else visualFrameTracker.trackFrom(sourceKey, snapshot.detections, grayFrame.key, completed = true,
            observedAtElapsedRealtimeMs = elapsedRealtimeMs(), executionBudgetNs = currentVisualTrackingBudgetNs,
            uprightQuarterTurns = snapshot.frameEvidence?.imageQuarterTurns ?: 0,
            prioritySourceIndices = priorityIndices)
        FrameSourceTracking.Evaluated(result)
    }
    fun recordTrackingStatus(result: VisualTrackingResult?, overlayCount: Int) {
        latestVisualTrackingStatus = result?.let {
            "visual=$overlayCount/${it.observations.size} " +
                "cost=${it.metrics.durationNs / 1_000_000L}ms " +
                "budget=${it.metrics.executionBudgetNs / 1_000_000L}ms overrun=${it.metrics.executionBudgetOverrun} " +
                "lost=${it.observations.mapNotNull { observation -> observation.failure }.distinct().joinToString()} " +
                "redetect=${it.redetectReasons.joinToString()}"
        } ?: "visual=source_or_current_image_unavailable"
    }
    fun processFrozenDepth(snapshot: DetectionSnapshot, evidence: DetectionFrameEvidence,
        depthDetections: List<DetectionCandidate>, generation: Int, epoch: WalkRuntimeEpoch): List<TrackedObjectDepth> =
        synchronized(frameStateLock) {
            if (!authority.ownsFrame(generation, epoch)) emptyList() else {
                val sourceCapturedAtElapsedMs = snapshot.capturedAtElapsedRealtimeMs?.takeIf { capturedAt ->
                    snapshot.frameEvidence?.frameId == evidence.frameId && evidence.visualFrameKey?.let { key ->
                        key.frameId == evidence.frameId && key.capturedAtElapsedRealtimeMs == capturedAt
                    } != false
                }
                objectDepthPipeline.process(snapshot = evidence.depthSnapshot, frameId = evidence.frameId,
                    timestampMs = evidence.frameTimestampMs, detections = depthDetections,
                    detectionSequenceId = evidence.frameId, detectionCompleted = true,
                    mapper = requireNotNull(evidence.depthMapper), mapperFrameId = evidence.depthMapper.frameId,
                    motionContext = evidence.motionContext, imageQuarterTurns = evidence.imageQuarterTurns,
                    capturedAtElapsedRealtimeMs = sourceCapturedAtElapsedMs).map { output ->
                    bindLegacyFeedbackToCapture(output, evidence.frameId, evidence.frameTimestampMs,
                        sourceCapturedAtElapsedMs, elapsedRealtimeMs())
                }
            }
        }
    fun processCurrentDepth(observation: CurrentTrackedFrameObservation, evidence: DetectionFrameEvidence,
        generation: Int, epoch: WalkRuntimeEpoch, visualSourceAllowsWork: () -> Boolean): List<TrackedObjectDepth> =
        synchronized(frameStateLock) {
            if (!authority.ownsFrame(generation, epoch) || !visualSourceAllowsWork()) emptyList()
            else currentObjectDepthPipeline.processTrackedObservation(snapshot = evidence.depthSnapshot,
                observation = observation, mapper = requireNotNull(evidence.depthMapper),
                mapperFrameId = evidence.depthMapper.frameId, nowElapsedRealtimeMs = elapsedRealtimeMs(),
                motionContext = evidence.motionContext, imageQuarterTurns = evidence.imageQuarterTurns)
        }

    fun hasFreshVisualSource(nowElapsedRealtimeMs: Long): Boolean {
        val source = latestDetectionSnapshot
        val capturedAt = source.capturedAtElapsedRealtimeMs ?: return false
        return !source.partial && source.detections.isNotEmpty() &&
            source.frameEvidence?.visualFrameKey != null &&
            nowElapsedRealtimeMs - capturedAt in 0L..MAX_DEPTH_DETECTION_SOURCE_AGE_MS
    }

    fun captureVisualTrackingFrame(
        input: FrameRecognitionInput?,
        snapshot: DepthFrameSnapshot,
        frameGeneration: Int,
        walkEpoch: WalkRuntimeEpoch,
    ): GrayTrackingFrame? {
        val gray = input?.gray ?: return null
        if (!FrameRecognitionAdmission.matchesDepth(input.cameraTimestampNs, gray.key.frameId, snapshot)) return null
        val geometryVersion = input.geometryVersion
        return synchronized(frameStateLock) {
            if (!authority.ownsFrame(frameGeneration, walkEpoch) || visualGeometryVersion != geometryVersion) return null
            // A repeated Session.update is not another camera observation or a new capture time.
            val previous = lastVisualFrameKey
            if (previous != null && previous.epoch == gray.key.epoch && previous.frameId >= gray.key.frameId) return null
            val admission = visualFrameTracker.offerFrame(gray)
            if (!admission.accepted) return null
            if (admission.reset) {
                authority.retireUnstartedFeedback("visual-$geometryVersion-")
                currentObjectDepthPipeline = ObjectDepthRuntimePipeline(
                    rawDepthMotionOnly = true,
                    tracker = ObjectTracker(trackIdPrefix = "visual-$geometryVersion-${gray.key.frameId}-track-"),
                ).also { it.setUserStepLength(authority.userStepLengthM()) }
            }
            lastVisualFrameKey = gray.key
            currentVisualTrackingBudgetNs = visualTrackingBudget.budgetForFrame(gray.key)
            gray
        }
    }

    fun selectOverlayDetections(
        detectionSnapshot: DetectionSnapshot,
        nowMs: Long,
        currentFrameTimestampMs: Long,
    ): FrameOverlaySelection {
        val staleReason = detectionSnapshot.staleReason(
            nowMs, currentFrameTimestampMs,
            MAX_OVERLAY_DETECTION_SOURCE_AGE_MS, MAX_OVERLAY_DETECTION_FRAME_DELTA_MS,
        )
        // Keep one capture identity. An empty/new result must not resurrect boxes from an older frame.
        val detections = if (staleReason == null) detectionSnapshot.detections else emptyList()
        return FrameOverlaySelection(
            detections = detections,
            mappingSnapshot = detectionSnapshot,
            debugState = buildFrameOverlayDiagnostics(
                selection = if (detections.isEmpty()) "empty" else "fresh_latest",
                detections = detections,
                mappingSnapshot = detectionSnapshot,
                nowMs = nowMs,
                currentFrameTimestampMs = currentFrameTimestampMs,
                staleReason = staleReason,
                holdApplied = false,
            ),
        )
    }

    private fun buildFrameOverlayDiagnostics(
        selection: String,
        detections: List<DetectionCandidate>,
        mappingSnapshot: DetectionSnapshot,
        nowMs: Long,
        currentFrameTimestampMs: Long,
        staleReason: String?,
        holdApplied: Boolean,
    ): FrameOverlayDiagnostics {
        return FrameOverlayDiagnostics(
            selection = selection,
            detectionCount = detections.size,
            tactileDetectionCount = detections.count { it.isTactileDetection() },
            sourceAgeMs = mappingSnapshot.sourceAgeMs(nowMs),
            frameDeltaMs = mappingSnapshot.frameDeltaMs(currentFrameTimestampMs),
            staleReason = staleReason,
            holdApplied = holdApplied && detections.isNotEmpty(),
            snapshotPartial = mappingSnapshot.partial,
        )
    }

    /** Bind the frozen capture without manufacturing a missing metric source clock. */
    fun bindLegacyFeedbackToCapture(
        output: TrackedObjectDepth,
        sourceFrameId: Long,
        sourceTimestampMs: Long,
        sourceCapturedAtElapsedRealtimeMs: Long?,
        nowElapsedRealtimeMs: Long,
    ): TrackedObjectDepth {
        val capturedAt = sourceCapturedAtElapsedRealtimeMs
        val depthObservedAt = output.depthObservedAtMs
        val depthAgeMs = if (depthObservedAt == null) {
            if (output.source.metric) -1L else 0L
        } else if (depthObservedAt >= 0L && sourceTimestampMs >= depthObservedAt) {
            sourceTimestampMs - depthObservedAt
        } else -1L
        val captureValid = capturedAt != null && capturedAt in 0L..nowElapsedRealtimeMs &&
            sourceFrameId > 0L && output.frameId == sourceFrameId &&
            depthAgeMs in 0L..MAX_DEPTH_DETECTION_SOURCE_AGE_MS
        val sourceAgeBudget = if (output.source.metric) {
            output.sourceMaxAgeMs?.takeIf { it > 0L }
                ?.coerceAtMost(MAX_DEPTH_DETECTION_SOURCE_AGE_MS)
        } else {
            minOf(output.sourceMaxAgeMs ?: MAX_DEPTH_DETECTION_SOURCE_AGE_MS,
                MAX_DEPTH_DETECTION_SOURCE_AGE_MS).takeIf { it > 0L }
        }
        val observedAt = if (!captureValid) null else if (output.source.metric) {
            output.sourceObservedAtElapsedRealtimeMs?.takeIf { it in 0L..requireNotNull(capturedAt) }
        } else {
            val captureObservation = requireNotNull(capturedAt) - depthAgeMs
            minOf(output.sourceObservedAtElapsedRealtimeMs ?: captureObservation, captureObservation)
                .takeIf { it >= 0L }
        }
        val captureBudget = if (output.source.metric) MAX_DEPTH_DETECTION_SOURCE_AGE_MS
            else MAX_DEPTH_DETECTION_SOURCE_AGE_MS - depthAgeMs
        val validSource = captureValid && observedAt != null && sourceAgeBudget != null &&
            observedAt < Long.MAX_VALUE - sourceAgeBudget &&
            requireNotNull(capturedAt) < Long.MAX_VALUE - captureBudget
        val deadline = if (validSource) minOf(
            requireNotNull(observedAt) + requireNotNull(sourceAgeBudget),
            requireNotNull(capturedAt) + captureBudget,
        ) else Long.MIN_VALUE
        return output.copy(
            sourceValidUntilElapsedRealtimeMs = minOf(
                output.sourceValidUntilElapsedRealtimeMs ?: Long.MAX_VALUE, deadline,
            ),
            sourceObservedAtElapsedRealtimeMs = observedAt.takeIf { validSource },
            sourceMaxAgeMs = sourceAgeBudget.takeIf { validSource },
        )
    }
    private fun DetectionCandidate.isTactileDetection(): Boolean =
        className.lowercase(Locale.US).contains("tactile") || className.contains("점자")
    private companion object {
        const val MAX_DEPTH_DETECTION_SOURCE_AGE_MS = 800L
        const val MAX_OVERLAY_DETECTION_SOURCE_AGE_MS = 1_200L
        const val MAX_OVERLAY_DETECTION_FRAME_DELTA_MS = 1_200L
    }
}

