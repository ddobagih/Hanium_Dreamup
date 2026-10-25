package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualAffineTransform
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingObservation
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingStatus

/** A current image observation derived from one completed detector capture, never a new detection. */
class CurrentTrackedFrameObservation private constructor(
    val sourceKey: VisualFrameKey,
    val targetKey: VisualFrameKey,
    val sourceDetections: List<DetectionCandidate>,
    val observations: List<VisualTrackingObservation>,
) {
    val timestampMs: Long get() = targetKey.frameId / 1_000_000L
    val trackedObservations: List<VisualTrackingObservation>
        get() = observations.filter { it.status == VisualTrackingStatus.TRACKED }

    fun isFreshAt(nowElapsedRealtimeMs: Long): Boolean =
        nowElapsedRealtimeMs >= targetKey.capturedAtElapsedRealtimeMs &&
            nowElapsedRealtimeMs - sourceKey.capturedAtElapsedRealtimeMs in 0L..MAX_SOURCE_AGE_MS

    fun matchesDepthSnapshot(snapshot: DepthFrameSnapshot): Boolean =
        snapshot.frameTimestampNs == targetKey.frameId &&
            snapshot.cameraImageTimestampNs == targetKey.cameraTimestampNs &&
            (snapshot.cameraPoseEvidence == null || snapshot.cameraPoseEvidence.timestampMs == timestampMs)

    internal fun measuredGeometry(sourceIndex: Int, extractor: MaskPolygonExtractor,
        imageQuarterTurns: Int?): ObjectGeometry? {
        val source = sourceDetections.getOrNull(sourceIndex) ?: return null
        val row = observations.singleOrNull { it.sourceIndex == sourceIndex } ?: return null
        if (row.status != VisualTrackingStatus.TRACKED || row.failure != null ||
            row.detectorSourceKey != sourceKey || row.trackedTargetKey != targetKey ||
            row.trackingQuality < CurrentTrackedFrameObservation.MIN_QUALITATIVE_TRACKING_QUALITY
        ) return null
        val transform = row.transformNorm ?: if (targetKey == sourceKey)
            VisualAffineTransform() else return null
        if (listOf(transform.a, transform.b, transform.c, transform.d, transform.tx, transform.ty)
                .any { !it.isFinite() } || transform.areaScale <= 0f
        ) return null
        val expected = mappedDetection(source, transform)
        if (row.geometry != expected) return null
        return extractor.extract(expected, imageQuarterTurns)
    }

    companion object {
        const val MAX_SOURCE_AGE_MS = 800L
        /** Existing qualitative depth-gap floor, also used for distance-free RGB references. */
        internal const val MIN_QUALITATIVE_TRACKING_QUALITY = 0.65f

        fun fromTrackingResult(
            result: VisualTrackingResult,
            sourceDetections: List<DetectionCandidate>,
            sourceDetectionCompleted: Boolean,
            nowElapsedRealtimeMs: Long,
        ): CurrentTrackedFrameObservation? {
            if (!sourceDetectionCompleted) return null
            val source = result.detectorSourceKey
            val target = result.trackedTargetKey
            if (source.frameId <= 0L || target.frameId <= 0L ||
                source.cameraTimestampNs <= 0L || target.cameraTimestampNs <= 0L ||
                source.capturedAtElapsedRealtimeMs < 0L ||
                source.epoch != target.epoch || source.geometryVersion != target.geometryVersion ||
                target.frameId - source.frameId !in 0L..MAX_SOURCE_AGE_MS * 1_000_000L ||
                target.cameraTimestampNs < source.cameraTimestampNs ||
                target.capturedAtElapsedRealtimeMs < source.capturedAtElapsedRealtimeMs
            ) return null
            val observations = result.observations
            // Every authoritative source index must be accounted for, including explicit LOST.
            if (observations.map { it.sourceIndex }.sorted() != sourceDetections.indices.toList()) return null
            if (observations.any { observation ->
                    observation.detectorSourceKey != source || observation.trackedTargetKey != target ||
                        when (observation.status) {
                            VisualTrackingStatus.LOST -> observation.geometry != null || observation.materialPoint != null
                            VisualTrackingStatus.TRACKED -> {
                                val geometry = observation.geometry
                                val original = sourceDetections[observation.sourceIndex]
                                geometry == null || observation.failure != null ||
                                    !observation.trackingQuality.isFinite() || observation.trackingQuality !in 0f..1f ||
                                    observation.trackingQuality <= 0f ||
                                    geometry.className != original.className ||
                                    geometry.detectionConfidence != original.detectionConfidence ||
                                    observation.materialPoint?.let { point ->
                                        point.targetKey != target || !point.imagePointNorm.x.isFinite() ||
                                            !point.imagePointNorm.y.isFinite() || point.imagePointNorm.x !in 0f..1f ||
                                            point.imagePointNorm.y !in 0f..1f
                                    } == true
                            }
                        }
                }
            ) return null
            return CurrentTrackedFrameObservation(
                source, target, sourceDetections.toList(), observations.toList(),
            ).takeIf { it.isFreshAt(nowElapsedRealtimeMs) }
        }
    }
}

