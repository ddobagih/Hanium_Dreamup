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

/** Installed-route voice is concise; scope disclosure remains available separately for presentation. */
@Suppress("UNUSED_PARAMETER")
fun routeStartAnnouncementKo(destinationName: String?, distanceM: Int, locationReady: Boolean, priority: String): String {
    require(distanceM >= 0)
    val name = destinationName?.trim()?.takeIf { it.isNotEmpty() } ?: "목적지"
    val status = if (locationReady) {
        "${name}까지 안내를 시작합니다. 경로 거리는 약 ${distanceM}미터입니다."
    } else {
        "${name}까지 약 ${distanceM}미터의 경로를 찾았습니다. 현재 위치를 확인하면 이동 방향을 안내하겠습니다."
    }
    return status
}
