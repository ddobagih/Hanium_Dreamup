package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.ObstacleLabels
import kr.co.hanium.dreamup.walksafe.inference.WalkMateClassPolicy
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
 * report-only, while only trusted metric sources can trigger distance-based obstacle warnings.
 */
class MessagePolicy(
    private val stepLengthM: Float = 0.65f,
    private val config: MessagePolicyConfig = MessagePolicyConfig(),
) {
    private val lastEmitAtByKey = mutableMapOf<String, Long>()
    private val activeObstacleLevelByKey = mutableMapOf<String, MessageLevel>()

    /** Final delivery queues own speech cooldowns; every frame must retain its current candidate. */
    fun forFeedbackQueue(): MessagePolicy = copyPolicy(
        stepLengthM,
        config.copy(rateLimit = MessageRateLimitConfig(0L, 0L, 0L)),
    )

    fun withStepLength(stepLengthM: Float): MessagePolicy = copyPolicy(stepLengthM, config)

    private fun copyPolicy(stepLengthM: Float, config: MessagePolicyConfig): MessagePolicy =
        MessagePolicy(stepLengthM, config).also {
            it.lastEmitAtByKey.putAll(lastEmitAtByKey)
            it.activeObstacleLevelByKey.putAll(activeObstacleLevelByKey)
        }

    fun buildUserFacing(result: MetricDepthDecision, nowMs: Long = System.currentTimeMillis()): UserFacingDepth {
        return evaluate(result, nowMs).userFacing
    }

    fun evaluate(result: MetricDepthDecision, nowMs: Long = System.currentTimeMillis()): MessagePolicyDecision {
        val candidate = when {
            CrosswalkReferencePolicy.isCrosswalkDetectorClass(result.className) -> crosswalkGuidanceMessage(result)
            WalkMateClassPolicy.isTrafficLightClass(result.className) -> none("traffic light color guidance deferred")
            isTactileClass(result.className) -> tactileMessage(result)
            else -> metricOrPseudoObstacleMessage(result, nowMs)
        }
        return applyRateLimit(candidate, result, nowMs)
    }

    private fun metricOrPseudoObstacleMessage(result: MetricDepthDecision, frameTimestampMs: Long): MessagePolicyDecision {
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
        if (level == MessageLevel.AWARE) {
            return MessagePolicyDecision(
                userFacing = UserFacingDepth(stepsAhead = null, messageLevel = MessageLevel.AWARE, message = null),
                purpose = MessagePurpose.NONE,
                reason = "approaching object aware",
            )
        }
        val target = labelForClass(result.className)
        val phrase = "전방 $target"
        val currentMotion = result.motionEstimate.takeIf { it.isCurrentMeasuredMotion(frameTimestampMs) }
        val motionPhrase = when {
            result.trend == Trend.APPROACHING && result.objectMotion == ObjectMotion.USER_APPROACHING_STATIONARY &&
                currentMotion?.direction == ObjectMovementDirection.STATIONARY ->
                " 정지해 있는 것으로 보이며, 현재 이 물체에 가까워지고 있습니다."
            result.trend == Trend.APPROACHING && result.objectMotion == ObjectMotion.OBJECT_APPROACHING &&
                currentMotion?.direction == ObjectMovementDirection.TOWARD_USER &&
                (currentMotion.relativeClosingSpeedMps ?: 0f) >= 0.25f ->
                " 이 물체가 사용자 쪽으로 다가오는 것으로 보입니다."
            currentMotion?.direction == ObjectMovementDirection.STATIONARY ->
                " 정지해 있는 것으로 보입니다."
            currentMotion?.direction == ObjectMovementDirection.TOWARD_USER ->
                " 이 물체가 사용자 쪽으로 이동하는 것으로 보입니다."
            currentMotion?.direction == ObjectMovementDirection.LEFT ->
                " 이 물체가 카메라 기준 왼쪽으로 이동하는 것으로 보입니다."
            currentMotion?.direction == ObjectMovementDirection.RIGHT ->
                " 이 물체가 카메라 기준 오른쪽으로 이동하는 것으로 보입니다."
            currentMotion?.direction == ObjectMovementDirection.AWAY_FROM_USER ->
                " 이 물체가 사용자에게서 멀어지는 방향으로 이동하는 것으로 보입니다."
            result.trend == Trend.APPROACHING -> " 거리가 줄어들고 있습니다."
            else -> ""
        }
        val action = if (level == MessageLevel.STOP) "멈추세요. 주변을 확인하세요." else "멈출 준비를 하세요."
        return MessagePolicyDecision(
            userFacing = UserFacingDepth(stepsAhead = null, messageLevel = level, message = "$phrase.$motionPhrase $action"),
            purpose = MessagePurpose.OBSTACLE_WARNING,
            reason = if (level == MessageLevel.STOP) "near metric obstacle" else "metric obstacle warning",
        )
    }

    private fun tactileMessage(result: MetricDepthDecision): MessagePolicyDecision {
        return when {
            // Normal tactile guidance is admitted later only when the active TMAP route and the
            // stable local tactile observation agree.
            WalkMateClassPolicy.isTraversableTactileClass(result.className) ->
                none("normal tactile guidance requires TMAP-aligned route policy")
            // Damage is captured by the report pipeline; announcing every detection would overload
            // the user and conflict with the current silent-auto-report policy.
            WalkMateClassPolicy.isDamagedTactileClass(result.className) -> MessagePolicyDecision(
                userFacing = UserFacingDepth(stepsAhead = null, messageLevel = MessageLevel.NONE, message = null),
                purpose = MessagePurpose.REPORT_ONLY,
                reason = "damaged tactile block is report-only",
            )
            result.className.equals("dot_tactile_paving", ignoreCase = true) ->
                none("dot tactile paving does not establish a walking direction")
            result.className.equals("tactile_damage_area", ignoreCase = true) ->
                none("tactile damage area is not user guidance")
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
                    message = "전방 ${target}와의 거리가 줄어드는 것 같습니다. 속도를 늦추고 주변을 확인하세요.",
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
                    message = "전방 가까운 $target 가능성. 속도를 늦추고 주변을 확인하세요.",
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
        return WalkMateClassPolicy.isTactileClass(className) || isTactileBlockClass(className)
    }

    private fun labelForClass(className: String): String = ObstacleLabels.labelFor(className)
}

data class MetricDepthDecision(
    val className: String,
    val source: DepthSource,
    val riskDistanceM: Float?,
    val trend: Trend,
    val confidenceFinal: Float,
    val trackKey: String? = null,
    val timeToCollisionMs: Long? = null,
    val objectMotion: ObjectMotion = ObjectMotion.UNKNOWN,
    val motionEstimate: ObjectMotionEstimate = ObjectMotionEstimate(),
)

fun defaultMetricSourceMinConfidence(): Map<DepthSource, Float> {
    return mapOf(
        DepthSource.ARCORE_RAW_DEPTH to 0.55f,
        DepthSource.ARCORE_FULL_DEPTH to 0.60f,
        DepthSource.WEBXR_DEPTH to 0.62f,
        DepthSource.MONOCULAR_METRIC_DEPTH to 0.70f,
    )
}

/** Frame-clock provenance is mandatory: a legacy/cached motion must not describe a new capture. */
internal fun ObjectMotionEstimate.isCurrentMeasuredMotion(frameTimestampMs: Long): Boolean =
    frameTimestampMs >= 0L && observedAtMs == frameTimestampMs &&
        referenceId?.let { it >= 0L } == true && elapsedMs in 600L..4_000L &&
        confidence.isFinite() && confidence >= 0.55f &&
        objectSpeedMps?.let { it.isFinite() && it >= 0f } == true &&
        relativeClosingSpeedMps?.isFinite() == true &&
        objectVelocityInAnchorMps.hasFiniteComponents() &&
        relativeVelocityInAnchorMps.hasFiniteComponents() &&
        cameraVelocityInAnchorMps.hasFiniteComponents()

private fun Vec3?.hasFiniteComponents(): Boolean =
    this != null && x.isFinite() && y.isFinite() && z.isFinite()
