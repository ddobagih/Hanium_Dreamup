package kr.co.hanium.dreamup.walksafe.report

/**
 * S12-17 / owner decision 5 (2026-09-29): an explicit report still needs report-grade metric
 * distance (FP-009 design_rules[1], FP-021 prohibited[4]); a damaged tactile block seen without it
 * is never saved. The refusal only distinguishes "no damage seen" from "damage seen, distance
 * unknown", with one next action, in one sentence used for speech, screen and TalkBack.
 */
internal object ExplicitReportRejectionNotice {
    const val STATUS_NO_DAMAGE = "reportCandidate=blocked:no_depth_object"
    const val STATUS_DAMAGE_WITHOUT_DISTANCE = "reportCandidate=blocked:damage_without_metric_depth"
    const val STATUS_COORDINATE_UNVERIFIED = "reportCandidate=blocked:candidate_coordinate_gate"

    const val NO_DAMAGE_KO = "신고할 손상 점자블록이 없습니다."
    const val DAMAGE_WITHOUT_DISTANCE_KO =
        "손상된 점자블록이 보이지만 거리를 확인할 수 없어 신고를 저장하지 않았습니다. " +
            "휴대전화를 손상 부위 쪽으로 향한 채 다시 신고해 주세요."
    const val COORDINATE_UNVERIFIED_KO =
        "사진과 거리 정보가 일치하는지 확인하지 못해 신고를 저장하지 않았습니다. " +
            "휴대전화를 손상 부위 쪽으로 향한 채 다시 신고해 주세요."

    /** Refines only the "no report output" refusal; every other status passes through unchanged. */
    fun explicitStatus(status: String, damageSeenWithoutReportDistance: Boolean): String =
        if (status == STATUS_NO_DAMAGE && damageSeenWithoutReportDistance) {
            STATUS_DAMAGE_WITHOUT_DISTANCE
        } else {
            status
        }

    fun messageFor(status: String): String? = when {
        status == STATUS_COORDINATE_UNVERIFIED -> COORDINATE_UNVERIFIED_KO
        status == STATUS_DAMAGE_WITHOUT_DISTANCE -> DAMAGE_WITHOUT_DISTANCE_KO
        status == STATUS_NO_DAMAGE || status.contains("blocked:not_reportable_class") -> NO_DAMAGE_KO
        else -> null
    }
}
