package kr.co.hanium.dreamup.walksafe.inference.tracking

import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm

/**
 * Short-lived CPU-image tracking, independent of detector scheduling and depth/risk evidence.
 * Calls serialize around owned image copies. Budget-limited backfill resumes at the last completed
 * object/frame edge; no geometry is published for an object until it reaches the exact target.
 */
class InterFrameDetectionTracker(
    private val config: VisualTrackingConfig = VisualTrackingConfig(),
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val history = ArrayDeque<GrayTrackingFrame>()
    private val estimator: VisualMotionEstimator by lazy {
        when (config.backend) {
            VisualTrackingBackend.OPENCV_PYRAMIDAL_LK -> OpenCvPyramidalMotionEstimator(config.maxFeaturesPerObject, config.allowSimilarityTransform)
            VisualTrackingBackend.PATCH_DIAGNOSTIC -> PatchMotionEstimator(minOf(config.maxFeaturesPerObject, 16))
        }
    }
    private var batch: Batch? = null
    private var acceptedEpoch: Long? = null
    private var acceptedGeometryVersion: Long? = null
    private var acceptedDimensions: FrameDimensions? = null
    private var coverageCursor = 0

    @Synchronized
    fun initialize(): Boolean = estimator.initialize()

    @Synchronized
    fun clear() {
        history.clear()
        batch = null
        coverageCursor = 0
    }

    @Synchronized
    fun offerFrame(frame: GrayTrackingFrame): TrackingFrameAdmission {
        val epoch = acceptedEpoch
        if (epoch != null && (frame.key.epoch < epoch || (frame.key.epoch == epoch &&
                frame.key.geometryVersion < requireNotNull(acceptedGeometryVersion)))) {
            return TrackingFrameAdmission(false, false, VisualTrackingFailure.EPOCH_OR_GEOMETRY_CHANGED)
        }
        val duplicate = history.firstOrNull { it.key.epoch == frame.key.epoch && it.key.frameId == frame.key.frameId }
        if (duplicate != null) {
            return if (duplicate.key == frame.key && duplicate.samePixels(frame)) {
                TrackingFrameAdmission(accepted = true, reset = false)
            } else {
                clear()
                TrackingFrameAdmission(false, true, VisualTrackingFailure.FRAME_IDENTITY_CONFLICT)
            }
        }
        var reset = false
        val contextChanged = epoch != null && (epoch != frame.key.epoch || acceptedGeometryVersion != frame.key.geometryVersion)
        // Context identity survives clear(), including a rejected frame that cleared the history.
        val dimensions = acceptedDimensions
        val dimensionsChanged = dimensions != null && (dimensions.width != frame.width || dimensions.height != frame.height ||
            dimensions.sourceWidth != frame.sourceWidth || dimensions.sourceHeight != frame.sourceHeight)
        if (dimensionsChanged && !contextChanged) {
            clear()
            return TrackingFrameAdmission(false, true, VisualTrackingFailure.FRAME_IDENTITY_CONFLICT)
        }
        if (contextChanged) {
            clear()
            reset = true
        }
        val previous = history.lastOrNull()
        if (previous != null && (
                frame.key.frameId <= previous.key.frameId ||
                    frame.key.cameraTimestampNs <= previous.key.cameraTimestampNs ||
                    frame.key.capturedAtElapsedRealtimeMs < previous.key.capturedAtElapsedRealtimeMs
                )) return TrackingFrameAdmission(false, reset, VisualTrackingFailure.FRAME_ORDER_INVALID)
        history.addLast(frame)
        acceptedEpoch = frame.key.epoch
        acceptedGeometryVersion = frame.key.geometryVersion
        acceptedDimensions = FrameDimensions(frame.width, frame.height, frame.sourceWidth, frame.sourceHeight)
        while (history.size > config.maxHistoryFrames ||
            frame.key.capturedAtElapsedRealtimeMs - history.first().key.capturedAtElapsedRealtimeMs > config.maxHistoryAgeMs
        ) history.removeFirst()
        return TrackingFrameAdmission(true, reset)
    }

    @Synchronized
    fun trackFrom(
        sourceKey: VisualFrameKey,
        detections: List<DetectionCandidate>,
        targetKey: VisualFrameKey,
        detectionRevision: Long = 0L,
        completed: Boolean = true,
        /** Live callers pass current elapsed realtime; the default also supports offline replay. */
        observedAtElapsedRealtimeMs: Long = targetKey.capturedAtElapsedRealtimeMs,
        executionBudgetNs: Long = config.maxExecutionNs,
        /** Sensor-to-upright rotation used for this detector capture, not an inferred distance. */
        uprightQuarterTurns: Int = 0,
        /** Fresh source-frame hazard indexes in risk order; fixed when this batch is first admitted. */
        prioritySourceIndices: List<Int> = emptyList(),
    ): VisualTrackingResult {
        require(executionBudgetNs in 1L..100_000_000L)
        require(uprightQuarterTurns in 0..3)
        val started = clockNanos()
        val budget = TrackingWorkBudget(config, clockNanos, started, executionBudgetNs)
        var edges = 0
        if (detections.size > config.maxInputDetections) {
            return result(sourceKey, targetKey, emptyList(), started, edges, budget, false).copy(
                batchFailure = VisualTrackingFailure.INPUT_BUDGET_EXCEEDED,
                rejectedInputCount = detections.size,
            )
        }
        fun failed(failure: VisualTrackingFailure): VisualTrackingResult = result(
            sourceKey, targetKey,
            detections.indices.map { lost(it, sourceKey, targetKey, failure) },
            started, edges, budget, false,
        )
        if (detections.any { it.polygonNorm.size > 128 || it.className.length > 128 }) {
            return failed(VisualTrackingFailure.INVALID_GEOMETRY)
        }
        if (sourceKey.epoch != targetKey.epoch || sourceKey.geometryVersion != targetKey.geometryVersion) {
            return failed(VisualTrackingFailure.EPOCH_OR_GEOMETRY_CHANGED)
        }
        val frames = history.toList()
        if (frames.any { (it.key.frameId == sourceKey.frameId && it.key != sourceKey) ||
                (it.key.frameId == targetKey.frameId && it.key != targetKey) }) {
            return failed(VisualTrackingFailure.FRAME_IDENTITY_CONFLICT)
        }
        val sourceIndex = frames.indexOfFirst { it.key == sourceKey }
        val targetIndex = frames.indexOfFirst { it.key == targetKey }
        if (sourceIndex < 0) return failed(VisualTrackingFailure.SOURCE_NOT_IN_HISTORY)
        if (targetIndex < 0) return failed(VisualTrackingFailure.TARGET_NOT_IN_HISTORY)
        if (targetIndex != frames.lastIndex) return failed(VisualTrackingFailure.TARGET_NOT_CURRENT)
        if (sourceIndex > targetIndex) return failed(VisualTrackingFailure.FRAME_ORDER_INVALID)
        if (observedAtElapsedRealtimeMs < targetKey.capturedAtElapsedRealtimeMs) return failed(VisualTrackingFailure.FRAME_ORDER_INVALID)
        if (targetKey.capturedAtElapsedRealtimeMs - sourceKey.capturedAtElapsedRealtimeMs > config.maxDetectionAgeMs ||
            observedAtElapsedRealtimeMs - sourceKey.capturedAtElapsedRealtimeMs > config.maxDetectionAgeMs ||
            (targetKey.cameraTimestampNs - sourceKey.cameraTimestampNs) / 1_000_000L > config.maxDetectionAgeMs
        ) return failed(VisualTrackingFailure.SOURCE_EXPIRED)
        if (detectionRevision < 0L) return failed(VisualTrackingFailure.DETECTION_REVISION_OLD)
        if (sourceKey != targetKey && !initialize()) return failed(VisualTrackingFailure.NATIVE_INITIALIZATION_FAILED)

        val previous = batch
        if (previous != null && previous.sourceKey != sourceKey &&
            previous.sourceKey.cameraTimestampNs >= sourceKey.cameraTimestampNs) {
            return failed(VisualTrackingFailure.DETECTOR_SOURCE_OUT_OF_ORDER)
        }
        if (previous?.sourceKey == sourceKey) {
            if (previous.uprightQuarterTurns != uprightQuarterTurns) {
                return failed(VisualTrackingFailure.DETECTION_IDENTITY_CONFLICT)
            }
            if (detectionRevision < previous.revision || (previous.completed && !completed)) {
                return failed(VisualTrackingFailure.DETECTION_REVISION_OLD)
            }
            if (detectionRevision == previous.revision && (previous.detections != detections || previous.completed != completed)) {
                return failed(VisualTrackingFailure.DETECTION_IDENTITY_CONFLICT)
            }
        }
        if (previous == null || previous.sourceKey != sourceKey || previous.revision != detectionRevision) {
            val frozen = detections.map { it.copy(polygonNorm = it.polygonNorm.toList()) }
            val selectedIndexes = selectTrackingIndexes(frozen, uprightQuarterTurns, prioritySourceIndices)
            val initialIndexes = selectedIndexes.take(config.maxTrackedObjects).toSet()
            batch = Batch(sourceKey, detectionRevision, completed, uprightQuarterTurns, frozen, selectedIndexes, frozen.mapIndexed { index, detection ->
                State(
                    lastKey = sourceKey,
                    admitted = index in initialIndexes,
                    failure = when {
                        !valid(detection) -> VisualTrackingFailure.INVALID_GEOMETRY
                        else -> null
                    },
                )
            }.toMutableList())
        }
        val current = requireNotNull(batch)
        val source = frames[sourceIndex]
        var budgetFailure: VisualTrackingFailure? = null
        var activeSlots = current.states.count { it.admitted && it.failure == null }
        // Preserve priority in execution order as well as admission when the call budget is tight.
        for (index in current.selectedIndexes) {
            val state = current.states[index]
            if (sourceKey == targetKey || state.failure != null) continue
            if (!state.admitted && activeSlots >= config.maxTrackedObjects) continue
            if (state.admitted && state.lastKey == targetKey) continue
            if (budgetFailure != null) continue
            val detection = current.detections[index]
            if (!state.admitted) {
                state.admitted = true
                activeSlots++
            }
            try {
                budget.charge()
                if (state.features == null) {
                    val features = estimator.seed(source, detection, budget)
                    state.features = features
                    state.originalCount = features.size
                    if (features.size < 6) {
                        state.failure = VisualTrackingFailure.TOO_FEW_FEATURES
                        continue
                    }
                    budget.charge()
                }
                val lastIndex = frames.indexOfFirst { it.key == state.lastKey }
                if (lastIndex < 0) {
                    state.failure = VisualTrackingFailure.SOURCE_NOT_IN_HISTORY
                    continue
                }
                for (nextIndex in lastIndex + 1..targetIndex) {
                    val before = frames[nextIndex - 1]
                    val target = frames[nextIndex]
                    if (target.key.capturedAtElapsedRealtimeMs - before.key.capturedAtElapsedRealtimeMs > config.maxFrameGapMs ||
                        (target.key.cameraTimestampNs - before.key.cameraTimestampNs) / 1_000_000L > config.maxFrameGapMs
                    ) {
                        state.failure = VisualTrackingFailure.FRAME_GAP
                        break
                    }
                    val estimate = estimator.advance(source, before, target, detection, requireNotNull(state.features), state.originalCount, budget)
                    edges++
                    if (estimate.failure != null) {
                        state.failure = estimate.failure
                        break
                    }
                    if (!valid(transform(detection, estimate.transformNorm))) {
                        state.failure = VisualTrackingFailure.OUT_OF_FRAME
                        break
                    }
                    // Commit only an entirely validated edge, so interrupted work cannot leak.
                    state.features = estimate.features
                    state.transform = estimate.transformNorm
                    state.residual = estimate.residual
                    state.quality = minOf(state.quality, estimate.quality)
                    state.lastKey = target.key
                    // Native calls cannot be preempted. Stop before starting another edge if
                    // this completed edge crossed the work budget.
                    budget.charge()
                }
            } catch (exceeded: TrackingBudgetExceeded) {
                budgetFailure = exceeded.failure
            } finally {
                // Terminal failures free a slot immediately; the next candidate uses this same
                // call's remaining time and pixel budget. Pending work retains its own slot.
                if (budgetFailure == null) rejectCompetingObjects(current, targetKey)
                activeSlots = current.states.count { it.admitted && it.failure == null }
            }
        }
        val reachedTarget = current.states.all { !it.admitted || it.failure != null || it.lastKey == targetKey }
        val completedAfterDeadline = budgetFailure == VisualTrackingFailure.TIME_BUDGET_EXCEEDED && reachedTarget
        val incompleteBatch = budgetFailure != null && !completedAfterDeadline
        if (!incompleteBatch) rejectCompetingObjects(current, targetKey)
        val observations = current.states.mapIndexed { index, state ->
            // An exact detector capture already measures its own geometry; flow admission only
            // limits propagation to a later image. Never reuse these untracked boxes on later frames.
            val failure = state.failure ?: when {
                sourceKey == targetKey -> null
                !state.admitted -> VisualTrackingFailure.OBJECT_BUDGET_EXCEEDED
                incompleteBatch || state.lastKey != targetKey -> budgetFailure
                else -> null
            }
            if (failure != null) {
                lost(index, sourceKey, targetKey, failure, state.originalCount, state.features?.size ?: 0)
            } else {
                val original = current.detections[index]
                VisualTrackingObservation(
                    sourceIndex = index,
                    detectorSourceKey = sourceKey,
                    trackedTargetKey = targetKey,
                    status = VisualTrackingStatus.TRACKED,
                    geometry = transform(original, state.transform),
                    trackingQuality = state.quality,
                    failure = null,
                    translationNorm = if (state.transform.translationOnly) Point2(state.transform.tx, state.transform.ty) else null,
                    originalFeatureCount = state.originalCount,
                    survivingFeatureCount = state.features?.size ?: 0,
                    residualPx = state.residual,
                    polygonConstrained = original.polygonNorm.isNotEmpty(),
                    transformNorm = state.transform,
                )
            }
        }
        return result(sourceKey, targetKey, observations, started, edges, budget,
            previous === current && edges == 0 && budget.pixelComparisons == 0L && budgetFailure == null,
            observedAtElapsedRealtimeMs)
    }

    private fun result(
        source: VisualFrameKey, target: VisualFrameKey, observations: List<VisualTrackingObservation>,
        started: Long, edges: Int, budget: TrackingWorkBudget, cacheHit: Boolean,
        observedAtElapsedRealtimeMs: Long? = null,
    ): VisualTrackingResult {
        val durationNs = (clockNanos() - started).coerceAtLeast(0L)
        // Check source lifetime at the same final instant as the duration metric, after native
        // work, competition checks and geometry construction (including completed overruns).
        val expired = observedAtElapsedRealtimeMs != null &&
            observedAtElapsedRealtimeMs - source.capturedAtElapsedRealtimeMs + durationNs / 1_000_000L > config.maxDetectionAgeMs
        val finalObservations = if (expired) observations.map {
            lost(it.sourceIndex, source, target, VisualTrackingFailure.SOURCE_EXPIRED,
                it.originalFeatureCount, it.survivingFeatureCount)
        } else observations
        return VisualTrackingResult(source, target, finalObservations, VisualTrackingMetrics(
            durationNs = durationNs,
            processedEdges = edges,
            pixelComparisons = budget.pixelComparisons,
            cacheHit = cacheHit,
            retainedFrames = history.size,
            retainedImageBytes = history.sumOf { it.retainedBytes },
            executionBudgetOverrun = durationNs > budget.executionBudgetNs,
            executionBudgetNs = budget.executionBudgetNs,
        ))
    }

    private fun lost(
        index: Int, source: VisualFrameKey, target: VisualFrameKey, failure: VisualTrackingFailure,
        originalCount: Int = 0, survivingCount: Int = 0,
    ) = VisualTrackingObservation(index, source, target, VisualTrackingStatus.LOST, null, 0f, failure, null,
        originalCount, survivingCount, null, false)

    private fun valid(detection: DetectionCandidate): Boolean {
        val rect = detection.bboxNorm
        val maskArea = detection.maskAreaNorm
        return detection.detectionConfidence.isFinite() && detection.detectionConfidence in 0f..1f &&
            rect.x.isFinite() && rect.y.isFinite() && rect.width.isFinite() && rect.height.isFinite() &&
            rect.x >= 0f && rect.y >= 0f && rect.width > 0f && rect.height > 0f &&
            rect.x + rect.width <= 1f && rect.y + rect.height <= 1f &&
            (detection.polygonNorm.isEmpty() || detection.polygonNorm.size in 3..128) &&
            detection.polygonNorm.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f } &&
            (maskArea == null || (maskArea.isFinite() && maskArea in 0f..1f))
    }

    private fun transform(detection: DetectionCandidate, transform: VisualAffineTransform): DetectionCandidate {
        val rect = detection.bboxNorm
        val transformedRect = if (transform.translationOnly) {
            rect.copy(x = rect.x + transform.tx, y = rect.y + transform.ty)
        } else {
            val corners = listOf(Point2(rect.x, rect.y), Point2(rect.x + rect.width, rect.y),
                Point2(rect.x + rect.width, rect.y + rect.height), Point2(rect.x, rect.y + rect.height)).map(transform::map)
            val left = corners.minOf { it.x }
            val top = corners.minOf { it.y }
            RectNorm(left, top, corners.maxOf { it.x } - left, corners.maxOf { it.y } - top)
        }
        return detection.copy(
            bboxNorm = transformedRect,
            polygonNorm = detection.polygonNorm.map(transform::map),
            maskAreaNorm = detection.maskAreaNorm?.let { it * transform.areaScale },
        )
    }

    private fun rejectCompetingObjects(batch: Batch, target: VisualFrameKey) {
        val indexes = batch.selectedIndexes.filter { batch.states[it].admitted && batch.states[it].failure == null }
        val count = indexes.size
        for (i in 0 until count) for (j in i + 1 until count) {
            val first = batch.states[indexes[i]]
            val second = batch.states[indexes[j]]
            if (first.failure != null || second.failure != null || first.lastKey != target || second.lastKey != target) continue
            val originalFirst = batch.detections[indexes[i]]
            val originalSecond = batch.detections[indexes[j]]
            if (iou(originalFirst.bboxNorm, originalSecond.bboxNorm) >= 0.30f) continue
            if (iou(transform(originalFirst, first.transform).bboxNorm,
                    transform(originalSecond, second.transform).bboxNorm) > 0.65f) {
                first.failure = VisualTrackingFailure.OBJECT_COMPETITION
                second.failure = VisualTrackingFailure.OBJECT_COMPETITION
            }
        }
    }

    private fun selectTrackingIndexes(
        detections: List<DetectionCandidate>, turns: Int, prioritySourceIndices: List<Int>,
    ): List<Int> {
        val validIndexes = detections.indices.filter { valid(detections[it]) }
        // Screen geometry is a priority heuristic, not metric depth or a calibrated risk score.
        val uprightRects = validIndexes.associateWith {
            UprightCameraImage.toSensor(detections[it].bboxNorm, (4 - turns) % 4)
        }
        val geometryRanked = validIndexes.sortedWith(compareByDescending<Int> {
            val rect = uprightRects.getValue(it)
            rect.x < 0.65f && rect.x + rect.width > 0.35f && rect.y + rect.height >= 0.5f
        }.thenByDescending { uprightRects.getValue(it).let { rect -> rect.y + rect.height } }
            .thenByDescending { uprightRects.getValue(it).area }
            .thenByDescending { detections[it].detectionConfidence }
            .thenBy { it })
        val preferred = prioritySourceIndices.take(config.maxInputDetections).distinct().filter { it in validIndexes }
        val ranked = preferred + geometryRanked.filter { it !in preferred }
        if (ranked.size <= config.maxTrackedObjects) return ranked
        // Confirmed hazards may fill every slot; coverage must never evict a supplied hazard.
        val priorityCount = maxOf((config.maxTrackedObjects - 1).coerceAtLeast(1),
            preferred.size.coerceAtMost(config.maxTrackedObjects))
        val priority = ranked.take(priorityCount)
        if (priorityCount == config.maxTrackedObjects) return priority + ranked.filter { it !in priority }
        // Keep one bounded coverage slot, advancing only when a new detector batch is admitted.
        // Repeated calls on the same revision keep the same identities and backfill state.
        val remaining = validIndexes.filter { it !in priority }
        val coverage = remaining[coverageCursor % remaining.size]
        coverageCursor = (coverageCursor + 1) % config.maxInputDetections
        val selected = priority + coverage
        return selected + ranked.filter { it !in selected }
    }

    private fun iou(a: RectNorm, b: RectNorm): Float {
        val area = (minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)).coerceAtLeast(0f) *
            (minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)).coerceAtLeast(0f)
        return area / (a.area + b.area - area).coerceAtLeast(0.00001f)
    }

    private data class FrameDimensions(val width: Int, val height: Int, val sourceWidth: Int, val sourceHeight: Int)

    private data class Batch(
        val sourceKey: VisualFrameKey,
        val revision: Long,
        val completed: Boolean,
        val uprightQuarterTurns: Int,
        val detections: List<DetectionCandidate>,
        val selectedIndexes: List<Int>,
        val states: MutableList<State>,
    )

    private data class State(
        var lastKey: VisualFrameKey,
        var admitted: Boolean = false,
        var features: List<VisualMotionFeature>? = null,
        var originalCount: Int = 0,
        var transform: VisualAffineTransform = VisualAffineTransform(),
        var quality: Float = 1f,
        var residual: Float? = null,
        var failure: VisualTrackingFailure? = null,
    )
}
