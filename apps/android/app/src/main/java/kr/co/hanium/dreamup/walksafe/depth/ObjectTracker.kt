package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualMaterialPointId
import java.util.Collections
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
    /**
     * S11-24: this observation's own distance error proxy (sample IQR / 1.349, the normal-sigma
     * equivalent of the same capture's depth spread). Null or non-positive = unknown.
     */
    val distanceSigmaM: Float? = null,
    /** Same-capture depth attribution in the observation's Frame clock, separate from Image ID. */
    val depthObservedAtFrameMs: Long? = null,
    val materialPointId: VisualMaterialPointId? = null,
)

data class IndexedObjectGeometry(
    val sourceDetectionIndex: Int,
    val geometry: ObjectGeometry,
    val association: MaskAssociationEvidence? = null,
)
data class IndexedTrackAssignment(val sourceDetectionIndex: Int, val track: TrackState)

/** One observed visual box with its capture time, used only for the qualitative trend. */
internal data class VisualTrendSample(val timestampMs: Long, val bboxNorm: RectNorm, val polygonNorm: List<Point2>)

data class ApproachKinematics(
    val trend: Trend,
    val approachScore: Float,
    val approachSpeedMps: Float?,
    /** Legacy numeric approach-time slot, not a qualified prediction of physical collision. */
    val timeToCollisionMs: Long?,
    val objectMotion: ObjectMotion = ObjectMotion.UNKNOWN,
    val motionEstimate: ObjectMotionEstimate = ObjectMotionEstimate(),
) {
    /** Same existing distance/closing-speed time; no second calculation or independent copy state. */
    val approachTimeMs: Long? get() = timeToCollisionMs
    /** Point motion alone cannot prove overlap with the user's passage and object extent. */
    val collisionAssessment: CollisionAssessment get() = CollisionAssessment.UNKNOWN
}

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
    private val classEvidence = LinkedHashMap<String, Float>()

    /**
     * S11-21: detector class with the largest accumulated confidence. Only classes of one compatible
     * group ever reach one track, so this never mixes risk or report meanings.
     */
    val accumulatedClassName: String
        get() = classEvidence.maxByOrNull { it.value }?.key ?: className

    private fun accumulateClassEvidence(geometry: ObjectGeometry) {
        val confidence = geometry.detectionConfidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        classEvidence[geometry.className] = (classEvidence[geometry.className] ?: 0f) + confidence
    }

    internal fun visualObservationAtOrBefore(timestampMs: Long): Pair<Long, ObjectGeometry>? =
        visualGeometryHistory.lastOrNull { it.first <= timestampMs }

    private fun rememberVisualGeometry(geometry: ObjectGeometry, timestampMs: Long) {
        visualGeometryHistory.addCapped(timestampMs to geometry, 64)
    }

    internal fun observeMaterialPointIdentity(id: VisualMaterialPointId?) = motionWindow.observeMaterialPointIdentity(id)

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

    // Stable live views avoid copying history on each frame; only this owner can mutate it.
    private val mutableBboxHistory = mutableListOf<RectNorm>()
    val bboxHistory: List<RectNorm> = Collections.unmodifiableList(mutableBboxHistory)
    private val mutableCenterHistory = mutableListOf<Point2>()
    val centerHistory: List<Point2> = Collections.unmodifiableList(mutableCenterHistory)
    private val mutablePolygonHistory = mutableListOf<List<Point2>>()
    val polygonHistory: List<List<Point2>> = Collections.unmodifiableList(mutablePolygonHistory)
    /** Timed visual boxes for the qualitative trend, pruned by age instead of sample count. */
    private val mutableVisualTrendHistory = mutableListOf<VisualTrendSample>()
    internal val visualTrendHistory: List<VisualTrendSample> = Collections.unmodifiableList(mutableVisualTrendHistory)
    private val mutableDistanceHistory = mutableListOf<DistanceObservation>()
    val distanceHistory: List<DistanceObservation> = Collections.unmodifiableList(mutableDistanceHistory)
    private val mutableConfidenceHistory = mutableListOf<Float>()
    val confidenceHistory: List<Float> = Collections.unmodifiableList(mutableConfidenceHistory)

    fun markSeen(geometry: ObjectGeometry, timestampMs: Long, minStableAgeFrames: Int) {
        val resolvingMaskAmbiguity = maskAssociationUncertain
        latestGeometry?.let { previous ->
            if (continuityIsUncertain(previous, geometry) || timestampMs < lastSeenAtMs) {
                idSwitchSuspected = true
                clearMotionHistory()
            }
        }
        clearImageHistoryOnRotationChange(geometry)
        accumulateClassEvidence(geometry)
        latestGeometry = geometry
        latestDetectionGeometry = geometry
        rememberVisualGeometry(geometry, timestampMs)
        hasCurrentVisualGeometry = false
        lastSeenAtMs = timestampMs
        lastDetectionAtMs = timestampMs
        missedFrames = 0
        ageFrames += 1
        stable = ageFrames >= minStableAgeFrames && !idSwitchSuspected
        mutableBboxHistory.addCapped(geometry.bboxNorm, 12)
        mutableCenterHistory.addCapped(geometry.centerNorm, 12)
        mutablePolygonHistory.addCapped(Collections.unmodifiableList(geometry.polygonNorm), 8)
        rememberTrendSample(geometry, timestampMs)
        if (resolvingMaskAmbiguity) {
            resetMetricAndSpatialHistoryPreservingTrackId(timestampMs)
            maskAssociationUncertain = false
        }
    }

    /** A completed detector capture confirms identity even when flow was not budgeted for it. */
    internal fun markSourceSeen(geometry: ObjectGeometry, timestampMs: Long, minStableAgeFrames: Int) {
        accumulateClassEvidence(geometry)
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
            mutableBboxHistory.addCapped(geometry.bboxNorm, 12)
            mutableCenterHistory.addCapped(geometry.centerNorm, 12)
            mutablePolygonHistory.addCapped(Collections.unmodifiableList(geometry.polygonNorm), 8)
            rememberTrendSample(geometry, timestampMs)
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
            mutableBboxHistory.clear()
            mutableCenterHistory.clear()
            mutablePolygonHistory.clear()
            mutableVisualTrendHistory.clear()
        }
    }

    private fun rememberTrendSample(geometry: ObjectGeometry, timestampMs: Long) {
        mutableVisualTrendHistory.add(VisualTrendSample(
            timestampMs, geometry.bboxNorm, Collections.unmodifiableList(geometry.polygonNorm),
        ))
        mutableVisualTrendHistory.removeAll { timestampMs - it.timestampMs > VISUAL_TREND_RETENTION_MS }
        while (visualTrendHistory.size > MAX_VISUAL_TREND_SAMPLES) mutableVisualTrendHistory.removeAt(0)
    }

    internal fun acceptMaskAssociation(evidence: MaskAssociationEvidence) {
        val previous = latestMaskAssociationEvidence
        if (previous != null && previous.representativeEpoch != evidence.representativeEpoch) {
            resetMetricAndSpatialHistoryPreservingTrackId(lastSeenAtMs)
        }
        latestMaskAssociationEvidence = evidence
    }

    /** A mask track whose correspondence was lost (absent or ambiguous) since its last confirmation. */
    internal val maskAssociationHeld: Boolean get() = maskAssociationUncertain

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
    fun markTracked(geometry: ObjectGeometry, timestampMs: Long): Boolean =
        markTrackedWithContinuity(geometry, timestampMs, null)

    internal fun markTrackedWithContinuity(
        geometry: ObjectGeometry, timestampMs: Long, continuity: TrackedGeometryContinuity?,
    ): Boolean {
        if (maskAssociationUncertain || missedFrames != 0 || !sameAssociationClass(geometry.className, className) ||
            timestampMs < lastSeenAtMs) return false
        if (timestampMs == lastSeenAtMs) {
            val matches = latestGeometry == geometry
            if (matches) hasCurrentVisualGeometry = true
            return matches
        }
        val measuredContinuity = !idSwitchSuspected && continuity?.let { proof ->
            (hasCurrentVisualGeometry && proof.matches(latestGeometry, lastSeenAtMs, geometry, timestampMs)) ||
                proof.matchesDetectorSource(this, geometry, timestampMs)
        } == true
        if (latestGeometry?.let { continuityIsUncertain(it, geometry) } != false && !measuredContinuity) return false
        clearImageHistoryOnRotationChange(geometry)
        latestGeometry = geometry
        rememberVisualGeometry(geometry, timestampMs)
        lastSeenAtMs = timestampMs
        hasCurrentVisualGeometry = true
        mutableBboxHistory.addCapped(geometry.bboxNorm, 12)
        mutableCenterHistory.addCapped(geometry.centerNorm, 12)
        mutablePolygonHistory.addCapped(Collections.unmodifiableList(geometry.polygonNorm), 8)
        rememberTrendSample(geometry, timestampMs)
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

    /**
     * S11-28: the last recorded distance was held for jump confirmation and was nearer than the
     * accepted history by more than the jump tolerance. It is never a trend or TTC sample.
     */
    internal var lastDistancePendingNearerJump: Boolean = false
        private set

    fun recordDistance(
        observation: DistanceObservation,
        maxDepthJumpM: Float,
        requireIndependentDepthObservation: Boolean = false,
        proximityOnly: Boolean = false,
    ): Boolean {
        lastDistancePendingNearerJump = false
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
        lastDistancePendingNearerJump = !decision.accepted &&
            distanceHistory.lastOrNull()?.let { it.distanceM - observation.distanceM > maxDepthJumpM } == true
        if (decision.resetHistory) {
            depthGap.clear()
            mutableDistanceHistory.clear()
            mutableConfidenceHistory.clear()
            motionWindow.clearSamples()
        }
        decision.observationsToAppend.forEach { accepted ->
            mutableDistanceHistory.addCapped(accepted, 12)
            mutableConfidenceHistory.addCapped(accepted.confidence, 12)
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
        mutableDistanceHistory.clear()
        mutableConfidenceHistory.clear()
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
                               currentTimestampMs: Long = timestampMs,
                               pointContinuity: Map<Int, TrackedMaterialPointContinuity> = emptyMap()): List<TrackState> {
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
            .groupBy { associationClassKey(tracks[it].className) }
        val geometryIndicesByClass = geometries.indices.filter { it !in maskGeometryIndices }
            .groupBy { associationClassKey(geometries[it].className) }
        val matchedTracks = BooleanArray(tracks.size)
        val matchedGeometries = BooleanArray(geometries.size)
        val mutuallyUniqueAnchoredMatches = mutualUniqueMatches(
            candidates = candidates.filter { it.iou >= minIoU },
            trackCount = tracks.size,
            geometryCount = geometries.size,
        )
        val anchoredMatchesByClass = mutuallyUniqueAnchoredMatches.groupBy { candidate ->
            associationClassKey(tracks[candidate.trackIndex].className)
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
            associationClassKey(tracks[candidate.trackIndex].className) in completeAnchoredClasses ||
                candidate in partialAnchoredMatches
        }
        val remainingMatches = mutualUniqueMatches(
            // Reserving an anchor must not turn an ambiguous center-only alternative into a match.
            candidates = candidates,
            trackCount = tracks.size,
            geometryCount = geometries.size,
        ).filter { candidate ->
            anchoredMatches.none { it.trackIndex == candidate.trackIndex || it.geometryIndex == candidate.geometryIndex }
        }
        // S11-20: weak neighbors need not erase a complete, strongly separated correspondence.
        // Count changes, tied costs and crossing paths still cannot inherit the old identities.
        val distinctMatches = completeDistinctMatches(candidates,
            eligibleTrackIndicesByClass.filterKeys { it !in completeAnchoredClasses },
            geometryIndicesByClass, geometries, timestampMs, detectorSourceOnly, currentTimestampMs)
        val geometryMatches = (anchoredMatches + remainingMatches + distinctMatches).distinct()
        val pointCandidates = if (detectorSourceOnly) pointContinuity.mapNotNull { (geometryIndex, proof) ->
            val geometry = currentGeometries[geometryIndex] ?: return@mapNotNull null
            val trackIndex = tracks.indices.singleOrNull { index ->
                index !in maskTrackIndices && geometryIndex !in maskGeometryIndices &&
                    proof.matches(tracks[index], geometries[geometryIndex], geometry, timestampMs, currentTimestampMs) &&
                    plausibleMatchIoU(tracks[index], geometry,
                        allowMeasuredSourceHandoff = proof.hasQualifiedSourceHandoff) != null
            } ?: return@mapNotNull null
            MatchingCandidate(trackIndex, geometryIndex,
                bboxIoU(requireNotNull(tracks[trackIndex].latestGeometry).bboxNorm, geometry.bboxNorm))
        } else emptyList()
        val uniquePointMatches = mutualUniqueMatches(pointCandidates, tracks.size, geometries.size)
        // The proof covers the complete still-observed class, not a partial winner whose missing
        // peer could own the point. Resolve before removing any geometric endpoint or candidate.
        val pointMatches = uniquePointMatches.groupBy { associationClassKey(tracks[it.trackIndex].className) }
            .flatMap { (className, matches) ->
                if (matches.size >= 2 &&
                    matches.map { it.trackIndex }.toSet() == eligibleTrackIndicesByClass[className].orEmpty().toSet() &&
                    matches.map { it.geometryIndex }.toSet() == geometryIndicesByClass[className].orEmpty().toSet()
                ) matches else emptyList()
            }
        val sourceMatches = (pointMatches + geometryMatches.filter { candidate ->
            pointMatches.none { it.trackIndex == candidate.trackIndex || it.geometryIndex == candidate.geometryIndex }
        }).distinct()
        val currentConflicts = if (detectorSourceOnly) conflictingFlowIndices(
            geometries, currentGeometries, sourceMatches, timestampMs, currentTimestampMs,
        ) else emptySet()
        // Veto only after source correspondence is resolved. Removing competing candidates
        // earlier could turn a rejected anchor's center-only alternative into a guessed ID.
        sourceMatches.filter { candidate ->
            candidate in pointMatches || (candidate.geometryIndex !in currentConflicts && currentAnchors.none { anchor ->
                (anchor.trackIndex == candidate.trackIndex && anchor.geometryIndex != candidate.geometryIndex) ||
                    (anchor.geometryIndex == candidate.geometryIndex && anchor.trackIndex != candidate.trackIndex)
            })
        }.forEach { candidate ->
            val track = tracks[candidate.trackIndex]
            val geometry = geometries[candidate.geometryIndex]
            if (detectorSourceOnly) track.markSourceSeen(geometry, timestampMs, minStableAgeFrames)
            else track.markSeen(geometry, timestampMs, minStableAgeFrames)
            matchedTracks[candidate.trackIndex] = true
            matchedGeometries[candidate.geometryIndex] = true
        }

        // S11-19 / FP-019 design_rules[3]: a mask that was missing or crossed (held) and is seen again
        // starts a new observation number; its count, stability and distance history start over.
        val reappearedMaskTracks = mutableListOf<TrackState>()
        maskResult.matches.forEach { match ->
            val track = tracks[match.trackIndex]
            matchedTracks[match.trackIndex] = true
            matchedGeometries[match.geometryIndex] = true
            val geometry = geometries[match.geometryIndex]
            val association = requireNotNull(associations[match.geometryIndex])
            if (track.maskAssociationHeld) {
                reappearedMaskTracks += track
                tracks += TrackState(
                    trackId = "$trackIdPrefix${nextTrackNumber++}",
                    className = geometry.className,
                    createdAtMs = timestampMs,
                ).also {
                    it.markSeen(geometry, timestampMs, minStableAgeFrames)
                    it.acceptMaskAssociation(association)
                }
                return@forEach
            }
            track.markSeen(geometry, timestampMs, minStableAgeFrames)
            track.acceptMaskAssociation(association)
        }
        tracks.forEachIndexed { index, track ->
            if (index < matchedTracks.size && !matchedTracks[index]) {
                if (index in maskTrackIndices) track.holdMaskAssociation(timestampMs) else track.markMissed()
            }
        }
        tracks.removeAll { track -> reappearedMaskTracks.any { it === track } }
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
    ): List<IndexedTrackAssignment> = updateSourceAssignments(
        geometries, timestampMs, currentGeometries, currentTimestampMs, emptyMap(),
    )

    internal fun updateSourceWithMaterialPointAssignments(
        geometries: List<IndexedObjectGeometry>, timestampMs: Long,
        currentGeometries: List<IndexedObjectGeometry>, currentTimestampMs: Long,
        continuityBySourceIndex: Map<Int, TrackedMaterialPointContinuity>,
    ): List<IndexedTrackAssignment> = updateSourceAssignments(
        geometries, timestampMs, currentGeometries, currentTimestampMs, continuityBySourceIndex,
    )

    private fun updateSourceAssignments(
        geometries: List<IndexedObjectGeometry>, timestampMs: Long,
        currentGeometries: List<IndexedObjectGeometry>, currentTimestampMs: Long,
        continuityBySourceIndex: Map<Int, TrackedMaterialPointContinuity>,
    ): List<IndexedTrackAssignment> {
        val counts = geometries.groupingBy { it.sourceDetectionIndex }.eachCount()
        val unique = geometries.filter { it.sourceDetectionIndex >= 0 && counts[it.sourceDetectionIndex] == 1 }
        val currentCounts = currentGeometries.groupingBy { it.sourceDetectionIndex }.eachCount()
        val currentBySource = currentGeometries.filter { currentCounts[it.sourceDetectionIndex] == 1 }
            .associate { it.sourceDetectionIndex to it.geometry }
        val currentByIndex = if (currentTimestampMs >= timestampMs) unique.mapIndexedNotNull { index, indexed ->
            currentBySource[indexed.sourceDetectionIndex]?.let { index to it }
        }.toMap() else emptyMap()
        val pointByIndex = unique.mapIndexedNotNull { index, indexed ->
            continuityBySourceIndex[indexed.sourceDetectionIndex]?.let { index to it }
        }.toMap()
        val observed = updateInternal(unique.map { it.geometry }, timestampMs, detectorSourceOnly = true,
            currentGeometries = currentByIndex, currentTimestampMs = currentTimestampMs, pointContinuity = pointByIndex)
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
    ): List<TrackState> = applyTrackedObservationsWithContinuity(
        observations, timestampMs, budgetDeferredTrackIds, emptyMap(),
    )

    internal fun applyTrackedObservationsWithContinuity(
        observations: List<Pair<String, ObjectGeometry>>, timestampMs: Long,
        budgetDeferredTrackIds: Set<String>, continuityByTrackId: Map<String, TrackedGeometryContinuity>,
    ): List<TrackState> {
        if (!acceptGeometryTimestamp(timestampMs)) return emptyList()
        val counts = observations.groupingBy { it.first }.eachCount()
        val accepted = observations.mapNotNull { (id, geometry) ->
            if (counts[id] != 1) return@mapNotNull null
            tracks.singleOrNull { it.trackId == id }?.takeIf {
                it.markTrackedWithContinuity(geometry, timestampMs, continuityByTrackId[id])
            }
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
        distanceSigmaM: Float? = null,
        depthObservedAtFrameMs: Long? = null,
        materialPointId: VisualMaterialPointId? = null,
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
                distanceSigmaM = distanceSigmaM,
                depthObservedAtFrameMs = depthObservedAtFrameMs,
                materialPointId = materialPointId,
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
        if (rotationMakesAxialTrendAmbiguous(track)) {
            return ApproachKinematics(Trend.UNKNOWN, 0f, null, null)
        }
        val fit = linearDistanceTrend(usable) ?: return ApproachKinematics(Trend.UNKNOWN, 0f, null, null)
        val slopeMps = fit.slopeMps
        val approachSpeed = max(0f, -slopeMps)
        val trend = when {
            slopeMps < -0.10f -> Trend.APPROACHING
            slopeMps > 0.10f -> Trend.RECEDING
            else -> Trend.STABLE
        }
        // S11-24: speed and TTC need an approach that stands out from the history's own residual
        // scatter (about two standard errors). One in-tolerance outlier or alternating surfaces
        // can tilt the fitted line, but then the slope is not distinguishable from zero.
        val speedSupported = approachSpeed >= 0.25f &&
            fit.slopeStdErrMps?.let { approachSpeed >= TTC_MIN_SPEED_TO_STD_ERR * it } == true
        val motionEstimate = ObjectMotionPolicy.estimate(track)
        // Axial depth may decrease while a lateral relative trajectory already moves away.
        // Preserve its distance trend, but do not publish an approach countdown against the
        // current, independently measured spatial evidence.
        val spatiallyNonClosing = motionEstimate.observedAtMs == track.lastSeenAtMs &&
            motionEstimate.confidence >= 0.55f &&
            motionEstimate.relativeClosingSpeedMps?.let { it.isFinite() && it <= 0f } == true
        val approachTimeMs = if (speedSupported && !spatiallyNonClosing)
            ((currentDistanceM / approachSpeed) * 1000f).toLong() else null
        return ApproachKinematics(
            trend = trend,
            approachScore = (approachSpeed / 1.5f).coerceIn(0f, 1f),
            approachSpeedMps = if (speedSupported) approachSpeed else null,
            timeToCollisionMs = approachTimeMs,
            objectMotion = ObjectMotionPolicy.classify(track, motionEstimate),
            motionEstimate = motionEstimate,
        )
    }

    /** Axial closing cannot establish TTC while the accepted camera poses rotate, including walking. */
    private fun rotationMakesAxialTrendAmbiguous(track: TrackState): Boolean {
        // Camera rotation concerns axial distance even without a material-point motion proof.
        // Independent depth admission still rejects repeated/reprojected information.
        val samples = track.kinematicSamples()
        if (samples.size < 4 || samples.last().timestampMs - samples.first().timestampMs !in 600L..4_000L ||
            samples.zipWithNext().any { (a, b) ->
                b.timestampMs - a.timestampMs !in 100L..1_500L ||
                    a.depthObservationTimestampNs == null || b.depthObservationTimestampNs == null ||
                    b.depthObservationTimestampNs <= a.depthObservationTimestampNs
            }
        ) return false
        val firstPose = samples.firstOrNull()?.cameraPoseEvidence ?: return false
        if (firstPose.referenceId < 0L ||
            samples.mapNotNull {it.depthObservationTimestampNs?.takeIf { stamp -> stamp > 0L }}.distinct().size != samples.size
        ) return false
        var rotated = false
        for (sample in samples) {
            val pose = sample.cameraPoseEvidence ?: return false
            if (!sample.source.trustedForStepGuidance || sample.confidence < 0.55f || !sample.confidence.isFinite() ||
                pose.referenceId != firstPose.referenceId || pose.timestampMs != sample.timestampMs ||
                pose.objectCenterInAnchor(Point2(.5f,.5f),1f) == null) return false
            val alignment = pose.forwardX*firstPose.forwardX + pose.forwardY*firstPose.forwardY + pose.forwardZ*firstPose.forwardZ
            if (alignment < .9961947f) rotated = true
        }
        return rotated
    }

    private fun sourceMatchIoU(
        track: TrackState, source: ObjectGeometry, sourceTimestampMs: Long,
        currentTimestampMs: Long,
    ): Float? = plausibleMatchIoU(track, source,
        sourceReference(track, sourceTimestampMs, currentTimestampMs)?.second)

    private fun sourceReference(
        track: TrackState, sourceTimestampMs: Long, currentTimestampMs: Long,
    ): Pair<Long, ObjectGeometry>? {
        if (track.hasCurrentVisualGeometry && currentTimestampMs - track.lastSeenAtMs in 0L..maxObservationGapMs) {
            // Never substitute a later current box for evidence preceding this capture.
            track.visualObservationAtOrBefore(sourceTimestampMs)?.let { return it }
        }
        return track.latestDetectionGeometry?.let { track.lastDetectionAtMs to it }
    }

    /** Only a complete class population can use dominant costs instead of graph degree one. */
    private fun completeDistinctMatches(
        candidates: List<MatchingCandidate>, tracksByClass: Map<String, List<Int>>,
        geometriesByClass: Map<String, List<Int>>, geometries: List<ObjectGeometry>,
        timestampMs: Long, detectorSourceOnly: Boolean, currentTimestampMs: Long,
    ): List<MatchingCandidate> {
        val byTrack = candidates.groupBy { it.trackIndex }
        val byGeometry = candidates.groupBy { it.geometryIndex }
        return tracksByClass.flatMap classMatches@ { (className, trackIndices) ->
            val geometryIndices = geometriesByClass[className].orEmpty()
            if (trackIndices.size < 2 || trackIndices.size != geometryIndices.size) return@classMatches emptyList()
            val winners = trackIndices.mapNotNull { index ->
                val choices = byTrack[index].orEmpty()
                val best = choices.maxByOrNull { it.iou } ?: return@mapNotNull null
                // Reuse the existing strong-anchor floor and base IoU tolerance as the minimum
                // winner/runner-up separation on BOTH endpoints. No center-only guess is admitted.
                if (!best.iou.isFinite() || best.iou < MIN_PARTIAL_ANCHOR_IOU ||
                    choices.any { it != best && (!it.iou.isFinite() || best.iou - it.iou <= minIoU) } ||
                    byGeometry[best.geometryIndex].orEmpty().any {
                        it != best && (!it.iou.isFinite() || best.iou - it.iou <= minIoU)
                    }) return@mapNotNull null
                best
            }
            if (winners.size != trackIndices.size ||
                winners.map { it.geometryIndex }.toSet() != geometryIndices.toSet()) return@classMatches emptyList()
            val references = winners.map { candidate ->
                val track = tracks[candidate.trackIndex]
                if (detectorSourceOnly) sourceReference(track, timestampMs, currentTimestampMs)
                else track.latestGeometry?.let { track.lastSeenAtMs to it }
            }
            // Different observation times cannot define one shared pair trajectory.
            if (references.any { it == null } || references.map { it?.first }.distinct().size != 1 ||
                references.any { timestampMs - requireNotNull(it).first !in 0L..maxObservationGapMs }
            ) return@classMatches emptyList()
            for (first in winners.indices) for (second in first + 1 until winners.size) {
                if (hasAmbiguousInterpolatedOverlap(
                        requireNotNull(references[first]).second.bboxNorm,
                        requireNotNull(references[second]).second.bboxNorm,
                        geometries[winners[first].geometryIndex].bboxNorm,
                        geometries[winners[second].geometryIndex].bboxNorm,
                    )) return@classMatches emptyList()
            }
            winners
        }
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
            if (!sameAssociationClass(source[a].className, source[b].className)) continue
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
        allowMeasuredSourceHandoff: Boolean = false,
    ): Float? {
        if (!sameAssociationClass(track.className, geometry.className) || track.missedFrames > 0 || previous == null) return null
        if (continuityIsUncertain(previous, geometry, allowMeasuredSourceHandoff)) return null
        val iou = bboxIoU(previous.bboxNorm, geometry.bboxNorm)
        val centerMove = centerDistance(previous.centerNorm, geometry.centerNorm)
        if (!allowMeasuredSourceHandoff && iou < minIoU && centerMove > maxCenterMoveNorm) return null
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

/**
 * S11-21 (originals/11.txt:202-205): spatial identity is kept across a class change only inside this
 * compatible group: the vehicle classes that share the spoken label and risk meaning ("차량",
 * [kr.co.hanium.dreamup.walksafe.inference.ObstacleLabels]). Motorcycles (size and speed differ),
 * tactile, e-scooter, crosswalk and report classes never join another class.
 */
private val COMPATIBLE_VEHICLE_CLASSES = setOf("car", "passenger_car", "bus", "truck")
private const val VEHICLE_ASSOCIATION_KEY = "vehicle"

internal fun associationClassKey(className: String): String =
    if (className.lowercase() in COMPATIBLE_VEHICLE_CLASSES) VEHICLE_ASSOCIATION_KEY else className

internal fun sameAssociationClass(first: String, second: String): Boolean =
    first == second || associationClassKey(first) == associationClassKey(second)
private const val TTC_MIN_SPEED_TO_STD_ERR = 2f

private fun continuityIsUncertain(
    previous: ObjectGeometry,
    current: ObjectGeometry,
    allowMeasuredSourceHandoff: Boolean = false,
): Boolean {
    // Only the completed source-bound common-image proof may replace these bbox heuristics.
    // Its measured point identity, source clocks and both original geometries were validated first.
    if (allowMeasuredSourceHandoff) return false
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

/** Least-squares distance slope and its residual standard error (null below three samples). */
private data class LinearDistanceTrend(val slopeMps: Float, val slopeStdErrMps: Float?)

/**
 * S11-24 (originals/11.txt:226-230): weighted least squares with each observation's own error
 * proxy (weight 1/sigma^2) when every sample carries one, so one noisy capture tilts the line less.
 * The residual scale still sets the slope standard error. With no or unequal knowledge (any sample
 * without a positive sigma) all weights are equal, which is exactly the ordinary fit.
 */
private fun linearDistanceTrend(observations: List<DistanceObservation>): LinearDistanceTrend? {
    if (observations.size < 2) return null
    val t0 = observations.first().timestampMs
    val xs = observations.map { (it.timestampMs - t0) / 1000f }
    val ys = observations.map { it.distanceM }
    val sigmas = observations.map { it.distanceSigmaM }
    val weights = if (sigmas.all { it != null && it.isFinite() && it > 0f }) {
        val raw = sigmas.map { sigma -> 1f / (sigma!! * sigma) }
        // Relative weights only (mean 1): the regression scale comes from the residuals.
        val mean = raw.average().toFloat()
        if (mean.isFinite() && mean > 0f) raw.map { it / mean } else List(observations.size) { 1f }
    } else List(observations.size) { 1f }
    val weightSum = weights.sum()
    var meanX = 0f
    var meanY = 0f
    for (index in observations.indices) {
        meanX += weights[index] * xs[index]
        meanY += weights[index] * ys[index]
    }
    meanX /= weightSum
    meanY /= weightSum
    var numerator = 0f
    var denominator = 0f
    for (index in observations.indices) {
        val dx = xs[index] - meanX
        numerator += weights[index] * dx * (ys[index] - meanY)
        denominator += weights[index] * dx * dx
    }
    if (denominator <= 1e-6f) return null
    val slope = numerator / denominator
    val stdErr = if (observations.size >= 3) {
        var residualSquares = 0f
        for (index in observations.indices) {
            val residual = ys[index] - (meanY + slope * (xs[index] - meanX))
            residualSquares += weights[index] * residual * residual
        }
        sqrt(residualSquares / (observations.size - 2) / denominator)
    } else null
    return LinearDistanceTrend(slope, stdErr)
}

private fun <T> MutableList<T>.addCapped(value: T, maxSize: Int) {
    add(value)
    while (size > maxSize) removeAt(0)
}

internal const val VISUAL_TREND_RETENTION_MS = 1_500L
private const val MAX_VISUAL_TREND_SAMPLES = 96
private const val MAX_CONTINUOUS_CENTER_MOVE_NORM = 0.35f
private const val MAX_CONTINUOUS_AREA_RATIO = 4f
private const val MIN_COMPARABLE_AREA_NORM = 0.0001f
