package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.navigation.CROSSWALK_REFERENCE_NOTICE_KO
import kr.co.hanium.dreamup.walksafe.navigation.CrosswalkReferencePolicy

data class MessagePolicyConfig(
    val metricSourceMinConfidence: Map<DepthSource, Float> = defaultMetricSourceMinConfidence(),
    val weakMetricMinConfidence: Float = 0.35f,
    val pseudoApproachMinConfidence: Float = 0.35f,
    val pathGuidanceMinConfidence: Float = 0.45f,
    val stopDistanceM: Float = 1.2f,
    val warningDistanceM: Float = 2.5f,
    val approachingWarningDistanceM: Float = 3.0f,
    val ttcStopMs: Long = 3_000L,
    val ttcWarningMs: Long = 10_000L,
    val ttcAwareMs: Long = 30_000L,
    val stopConfidence: Float = 0.75f,
    val rateLimit: MessageRateLimitConfig = MessageRateLimitConfig(),
    val hysteresis: MessageHysteresisConfig = MessageHysteresisConfig(),
)

data class MessageRateLimitConfig(
    val obstacleWarningIntervalMs: Long = 2_500L,
    val approachCautionIntervalMs: Long = 2_500L,
    val pathGuidanceIntervalMs: Long = 8_000L,
)

data class MessageHysteresisConfig(
    val distanceMarginM: Float = 0.25f,
    val confidenceMargin: Float = 0.05f,
)

enum class MessagePurpose {
    NONE,
    OBSTACLE_WARNING,
    APPROACH_CAUTION,
    PATH_GUIDANCE,
    REPORT_ONLY,
}

data class MessagePolicyDecision(
    val userFacing: UserFacingDepth,
    val purpose: MessagePurpose,
    val reason: String,
)

/**
 * Converts depth/tracking evidence into a rate-limited user message.
 *
 * Reporting and user guidance are deliberately separate: damaged tactile blocks stay silent and
 * report-only, while only trusted metric sources can produce distance or step guidance.
 */
