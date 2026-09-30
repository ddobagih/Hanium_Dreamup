package kr.co.hanium.dreamup.walksafe.session

/**
 * S03-6 (FP-018 user_guidance, FP-043 design_rules[4]): the sentence said and shown when a running
 * walk is safety-stopped because its required features changed. It names the lost feature and the
 * next action; the raw readiness codes stay in the navigation diagnostics only.
 */
fun walkReadinessStopReasonKo(readiness: WalkSessionReadinessSnapshot): String {
    fun lost(requirement: WalkSessionReadinessRequirement): Boolean =
        readiness.plan.requires(requirement) &&
            readiness.observations.getValue(requirement).status != WalkSessionReadinessStatus.READY
    return when {
        lost(WalkSessionReadinessRequirement.LOCATION) ->
            "정확한 위치를 사용할 수 없어 보행 안내를 멈췄습니다. " +
                "위치 서비스와 정확한 위치 권한을 켠 뒤 새 보행 준비를 눌러 주세요."
        lost(WalkSessionReadinessRequirement.CAMERA) || lost(WalkSessionReadinessRequirement.MODEL) ->
            "카메라 장애물 안내를 사용할 수 없어 보행 안내를 멈췄습니다. " +
                "카메라 권한과 카메라·거리 기능 점검을 확인한 뒤 새 보행 준비를 눌러 주세요."
        // S03-7: every required feature is READY but the functional mode changed (for example the
        // metric distance frames were lost), or metric distance itself is no longer available.
        lost(WalkSessionReadinessRequirement.METRIC_DISTANCE) || readiness.isReady ->
            "거리 확인 기능 상태가 바뀌어 보행 안내를 멈췄습니다. " +
                "카메라가 앞을 향하게 한 뒤 새 보행 준비를 눌러 다시 점검해 주세요."
        else ->
            "필수 기능 상태가 바뀌어 보행 안내를 멈췄습니다. 새 보행 준비를 눌러 다시 점검해 주세요."
    }
}
