package kr.co.hanium.dreamup.walksafe.navigation

import kotlin.math.ceil

/** A cue belongs to one installed route and one approach stage, never just its spoken text. */
data class RouteSpeechCueToken(
    val routeRevision: Long,
    val cueRevision: Long,
    val guideIndex: Int?,
    val distanceBand: Int,
)

internal fun routeGuidanceDistanceBand(distanceM: Int?): Int = when {
    distanceM == null -> -1
    distanceM <= 12 -> 0
    distanceM <= 30 -> 1
    distanceM <= 60 -> 2
    distanceM <= 100 -> 3
    else -> 3 + ceil(distanceM / 100.0).toInt()
}

/**
 * TMAP pedestrian Point turnType is the maneuver at that Point. Its description can also
 * describe the following LineString, so its distance must not be parsed as approach distance.
 * https://tmapapi.tmapmobility.com/webservice/docs/tmapRoutePedestrianDoc.html
 */
internal fun WalkingRouteGuidePoint.maneuverInstruction(): String? = when (turnType) {
    11 -> "직진하세요."
    12 -> "왼쪽으로 꺾으세요."
    13 -> "오른쪽으로 꺾으세요."
    14 -> "뒤로 돌아가세요."
    16 -> "8시 방향 왼쪽 길로 이동하세요."
    17 -> "10시 방향 왼쪽 길로 이동하세요."
    18 -> "2시 방향 오른쪽 길로 이동하세요."
    19 -> "4시 방향 오른쪽 길로 이동하세요."
    201 -> "목적지 근처 안내 지점입니다. 실제 목적지를 확인해 주세요."
    else -> instruction?.takeIf(String::isNotBlank)
}
