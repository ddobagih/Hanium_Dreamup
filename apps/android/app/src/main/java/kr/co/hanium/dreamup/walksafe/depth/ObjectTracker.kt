package kr.co.hanium.dreamup.walksafe.depth

import kotlin.math.max
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class DistanceObservation(
    val timestampMs: Long,
    val distanceM: Float,
    val source: DepthSource,
    val confidence: Float,
    val cameraPoseEvidence: CameraPoseEvidence? = null,
    val objectPositionInAnchor: Vec3? = null,
    val depthObservationTimestampNs: Long? = null,
)

data class IndexedObjectGeometry(
    val sourceDetectionIndex: Int,
    val geometry: ObjectGeometry,
    val association: MaskAssociationEvidence? = null,
)
data class IndexedTrackAssignment(val sourceDetectionIndex: Int, val track: TrackState)

data class ApproachKinematics(
    val trend: Trend,
    val approachScore: Float,
    val approachSpeedMps: Float?,
    val timeToCollisionMs: Long?,
    val objectMotion: ObjectMotion = ObjectMotion.UNKNOWN,
    val motionEstimate: ObjectMotionEstimate = ObjectMotionEstimate(),
)

class TrackState(
    val trackId: String,
    val className: String,
    val createdAtMs: Long,
) {
    var lastSeenAtMs: Long = createdAtMs
        private set
    var lastDetectionAtMs: Long = createdAtMs
        private set
    var ageFrames: Int = 0
        private set
    var missedFrames: Int = 0
        private set
    var stable: Boolean = false
        private set
    var idSwitchSuspected: Boolean = false
        private set
    var latestGeometry: ObjectGeometry? = null
        private set
    internal var latestDetectionGeometry: ObjectGeometry? = null
        private set
    internal var hasCurrentVisualGeometry: Boolean = false
        private set
    var metricDistanceReliable: Boolean = true
        private set
    private var distanceContinuity = MetricDistanceContinuityPolicy()
    private val motionWindow = MotionObservationWindow()
    internal val depthGap = ShortDepthGapState()
    private val proximityWindow = MotionObservationWindow()
    private var proximityContinuity = MetricDistanceContinuityPolicy()
    private val proximityHistory = mutableListOf<DistanceObservation>()
    private var currentDistanceIsProximityOnly = false
    private var independentDistanceRequired = false
    private var distanceBoundaryPending = false
    internal var latestMaskAssociationEvidence: MaskAssociationEvidence? = null
        private set
    private var maskAssociationUncertain = false
    private val visualGeometryHistory = mutableListOf<Pair<Long, ObjectGeometry>>()

    internal fun visualGeometryAtOrBefore(timestampMs: Long): ObjectGeometry? =
        visualGeometryHistory.lastOrNull { it.first <= timestampMs }?.second

    private fun rememberVisualGeometry(geometry: ObjectGeometry, timestampMs: Long) {
        visualGeometryHistory.addCapped(timestampMs to geometry, 64)
    }

    internal fun motionSamples(): List<DistanceObservation> =
        if (currentDistanceIsProximityOnly) emptyList() else motionWindow.spatialSamples(lastSeenAtMs)
    internal fun denseMotionHistory(): List<DistanceObservation> = motionWindow.spatialHistory()
    internal fun kinematicSamples(): List<DistanceObservation> = if (currentDistanceIsProximityOnly) {
        emptyList()
    } else if (independentDistanceRequired) {
        motionWindow.metricSamples(lastSeenAtMs)
    } else {
        distanceHistory.filter { it.source.metric && it.confidence >= 0.35f }.takeLast(8)
    }
    internal fun requiresIndependentDistance(): Boolean = independentDistanceRequired

    val bboxHistory: MutableList<RectNorm> = mutableListOf()
    val centerHistory: MutableList<Point2> = mutableListOf()
    val polygonHistory: MutableList<List<Point2>> = mutableListOf()
    val distanceHistory: MutableList<DistanceObservation> = mutableListOf()
    val confidenceHistory: MutableList<Float> = mutableListOf()

    fun markSeen(geometry: ObjectGeometry, timestampMs: Long, minStableAgeFrames: Int) {
        val resolvingMaskAmbiguity = maskAssociationUncertain
        latestGeometry?.let { previous ->
            if (continuityIsUncertain(previous, geometry) || timestampMs < lastSeenAtMs) {
                idSwitchSuspected = true
                clearMotionHistory()
            }
        }
        clearImageHistoryOnRotationChange(geometry)
        latestGeometry = geometry
        latestDetectionGeometry = geometry
        rememberVisualGeometry(geometry, timestampMs)
        hasCurrentVisualGeometry = false
        lastSeenAtMs = timestampMs
        lastDetectionAtMs = timestampMs
        missedFrames = 0
        ageFrames += 1
        stable = ageFrames >= minStableAgeFrames && !idSwitchSuspected
        bboxHistory.addCapped(geometry.bboxNorm, 12)
        centerHistory.addCapped(geometry.centerNorm, 12)
        polygonHistory.addCapped(geometry.polygonNorm, 8)
        if (resolvingMaskAmbiguity) {
            resetMetricAndSpatialHistoryPreservingTrackId(timestampMs)
            maskAssociationUncertain = false
        }
    }

    /** A completed detector capture confirms identity even when flow was not budgeted for it. */
    internal fun markSourceSeen(geometry: ObjectGeometry, timestampMs: Long, minStableAgeFrames: Int) {
        latestDetectionGeometry = geometry
        lastDetectionAtMs = timestampMs
        missedFrames = 0
        ageFrames += 1
        stable = ageFrames >= minStableAgeFrames && !idSwitchSuspected
        // A detector may finish after a newer image has already been tracked. Never rewind it.
        if (timestampMs >= lastSeenAtMs) {
            clearImageHistoryOnRotationChange(geometry)
            latestGeometry = geometry
            rememberVisualGeometry(geometry, timestampMs)
            hasCurrentVisualGeometry = false
            lastSeenAtMs = timestampMs
            bboxHistory.addCapped(geometry.bboxNorm, 12)
            centerHistory.addCapped(geometry.centerNorm, 12)
            polygonHistory.addCapped(geometry.polygonNorm, 8)
        }
    }

    internal fun deferVisualTracking() {
        depthGap.clear()
        hasCurrentVisualGeometry = false
        visualGeometryHistory.clear()
        // Keep completed detector confirmations, but a gap cannot carry motion evidence forward.
        metricDistanceReliable = false
        // Keep the information clocks too: an old depth image is still old after coverage resumes.
        motionWindow.clearSamples()
        clearDistanceHistory()
        clearProximityHistory()
        distanceBoundaryPending = false
        independentDistanceRequired = true
    }

    private fun clearImageHistoryOnRotationChange(geometry: ObjectGeometry) {
        if (latestGeometry?.imageQuarterTurns != geometry.imageQuarterTurns) {
            depthGap.clear()
            visualGeometryHistory.clear()
            bboxHistory.clear()
            centerHistory.clear()
            polygonHistory.clear()
        }
    }

    internal fun acceptMaskAssociation(evidence: MaskAssociationEvidence) {
        val previous = latestMaskAssociationEvidence
        if (previous != null && previous.representativeEpoch != evidence.representativeEpoch) {
            resetMetricAndSpatialHistoryPreservingTrackId(lastSeenAtMs)
        }
        latestMaskAssociationEvidence = evidence
    }

    internal fun holdMaskAssociation(timestampMs: Long) {
        depthGap.clear()
        maskAssociationUncertain = true
        metricDistanceReliable = false
        // Keep identity, last confirmed geometry, and bounded history. Never append guessed geometry.
        motionWindow.invalidateAt(maxOf(timestampMs, lastSeenAtMs))
    }

    fun markMissed() {
        hasCurrentVisualGeometry = false
        visualGeometryHistory.clear()
        missedFrames += 1
        stable = false
        clearMotionHistory()
    }

    /** Updates a current image observation without manufacturing a new semantic confirmation. */
    fun markTracked(geometry: ObjectGeometry, timestampMs: Long): Boolean {
        if (maskAssociationUncertain || missedFrames != 0 || geometry.className != className || timestampMs < lastSeenAtMs) return false
        if (timestampMs == lastSeenAtMs) {
            val matches = latestGeometry == geometry
            if (matches) hasCurrentVisualGeometry = true
            return matches
        }
        if (latestGeometry?.let { continuityIsUncertain(it, geometry) } != false) return false
        clearImageHistoryOnRotationChange(geometry)
        latestGeometry = geometry
        rememberVisualGeometry(geometry, timestampMs)
        lastSeenAtMs = timestampMs
        hasCurrentVisualGeometry = true
        bboxHistory.addCapped(geometry.bboxNorm, 12)
        centerHistory.addCapped(geometry.centerNorm, 12)
        polygonHistory.addCapped(geometry.polygonNorm, 8)
        return true
    }

    fun invalidateMotionEvidence(timestampMs: Long) {
        if (timestampMs >= lastSeenAtMs) {
            metricDistanceReliable = false
            motionWindow.invalidateAt(timestampMs)
        }
    }

    /** A spatial representative boundary is not a lost visual track. */
    fun resetMetricAndSpatialHistoryPreservingTrackId(timestampMs: Long): Boolean {
        if (timestampMs != lastSeenAtMs) return false
        depthGap.clear()
        // Keep the information-clock ledger: old raw depth must not become a new sample.
        motionWindow.clearSamples()
        clearDistanceHistory()
        clearProximityHistory()
        // The visual mask remains useful for correspondence; old scoped spatial evidence does not.
        latestMaskAssociationEvidence = latestMaskAssociationEvidence?.copy(spatial = null)
        distanceBoundaryPending = false
        independentDistanceRequired = true
        metricDistanceReliable = false
        return true
    }

    fun recordDistance(
        observation: DistanceObservation,
        maxDepthJumpM: Float,
        requireIndependentDepthObservation: Boolean = false,
        proximityOnly: Boolean = false,
    ): Boolean {
        currentDistanceIsProximityOnly = proximityOnly
        if (maskAssociationUncertain) {
            metricDistanceReliable = false
            return false
        }
        independentDistanceRequired = independentDistanceRequired || requireIndependentDepthObservation || proximityOnly
        if (proximityOnly) return recordProximityDistance(observation, maxDepthJumpM)
        if (proximityWindow.observeProximityContext(observation.timestampMs, observation.cameraPoseEvidence?.referenceId)) {
            clearProximityHistory()
        }
        val admission = motionWindow.admit(observation)
        if (motionWindow.resetOnAdmission && independentDistanceRequired) distanceBoundaryPending = true
        if (observation.timestampMs < 0L || !observation.source.metric || !observation.confidence.isFinite() ||
            !observation.distanceM.isFinite() || observation.distanceM <= 0f
        ) {
            metricDistanceReliable = false
            return false
        }
        if (independentDistanceRequired && admission != MotionObservationAdmission.NEW) {
            // A repeated/reprojected distance may still be displayed. It cannot confirm a jump or velocity.
            val previous = distanceHistory.lastOrNull()
            metricDistanceReliable = admission != MotionObservationAdmission.DISCONTINUOUS &&
                (previous == null || abs(previous.distanceM - observation.distanceM) <= maxDepthJumpM)
            return metricDistanceReliable
        }
        if (distanceBoundaryPending && independentDistanceRequired) {
            clearDistanceHistory()
            distanceBoundaryPending = false
        }
        val decision = distanceContinuity.evaluate(observation, distanceHistory, maxDepthJumpM)
        metricDistanceReliable = decision.accepted
        if (decision.resetHistory) {
            depthGap.clear()
            distanceHistory.clear()
            confidenceHistory.clear()
            motionWindow.clearSamples()
        }
        decision.observationsToAppend.forEach { accepted ->
            distanceHistory.addCapped(accepted, 12)
            confidenceHistory.addCapped(accepted.confidence, 12)
            if (admission == MotionObservationAdmission.NEW) motionWindow.appendAccepted(accepted)
        }
        return decision.accepted
    }

    /** Full proximity has its own jump and information-clock checks, never the Raw motion ledger. */
    private fun recordProximityDistance(observation: DistanceObservation, maxDepthJumpM: Float): Boolean {
        if (motionWindow.observeProximityContext(observation.timestampMs, observation.cameraPoseEvidence?.referenceId)) {
            clearDistanceHistory()
            distanceBoundaryPending = false
        }
        val admission = proximityWindow.admit(observation)
        if (proximityWindow.resetOnAdmission) {
            proximityHistory.clear()
            proximityContinuity = MetricDistanceContinuityPolicy()
        }
        if (observation.timestampMs < 0L || !observation.source.metric || !observation.confidence.isFinite() ||
            !observation.distanceM.isFinite() || observation.distanceM <= 0f
        ) {
            metricDistanceReliable = false
            return false
        }
        if (admission != MotionObservationAdmission.NEW) {
            val previous = proximityHistory.lastOrNull()
            metricDistanceReliable = admission != MotionObservationAdmission.DISCONTINUOUS &&
                (previous == null || abs(previous.distanceM - observation.distanceM) <= maxDepthJumpM)
            return metricDistanceReliable
        }
        val decision = proximityContinuity.evaluate(observation, proximityHistory, maxDepthJumpM)
        metricDistanceReliable = decision.accepted
        if (decision.resetHistory) {
            depthGap.clear()
            proximityHistory.clear()
            proximityWindow.clearSamples()
        }
        decision.observationsToAppend.forEach { accepted ->
            proximityHistory.addCapped(accepted, 12)
            proximityWindow.appendAccepted(accepted)
        }
        return decision.accepted
    }

    private fun clearProximityHistory() {
        proximityHistory.clear()
        proximityContinuity = MetricDistanceContinuityPolicy()
        // Retain the Full information clock through support resets just as with Raw.
        proximityWindow.clearSamples()
    }

    private fun clearDistanceHistory() {
        distanceHistory.clear()
        confidenceHistory.clear()
        distanceContinuity = MetricDistanceContinuityPolicy()
    }

    private fun clearMotionHistory() {
        depthGap.clear()
        motionWindow.clear()
        clearProximityHistory()
        proximityWindow.clear()
        distanceBoundaryPending = false
        if (independentDistanceRequired) clearDistanceHistory()
    }
}

