package kr.co.hanium.dreamup.walksafe.report

/**
 * S12-17 / owner decision 5 (2026-09-29): an explicit report still needs report-grade metric
 * distance (FP-009 design_rules[1], FP-021 prohibited[4]); a damaged tactile block seen without it
 * is never saved. Explicit refusals use fixed, user-facing reasons for speech, screen and
 * TalkBack. Internal diagnostic values and image/location metadata are never read aloud.
 */
internal object ExplicitReportRejectionNotice {
    const val STATUS_PAYLOAD_TOO_LARGE = "reportCandidate=blocked:payload_too_large"
    const val PAYLOAD_TOO_LARGE_KO =
        "사진과 신고 정보의 크기가 저장 한도를 넘어 신고를 저장하지 않았습니다. 다시 촬영해 주세요."
    const val STATUS_METRIC_UNAVAILABLE = "reportCandidate=blocked:runtime_metric_unavailable"
    const val METRIC_UNAVAILABLE_KO = "거리 측정이 준비되지 않아 신고를 저장하지 않았습니다. 기기 점검을 완료한 뒤 다시 신고해 주세요."
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
        status == STATUS_METRIC_UNAVAILABLE -> METRIC_UNAVAILABLE_KO
        status == "reportCandidate=blocked:sensitive_storage" ->
            "휴대전화에 저장된 동의 정보를 확인할 수 없어 신고를 중단했습니다. 설정에서 동의 상태를 확인해 주세요."
        status == "reportCandidate=blocked:unreadable_report_image" ->
            "사진을 읽을 수 없어 신고를 저장하지 않았습니다. 손상 부위를 향한 채 다시 신고해 주세요."
        status == STATUS_PAYLOAD_TOO_LARGE -> PAYLOAD_TOO_LARGE_KO
        status == STATUS_COORDINATE_UNVERIFIED -> COORDINATE_UNVERIFIED_KO
        status == STATUS_DAMAGE_WITHOUT_DISTANCE -> DAMAGE_WITHOUT_DISTANCE_KO
        status == STATUS_NO_DAMAGE || status.contains("blocked:not_reportable_class") -> NO_DAMAGE_KO
        else -> AndroidReportCandidateRejection.entries.firstOrNull {
            status == "reportCandidate=blocked:candidate_${it.diagnosticCode}"
        }?.let(::candidateMessage)
    }

    private fun candidateMessage(reason: AndroidReportCandidateRejection): String = when (reason) {
        AndroidReportCandidateRejection.COORDINATE_GATE_NOT_PASSED -> COORDINATE_UNVERIFIED_KO
        AndroidReportCandidateRejection.CLASS_NOT_REPORTABLE -> NO_DAMAGE_KO
        AndroidReportCandidateRejection.DEPTH_SOURCE_NOT_SENSOR,
        AndroidReportCandidateRejection.DEPTH_NOT_MEASURED,
        AndroidReportCandidateRejection.DISTANCE_INVALID -> DAMAGE_WITHOUT_DISTANCE_KO
        AndroidReportCandidateRejection.DEPTH_EXPIRED,
        AndroidReportCandidateRejection.DETECTION_AGE_INVALID ->
            "촬영 정보가 오래되어 신고를 저장하지 않았습니다. 손상 부위를 향한 채 다시 신고해 주세요."
        AndroidReportCandidateRejection.LOCATION_UNTRUSTED ->
            "위치가 정확하지 않아 신고를 저장하지 않았습니다. 위치 확인 후 다시 신고해 주세요."
        AndroidReportCandidateRejection.DEVICE_GATE_BLOCKED ->
            "기기 준비 상태를 확인할 수 없어 신고를 저장하지 않았습니다. 길안내 준비 상태를 확인해 주세요."
        AndroidReportCandidateRejection.MODEL_NOT_ALLOWED,
        AndroidReportCandidateRejection.THRESHOLD_MISSING,
        AndroidReportCandidateRejection.SOURCE_MODEL_MISSING,
        AndroidReportCandidateRejection.MODEL_CLASS_UNMAPPED,
        AndroidReportCandidateRejection.SOURCE_COMMIT_INVALID ->
            "인식 기능을 확인할 수 없어 신고를 저장하지 않았습니다. 앱을 다시 실행한 뒤 신고해 주세요."
        AndroidReportCandidateRejection.NUMBER_INVALID,
        AndroidReportCandidateRejection.BBOX_INVALID,
        AndroidReportCandidateRejection.AUTOMATIC_TRACK_UNSTABLE,
        AndroidReportCandidateRejection.AUTOMATIC_CONFIDENCE_LOW ->
            "손상 상태를 확인할 수 없어 신고를 저장하지 않았습니다. 손상 부위를 향한 채 다시 신고해 주세요."
        AndroidReportCandidateRejection.TRIGGER_NOT_ALLOWED ->
            "신고 요청을 확인할 수 없어 저장하지 않았습니다. 다시 신고해 주세요."
    }
}