class MessagePolicy(
    private val stepLengthM: Float = 0.65f,
    private val config: MessagePolicyConfig = MessagePolicyConfig(),
) {
    private val lastEmitAtByKey = mutableMapOf<String, Long>()
    private val activeObstacleLevelByKey = mutableMapOf<String, MessageLevel>()

    fun buildUserFacing(result: MetricDepthDecision, nowMs: Long = System.currentTimeMillis()): UserFacingDepth {
        return evaluate(result, nowMs).userFacing
    }

    fun evaluate(result: MetricDepthDecision, nowMs: Long = System.currentTimeMillis()): MessagePolicyDecision {
        val candidate = when {
            CrosswalkReferencePolicy.isCrosswalkDetectorClass(result.className) -> crosswalkGuidanceMessage(result)
            result.className.equals("traffic light", ignoreCase = true) -> none("traffic light color guidance deferred")
            isTactileClass(result.className) -> tactileMessage(result)
            else -> metricOrPseudoObstacleMessage(result)
        }
        return applyRateLimit(candidate, result, nowMs)
    }

    private fun metricOrPseudoObstacleMessage(result: MetricDepthDecision): MessagePolicyDecision {
        if (!hasReliableMetricDistance(result)) {
            activeObstacleLevelByKey.remove(stateKeyFor(result))
            return pseudoOrWeakMessage(result)
        }

        val stateKey = stateKeyFor(result)
        result.riskDistanceM ?: return pseudoOrWeakMessage(result)
        val level = obstacleLevel(result, stateKey)
        if (level == MessageLevel.NONE) {
            activeObstacleLevelByKey.remove(stateKey)
            return none("outside obstacle warning threshold")
        }
        activeObstacleLevelByKey[stateKey] = level
        val steps = distanceMetersToSteps(result.riskDistanceM, stepLengthM)
        if (level == MessageLevel.AWARE) {
            return MessagePolicyDecision(
                userFacing = UserFacingDepth(stepsAhead = steps, messageLevel = MessageLevel.AWARE, message = null),
                purpose = MessagePurpose.NONE,
                reason = "approaching object aware",
            )
        }
        val target = labelForClass(result.className)
        val phrase = when {
            steps == null -> "전방 $target"
            steps <= 1 -> "전방 바로 앞 $target"
            steps <= 2 -> "전방 약 ${steps}보 이내 $target"
            steps <= 4 -> "전방 약 ${steps}보 앞 $target"
            else -> "전방 $target"
        }
        // RQ-FP-020-001: 이동할 공간의 안전성이 검증되기 전에는 좌우 이동을 지시하지 않고 이 문구로 제한한다.
        val action = if (level == MessageLevel.STOP) "멈추세요. 주변을 확인하세요." else "속도를 줄이세요."
        return MessagePolicyDecision(
            userFacing = UserFacingDepth(stepsAhead = steps, messageLevel = level, message = "$phrase. $action"),
            purpose = MessagePurpose.OBSTACLE_WARNING,
            reason = if (level == MessageLevel.STOP) "near metric obstacle" else "metric obstacle warning",
        )
    }

    private fun tactileMessage(result: MetricDepthDecision): MessagePolicyDecision {
        return when (result.className.lowercase()) {
            // Normal tactile guidance is admitted later only when the active TMAP route and the
            // stable local tactile observation agree.
            "normal_tactile_block" -> none("normal tactile guidance requires TMAP-aligned route policy")
            // Damage is captured by the report pipeline; announcing every detection would overload
            // the user and conflict with the current silent-auto-report policy.
            "damaged_tactile_block" -> MessagePolicyDecision(
                userFacing = UserFacingDepth(stepsAhead = null, messageLevel = MessageLevel.NONE, message = null),
                purpose = MessagePurpose.REPORT_ONLY,
                reason = "damaged tactile block is report-only",
            )
            "tactile_damage_area" -> none("tactile damage area is not user guidance")
            else -> none("unrecognized tactile class is not traversable")
        }
    }

    private fun crosswalkGuidanceMessage(result: MetricDepthDecision): MessagePolicyDecision {
        if (result.confidenceFinal < config.pathGuidanceMinConfidence) {
            return none("crosswalk guidance confidence below threshold")
        }
        return MessagePolicyDecision(
            userFacing = UserFacingDepth(
                stepsAhead = null,
                messageLevel = MessageLevel.INFO,
                message = CROSSWALK_REFERENCE_NOTICE_KO,
            ),
            purpose = MessagePurpose.PATH_GUIDANCE,
            reason = "crosswalk reference notice",
        )
    }

    private fun pseudoOrWeakMessage(result: MetricDepthDecision): MessagePolicyDecision {
        val target = labelForClass(result.className)
        if (!result.source.metric && result.trend == Trend.APPROACHING && result.confidenceFinal >= config.pseudoApproachMinConfidence) {
            return MessagePolicyDecision(
                userFacing = UserFacingDepth(
                    stepsAhead = null,
                    messageLevel = MessageLevel.CAUTION,
                    message = "전방 ${target}와의 거리가 줄어드는 것 같습니다. 주의하세요.",
                ),
                purpose = MessagePurpose.APPROACH_CAUTION,
                reason = "pseudo-depth approaching",
            )
        }
        if (isWeakMetricCandidate(result)) {
            return MessagePolicyDecision(
                userFacing = UserFacingDepth(
                    stepsAhead = null,
                    messageLevel = MessageLevel.CAUTION,
                    message = "전방 가까운 $target 가능성. 주의하세요.",
                ),
                purpose = MessagePurpose.APPROACH_CAUTION,
                reason = "metric source confidence below warning threshold",
            )
        }
        return none("below message threshold")
    }

    /** Central gate that prevents pseudo or weak depth from becoming a spoken metric distance. */
    private fun hasReliableMetricDistance(result: MetricDepthDecision): Boolean {
        val distance = result.riskDistanceM
        if (!result.source.trustedForStepGuidance || distance == null || !distance.isFinite() || distance <= 0f) return false
        val threshold = metricSourceThreshold(result.source)
        val stateKey = stateKeyFor(result)
        val wasActive = activeObstacleLevelByKey.containsKey(stateKey)
        val minConfidence = if (wasActive) threshold - config.hysteresis.confidenceMargin else threshold
        return result.confidenceFinal >= minConfidence.coerceIn(0f, 1f)
    }

    private fun obstacleLevel(result: MetricDepthDecision, stateKey: String): MessageLevel {
        val distance = result.riskDistanceM ?: return MessageLevel.NONE
        val previous = activeObstacleLevelByKey[stateKey]
        val distanceMargin = if (previous != null) config.hysteresis.distanceMarginM else 0f
        val stopDistance = config.stopDistanceM + if (previous == MessageLevel.STOP) distanceMargin else 0f
        val warningDistance = config.warningDistanceM + distanceMargin
        val approachingDistance = config.approachingWarningDistanceM + distanceMargin

        return when {
            distance <= stopDistance -> MessageLevel.STOP
            distance <= warningDistance && result.confidenceFinal >= config.stopConfidence -> MessageLevel.STOP
            distance <= warningDistance -> MessageLevel.WARNING
            result.trend == Trend.APPROACHING && result.timeToCollisionMs != null &&
                result.timeToCollisionMs <= config.ttcStopMs -> MessageLevel.STOP
            result.trend == Trend.APPROACHING && result.timeToCollisionMs != null &&
                result.timeToCollisionMs <= config.ttcWarningMs -> MessageLevel.WARNING
            result.trend == Trend.APPROACHING && result.timeToCollisionMs != null &&
                result.timeToCollisionMs <= config.ttcAwareMs -> MessageLevel.AWARE
            result.trend == Trend.APPROACHING && distance <= approachingDistance -> MessageLevel.WARNING
            else -> MessageLevel.NONE
        }
    }

    private fun isWeakMetricCandidate(result: MetricDepthDecision): Boolean {
        val distance = result.riskDistanceM
        if (!result.source.trustedForStepGuidance || distance == null || !distance.isFinite() || distance <= 0f) return false
        val threshold = metricSourceThreshold(result.source)
        return result.confidenceFinal >= config.weakMetricMinConfidence &&
            result.confidenceFinal < threshold &&
            distance <= config.warningDistanceM + config.hysteresis.distanceMarginM
    }

    private fun applyRateLimit(
        decision: MessagePolicyDecision,
        result: MetricDepthDecision,
        nowMs: Long,
    ): MessagePolicyDecision {
        if (decision.userFacing.message == null) return decision

        val intervalMs = when (decision.purpose) {
            MessagePurpose.OBSTACLE_WARNING -> config.rateLimit.obstacleWarningIntervalMs
            MessagePurpose.APPROACH_CAUTION -> config.rateLimit.approachCautionIntervalMs
            MessagePurpose.PATH_GUIDANCE -> config.rateLimit.pathGuidanceIntervalMs
            MessagePurpose.NONE, MessagePurpose.REPORT_ONLY -> 0L
        }
        if (intervalMs <= 0L) return decision

        val rateKey = "${decision.purpose}:${stateKeyFor(result)}"
        val lastAt = lastEmitAtByKey[rateKey]
        if (lastAt != null && nowMs >= lastAt && nowMs - lastAt < intervalMs) {
            return decision.copy(
                userFacing = decision.userFacing.copy(message = null),
                reason = "rate limited: ${decision.reason}",
            )
        }

        lastEmitAtByKey[rateKey] = nowMs
        return decision
    }

    private fun none(reason: String): MessagePolicyDecision {
        return MessagePolicyDecision(
            userFacing = UserFacingDepth(stepsAhead = null, messageLevel = MessageLevel.NONE, message = null),
            purpose = MessagePurpose.NONE,
            reason = reason,
        )
    }

    private fun stateKeyFor(result: MetricDepthDecision): String {
        return result.trackKey ?: "${result.className.lowercase()}:${result.source.name}"
    }

    private fun metricSourceThreshold(source: DepthSource): Float {
        return config.metricSourceMinConfidence[source]?.coerceIn(0f, 1f) ?: 1f
    }

    private fun isTactileClass(className: String): Boolean {
        return className.lowercase() in tactileClassNames || isTactileBlockClass(className)
    }

    private fun labelForClass(className: String): String {
        return when (className.lowercase()) {
            "person" -> "사람"
            "bicycle" -> "자전거"
            "car", "bus", "truck", "motorcycle" -> "차량"
            "normal_tactile_block" -> "점자블록"
            "crosswalk" -> "횡단보도"
            "curb_step" -> "보도 턱"
            "uneven_sidewalk" -> "고르지 않은 보도"
            "e_scooter_obstruction" -> "방치 킥보드"
            else -> "물체"
        }
    }

    private companion object {
        val tactileClassNames = setOf(
            "normal_tactile_block",
            "damaged_tactile_block",
            "tactile_damage_area",
        )
    }
}

data class MetricDepthDecision(
    val className: String,
    val source: DepthSource,
    val riskDistanceM: Float?,
    val trend: Trend,
    val confidenceFinal: Float,
    val trackKey: String? = null,
    val timeToCollisionMs: Long? = null,
)

fun defaultMetricSourceMinConfidence(): Map<DepthSource, Float> {
    return mapOf(
        DepthSource.ARCORE_RAW_DEPTH to 0.55f,
        DepthSource.ARCORE_FULL_DEPTH to 0.60f,
        DepthSource.WEBXR_DEPTH to 0.62f,
        DepthSource.MONOCULAR_METRIC_DEPTH to 0.70f,
    )
}
