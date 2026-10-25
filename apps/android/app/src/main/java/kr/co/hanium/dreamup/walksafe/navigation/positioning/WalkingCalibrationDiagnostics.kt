package kr.co.hanium.dreamup.walksafe.navigation.positioning

enum class WalkingCalibrationStage {
    STEP_ELIGIBILITY,
    RAW_FIX_CREDIBILITY,
    TIMESTAMP_ALIGNMENT,
    STRAIGHT_SEGMENT,
}

enum class WalkingCalibrationOutcome {
    PASSED,
    WAITING,
    REJECTED,
    SAMPLE_READY,
}

enum class WalkingCalibrationReason {
    ELIGIBLE_STEP,
    CREDIBLE_RAW_FIX,
    ALIGNED_FIX,
    STEP_NOT_INDIVIDUAL,
    STEP_NOT_SINGLE,
    INVALID_STEP_TIME,
    STALE_STEP,
    STEP_ORDER,
    STEP_GAP,
    STEP_COUNT_OVERFLOW,
    DELIVERY_ORDER,
    FIX_CONFIRMATION_EXPIRED,
    FIX_ORDER,
    PENDING_FIX_CAPACITY,
    WAITING_FOR_RAW_FIX,
    WAITING_FOR_STEP_WATERMARK,
    MISSING_STEP_BRACKET,
    STEP_BRACKET_GAP,
    UNTRUSTED_RAW_FIX,
    INVALID_FIX_COORDINATE,
    INVALID_FIX_TIME,
    STALE_FIX,
    MISSING_FIX_ACCURACY,
    MISSING_SPEED,
    MISSING_SPEED_ACCURACY,
    MISSING_COURSE,
    MISSING_COURSE_ACCURACY,
    UNSUITABLE_FIX_ACCURACY,
    UNSUITABLE_SPEED,
    UNSUITABLE_SPEED_ACCURACY,
    UNSUITABLE_COURSE,
    UNSUITABLE_COURSE_ACCURACY,
    INVALID_TOTAL_STEP_COUNT,
    NEW_SEGMENT_ANCHOR,
    FIX_GAP,
    SEGMENT_DURATION_EXCEEDED,
    STEP_COUNT_REGRESSED,
    STEP_SILENCE,
    COURSE_SPREAD,
    POSITION_JUMP,
    REVERSED_DISPLACEMENT,
    LATERAL_DISPLACEMENT,
    DISPLACEMENT_COURSE_MISMATCH,
    INSUFFICIENT_STEPS,
    INSUFFICIENT_DURATION,
    INSUFFICIENT_DISTANCE,
    SPEED_DISTANCE_MISMATCH,
    STRAIGHT_SEGMENT_READY,
}

data class WalkingCalibrationDecision(
    val stage: WalkingCalibrationStage,
    val outcome: WalkingCalibrationOutcome,
    val reason: WalkingCalibrationReason,
)

/**
 * In-memory counts of evaluated decisions, separated by stage. Waiting is not rejection.
 * Counts are not numbers of physical steps/fixes or a measured field rejection rate; one
 * input can encounter several stages. History resets retain them; resetForScope clears them.
 * No coordinates, sample timestamps or personal profile values are included.
 */
data class WalkingCalibrationDiagnosticSnapshot(
    val latestDecision: WalkingCalibrationDecision?,
    val decisionCounts: Map<WalkingCalibrationDecision, Long>,
)
