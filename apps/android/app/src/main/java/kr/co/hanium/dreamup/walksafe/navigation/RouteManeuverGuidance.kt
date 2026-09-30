package kr.co.hanium.dreamup.walksafe.navigation

import kotlin.math.ceil

/** A cue belongs to one installed route and one approach stage, never just its spoken text. */
data class RouteSpeechCueToken(
    val routeRevision: Long,
    val cueRevision: Long,
    val guideIndex: Int?,
    val distanceBand: Int,
    val directionOnly: Boolean = false,
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
    // Provider text is not verified as a motion instruction, whether the code is missing or unknown.
    else -> null
}

/**
 * S08-27: the next mapped maneuver as a route overview, for when the facing is unknown and no
 * command may be executed from the current position. It has no imperative and no clock number
 * (one clock frame per sentence); provider text and other turn types give no overview.
 * nav-F7: the distance is measured along the route ("경로를 따라 Nm 가면"), never "ahead" of a
 * facing that is not known. nav-F3: a crossing marker is described with its reference notice and
 * any mapped turn, never as a crossing command or a signal judgement.
 */
internal fun WalkingRouteGuidePoint.maneuverOverview(distanceM: Int?): String? {
    val maneuver = when (turnType) {
        11 -> "경로는 직진으로 이어집니다."
        12 -> "왼쪽 회전이 있습니다."
        13 -> "오른쪽 회전이 있습니다."
        14 -> "되돌아가는 회전이 있습니다."
        16, 17 -> "왼쪽 길로 가는 회전이 있습니다."
        18, 19 -> "오른쪽 길로 가는 회전이 있습니다."
        else -> null
    }
    val place = if (distanceM != null && distanceM > 0) "경로를 따라 ${distanceM}m 가면 다음 안내 지점" else "다음 안내 지점"
    if (CrosswalkReferencePolicy.isCrosswalkGuide(this)) {
        val turn = maneuver?.let { if (turnType == 11) " 그 지점에서 $it" else " 그 지점에는 $it" }.orEmpty()
        return "${place}에 횡단보도가 있습니다.$turn $CROSSWALK_REFERENCE_NOTICE_KO"
    }
    maneuver ?: return null
    return if (turnType == 11) "${place}에서 $maneuver" else "${place}에는 $maneuver"
}