/**
 * Maintains short-lived, class-preserving tracks with conservative IoU/center matching.
 * Only accepted metric distance enters kinematics; isolated bad depth does not change identity.
 */
class ObjectTracker(
    private val minIoU: Float = 0.20f,
    private val maxCenterMoveNorm: Float = 0.25f,
    private val maxDepthJumpM: Float = 1.2f,
    private val maxMissedFrames: Int = 5,
    private val minStableAgeFrames: Int = 3,
    private val maxObservationGapMs: Long = 1_500L,
    private val trackIdPrefix: String = "track-",
) {
    private var nextTrackNumber = 1
    private val tracks = mutableListOf<TrackState>()
    private var lastGeometryObservationAtMs: Long? = null
    private var lastSourceObservationAtMs: Long? = null
    private var lastMaskCameraTimestampNs: Long? = null
    private var maskAmbiguousIndices = emptySet<Int>()

    fun update(geometries: List<ObjectGeometry>, timestampMs: Long): List<TrackState> =
        updateInternal(geometries, timestampMs)

    private fun updateInternal(geometries: List<ObjectGeometry>, timestampMs: Long,
                               associations: List<MaskAssociationEvidence?> = emptyList(),
                               maskAware: Boolean = false,
                               detectorSourceOnly: Boolean = false,
                               currentGeometries: Map<Int, ObjectGeometry> = emptyMap(),
                               currentTimestampMs: Long = timestampMs): List<TrackState> {
        if (detectorSourceOnly) {
            if (timestampMs < 0L || lastSourceObservationAtMs?.let { timestampMs <= it } == true) return emptyList()
            lastSourceObservationAtMs = timestampMs
        } else if (!acceptGeometryTimestamp(timestampMs)) return activeTracks()
        // A detector stall must not connect old observations into a seemingly continuous track.
        tracks.removeAll { track ->
            timestampMs - (if (detectorSourceOnly) track.lastDetectionAtMs else track.lastSeenAtMs) > maxObservationGapMs
        }
        val maskGeometryIndices = if (maskAware) geometries.indices.filter { geometries[it].className == UNNAMED_OBSTACLE_CLASS }.toSet() else emptySet()
        val maskTrackIndices = if (maskAware) tracks.indices.filter { tracks[it].latestMaskAssociationEvidence != null }.toSet() else emptySet()
        val maskResult = MaskCorrespondencePolicy.associate(
            maskTrackIndices.map { it to requireNotNull(tracks[it].latestMaskAssociationEvidence) },
            maskGeometryIndices.mapNotNull { index -> associations.getOrNull(index)?.let { index to it } },
            candidateAllowed = { old, current -> tracks[old].latestGeometry?.let {
                !continuityIsUncertain(it, geometries[current])
            } == true },
        )
        maskAmbiguousIndices = maskResult.ambiguous + maskGeometryIndices.filter { associations.getOrNull(it) == null }
        // Only an observation at this same current time can anchor a current box. Comparing
        // against an older publish can mistake two objects' parallel motion for an ID switch.
        val currentAnchors = if (detectorSourceOnly) tracks.flatMapIndexed { trackIndex, track ->
            if (!track.hasCurrentVisualGeometry || currentTimestampMs != track.lastSeenAtMs) {
                return@flatMapIndexed emptyList()
            }
            currentGeometries.mapNotNull { (geometryIndex, geometry) ->
                plausibleMatchIoU(track, geometry)?.takeIf { it >= minIoU }?.let {
                    MatchingCandidate(trackIndex, geometryIndex, it)
                }
            }
        } else emptyList()
        val candidates = tracks.flatMapIndexed { trackIndex, track ->
            geometries.mapIndexedNotNull { geometryIndex, geometry ->
                if (trackIndex in maskTrackIndices || geometryIndex in maskGeometryIndices) return@mapIndexedNotNull null
                val iou = if (detectorSourceOnly) sourceMatchIoU(
                    track, geometry, timestampMs, currentTimestampMs,
                ) else plausibleMatchIoU(track, geometry)
                iou?.let { MatchingCandidate(trackIndex, geometryIndex, it) }
            }
        }
        val eligibleTrackIndicesByClass = tracks.indices
            .filter { tracks[it].missedFrames == 0 && it !in maskTrackIndices }
            .groupBy { tracks[it].className }
        val geometryIndicesByClass = geometries.indices.filter { it !in maskGeometryIndices }.groupBy { geometries[it].className }
        val matchedTracks = BooleanArray(tracks.size)
        val matchedGeometries = BooleanArray(geometries.size)
        val mutuallyUniqueAnchoredMatches = mutualUniqueMatches(
            candidates = candidates.filter { it.iou >= minIoU },
            trackCount = tracks.size,
            geometryCount = geometries.size,
        )
        val anchoredMatchesByClass = mutuallyUniqueAnchoredMatches.groupBy { candidate ->
            tracks[candidate.trackIndex].className
        }
        val completeAnchoredClasses =
            (eligibleTrackIndicesByClass.keys + geometryIndicesByClass.keys).filter { className ->
                val eligibleTrackIndices = eligibleTrackIndicesByClass[className].orEmpty()
                val currentGeometryIndices = geometryIndicesByClass[className].orEmpty()
                val classMatches = anchoredMatchesByClass[className].orEmpty()
                eligibleTrackIndices.size == currentGeometryIndices.size &&
                    classMatches.map { it.trackIndex }.toSet() == eligibleTrackIndices.toSet() &&
                    classMatches.map { it.geometryIndex }.toSet() == currentGeometryIndices.toSet()
            }.toSet()
        // Budget changes can remove one same-class object while the others remain clearly visible.
        // Preserve strong partial anchors only when no other plausible box overlaps either endpoint.
        val partialAnchoredMatches = mutualUniqueMatches(
            candidates = candidates.filter { it.iou > 0f },
            trackCount = tracks.size,
            geometryCount = geometries.size,
        ).filter { it.iou >= MIN_PARTIAL_ANCHOR_IOU }.toSet()
        val anchoredMatches = mutuallyUniqueAnchoredMatches.filter { candidate ->
            tracks[candidate.trackIndex].className in completeAnchoredClasses || candidate in partialAnchoredMatches
        }
        val remainingMatches = mutualUniqueMatches(
            // Reserving an anchor must not turn an ambiguous center-only alternative into a match.
            candidates = candidates,
            trackCount = tracks.size,
            geometryCount = geometries.size,
        ).filter { candidate ->
            anchoredMatches.none { it.trackIndex == candidate.trackIndex || it.geometryIndex == candidate.geometryIndex }
        }
        val sourceMatches = anchoredMatches + remainingMatches
        val currentConflicts = if (detectorSourceOnly) conflictingFlowIndices(
            geometries, currentGeometries, sourceMatches, timestampMs, currentTimestampMs,
        ) else emptySet()
        // Veto only after source correspondence is resolved. Removing competing candidates
        // earlier could turn a rejected anchor's center-only alternative into a guessed ID.
        sourceMatches.filter { candidate ->
            candidate.geometryIndex !in currentConflicts && currentAnchors.none { anchor ->
                (anchor.trackIndex == candidate.trackIndex && anchor.geometryIndex != candidate.geometryIndex) ||
                    (anchor.geometryIndex == candidate.geometryIndex && anchor.trackIndex != candidate.trackIndex)
            }
        }.forEach { candidate ->
            val track = tracks[candidate.trackIndex]
            val geometry = geometries[candidate.geometryIndex]
            if (detectorSourceOnly) track.markSourceSeen(geometry, timestampMs, minStableAgeFrames)
            else track.markSeen(geometry, timestampMs, minStableAgeFrames)
            matchedTracks[candidate.trackIndex] = true
            matchedGeometries[candidate.geometryIndex] = true
        }

        maskResult.matches.forEach { match ->
            val track = tracks[match.trackIndex]
            track.markSeen(geometries[match.geometryIndex], timestampMs, minStableAgeFrames)
            track.acceptMaskAssociation(requireNotNull(associations[match.geometryIndex]))
            matchedTracks[match.trackIndex] = true
            matchedGeometries[match.geometryIndex] = true
        }
        tracks.forEachIndexed { index, track ->
            if (!matchedTracks[index]) {
                if (index in maskTrackIndices) track.holdMaskAssociation(timestampMs) else track.markMissed()
            }
        }
        geometries.forEachIndexed { index, geometry ->
            if (matchedGeometries[index] || index in maskAmbiguousIndices) return@forEachIndexed
            val track = TrackState(
                trackId = "$trackIdPrefix${nextTrackNumber++}",
                className = geometry.className,
                createdAtMs = timestampMs,
            )
            if (detectorSourceOnly) track.markSourceSeen(geometry, timestampMs, minStableAgeFrames)
            else track.markSeen(geometry, timestampMs, minStableAgeFrames)
            if (index in maskGeometryIndices) track.acceptMaskAssociation(requireNotNull(associations[index]))
            tracks += track
        }
        tracks.removeAll { it.missedFrames > maxMissedFrames }
        return activeTracks()
    }

    fun predictOnly() {
        tracks.forEach { it.markMissed() }
        tracks.removeAll { it.missedFrames > maxMissedFrames }
    }

    fun updateWithAssignments(geometries: List<IndexedObjectGeometry>, timestampMs: Long): List<IndexedTrackAssignment> {
        val counts = geometries.groupingBy { it.sourceDetectionIndex }.eachCount()
        val unique = geometries.filter { it.sourceDetectionIndex >= 0 && counts[it.sourceDetectionIndex] == 1 }
        val observed = update(unique.map { it.geometry }, timestampMs).filter { it.missedFrames == 0 }
        return unique.mapNotNull { indexed ->
            observed.singleOrNull { it.latestGeometry === indexed.geometry }?.let {
                IndexedTrackAssignment(indexed.sourceDetectionIndex, it)
            }
        }
    }

    /** Full source geometry retains deferred IDs; continuous current flow also anchors moving IDs. */
    fun updateSourceWithAssignments(
        geometries: List<IndexedObjectGeometry>, timestampMs: Long,
        currentGeometries: List<IndexedObjectGeometry> = emptyList(), currentTimestampMs: Long = timestampMs,
    ): List<IndexedTrackAssignment> {
        val counts = geometries.groupingBy { it.sourceDetectionIndex }.eachCount()
        val unique = geometries.filter { it.sourceDetectionIndex >= 0 && counts[it.sourceDetectionIndex] == 1 }
        val currentCounts = currentGeometries.groupingBy { it.sourceDetectionIndex }.eachCount()
        val currentBySource = currentGeometries.filter { currentCounts[it.sourceDetectionIndex] == 1 }
            .associate { it.sourceDetectionIndex to it.geometry }
        val currentByIndex = if (currentTimestampMs >= timestampMs) unique.mapIndexedNotNull { index, indexed ->
            currentBySource[indexed.sourceDetectionIndex]?.let { index to it }
        }.toMap() else emptyMap()
        val observed = updateInternal(unique.map { it.geometry }, timestampMs, detectorSourceOnly = true,
            currentGeometries = currentByIndex, currentTimestampMs = currentTimestampMs)
            .filter { it.missedFrames == 0 }
        return unique.mapNotNull { indexed ->
            observed.singleOrNull { it.latestDetectionGeometry === indexed.geometry }?.let {
                IndexedTrackAssignment(indexed.sourceDetectionIndex, it)
            }
        }
    }

    /** Every input retains a result; ambiguous masks receive no guessed persistent identity. */
    fun updateMaskAwareWithAssignments(geometries: List<IndexedObjectGeometry>, timestampMs: Long): List<MaskTrackAssignment> {
        val counts = geometries.groupingBy { it.sourceDetectionIndex }.eachCount()
        val unique = geometries.filter { it.sourceDetectionIndex >= 0 && counts[it.sourceDetectionIndex] == 1 }
        val staleFrame = timestampMs < 0L || lastGeometryObservationAtMs?.let { timestampMs <= it } == true
        if (staleFrame) {
            tracks.filter { it.latestMaskAssociationEvidence != null }.forEach { it.holdMaskAssociation(timestampMs) }
            return geometries.map { MaskTrackAssignment(it.sourceDetectionIndex, null, MaskAssociationStatus.AMBIGUOUS, "FRAME_NOT_NEW") }
        }
        val maskInputs = unique.filter { it.geometry.className == UNNAMED_OBSTACLE_CLASS }
        val cameraTimes = maskInputs.mapNotNull { it.association?.cameraTimestampNs }.distinct()
        val cameraTime = cameraTimes.singleOrNull()
        val validCameraFrame = maskInputs.isEmpty() || (cameraTime != null && cameraTime > 0L &&
            lastMaskCameraTimestampNs?.let { cameraTime > it } != false)
        val associations = unique.map { indexed -> indexed.association?.takeIf {
            validCameraFrame && it.validAt(timestampMs) && it.cameraTimestampNs == cameraTime
        } }
        val observed = updateInternal(unique.map { it.geometry }, timestampMs, associations, maskAware = true)
            .filter { it.missedFrames == 0 }
        if (validCameraFrame && cameraTime != null) lastMaskCameraTimestampNs = cameraTime
        return geometries.map { indexed ->
            val index = unique.indexOfFirst { it === indexed }
            val track = if (index >= 0 && index !in maskAmbiguousIndices)
                observed.singleOrNull { it.latestGeometry === indexed.geometry } else null
            val reason = when {
                index < 0 -> "INVALID_OR_DUPLICATE_SOURCE_INDEX"
                indexed.geometry.className == UNNAMED_OBSTACLE_CLASS && associations[index] == null -> "INVALID_MASK_FRAME_EVIDENCE"
                track == null -> "AMBIGUOUS_MASK_CORRESPONDENCE"
                track.createdAtMs == timestampMs -> "NEW_VISUAL_ID"
                else -> "CONFIDENT_MASK_CORRESPONDENCE"
            }
            MaskTrackAssignment(indexed.sourceDetectionIndex, track,
                if (track == null) MaskAssociationStatus.AMBIGUOUS else if (track.createdAtMs == timestampMs)
                    MaskAssociationStatus.NEW else MaskAssociationStatus.MATCHED, reason)
        }
    }

    fun applyTrackedObservations(
        observations: List<Pair<String, ObjectGeometry>>, timestampMs: Long,
        budgetDeferredTrackIds: Set<String> = emptySet(),
    ): List<TrackState> {
        if (!acceptGeometryTimestamp(timestampMs)) return emptyList()
        val counts = observations.groupingBy { it.first }.eachCount()
        val accepted = observations.mapNotNull { (id, geometry) ->
            if (counts[id] != 1) return@mapNotNull null
            tracks.singleOrNull { it.trackId == id }?.takeIf { it.markTracked(geometry, timestampMs) }
        }
        val acceptedIds = accepted.map { it.trackId }.toSet()
        tracks.filter { it.trackId !in acceptedIds }.forEach {
            if (it.trackId in budgetDeferredTrackIds && it.missedFrames == 0 &&
                timestampMs - it.lastDetectionAtMs in 0L..maxObservationGapMs
            ) it.deferVisualTracking() else it.markMissed()
        }
        tracks.removeAll { it.missedFrames > maxMissedFrames }
        return accepted
    }

    private fun acceptGeometryTimestamp(timestampMs: Long): Boolean {
        if (timestampMs < 0L || lastGeometryObservationAtMs?.let { timestampMs < it } == true) {
            tracks.forEach { it.markMissed() }
            tracks.removeAll { it.missedFrames > maxMissedFrames }
            return false
        }
        lastGeometryObservationAtMs = timestampMs
        return true
    }

    fun activeTracks(): List<TrackState> = tracks.filter { it.missedFrames <= maxMissedFrames }

    fun recordDistance(
        track: TrackState,
        distanceM: Float?,
        source: DepthSource,
        confidence: Float,
        timestampMs: Long,
        cameraPoseEvidence: CameraPoseEvidence? = null,
        objectPositionInAnchor: Vec3? = null,
        depthObservationTimestampNs: Long? = null,
        requireIndependentDepthObservation: Boolean = false,
        proximityOnly: Boolean = false,
    ): Boolean {
        if (distanceM == null || !distanceM.isFinite() || distanceM <= 0f || !source.metric) return false
        return track.recordDistance(
            DistanceObservation(
                timestampMs = timestampMs,
                distanceM = distanceM,
                source = source,
                confidence = confidence.coerceIn(0f, 1f),
                cameraPoseEvidence = cameraPoseEvidence,
                objectPositionInAnchor = objectPositionInAnchor,
                depthObservationTimestampNs = depthObservationTimestampNs,
            ),
            maxDepthJumpM = maxDepthJumpM,
            requireIndependentDepthObservation = requireIndependentDepthObservation,
            proximityOnly = proximityOnly,
        )
    }

    fun approachKinematics(track: TrackState, currentDistanceM: Float?, source: DepthSource): ApproachKinematics {
        if (!source.metric || currentDistanceM == null || !track.stable || track.idSwitchSuspected || !track.metricDistanceReliable) {
            return ApproachKinematics(Trend.UNKNOWN, approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null)
        }
        val usable = track.kinematicSamples()
        if (usable.size < 4) {
            return ApproachKinematics(Trend.UNKNOWN, approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null)
        }
        if (track.requiresIndependentDistance() &&
            (usable.last().timestampMs - usable.first().timestampMs !in 600L..4_000L ||
                usable.zipWithNext().any { (a, b) -> b.timestampMs - a.timestampMs !in 100L..1_500L })
        ) return ApproachKinematics(Trend.UNKNOWN, 0f, null, null)
        if (rotationOnlyMakesAxialTrendAmbiguous(track)) {
            return ApproachKinematics(Trend.UNKNOWN, 0f, null, null)
        }
        val slopeMps = linearSlopeMps(usable) ?: return ApproachKinematics(Trend.UNKNOWN, 0f, null, null)
        val approachSpeed = max(0f, -slopeMps)
        val trend = when {
            slopeMps < -0.10f -> Trend.APPROACHING
            slopeMps > 0.10f -> Trend.RECEDING
            else -> Trend.STABLE
        }
        val ttcMs = if (approachSpeed >= 0.25f) ((currentDistanceM / approachSpeed) * 1000f).toLong() else null
        val motionEstimate = ObjectMotionPolicy.estimate(track)
        return ApproachKinematics(
            trend = trend,
            approachScore = (approachSpeed / 1.5f).coerceIn(0f, 1f),
            approachSpeedMps = if (approachSpeed >= 0.25f) approachSpeed else null,
            timeToCollisionMs = ttcMs,
            objectMotion = ObjectMotionPolicy.classify(track, motionEstimate),
            motionEstimate = motionEstimate,
        )
    }

    /** Narrow gate: good, static 3D support contradicts rotation-induced Z closing. */
    private fun rotationOnlyMakesAxialTrendAmbiguous(track: TrackState): Boolean {
        // Reuse the window's accepted independent spatial observations. Legacy distanceHistory
        // can contain a short display window or timestamps rejected by the information ledger.
        val samples = track.motionSamples()
        if (samples.size < 4 || samples.last().timestampMs - samples.first().timestampMs !in 600L..4_000L ||
            samples.zipWithNext().any { (a, b) ->
                b.timestampMs - a.timestampMs !in 100L..1_500L ||
                    a.depthObservationTimestampNs == null || b.depthObservationTimestampNs == null ||
                    b.depthObservationTimestampNs <= a.depthObservationTimestampNs
            }
        ) return false
        val firstPose = samples.firstOrNull()?.cameraPoseEvidence ?: return false
        val firstPoint = samples.firstOrNull()?.objectPositionInAnchor ?: return false
        fun position(p: CameraPoseEvidence) = Vec3(p.positionX, p.positionY, p.positionZ)
        fun finite(v: Vec3) = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
        if (!finite(firstPoint) || firstPose.referenceId < 0L ||
            samples.mapNotNull {it.depthObservationTimestampNs?.takeIf { stamp -> stamp > 0L }}.distinct().size != samples.size
        ) return false
        var rotated = false
        for (sample in samples) {
            val pose = sample.cameraPoseEvidence ?: return false
            val point = sample.objectPositionInAnchor ?: return false
            if (!sample.source.trustedForStepGuidance || sample.confidence < 0.55f || !sample.confidence.isFinite() ||
                pose.referenceId != firstPose.referenceId || pose.timestampMs != sample.timestampMs ||
                pose.objectCenterInAnchor(Point2(.5f,.5f),1f) == null || !finite(point)) return false
            if ((position(pose)-position(firstPose)).norm() > .03f || (point-firstPoint).norm() > .05f) return false
            val alignment = pose.forwardX*firstPose.forwardX + pose.forwardY*firstPose.forwardY + pose.forwardZ*firstPose.forwardZ
            if (alignment < .9961947f) rotated = true
        }
        return rotated
    }

    private fun sourceMatchIoU(
        track: TrackState, source: ObjectGeometry, sourceTimestampMs: Long,
        currentTimestampMs: Long,
    ): Float? {
        if (track.hasCurrentVisualGeometry && currentTimestampMs - track.lastSeenAtMs in 0L..maxObservationGapMs) {
            // Use the capture itself, or its closest preceding actual observation when this
            // pipeline did not publish that capture. Never substitute a later current box:
            // it could hide source/current identity conflicts between published frames.
            val atSource = track.visualGeometryAtOrBefore(sourceTimestampMs)
            if (atSource != null) return plausibleMatchIoU(track, source, atSource)
        }
        // A budget-deferred track has only detector evidence and retains conservative matching.
        return plausibleMatchIoU(track, source, track.latestDetectionGeometry)
    }

    /** Compare each pair at shared times, retaining the veto for crossing or merged flow. */
    private fun conflictingFlowIndices(
        source: List<ObjectGeometry>, current: Map<Int, ObjectGeometry>,
        sourceMatches: List<MatchingCandidate>, sourceTimestampMs: Long, currentTimestampMs: Long,
    ): Set<Int> {
        val conflicts = mutableSetOf<Int>()
        val indices = current.keys.toList()
        val matchedTracks = sourceMatches.associate { it.geometryIndex to tracks[it.trackIndex] }
        for (first in indices.indices) for (second in first + 1 until indices.size) {
            val a = indices[first]
            val b = indices[second]
            if (source[a].className != source[b].className) continue
            val trackA = matchedTracks[a]
            val trackB = matchedTracks[b]
            // Later published observations can already establish a path around another
            // object. Compare from that common time instead of replaying an older straight line.
            val useLatest = trackA != null && trackB != null && trackA.hasCurrentVisualGeometry &&
                trackB.hasCurrentVisualGeometry && trackA.lastSeenAtMs == trackB.lastSeenAtMs &&
                trackA.lastSeenAtMs in sourceTimestampMs..currentTimestampMs
            val a0 = (if (useLatest) trackA?.latestGeometry else null)?.bboxNorm ?: source[a].bboxNorm
            val b0 = (if (useLatest) trackB?.latestGeometry else null)?.bboxNorm ?: source[b].bboxNorm
            val a1 = current.getValue(a).bboxNorm
            val b1 = current.getValue(b).bboxNorm
            if (hasAmbiguousInterpolatedOverlap(a0, b0, a1, b1)) {
                conflicts += a
                conflicts += b
            }
        }
        return conflicts
    }

    /** Linear interpolation is a conservative identity veto, never a new visual observation. */
    private fun hasAmbiguousInterpolatedOverlap(a0: RectNorm, b0: RectNorm, a1: RectNorm, b1: RectNorm): Boolean {
        fun edges(a: RectNorm, b: RectNorm) = doubleArrayOf(
            a.x.toDouble(), a.x.toDouble() + a.width, b.x.toDouble(), b.x.toDouble() + b.width,
            a.y.toDouble(), a.y.toDouble() + a.height, b.y.toDouble(), b.y.toDouble() + b.height,
        )
        val start = edges(a0, b0)
        val end = edges(a1, b1)
        val delta = DoubleArray(start.size) { end[it] - start[it] }
        val cuts = mutableSetOf(0.0, 1.0)
        // Every same-axis edge crossing changes a possible min/max or zero-overlap boundary.
        for (axis in listOf(0, 4)) for (first in axis until axis + 4) for (second in first + 1 until axis + 4) {
            val relativeDelta = delta[first] - delta[second]
            if (relativeDelta != 0.0) {
                val crossing = (start[second] - start[first]) / relativeDelta
                if (crossing > 0.0 && crossing < 1.0) cuts += crossing
            }
        }
        fun margin(fraction: Double): Double {
            val at = DoubleArray(start.size) { start[it] + delta[it] * fraction }
            val intersection = max(0.0, min(at[1], at[3]) - max(at[0], at[2])) *
                max(0.0, min(at[5], at[7]) - max(at[4], at[6]))
            val areas = (at[1] - at[0]) * (at[5] - at[4]) + (at[3] - at[2]) * (at[7] - at[6])
            // IoU >= threshold iff (1 + threshold) * intersection - threshold * summed areas >= 0.
            return (1.0 + minIoU) * intersection - minIoU * areas
        }
        val orderedCuts = cuts.sorted()
        for (index in 0 until orderedCuts.lastIndex) {
            val left = orderedCuts[index]
            val right = orderedCuts[index + 1]
            val atLeft = margin(left)
            val atRight = margin(right)
            val atMiddle = margin((left + right) / 2.0)
            if (atLeft >= 0.0 || atRight >= 0.0 || atMiddle >= 0.0) return true
            // On this interval the overlap widths, heights and bbox edges are linear, so
            // the margin is quadratic. Only a concave interval can peak above both ends.
            val quadratic = 2.0 * (atLeft + atRight - 2.0 * atMiddle)
            val linear = atRight - atLeft - quadratic
            if (quadratic < 0.0) {
                val vertex = -linear / (2.0 * quadratic)
                if (vertex > 0.0 && vertex < 1.0 && margin(left + (right - left) * vertex) >= 0.0) return true
            }
        }
        return false
    }

    private fun plausibleMatchIoU(
        track: TrackState, geometry: ObjectGeometry, previous: ObjectGeometry? = track.latestGeometry,
    ): Float? {
        if (track.className != geometry.className || track.missedFrames > 0 || previous == null) return null
        if (continuityIsUncertain(previous, geometry)) return null
        val iou = bboxIoU(previous.bboxNorm, geometry.bboxNorm)
        val centerMove = centerDistance(previous.centerNorm, geometry.centerNorm)
        if (iou < minIoU && centerMove > maxCenterMoveNorm) return null
        return iou
    }

    private fun mutualUniqueMatches(
        candidates: List<MatchingCandidate>,
        trackCount: Int,
        geometryCount: Int,
    ): List<MatchingCandidate> {
        val trackDegrees = IntArray(trackCount)
        val geometryDegrees = IntArray(geometryCount)
        candidates.forEach { candidate ->
            trackDegrees[candidate.trackIndex] += 1
            geometryDegrees[candidate.geometryIndex] += 1
        }
        return candidates.filter { candidate ->
            trackDegrees[candidate.trackIndex] == 1 && geometryDegrees[candidate.geometryIndex] == 1
        }
    }

    private data class MatchingCandidate(
        val trackIndex: Int,
        val geometryIndex: Int,
        val iou: Float,
    )
}

