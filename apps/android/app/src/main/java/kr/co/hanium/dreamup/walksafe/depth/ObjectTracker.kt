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
    var metricDistanceReliable: Boolean = true
        private set
    private var distanceContinuity = MetricDistanceContinuityPolicy()
    private val motionWindow = MotionObservationWindow()
    private var independentDistanceRequired = false
    private var distanceBoundaryPending = false
    internal var latestMaskAssociationEvidence: MaskAssociationEvidence? = null
        private set
    private var maskAssociationUncertain = false

    internal fun motionSamples(): List<DistanceObservation> = motionWindow.spatialSamples(lastSeenAtMs)
    internal fun denseMotionHistory(): List<DistanceObservation> = motionWindow.spatialHistory()
    internal fun kinematicSamples(): List<DistanceObservation> = if (independentDistanceRequired) {
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
        latestGeometry = geometry
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

    internal fun acceptMaskAssociation(evidence: MaskAssociationEvidence) {
        val previous = latestMaskAssociationEvidence
        if (previous != null && previous.representativeEpoch != evidence.representativeEpoch) {
            resetMetricAndSpatialHistoryPreservingTrackId(lastSeenAtMs)
        }
        latestMaskAssociationEvidence = evidence
    }

    internal fun holdMaskAssociation(timestampMs: Long) {
        maskAssociationUncertain = true
        metricDistanceReliable = false
        // Keep identity, last confirmed geometry, and bounded history. Never append guessed geometry.
        motionWindow.invalidateAt(maxOf(timestampMs, lastSeenAtMs))
    }

    fun markMissed() {
        missedFrames += 1
        stable = false
        clearMotionHistory()
    }

    /** Updates a current image observation without manufacturing a new semantic confirmation. */
    fun markTracked(geometry: ObjectGeometry, timestampMs: Long): Boolean {
        if (maskAssociationUncertain || missedFrames != 0 || geometry.className != className || timestampMs < lastSeenAtMs) return false
        if (timestampMs == lastSeenAtMs) return latestGeometry == geometry
        if (latestGeometry?.let { continuityIsUncertain(it, geometry) } != false) return false
        latestGeometry = geometry
        lastSeenAtMs = timestampMs
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
        // Keep the information-clock ledger: old raw depth must not become a new sample.
        motionWindow.clearSamples()
        clearDistanceHistory()
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
    ): Boolean {
        if (maskAssociationUncertain) {
            metricDistanceReliable = false
            return false
        }
        independentDistanceRequired = independentDistanceRequired || requireIndependentDepthObservation
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

    private fun clearDistanceHistory() {
        distanceHistory.clear()
        confidenceHistory.clear()
        distanceContinuity = MetricDistanceContinuityPolicy()
    }

    private fun clearMotionHistory() {
        motionWindow.clear()
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
    private var lastMaskCameraTimestampNs: Long? = null
    private var maskAmbiguousIndices = emptySet<Int>()

    fun update(geometries: List<ObjectGeometry>, timestampMs: Long): List<TrackState> =
        updateInternal(geometries, timestampMs)

    private fun updateInternal(geometries: List<ObjectGeometry>, timestampMs: Long,
                               associations: List<MaskAssociationEvidence?> = emptyList(),
                               maskAware: Boolean = false): List<TrackState> {
        if (!acceptGeometryTimestamp(timestampMs)) return activeTracks()
        // A detector stall must not connect old observations into a seemingly continuous track.
        tracks.removeAll { track -> timestampMs - track.lastSeenAtMs > maxObservationGapMs }
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
        val candidates = tracks.flatMapIndexed { trackIndex, track ->
            geometries.mapIndexedNotNull { geometryIndex, geometry ->
                if (trackIndex in maskTrackIndices || geometryIndex in maskGeometryIndices) return@mapIndexedNotNull null
                plausibleMatchIoU(track, geometry)?.let { iou ->
                    MatchingCandidate(trackIndex, geometryIndex, iou)
                }
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
        val anchoredMatches = mutuallyUniqueAnchoredMatches.filter { candidate ->
            tracks[candidate.trackIndex].className in completeAnchoredClasses
        }
        anchoredMatches.forEach { candidate ->
            matchedTracks[candidate.trackIndex] = true
            matchedGeometries[candidate.geometryIndex] = true
        }
        val remainingMatches = mutualUniqueMatches(
            candidates = candidates.filter { candidate ->
                !matchedTracks[candidate.trackIndex] && !matchedGeometries[candidate.geometryIndex]
            },
            trackCount = tracks.size,
            geometryCount = geometries.size,
        )
        (anchoredMatches + remainingMatches).forEach { candidate ->
            tracks[candidate.trackIndex].markSeen(
                geometries[candidate.geometryIndex],
                timestampMs,
                minStableAgeFrames,
            )
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
            track.markSeen(geometry, timestampMs, minStableAgeFrames)
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

    fun applyTrackedObservations(observations: List<Pair<String, ObjectGeometry>>, timestampMs: Long): List<TrackState> {
        if (!acceptGeometryTimestamp(timestampMs)) return emptyList()
        val counts = observations.groupingBy { it.first }.eachCount()
        val accepted = observations.mapNotNull { (id, geometry) ->
            if (counts[id] != 1) return@mapNotNull null
            tracks.singleOrNull { it.trackId == id }?.takeIf { it.markTracked(geometry, timestampMs) }
        }
        val acceptedIds = accepted.map { it.trackId }.toSet()
        tracks.filter { it.trackId !in acceptedIds }.forEach { it.markMissed() }
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

    private fun plausibleMatchIoU(track: TrackState, geometry: ObjectGeometry): Float? {
        if (track.className != geometry.className) return null
        if (track.missedFrames > 0) return null
        val previous = track.latestGeometry ?: return null
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