/** Measured geometry bound to current image targets or its actual assigned detector source. */
internal class TrackedGeometryContinuity private constructor(
    private val previousGeometry: ObjectGeometry,
    private val currentGeometry: ObjectGeometry,
    private val previousTimestampMs: Long,
    private val currentTimestampMs: Long,
    private val sourceTrackId: String? = null,
) {
    fun matches(previous: ObjectGeometry?, previousAtMs: Long, current: ObjectGeometry, currentAtMs: Long): Boolean =
        sourceTrackId == null && previous == previousGeometry && current == currentGeometry &&
            previousAtMs == previousTimestampMs && currentAtMs == currentTimestampMs

    fun matchesDetectorSource(track: TrackState, current: ObjectGeometry, currentAtMs: Long): Boolean =
        sourceTrackId != null && track.trackId == sourceTrackId && !track.hasCurrentVisualGeometry &&
            track.latestDetectionGeometry == previousGeometry && track.lastDetectionAtMs == previousTimestampMs &&
            track.latestGeometry == previousGeometry && track.lastSeenAtMs == previousTimestampMs &&
            current == currentGeometry && currentAtMs == currentTimestampMs

    companion object {
        internal fun fromQualifiedSourceHandoff(
            proof: TrackedMaterialPointContinuity, assignedTrackId: String,
        ): TrackedGeometryContinuity? = if (proof.hasQualifiedSourceHandoff &&
            assignedTrackId == proof.previousTrackId && proof.sourceTimestampMs < proof.previousTimestampMs
        ) TrackedGeometryContinuity(proof.previousGeometry, proof.currentGeometry,
            proof.previousTimestampMs, proof.currentTimestampMs) else null

        /** Only the actual assigned source-time track may start from this validated affine. */
        fun fromDetectorSource(
            current: CurrentTrackedFrameObservation, sourceIndex: Int, assignedTrackId: String,
            extractor: MaskPolygonExtractor, imageQuarterTurns: Int?,
        ): TrackedGeometryContinuity? {
            if (imageQuarterTurns == null || imageQuarterTurns !in 0..3 ||
                current.targetKey.frameId <= current.sourceKey.frameId ||
                current.targetKey.cameraTimestampNs <= current.sourceKey.cameraTimestampNs ||
                !current.isFreshAt(current.targetKey.capturedAtElapsedRealtimeMs)
            ) return null
            val row = current.observations.singleOrNull { it.sourceIndex == sourceIndex } ?: return null
            if (row.originalFeatureCount < 6 || row.survivingFeatureCount < 6) return null
            val after = current.measuredGeometry(sourceIndex, extractor, imageQuarterTurns) ?: return null
            val source = current.sourceDetections.getOrNull(sourceIndex) ?: return null
            return TrackedGeometryContinuity(extractor.extract(source, imageQuarterTurns), after,
                current.sourceKey.frameId / 1_000_000L, current.timestampMs, assignedTrackId)
        }

        fun from(
            previous: CurrentTrackedFrameObservation?, current: CurrentTrackedFrameObservation,
            sourceIndex: Int, extractor: MaskPolygonExtractor, imageQuarterTurns: Int?,
        ): TrackedGeometryContinuity? {
            if (previous == null || previous.sourceKey != current.sourceKey ||
                previous.sourceDetections != current.sourceDetections ||
                current.targetKey.frameId <= previous.targetKey.frameId ||
                current.targetKey.cameraTimestampNs <= previous.targetKey.cameraTimestampNs ||
                current.targetKey.capturedAtElapsedRealtimeMs < previous.targetKey.capturedAtElapsedRealtimeMs
            ) return null
            val before = previous.measuredGeometry(sourceIndex, extractor, imageQuarterTurns) ?: return null
            val after = current.measuredGeometry(sourceIndex, extractor, imageQuarterTurns) ?: return null
            return TrackedGeometryContinuity(before, after, previous.timestampMs, current.timestampMs)
        }
    }
}

