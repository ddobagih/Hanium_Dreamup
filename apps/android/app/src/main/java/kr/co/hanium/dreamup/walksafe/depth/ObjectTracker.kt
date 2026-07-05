package kr.co.hanium.dreamup.walksafe.depth

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class DistanceObservation(
    val timestampMs: Long,
    val distanceM: Float,
    val source: DepthSource,
    val confidence: Float,
)

data class ApproachKinematics(
    val trend: Trend,
    val approachScore: Float,
    val approachSpeedMps: Float?,
    val timeToCollisionMs: Long?,
)

class TrackState(
    val trackId: String,
    val className: String,
    val createdAtMs: Long,
) {
    var lastSeenAtMs: Long = createdAtMs
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

    val bboxHistory: MutableList<RectNorm> = mutableListOf()
    val centerHistory: MutableList<Point2> = mutableListOf()
    val polygonHistory: MutableList<List<Point2>> = mutableListOf()
    val distanceHistory: MutableList<DistanceObservation> = mutableListOf()
    val confidenceHistory: MutableList<Float> = mutableListOf()

    fun markSeen(geometry: ObjectGeometry, timestampMs: Long, minStableAgeFrames: Int) {
        latestGeometry?.let { previous ->
            if (centerDistance(previous.centerNorm, geometry.centerNorm) > 0.35f) idSwitchSuspected = true
            val oldArea = previous.maskAreaNorm.coerceAtLeast(0.0001f)
            val newArea = geometry.maskAreaNorm.coerceAtLeast(0.0001f)
            val ratio = max(oldArea, newArea) / min(oldArea, newArea)
            if (ratio > 4f) idSwitchSuspected = true
        }
        latestGeometry = geometry
        lastSeenAtMs = timestampMs
        missedFrames = 0
        ageFrames += 1
        stable = ageFrames >= minStableAgeFrames && !idSwitchSuspected
        bboxHistory.addCapped(geometry.bboxNorm, 12)
        centerHistory.addCapped(geometry.centerNorm, 12)
        polygonHistory.addCapped(geometry.polygonNorm, 8)
    }

    fun markMissed() {
        missedFrames += 1
        stable = false
    }

    fun recordDistance(observation: DistanceObservation, maxDepthJumpM: Float) {
        distanceHistory.lastOrNull()?.let { previous ->
            if (abs(previous.distanceM - observation.distanceM) > maxDepthJumpM) {
                idSwitchSuspected = true
                stable = false
            }
        }
        distanceHistory.addCapped(observation, 12)
        confidenceHistory.addCapped(observation.confidence, 12)
    }
}

class ObjectTracker(
    private val minIoU: Float = 0.20f,
    private val maxCenterMoveNorm: Float = 0.25f,
    private val maxDepthJumpM: Float = 1.2f,
    private val maxMissedFrames: Int = 5,
    private val minStableAgeFrames: Int = 3,
) {
    private var nextTrackNumber = 1
    private val tracks = mutableListOf<TrackState>()

    fun update(geometries: List<ObjectGeometry>, timestampMs: Long): List<TrackState> {
        val unmatchedTracks = tracks.toMutableList()
        val unmatchedGeometries = geometries.toMutableList()
        val matches = mutableListOf<Pair<TrackState, ObjectGeometry>>()

        while (true) {
            val best = unmatchedTracks.flatMap { track ->
                unmatchedGeometries.mapNotNull { geometry ->
                    matchingCost(track, geometry)?.let { cost -> Triple(track, geometry, cost) }
                }
            }.minByOrNull { it.third } ?: break
            matches += best.first to best.second
            unmatchedTracks.remove(best.first)
            unmatchedGeometries.remove(best.second)
        }

        matches.forEach { (track, geometry) -> track.markSeen(geometry, timestampMs, minStableAgeFrames) }
        unmatchedTracks.forEach { it.markMissed() }
        unmatchedGeometries.forEach { geometry ->
            val track = TrackState(
                trackId = "track-${nextTrackNumber++}",
                className = geometry.className,
                createdAtMs = timestampMs,
            )
            track.markSeen(geometry, timestampMs, minStableAgeFrames)
            tracks += track
        }
        tracks.removeAll { it.missedFrames > maxMissedFrames }
        return activeTracks()
    }

    fun predictOnly() {
        tracks.forEach { it.markMissed() }
        tracks.removeAll { it.missedFrames > maxMissedFrames }
    }

    fun activeTracks(): List<TrackState> = tracks.filter { it.missedFrames <= maxMissedFrames }

    fun recordDistance(track: TrackState, distanceM: Float?, source: DepthSource, confidence: Float, timestampMs: Long) {
        if (distanceM == null || !distanceM.isFinite() || distanceM <= 0f || !source.metric) return
        track.recordDistance(
            DistanceObservation(
                timestampMs = timestampMs,
                distanceM = distanceM,
                source = source,
                confidence = confidence.coerceIn(0f, 1f),
            ),
            maxDepthJumpM = maxDepthJumpM,
        )
    }

    fun approachKinematics(track: TrackState, currentDistanceM: Float?, source: DepthSource): ApproachKinematics {
        if (!source.metric || currentDistanceM == null || !track.stable || track.idSwitchSuspected) {
            return ApproachKinematics(Trend.UNKNOWN, approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null)
        }
        val usable = track.distanceHistory
            .filter { it.source.metric && it.confidence >= 0.35f }
            .takeLast(8)
        if (usable.size < 4) {
            return ApproachKinematics(Trend.UNKNOWN, approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null)
        }
        val slopeMps = linearSlopeMps(usable) ?: return ApproachKinematics(Trend.UNKNOWN, 0f, null, null)
        val approachSpeed = max(0f, -slopeMps)
        val trend = when {
            slopeMps < -0.10f -> Trend.APPROACHING
            slopeMps > 0.10f -> Trend.RECEDING
            else -> Trend.STABLE
        }
        val ttcMs = if (approachSpeed >= 0.25f) ((currentDistanceM / approachSpeed) * 1000f).toLong() else null
        return ApproachKinematics(
            trend = trend,
            approachScore = (approachSpeed / 1.5f).coerceIn(0f, 1f),
            approachSpeedMps = if (approachSpeed >= 0.25f) approachSpeed else null,
            timeToCollisionMs = ttcMs,
        )
    }

    private fun matchingCost(track: TrackState, geometry: ObjectGeometry): Float? {
        if (track.className != geometry.className) return null
        val previous = track.latestGeometry ?: return null
        val iou = bboxIoU(previous.bboxNorm, geometry.bboxNorm)
        val centerMove = centerDistance(previous.centerNorm, geometry.centerNorm)
        if (iou < minIoU && centerMove > maxCenterMoveNorm) return null
        return (1f - iou) * 0.70f + centerMove * 0.30f
    }
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
