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
    private var estimatorOverride: VisualMotionEstimator? = null
    internal constructor(config: VisualTrackingConfig, clockNanos: () -> Long, estimator: VisualMotionEstimator) :
        this(config, clockNanos) { estimatorOverride = estimator }

    private val history = ArrayDeque<GrayTrackingFrame>()
    private val estimator: VisualMotionEstimator by lazy {
        estimatorOverride ?: when (config.backend) {
            VisualTrackingBackend.OPENCV_PYRAMIDAL_LK -> OpenCvPyramidalMotionEstimator(config.maxFeaturesPerObject, config.allowSimilarityTransform)
            VisualTrackingBackend.PATCH_DIAGNOSTIC -> PatchMotionEstimator(minOf(config.maxFeaturesPerObject, 16))
        }
    }
    private var batch: Batch? = null
    private var acceptedEpoch: Long? = null
    private var acceptedGeometryVersion: Long? = null
    private var acceptedDimensions: FrameDimensions? = null
    private var coverageCursor = 0
    /** Ownership is by token identity so a late worker cannot release a later reservation. */
    class PendingSourceLease internal constructor(val sourceKey: VisualFrameKey)

    private var pendingSourceLease: PendingSourceLease? = null
    private var publishedSourceLease: PendingSourceLease? = null

    @Synchronized
    fun initialize(): Boolean = estimator.initialize()

    @Synchronized
    fun clear() {
        history.clear()
        batch = null
        pendingSourceLease = null
        publishedSourceLease = null
        coverageCursor = 0
    }

    /** Reserves one in-flight source independently of the latest completed publication. */
    @Synchronized
    fun reservePendingSourceFrame(sourceKey: VisualFrameKey): PendingSourceLease? {
        val current = history.lastOrNull()?.key ?: return null
        expireSourceLeases(current)
        if (pendingSourceLease != null || history.none { it.key == sourceKey } ||
            !withinDetectionAge(sourceKey, current)) return null
        return PendingSourceLease(sourceKey).also { pendingSourceLease = it }
    }

    /** Called only when the matching worker publishes its completed snapshot, never for partials. */
    @Synchronized
    fun publishPendingSourceFrame(lease: PendingSourceLease): Boolean {
        val current = history.lastOrNull()?.key ?: return false
        expireSourceLeases(current)
        if (pendingSourceLease !== lease) return false
        val sourceKey = lease.sourceKey
        val published = publishedSourceLease?.sourceKey
        if (history.none { it.key == sourceKey } || !withinDetectionAge(sourceKey, current) ||
            (published != null && published.cameraTimestampNs > sourceKey.cameraTimestampNs)) return false
        publishedSourceLease = lease
        pendingSourceLease = null
        return true
    }

    /** Cancellation affects only this worker's pending ownership, never a completed publication. */
    @Synchronized
    fun releasePendingSourceFrame(lease: PendingSourceLease): Boolean {
        if (pendingSourceLease !== lease) return false
        pendingSourceLease = null
        return true
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
        trimHistory(frame.key)
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
            // Retain only bounded point descriptors, never the old batch's feature/image buffers.
            val handoffs = if (completed && previous?.completed == true &&
                previous.uprightQuarterTurns == uprightQuarterTurns && withinDetectionAge(previous.sourceKey, targetKey)
            ) previous.states.mapIndexedNotNull { index, state ->
                val point = state.materialPoint ?: return@mapIndexedNotNull null
                if (!state.admitted || state.failure != null || state.quality < MATERIAL_POINT_MIN_QUALITY ||
                    frames.indexOfFirst { it.key == state.lastKey } !in sourceIndex..targetIndex
                ) return@mapIndexedNotNull null
                val geometry = transform(previous.detections[index], state.transform)
                if (!contains(geometry, Point2(point.x / frames[sourceIndex].width, point.y / frames[sourceIndex].height))) {
                    return@mapIndexedNotNull null
                }
                val norm = Point2(point.x / frames[sourceIndex].width, point.y / frames[sourceIndex].height)
                // A missing descriptor is not evidence that an old same-class owner disappeared.
                // Freeze this proof while the entire previous batch is still available. Single-class
                // handoffs retain their existing contract and do not require this additional proof.
                val repeatedClassOwnershipProven = previous.states.indices.none { other ->
                    if (other == index) false else {
                        val peer = previous.states[other]
                        val sameClass = previous.detections[other].className == geometry.className
                        (sameClass && (!peer.admitted || peer.failure != null ||
                            peer.quality < MATERIAL_POINT_MIN_QUALITY || peer.features == null ||
                            peer.originalCount < 6 || peer.lastKey != state.lastKey)) ||
                            (peer.failure == null && (peer.lastKey != state.lastKey ||
                                contains(transform(previous.detections[other], peer.transform), norm)))
                    }
                }
                PointHandoff(state.lastKey, geometry, point, repeatedClassOwnershipProven,
                    previous.sourceKey, index, previous.detections[index], state.transform)
            }.take(config.maxTrackedObjects).toMutableList() else mutableListOf()
            batch = Batch(sourceKey, detectionRevision, completed, uprightQuarterTurns, frozen, selectedIndexes, frozen.mapIndexed { index, detection ->
                State(
                    lastKey = sourceKey,
                    admitted = index in initialIndexes,
                    failure = when {
                        !valid(detection) -> VisualTrackingFailure.INVALID_GEOMETRY
                        else -> null
                    },
                )
            }.toMutableList(), handoffs)
        }
        // An accepted completed batch takes over the published image. A partial result or
        // another source cannot release a worker's pending/completed publication ownership.
        if (completed && publishedSourceLease?.sourceKey == sourceKey) publishedSourceLease = null
        val current = requireNotNull(batch)
        val source = frames[sourceIndex]
        var budgetFailure: VisualTrackingFailure? = null
        var activeSlots = current.states.count { it.admitted && it.failure == null }
        // Complete each image edge across the admitted objects before continuing backfill.
        // This checks physical point ownership at intermediate frames as well as the final target.
        for (phaseIndex in sourceIndex + 1..targetIndex) {
            val phaseKey = frames[phaseIndex].key
            // Preserve admission priority inside each edge when the work budget is tight.
            for (index in current.selectedIndexes) {
                val state = current.states[index]
                if (sourceKey == targetKey || state.failure != null) continue
                if (!state.admitted && activeSlots >= config.maxTrackedObjects) continue
                if (state.admitted && state.lastKey.cameraTimestampNs >= phaseKey.cameraTimestampNs) continue
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
                        // One actual seed becomes the auxiliary material point. Geometry still needs
                        // at least six points; six-point seeds keep geometry and provide no motion point.
                        val representative = if (estimator.supportsMaterialPointTracking && features.size >= 7) {
                            val center = detection.bboxNorm.center
                            features.minByOrNull {
                                val dx = it.currentX / source.width - center.x
                                val dy = it.currentY / source.height - center.y
                                dx * dx + dy * dy
                            }
                        } else null
                        state.features = if (representative == null) features else features.filter { it !== representative }
                        state.originalCount = requireNotNull(state.features).size
                        if (representative != null && state.materialPoint == null) {
                            state.materialPoint = VisualMotionPoint(VisualMaterialPointId(), representative.currentX, representative.currentY)
                        }
                        if (state.originalCount < 6) {
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
                    adoptMaterialPointAtCurrentFrame(current, index, source)
                    for (nextIndex in lastIndex + 1..phaseIndex) {
                        val before = frames[nextIndex - 1]
                        val target = frames[nextIndex]
                        if (target.key.capturedAtElapsedRealtimeMs - before.key.capturedAtElapsedRealtimeMs > config.maxFrameGapMs ||
                            (target.key.cameraTimestampNs - before.key.cameraTimestampNs) / 1_000_000L > config.maxFrameGapMs
                        ) {
                            state.failure = VisualTrackingFailure.FRAME_GAP
                            break
                        }
                        val estimate = estimator.advance(source, before, target, detection, requireNotNull(state.features), state.originalCount, budget,
                            materialPoint = state.materialPoint)
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
                        state.materialPoint = estimate.materialPoint?.takeIf { point ->
                            contains(transform(detection, estimate.transformNorm), Point2(point.x / source.width, point.y / source.height))
                        }
                        if (state.materialPoint == null) {
                            // Every backfill edge has the same ownership boundary as a live frame.
                            // A point that left the object cannot return later with its old identity.
                            val retiredId = estimate.materialPoint?.id
                            if (retiredId != null) current.handoffs.removeAll { it.point.id === retiredId }
                        }
                        adoptMaterialPointAtCurrentFrame(current, index, source)
                        // Native calls cannot be preempted. Stop before starting another edge if
                        // this completed edge crossed the work budget.
                        budget.charge()
                    }
                } catch (exceeded: TrackingBudgetExceeded) {
                    budgetFailure = exceeded.failure
                } finally {
                    // Terminal failures free a slot immediately; the next candidate uses this same
                    // call's remaining time and pixel budget. Pending work retains its own slot.
                    if (budgetFailure == null) rejectCompetingObjects(current, phaseKey)
                    activeSlots = current.states.count { it.admitted && it.failure == null }
                }
            }
            rejectCompetingObjects(current, phaseKey)
            retireUnownedMaterialPoints(current, phaseKey, frames[phaseIndex])
            if (budgetFailure != null) {
                // An unfinished multi-object edge cannot establish intermediate ownership.
                if (current.states.count { it.admitted && it.failure == null } > 1) {
                    current.states.forEach { it.materialPoint = null }
                    current.handoffs.clear()
                }
                break
            }
            // Per-object calls above cannot prove repeated-class ownership until every peer has
            // completed this exact image edge. Recheck before moving any object to the next frame.
            current.selectedIndexes.forEach { adoptMaterialPointAtCurrentFrame(current, it, source) }
        }
        val reachedTarget = current.states.all { !it.admitted || it.failure != null || it.lastKey == targetKey }
        val completedAfterDeadline = budgetFailure == VisualTrackingFailure.TIME_BUDGET_EXCEEDED && reachedTarget
        val incompleteBatch = budgetFailure != null && !completedAfterDeadline
        if (!incompleteBatch) {
            rejectCompetingObjects(current, targetKey)
            current.selectedIndexes.forEach { adoptMaterialPointAtCurrentFrame(current, it, source) }
        }
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
                val materialPoint = if (!completed || incompleteBatch || state.quality < MATERIAL_POINT_MIN_QUALITY) null else {
                    state.materialPoint?.let { point ->
                        val norm = Point2(point.x / source.width, point.y / source.height)
                        point.takeIf {
                            contains(transform(original, state.transform), norm) &&
                                current.states.indices.none { other ->
                                    other != index && current.states[other].failure == null &&
                                        (if (current.states[other].lastKey == targetKey) contains(
                                            transform(current.detections[other], current.states[other].transform), norm
                                        ) else true)
                                }
                        }?.let { VisualMaterialPoint(it.id, targetKey, norm) }
                    }
                }
                // Uncertain ownership retires the physical identity even if this frame has no
                // depth. It cannot resume the old spatial history when overlap later disappears.
                if (!incompleteBatch && materialPoint == null) state.materialPoint = null
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
                    materialPoint = materialPoint,
                ).also { observation ->
                    state.materialPointHandoff?.takeIf { materialPoint?.id === it.pointId }
                        ?.let(observation::bindMaterialPointHandoff)
                }
            }
        }
        return result(sourceKey, targetKey, observations, started, edges, budget,
            previous === current && edges == 0 && budget.pixelComparisons == 0L && budgetFailure == null,
            observedAtElapsedRealtimeMs).copy(
            redetectReasons = if (sourceKey == targetKey) emptySet() else redetectReasons(current, targetKey, incompleteBatch),
        )
    }

    /**
     * Batch priority is fixed at admission; these states ask for an earlier fresh detection instead of
     * re-ranking or reviving identities inside the current batch.
     */
    private fun redetectReasons(batch: Batch, target: VisualFrameKey, incompleteBatch: Boolean): Set<VisualRedetectReason> {
        val reasons = mutableSetOf<VisualRedetectReason>()
        batch.states.forEachIndexed { index, state ->
            val failure = state.failure
            when {
                failure == VisualTrackingFailure.OBJECT_COMPETITION -> reasons += VisualRedetectReason.OCCLUSION_OR_COMPETITION
                state.admitted && failure != null && failure !in NON_VISUAL_LOSS -> reasons += VisualRedetectReason.TRACK_LOST
                !state.admitted && failure == null && index in batch.selectedIndexes -> reasons += VisualRedetectReason.UNTRACKED_CANDIDATES
                state.admitted && failure == null && !incompleteBatch && state.lastKey == target -> {
                    val original = batch.detections[index]
                    if (!inWalkingZone(upright(original.bboxNorm, batch.uprightQuarterTurns)) &&
                        inWalkingZone(upright(transform(original, state.transform).bboxNorm, batch.uprightQuarterTurns))
                    ) reasons += VisualRedetectReason.WALKING_ZONE_ENTRY
                }
            }
        }
        return reasons
    }

    private fun retireUnownedMaterialPoints(batch: Batch, key: VisualFrameKey, frame: GrayTrackingFrame) {
        for (index in batch.states.indices) {
            val state = batch.states[index]
            val point = state.materialPoint ?: continue
            if (state.lastKey != key) continue
            val norm = Point2(point.x / frame.width, point.y / frame.height)
            if (state.failure != null || !contains(transform(batch.detections[index], state.transform), norm) ||
                batch.states.indices.any { other ->
                    other != index && batch.states[other].failure == null &&
                        (batch.states[other].lastKey != key ||
                            contains(transform(batch.detections[other], batch.states[other].transform), norm))
                }
            ) {
                state.materialPoint = null
                batch.handoffs.removeAll { it.point.id === point.id }
            }
        }
    }

    /** A handoff copies a measured point only at its exact frame, never an affine extrapolation. */
    private fun adoptMaterialPointAtCurrentFrame(batch: Batch, index: Int, frame: GrayTrackingFrame) {
        if (!batch.completed || !estimator.supportsMaterialPointTracking) return
        val state = batch.states[index]
        if (!state.admitted || state.failure != null || state.quality < MATERIAL_POINT_MIN_QUALITY) return
        val geometry = transform(batch.detections[index], state.transform)
        val related = batch.states.indices.filter { batch.detections[it].className == geometry.className }
        val repeatedClass = related.size > 1
        if (repeatedClass && related.any { other ->
                val peer = batch.states[other]
                val point = peer.materialPoint
                !peer.admitted || peer.failure != null || peer.quality < MATERIAL_POINT_MIN_QUALITY ||
                    peer.lastKey != state.lastKey || peer.features == null || peer.originalCount < 6 ||
                    point == null || !contains(transform(batch.detections[other], peer.transform),
                        Point2(point.x / frame.width, point.y / frame.height))
            }) return
        fun eligible(handoff: PointHandoff, targetIndex: Int): Boolean {
            val target = batch.states[targetIndex]
            val targetGeometry = transform(batch.detections[targetIndex], target.transform)
            val norm = Point2(handoff.point.x / frame.width, handoff.point.y / frame.height)
            return handoff.key == target.lastKey && handoff.geometry.className == targetGeometry.className &&
                iou(handoff.geometry.bboxNorm, targetGeometry.bboxNorm) >= 0.65f &&
                contains(handoff.geometry, norm) && contains(targetGeometry, norm)
        }
        // Use the original descriptors for both directions and ownership vetoes. Consuming an
        // earlier row must not turn an ambiguous later row into a unique association.
        val candidates = batch.handoffOrigins.filter { handoff ->
            handoff.key == state.lastKey && handoff.geometry.className == geometry.className &&
                (!repeatedClass || (eligible(handoff, index) && handoff.repeatedClassOwnershipProven))
        }
        val handoff = candidates.singleOrNull() ?: return
        if (!eligible(handoff, index)) return
        val norm = Point2(handoff.point.x / frame.width, handoff.point.y / frame.height)
        if (batch.handoffOrigins.any { it !== handoff && it.key == state.lastKey &&
                (it.point.id === handoff.point.id || contains(it.geometry, norm))
            } || (repeatedClass && (related.any { it != index && eligible(handoff, it) } ||
                batch.states.indices.any { other ->
                    other != index && batch.states[other].failure == null &&
                        (batch.states[other].lastKey != state.lastKey ||
                            contains(transform(batch.detections[other], batch.states[other].transform), norm))
                }))
        ) return
        if (batch.handoffs.none { it === handoff }) return
        state.materialPoint = handoff.point
        state.materialPointHandoff = VisualMaterialPointHandoff(
            handoff.sourceKey, batch.sourceKey, handoff.key, handoff.sourceIndex, index,
            handoff.detection, batch.detections[index], handoff.geometry, handoff.transform, geometry, state.transform,
            handoff.point.id, norm, batch.uprightQuarterTurns,
        )
        batch.handoffs.remove(handoff)
    }

    private fun contains(detection: DetectionCandidate, point: Point2): Boolean {
        val box = detection.bboxNorm
        if (!point.x.isFinite() || !point.y.isFinite() || point.x < box.x || point.y < box.y ||
            point.x > box.x + box.width || point.y > box.y + box.height) return false
        if (detection.polygonNorm.isEmpty()) return true
        var inside = false
        var before = detection.polygonNorm.last()
        for (after in detection.polygonNorm) {
            if ((after.y > point.y) != (before.y > point.y) &&
                point.x < (before.x - after.x) * (point.y - after.y) / (before.y - after.y) + after.x
            ) inside = !inside
            before = after
        }
        return inside
    }

    private fun upright(rect: RectNorm, turns: Int): RectNorm = UprightCameraImage.toSensor(rect, (4 - turns) % 4)

    /** Screen heuristic shared with admission ranking; not metric depth or a calibrated risk score. */
    private fun inWalkingZone(rect: RectNorm): Boolean =
        rect.x < 0.65f && rect.x + rect.width > 0.35f && rect.y + rect.height >= 0.5f

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
        val uprightRects = validIndexes.associateWith { upright(detections[it].bboxNorm, turns) }
        val geometryRanked = validIndexes.sortedWith(compareByDescending<Int> {
            inWalkingZone(uprightRects.getValue(it))
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

    private companion object {
        const val MATERIAL_POINT_MIN_QUALITY = 0.65f

        /** Budget/geometry outcomes are not evidence that an admitted object visually disappeared. */
        val NON_VISUAL_LOSS = setOf(
            VisualTrackingFailure.INVALID_GEOMETRY, VisualTrackingFailure.INPUT_BUDGET_EXCEEDED,
            VisualTrackingFailure.OBJECT_BUDGET_EXCEEDED, VisualTrackingFailure.WORK_BUDGET_EXCEEDED,
            VisualTrackingFailure.TIME_BUDGET_EXCEEDED,
        )
    }

    private data class FrameDimensions(val width: Int, val height: Int, val sourceWidth: Int, val sourceHeight: Int)

    /** Protect only live origins/cursors; intermediate images may be sampled within the gap cap. */
    private fun trimHistory(currentKey: VisualFrameKey) {
        expireSourceLeases(currentKey)
        val activeBatch = batch?.takeIf { withinDetectionAge(it.sourceKey, currentKey) }
        val protectedKeys = buildSet {
            pendingSourceLease?.sourceKey?.let(::add)
            publishedSourceLease?.sourceKey?.let(::add)
            activeBatch?.let { current ->
                add(current.sourceKey)
                current.states.asSequence()
                    .filter { it.admitted && it.failure == null }
                    .map { it.lastKey }
                    .filter { withinDetectionAge(it, currentKey) }
                    .forEach(::add)
            }
        }

        while (history.isNotEmpty() &&
            currentKey.capturedAtElapsedRealtimeMs - history.first().key.capturedAtElapsedRealtimeMs > config.maxHistoryAgeMs
        ) {
            removeHistoryFrame(history.first())
        }

        while (history.size > config.maxHistoryFrames) {
            val frames = history.toList()
            // Remove the oldest safe image first. A middle image is redundant only when its
            // neighbors remain within the existing elapsed-time and camera-time gap contract.
            val removableIndex = frames.indices.firstOrNull { index ->
                index < frames.lastIndex && frames[index].key !in protectedKeys &&
                    (index == 0 || frameGapWithinLimit(frames[index - 1], frames[index + 1]))
            } ?: frames.indices.firstOrNull { index ->
                // The 32-image hard bound takes precedence if malformed/sparse input leaves no
                // safe sample to remove; tracking will report FRAME_GAP for the broken path.
                index < frames.lastIndex && frames[index].key !in protectedKeys
            } ?: 0 // Tiny configured histories may not fit all owners; never exceed the cap.
            removeHistoryFrame(frames[removableIndex])
        }
    }

    private fun expireSourceLeases(currentKey: VisualFrameKey) {
        pendingSourceLease = pendingSourceLease?.takeIf { withinDetectionAge(it.sourceKey, currentKey) }
        publishedSourceLease = publishedSourceLease?.takeIf { withinDetectionAge(it.sourceKey, currentKey) }
    }

    private fun removeHistoryFrame(frame: GrayTrackingFrame) {
        history.remove(frame)
        if (pendingSourceLease?.sourceKey == frame.key) pendingSourceLease = null
        if (publishedSourceLease?.sourceKey == frame.key) publishedSourceLease = null
        // A batch whose source/cursor had to be evicted fails closed at trackFrom's history check.
    }

    private fun withinDetectionAge(sourceKey: VisualFrameKey, currentKey: VisualFrameKey): Boolean {
        val elapsedAgeMs = currentKey.capturedAtElapsedRealtimeMs - sourceKey.capturedAtElapsedRealtimeMs
        val cameraAgeMs = (currentKey.cameraTimestampNs - sourceKey.cameraTimestampNs) / 1_000_000L
        return elapsedAgeMs in 0L..config.maxDetectionAgeMs && cameraAgeMs in 0L..config.maxDetectionAgeMs
    }

    private fun frameGapWithinLimit(before: GrayTrackingFrame, after: GrayTrackingFrame): Boolean {
        val elapsedGapMs = after.key.capturedAtElapsedRealtimeMs - before.key.capturedAtElapsedRealtimeMs
        val cameraGapMs = (after.key.cameraTimestampNs - before.key.cameraTimestampNs) / 1_000_000L
        return elapsedGapMs in 0L..config.maxFrameGapMs && cameraGapMs in 0L..config.maxFrameGapMs
    }

    private data class Batch(
        val sourceKey: VisualFrameKey,
        val revision: Long,
        val completed: Boolean,
        val uprightQuarterTurns: Int,
        val detections: List<DetectionCandidate>,
        val selectedIndexes: List<Int>,
        val states: MutableList<State>,
        val handoffs: MutableList<PointHandoff>,
    ) {
        // Bounded by maxTrackedObjects, and independent of later consumption/retirement.
        val handoffOrigins: List<PointHandoff> = handoffs.toList()
    }

    private data class PointHandoff(
        val key: VisualFrameKey,
        val geometry: DetectionCandidate,
        val point: VisualMotionPoint,
        val repeatedClassOwnershipProven: Boolean,
        val sourceKey: VisualFrameKey,
        val sourceIndex: Int,
        val detection: DetectionCandidate,
        val transform: VisualAffineTransform,
    )

    private data class State(
        var lastKey: VisualFrameKey,
        var admitted: Boolean = false,
        var features: List<VisualMotionFeature>? = null,
        var originalCount: Int = 0,
        var materialPoint: VisualMotionPoint? = null,
        var materialPointHandoff: VisualMaterialPointHandoff? = null,
        var transform: VisualAffineTransform = VisualAffineTransform(),
        var quality: Float = 1f,
        var residual: Float? = null,
        var failure: VisualTrackingFailure? = null,
    )
}
