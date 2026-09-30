package kr.co.hanium.dreamup.walksafe.navigation

/**
 * S07-12 (FP-022-DETAIL-03 interim rule): stair avoidance is applied, and the user is told that no
 * other accessibility information was confirmed. A stair-free TMAP route is not a verified safe or
 * accessible route (FP-022 prohibited: TMAP alone never guarantees sidewalk state or nearby obstacles),
 * so this sentence never says "안전" or "적합". Null for any other priority (those routes are rejected).
 */
fun routeScopeDisclosureKo(priority: String): String? =
    if (priority == "STAIR_AVOID") {
        "계단을 피하는 경로입니다. 보도 상태나 공사 같은 다른 접근성 정보는 확인되지 않았습니다."
    } else {
        null
    }