/** An existing assigned track, bound to unique measured points before source association. */
internal class TrackedMaterialPointContinuity private constructor(
    internal val previousTrackId: String,
    private val previousSourceGeometry: ObjectGeometry,
    private val currentSourceGeometry: ObjectGeometry,
    internal val previousGeometry: ObjectGeometry,
    internal val currentGeometry: ObjectGeometry,
    private val previousSourceTimestampMs: Long,
    internal val sourceTimestampMs: Long,
    internal val previousTimestampMs: Long,
    internal val currentTimestampMs: Long,
    val hasQualifiedSourceHandoff: Boolean,
) {
    /** Late source detections do not rewind the track's last measured geometry. */
    fun currentGeometryContinuity(assignedTrackId: String): TrackedGeometryContinuity? =
        TrackedGeometryContinuity.fromQualifiedSourceHandoff(this, assignedTrackId)

    fun matches(track: TrackState, source: ObjectGeometry, current: ObjectGeometry,
        sourceAtMs: Long, currentAtMs: Long): Boolean =
        track.trackId == previousTrackId && track.missedFrames == 0 && !track.idSwitchSuspected &&
            track.hasCurrentVisualGeometry && track.latestGeometry == previousGeometry &&
            track.lastSeenAtMs == previousTimestampMs && track.latestDetectionGeometry == previousSourceGeometry &&
            track.lastDetectionAtMs == previousSourceTimestampMs && source == currentSourceGeometry &&
            current == currentGeometry &&
            sourceAtMs == sourceTimestampMs && currentAtMs == currentTimestampMs

    companion object {
        fun fromSourceChange(
            previous: CurrentTrackedFrameObservation?, current: CurrentTrackedFrameObservation,
            previousAssignments: Map<Int, String>, extractor: MaskPolygonExtractor, imageQuarterTurns: Int?,
        ): Map<Int, TrackedMaterialPointContinuity> {
            if (previous == null || imageQuarterTurns == null || imageQuarterTurns !in 0..3 || previous.sourceKey == current.sourceKey ||
                previous.targetKey.epoch != current.targetKey.epoch ||
                previous.targetKey.geometryVersion != current.targetKey.geometryVersion ||
                current.sourceKey.frameId <= previous.sourceKey.frameId ||
                current.sourceKey.cameraTimestampNs <= previous.sourceKey.cameraTimestampNs ||
                current.sourceKey.capturedAtElapsedRealtimeMs < previous.sourceKey.capturedAtElapsedRealtimeMs ||
                current.targetKey.frameId <= previous.targetKey.frameId ||
                current.targetKey.cameraTimestampNs <= previous.targetKey.cameraTimestampNs ||
                current.targetKey.capturedAtElapsedRealtimeMs < previous.targetKey.capturedAtElapsedRealtimeMs ||
                !previous.isFreshAt(current.targetKey.capturedAtElapsedRealtimeMs)
            ) return emptyMap()
            fun geometries(frame: CurrentTrackedFrameObservation): Map<Int, ObjectGeometry>? {
                val result = frame.observations.associate { row ->
                    row.sourceIndex to (frame.measuredGeometry(row.sourceIndex, extractor, imageQuarterTurns)
                        ?.takeIf { row.originalFeatureCount >= 6 && row.survivingFeatureCount >= 6 })
                }
                if (result.values.any { it == null }) return null
                return result.mapValues { requireNotNull(it.value) }
            }
            val beforeGeometry = geometries(previous) ?: return emptyMap()
            val afterGeometry = geometries(current) ?: return emptyMap()
            fun ownsPoint(frame: CurrentTrackedFrameObservation,
                row: VisualTrackingObservation): Boolean {
                val point = row.materialPoint ?: return false
                if (point.targetKey != frame.targetKey || !contains(requireNotNull(row.geometry), point.imagePointNorm)) return false
                return frame.observations.none { peer ->
                    peer.sourceIndex != row.sourceIndex && contains(requireNotNull(peer.geometry), point.imagePointNorm)
                }
            }
            fun uniquePoints(frame: CurrentTrackedFrameObservation): Boolean {
                val points = frame.observations.mapNotNull { it.materialPoint }
                return points.map { it.id }.distinct().size == points.size
            }
            if (!uniquePoints(previous) || !uniquePoints(current)) return emptyMap()
            val result = mutableMapOf<Int, TrackedMaterialPointContinuity>()
            current.observations.groupBy { requireNotNull(it.geometry).className }.forEach { (className, rows) ->
                // This is a complete repeated-class proof, not a new single-class matching policy.
                if (className == UNNAMED_OBSTACLE_CLASS || rows.size < 2) return@forEach
                val oldRows = previous.observations.filter { it.geometry?.className == className }
                if (oldRows.size != rows.size || rows.any { !ownsPoint(current, it) } ||
                    oldRows.any { !ownsPoint(previous, it) }) return@forEach
                val oldIds = oldRows.map { requireNotNull(it.materialPoint).id }.toSet()
                if (rows.map { requireNotNull(it.materialPoint).id }.toSet() != oldIds) return@forEach
                val assigned = oldRows.mapNotNull { previousAssignments[it.sourceIndex] }
                if (assigned.size != oldRows.size || assigned.distinct().size != assigned.size) return@forEach
                rows.forEach { row ->
                    val old = oldRows.single { it.materialPoint!!.id === row.materialPoint!!.id }
                    result[row.sourceIndex] = TrackedMaterialPointContinuity(
                        requireNotNull(previousAssignments[old.sourceIndex]),
                        extractor.extract(previous.sourceDetections[old.sourceIndex], imageQuarterTurns),
                        extractor.extract(current.sourceDetections[row.sourceIndex], imageQuarterTurns),
                        requireNotNull(beforeGeometry[old.sourceIndex]), requireNotNull(afterGeometry[row.sourceIndex]),
                        previous.sourceKey.frameId / 1_000_000L, current.sourceKey.frameId / 1_000_000L,
                        previous.timestampMs, current.timestampMs,
                        qualifiedSourceHandoff(previous, current, old, row, imageQuarterTurns),
                    )
                }
            }
            return result.toMap()
        }

        private fun qualifiedSourceHandoff(
            previous: CurrentTrackedFrameObservation, current: CurrentTrackedFrameObservation,
            old: VisualTrackingObservation, row: VisualTrackingObservation, imageQuarterTurns: Int,
        ): Boolean {
            val proof = row.materialPointHandoff ?: return false
            val oldPoint = old.materialPoint ?: return false
            val point = row.materialPoint ?: return false
            val transforms = listOf(proof.previousTransformAtCommonFrame, proof.transformAtCommonFrame)
            if (transforms.any { transform ->
                    listOf(transform.a, transform.b, transform.c, transform.d, transform.tx, transform.ty)
                        .any { !it.isFinite() } || !transform.areaScale.isFinite() || transform.areaScale <= 0f
                }) return false
            val common = proof.commonFrameKey
            return proof.previousSourceKey == previous.sourceKey && proof.sourceKey == current.sourceKey &&
                common.epoch == previous.targetKey.epoch && common.geometryVersion == previous.targetKey.geometryVersion &&
                common.frameId in previous.targetKey.frameId..current.targetKey.frameId &&
                common.cameraTimestampNs in previous.targetKey.cameraTimestampNs..current.targetKey.cameraTimestampNs &&
                common.capturedAtElapsedRealtimeMs in previous.targetKey.capturedAtElapsedRealtimeMs..current.targetKey.capturedAtElapsedRealtimeMs &&
                current.sourceKey.frameId <= proof.commonFrameKey.frameId &&
                current.sourceKey.cameraTimestampNs <= proof.commonFrameKey.cameraTimestampNs &&
                current.sourceKey.capturedAtElapsedRealtimeMs <= proof.commonFrameKey.capturedAtElapsedRealtimeMs &&
                proof.previousSourceIndex == old.sourceIndex &&
                proof.sourceIndex == row.sourceIndex && proof.uprightQuarterTurns == imageQuarterTurns &&
                proof.previousDetection == previous.sourceDetections[old.sourceIndex] &&
                proof.detection == current.sourceDetections[row.sourceIndex] &&
                proof.previousGeometryAtCommonFrame == mappedDetection(proof.previousDetection, proof.previousTransformAtCommonFrame) &&
                proof.geometryAtCommonFrame == mappedDetection(proof.detection, proof.transformAtCommonFrame) &&
                proof.pointId === oldPoint.id && proof.pointId === point.id &&
                // The producer measured this point at common; neither its older nor later
                // point coordinates are reconstructed from an affine geometry transform.
                bboxIoU(proof.previousGeometryAtCommonFrame.bboxNorm, proof.geometryAtCommonFrame.bboxNorm) >= .65f &&
                contains(proof.previousGeometryAtCommonFrame, proof.pointAtCommonFrame) &&
                contains(proof.geometryAtCommonFrame, proof.pointAtCommonFrame)
        }

        private fun contains(geometry: DetectionCandidate, point: Point2): Boolean {
            val box = geometry.bboxNorm
            if (!point.x.isFinite() || !point.y.isFinite() || point.x < box.x || point.y < box.y ||
                point.x > box.x + box.width || point.y > box.y + box.height) return false
            if (geometry.polygonNorm.isEmpty()) return true
            var inside = false
            var before = geometry.polygonNorm.last()
            for (after in geometry.polygonNorm) {
                if ((after.y > point.y) != (before.y > point.y) &&
                    point.x < (before.x - after.x) * (point.y - after.y) / (before.y - after.y) + after.x
                ) inside = !inside
                before = after
            }
            return inside
        }
    }
}

/** Maps the original detector geometry, never a prior axis-aligned envelope. */
private fun mappedDetection(source: DetectionCandidate, transform: VisualAffineTransform): DetectionCandidate {
    val rect = source.bboxNorm
    val box = if (transform.translationOnly) {
        rect.copy(x = rect.x + transform.tx, y = rect.y + transform.ty)
    } else {
        val corners = listOf(Point2(rect.x, rect.y), Point2(rect.x + rect.width, rect.y),
            Point2(rect.x + rect.width, rect.y + rect.height), Point2(rect.x, rect.y + rect.height))
            .map(transform::map)
        val left = corners.minOf { it.x }
        val top = corners.minOf { it.y }
        RectNorm(left, top, corners.maxOf { it.x } - left, corners.maxOf { it.y } - top)
    }
    return source.copy(bboxNorm = box, polygonNorm = source.polygonNorm.map(transform::map),
        maskAreaNorm = source.maskAreaNorm?.let { it * transform.areaScale })
}
