package kr.co.hanium.dreamup.walksafe.device

/** Describes existing stop facts; this does not select a cause or grant recovery permission. */
fun WalkRuntimeSafetyStopStatus.userFacingSummary(): String {
    val initial = initialCauses.sortedBy { it.name }.joinToString(", ") { it.userFacingReason() }
        .ifEmpty { "확인 중" }
    val added = (stop.causes - initialCauses).sortedBy { it.name }.joinToString(", ") { it.userFacingReason() }
    val recovery = when {
        cleanupExhausted -> "안전 정리를 완료하지 못했습니다. 앱을 다시 시작한 뒤 기기 점검을 다시 진행해 주세요."
        cleanupComplete -> "원인을 확인하고 기기 점검을 다시 통과한 뒤 새 보행을 시작하세요. 자동으로 재개하지 않습니다."
        else -> "안전 정리 중입니다. 정리가 끝나고 기기 점검을 통과하기 전에는 새 보행을 시작하지 마세요."
    }
    return listOf("처음 중지한 이유: $initial", added.takeIf(String::isNotBlank)?.let { "추가 확인된 이유: $it" }, recovery)
        .filterNotNull().joinToString("\n")
}

private fun WalkRuntimeSafetyStopCause.userFacingReason(): String = when (this) {
    WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST -> "카메라 영상 확인 불가"
    WalkRuntimeSafetyStopCause.DEPTH_TRUST_LOST -> "거리 정보 확인 불가"
    WalkRuntimeSafetyStopCause.GPS_TRUST_LOST -> "위치 정보 확인 불가"
    WalkRuntimeSafetyStopCause.RISK_TRUST_LOST -> "위험 판단 정보 확인 불가"
    WalkRuntimeSafetyStopCause.TTS_TRUST_LOST -> "음성 안내 확인 불가"
    WalkRuntimeSafetyStopCause.BATTERY_CRITICAL -> "배터리 부족"
    WalkRuntimeSafetyStopCause.STORAGE_CRITICAL -> "저장 공간 부족"
    WalkRuntimeSafetyStopCause.THERMAL_CRITICAL -> "과도한 발열"
    WalkRuntimeSafetyStopCause.FRAME_AGE_EXCEEDED -> "영상 정보 지연"
    WalkRuntimeSafetyStopCause.INFERENCE_LATENCY_EXCEEDED -> "인식 처리 지연"
    WalkRuntimeSafetyStopCause.EPOCH_MISMATCH -> "보행 상태 정보 불일치"
    WalkRuntimeSafetyStopCause.INVALID_TIME -> "측정 시각 확인 불가"
    WalkRuntimeSafetyStopCause.EVIDENCE_EXPIRED -> "확인 정보 만료"
}
