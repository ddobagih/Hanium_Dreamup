package kr.co.hanium.dreamup.walksafe.session

/** Describe the evidence actually checked; education is not proof of current outdoor conditions. */
fun OfficialEnvironmentAssessment.preparationNoticeKo(): String = when {
    !canStartWalk -> "환경 점검을 완료하지 못했습니다. 다시 점검해 주세요."
    conditionallyAllowed ->
        "센서 점검은 통과했지만 날씨·보도 등 일부 주변 환경은 미확인입니다."
    else -> "사용 중인 센서와 주변 환경 확인을 마쳤습니다."
}
