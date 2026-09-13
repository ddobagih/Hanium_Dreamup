package kr.co.hanium.dreamup.walksafe.feedback

import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.inference.ObstacleLabels

enum class NonMetricObstacleDirection(val labelKo: String) {
    LEFT("왼쪽"),
    CENTER("가운데"),
    RIGHT("오른쪽"),
}

data class NonMetricObstacleAdvisoryConfig(
    val minConfidence: Float = 0.70f,
    val minBoxArea: Float = 0.015f,
    val minBoxBottom: Float = 0.58f,
    val minContinuityIou: Float = 0.10f,
    val minStableFrames: Int = 3,
    val minStableMs: Long = 700L,
    val maxContinuityGapMs: Long = 1_600L,
    val globalIntervalMs: Long = 4_000L,
    val perKeyIntervalMs: Long = 8_000L,
    val actionTtlMs: Long = 1_500L,
    val pendingQueueCapacity: Int = 3,
)

data class NonMetricAdvisoryGate(
    val cameraPermissionGranted: Boolean,
    val cameraFallbackRunning: Boolean,
    val detectorAvailable: Boolean,
    val imuFresh: Boolean,
    val tmapRouteActive: Boolean,
) {
    val allowed: Boolean
        get() = cameraPermissionGranted && cameraFallbackRunning && detectorAvailable && imuFresh
}

data class NonMetricObstacleAdvisoryAction(
    val key: String,
    val direction: NonMetricObstacleDirection,
    val message: String,
    val observedAtMs: Long,
    val validUntilMs: Long,
)

/**
 * Camera-only observation policy. It can run without an active route and intentionally emits no
 * distance, step, route-correction or report evidence; TMAP remains the sole route authority when
 * route guidance is active.
 */