private const val MIN_PARTIAL_ANCHOR_IOU = 0.75f

private fun continuityIsUncertain(
    previous: ObjectGeometry,
    current: ObjectGeometry,
): Boolean {
    if (centerDistance(previous.centerNorm, current.centerNorm) > MAX_CONTINUOUS_CENTER_MOVE_NORM) {
        return true
    }
    val previousArea = previous.maskAreaNorm.coerceAtLeast(MIN_COMPARABLE_AREA_NORM)
    val currentArea = current.maskAreaNorm.coerceAtLeast(MIN_COMPARABLE_AREA_NORM)
    return max(previousArea, currentArea) / min(previousArea, currentArea) >
        MAX_CONTINUOUS_AREA_RATIO
}

fun bboxIoU(a: RectNorm, b: RectNorm): Float {
    val left = max(a.x, b.x)
    val top = max(a.y, b.y)
    val right = min(a.x + a.width, b.x + b.width)
    val bottom = min(a.y + a.height, b.y + b.height)
    val intersection = max(0f, right - left) * max(0f, bottom - top)
    val union = a.area + b.area - intersection
    return if (union <= 0f) 0f else (intersection / union).coerceIn(0f, 1f)
}

fun centerDistance(a: Point2, b: Point2): Float {
    return sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2))
}

private fun linearSlopeMps(observations: List<DistanceObservation>): Float? {
    if (observations.size < 2) return null
    val t0 = observations.first().timestampMs
    val xs = observations.map { (it.timestampMs - t0) / 1000f }
    val ys = observations.map { it.distanceM }
    val meanX = xs.average().toFloat()
    val meanY = ys.average().toFloat()
    var numerator = 0f
    var denominator = 0f
    for (index in observations.indices) {
        val dx = xs[index] - meanX
        numerator += dx * (ys[index] - meanY)
        denominator += dx * dx
    }
    if (denominator <= 1e-6f) return null
    return numerator / denominator
}

private fun <T> MutableList<T>.addCapped(value: T, maxSize: Int) {
    add(value)
    while (size > maxSize) removeAt(0)
}

private const val MAX_CONTINUOUS_CENTER_MOVE_NORM = 0.35f
private const val MAX_CONTINUOUS_AREA_RATIO = 4f
private const val MIN_COMPARABLE_AREA_NORM = 0.0001f