class AndroidNonMetricObstacleAdvisoryPolicy(
    private val config: NonMetricObstacleAdvisoryConfig = NonMetricObstacleAdvisoryConfig(),
) {
    private data class Observation(
        val key: String,
        val direction: NonMetricObstacleDirection,
        val categoryLabelKo: String,
        val candidate: DetectionCandidate,
    )

    private data class Sighting(
        var consecutiveFrames: Int,
        val firstSeenAtMs: Long,
        var lastSeenAtMs: Long,
        var lastFrameId: Long,
        var observation: Observation,
    )

    private data class QueuedAdvisory(
        var observation: Observation,
        val sequence: Long,
    )

    private enum class DeliveryState {
        RESERVED,
        CLAIMED,
    }

    private data class PendingDelivery(
        val action: NonMetricObstacleAdvisoryAction,
        val state: DeliveryState,
    )

    private val sightings = mutableMapOf<String, Sighting>()
    private val queuedAdvisories = mutableListOf<QueuedAdvisory>()
    private val lastDeliveredByKey = mutableMapOf<String, Long>()
    private var pendingDelivery: PendingDelivery? = null
    private var nextQueueSequence = 0L
    private var lastGlobalDeliveryAtMs: Long? = null

    init {
        require(config.minStableFrames > 0) { "minStableFrames must be positive" }
        require(config.minContinuityIou in 0f..1f) { "minContinuityIou must be between zero and one" }
        require(config.minStableMs >= 0L) { "minStableMs must not be negative" }
        require(config.maxContinuityGapMs > 0L) { "maxContinuityGapMs must be positive" }
        require(config.globalIntervalMs >= 0L) { "globalIntervalMs must not be negative" }
        require(config.perKeyIntervalMs >= 0L) { "perKeyIntervalMs must not be negative" }
        require(config.actionTtlMs > 0L) { "actionTtlMs must be positive" }
        require(config.pendingQueueCapacity > 0) { "pendingQueueCapacity must be positive" }
    }

    @Synchronized
    fun evaluate(
        detections: List<DetectionCandidate>,
        gate: NonMetricAdvisoryGate,
        frameId: Long,
        nowMs: Long,
    ): NonMetricObstacleAdvisoryAction? {
        if (!gate.allowed) {
            reset()
            return null
        }
        val observations = detections.mapNotNull(::toObservation)
            .groupBy(Observation::key)
            .mapNotNull { (_, candidates) -> candidates.maxByOrNull { it.candidate.visualPriority() } }
        val observedKeys = observations.mapTo(hashSetOf(), Observation::key)
        sightings.keys.retainAll(observedKeys)
        observations.forEach { observation ->
            val existing = sightings[observation.key]
            if (
                existing == null ||
                nowMs - existing.lastSeenAtMs !in 0L..config.maxContinuityGapMs ||
                existing.observation.candidate.iouWith(observation.candidate) < config.minContinuityIou
            ) {
                sightings[observation.key] = Sighting(1, nowMs, nowMs, frameId, observation)
            } else {
                if (existing.lastFrameId != frameId) existing.consecutiveFrames += 1
                existing.lastSeenAtMs = nowMs
                existing.lastFrameId = frameId
                existing.observation = observation
            }
        }

        val stable = sightings.values
            .asSequence()
            .filter { it.consecutiveFrames >= config.minStableFrames }
            .filter { nowMs - it.firstSeenAtMs >= config.minStableMs }
            .filter { nowMs - it.lastSeenAtMs in 0L..config.maxContinuityGapMs }
            .filter { sighting ->
                val lastForKey = lastDeliveredByKey[sighting.observation.key]
                lastForKey == null || nowMs - lastForKey >= config.perKeyIntervalMs
            }
            .sortedWith(
                compareByDescending<Sighting> { it.observation.direction == NonMetricObstacleDirection.CENTER }
                    .thenByDescending { it.observation.candidate.visualPriority() },
            )
            .map(Sighting::observation)
            .toList()
        val stableKeys = stable.mapTo(hashSetOf(), Observation::key)
        queuedAdvisories.removeAll { it.observation.key !in stableKeys }
        pendingDelivery?.let { pending ->
            val expiredBeforeClaim = pending.state == DeliveryState.RESERVED && nowMs > pending.action.validUntilMs
            if (pending.action.key !in stableKeys || expiredBeforeClaim) pendingDelivery = null
        }
        stable.forEach { observation ->
            val queued = queuedAdvisories.firstOrNull { it.observation.key == observation.key }
            if (queued != null) {
                queued.observation = observation
                return@forEach
            }
            if (pendingDelivery?.action?.key == observation.key) return@forEach
            val occupied = queuedAdvisories.size + if (pendingDelivery == null) 0 else 1
            if (occupied >= config.pendingQueueCapacity) return@forEach
            queuedAdvisories += QueuedAdvisory(observation, nextQueueSequence++)
        }
        if (pendingDelivery != null) return null
        val lastGlobal = lastGlobalDeliveryAtMs
        if (lastGlobal != null && nowMs - lastGlobal < config.globalIntervalMs) return null
        val selected = queuedAdvisories.minByOrNull(QueuedAdvisory::sequence) ?: return null
        queuedAdvisories.remove(selected)

        val observation = selected.observation
        val action = NonMetricObstacleAdvisoryAction(
            key = observation.key,
            direction = observation.direction,
            message = buildMessage(observation.direction, observation.categoryLabelKo),
            observedAtMs = nowMs,
            validUntilMs = nowMs + config.actionTtlMs,
        )
        pendingDelivery = PendingDelivery(action, DeliveryState.RESERVED)
        return action
    }

    @Synchronized
    fun claimDelivery(action: NonMetricObstacleAdvisoryAction): Boolean {
        val pending = pendingDelivery ?: return false
        if (pending.action != action || pending.state != DeliveryState.RESERVED) return false
        pendingDelivery = pending.copy(state = DeliveryState.CLAIMED)
        return true
    }

    @Synchronized
    fun isDeliveryCurrent(action: NonMetricObstacleAdvisoryAction): Boolean =
        pendingDelivery?.action == action

    @Synchronized
    fun confirmDelivery(
        action: NonMetricObstacleAdvisoryAction,
        deliveredAtMs: Long = action.observedAtMs,
    ): Boolean {
        val pending = pendingDelivery ?: return false
        if (pending.action != action || pending.state != DeliveryState.CLAIMED) return false
        val confirmedAtMs = deliveredAtMs.coerceAtLeast(action.observedAtMs)
        pendingDelivery = null
        lastGlobalDeliveryAtMs = confirmedAtMs
        lastDeliveredByKey[action.key] = confirmedAtMs
        return true
    }

    @Synchronized
    fun rejectDelivery(action: NonMetricObstacleAdvisoryAction): Boolean {
        val pending = pendingDelivery ?: return false
        if (pending.action != action) return false
        pendingDelivery = null
        return true
    }

    @Synchronized
    fun reset() {
        sightings.clear()
        queuedAdvisories.clear()
        lastDeliveredByKey.clear()
        pendingDelivery = null
        nextQueueSequence = 0L
        lastGlobalDeliveryAtMs = null
    }

    private fun toObservation(candidate: DetectionCandidate): Observation? {
        val className = candidate.className.lowercase(Locale.US)
        if (className !in NON_METRIC_OBSTACLE_CLASSES) return null
        if (!candidate.detectionConfidence.isFinite() || candidate.detectionConfidence < config.minConfidence) return null
        val box = candidate.bboxNorm
        val right = box.x + box.width
        val bottom = box.y + box.height
        if (
            !box.x.isFinite() || !box.y.isFinite() || !box.width.isFinite() || !box.height.isFinite() ||
            box.x < 0f || box.y < 0f || box.width <= 0f || box.height <= 0f ||
            right > 1f || bottom > 1f || box.area < config.minBoxArea || bottom < config.minBoxBottom
        ) return null
        val direction = when {
            box.center.x < LEFT_ZONE_END -> NonMetricObstacleDirection.LEFT
            box.center.x > RIGHT_ZONE_START -> NonMetricObstacleDirection.RIGHT
            else -> NonMetricObstacleDirection.CENTER
        }
        return Observation(
            key = "$className|${direction.name}",
            direction = direction,
            categoryLabelKo = ObstacleLabels.labelFor(className),
            candidate = candidate,
        )
    }

    private fun DetectionCandidate.visualPriority(): Float = detectionConfidence + bboxNorm.area

    private fun DetectionCandidate.iouWith(other: DetectionCandidate): Float {
        val left = maxOf(bboxNorm.x, other.bboxNorm.x)
        val top = maxOf(bboxNorm.y, other.bboxNorm.y)
        val right = minOf(bboxNorm.x + bboxNorm.width, other.bboxNorm.x + other.bboxNorm.width)
        val bottom = minOf(bboxNorm.y + bboxNorm.height, other.bboxNorm.y + other.bboxNorm.height)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val union = bboxNorm.area + other.bboxNorm.area - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private fun buildMessage(direction: NonMetricObstacleDirection, categoryLabelKo: String): String =
        "카메라 보조 경고. ${direction.labelKo}에 $categoryLabelKo 후보가 보입니다. " +
            "주변을 확인하세요."

    private companion object {
        const val LEFT_ZONE_END = 0.38f
        const val RIGHT_ZONE_START = 0.62f
        val NON_METRIC_OBSTACLE_CLASSES = setOf(
            "person",
            "bicycle",
            "car",
            "passenger_car",
            "motorcycle",
            "bus",
            "truck",
            "bench",
            "normal_tactile_block",
            "linear_tactile_paving",
            "dot_tactile_paving",
            "curb_step",
            "uneven_sidewalk",
            "e_scooter_obstruction",
            "abandoned_e_scooter",
            "moving_e_scooter",
            "construction_fence",
            "barricade",
            "traffic_cone",
            "bollard",
            "utility_or_streetlight_pole",
            "trash_bin",
            "portable_sign",
        )
    }
}
