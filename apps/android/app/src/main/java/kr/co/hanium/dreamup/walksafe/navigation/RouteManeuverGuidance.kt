package kr.co.hanium.dreamup.walksafe.navigation

import kotlin.math.ceil

/** A cue belongs to one installed route and one approach stage, never just its spoken text. */
data class RouteSpeechCueToken(
    val routeRevision: Long,
    val cueRevision: Long,
    val guideIndex: Int?,
    val distanceBand: Int,
    val directionOnly: Boolean = false,
    /** Installed route identity captured with this cue, in addition to its install revision. */
    val routeId: String? = null,
    /** Only explicitly non-relative sentences may omit the facing observation. */
    val requiresFacingEvidence: Boolean = true,
    val facingObservedAtElapsedRealtimeMs: Long? = null,
    val facingValidUntilElapsedRealtimeMs: Long? = null,
    /** Separate authorization; the original sensor observation and its normal lease stay intact. */
    val directionGraceEpisodeId: Long? = null,
    val directionGraceStartedAtElapsedRealtimeMs: Long? = null,
    val directionGraceDeadlineElapsedRealtimeMs: Long? = null,
) {
    internal fun hasCurrentFacingEvidence(nowMs: Long): Boolean {
        if (!requiresFacingEvidence) return true
        val observedAt = facingObservedAtElapsedRealtimeMs ?: return false
        val validUntil = facingValidUntilElapsedRealtimeMs ?: return false
        return RouteFacingGuidance.isObservationAgeCurrent(observedAt, nowMs) && validUntil >= observedAt &&
            validUntil < Long.MAX_VALUE && nowMs <= validUntil
    }
}

/** Non-relative information belongs to the completed action, not to its facing lease. */
data class RouteInformationCueToken(
    val routeRevision: Long,
    val routeId: String,
    val actionCueRevision: Long,
    val guideIndex: Int?,
    val positionEstimateUncertain: Boolean,
)

data class RouteInformationUtterance(
    val message: String,
    val cueToken: RouteInformationCueToken,
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
    12 -> "9시 방향으로 꺾으세요."
    13 -> "3시 방향으로 꺾으세요."
    14 -> "6시 방향으로 돌아가세요."
    16 -> "8시 방향 길로 이동하세요."
    17 -> "10시 방향 길로 이동하세요."
    18 -> "2시 방향 길로 이동하세요."
    19 -> "4시 방향 길로 이동하세요."
    125 -> "육교 진입 지점입니다."
    126 -> "지하보도 진입 지점입니다."
    128 -> "경사로 진입 지점입니다."
    218 -> "엘리베이터 진입 지점입니다."
    // These describe the crossing relative to the incoming route, never permission to cross.
    212 -> "다음 횡단보도는 9시 방향입니다."
    213 -> "다음 횡단보도는 3시 방향입니다."
    214 -> "다음 횡단보도는 8시 방향입니다."
    215 -> "다음 횡단보도는 10시 방향입니다."
    216 -> "다음 횡단보도는 2시 방향입니다."
    217 -> "다음 횡단보도는 4시 방향입니다."
    201 -> "목적지 근처 안내 지점입니다. 실제 목적지를 확인해 주세요."
    // Provider text is not verified as a motion instruction, whether the code is missing or unknown.
    else -> null
}

internal fun WalkingRouteGuidePoint.facilityNameKo(): String? = when (turnType) {
    125 -> "육교"
    126 -> "지하보도"
    128 -> "경사로"
    218 -> "엘리베이터"
    else -> null
}

/**
 * S08-27: the next mapped maneuver as a route overview, for when the facing is unknown and no
 * command may be executed from the current position. It has no movement imperative; ordinary
 * turns have no clock number, and a directional crossing's clock explicitly uses the route frame.
 * Provider text and other turn types give no overview.
 * nav-F7: the distance is measured along the route ("경로를 따라 Nm 가면"), never "ahead" of a
 * facing that is not known. nav-F3: a crossing marker is described with its reference notice and
 * any mapped turn, never as a crossing command or a signal judgement.
 */
internal fun WalkingRouteGuidePoint.maneuverOverview(distanceM: Int?): String? {
    facilityNameKo()?.let { facility ->
        val approach = if (distanceM != null && distanceM > 0) "경로를 따라 ${distanceM}m 가면" else "다음 안내 지점에"
        return "$approach $facility 진입 지점이 있습니다."
    }
    val maneuver = when (turnType) {
        11 -> "경로는 직진으로 이어집니다."
        12 -> "경로 기준 9시 방향 회전이 있습니다."
        13 -> "경로 기준 3시 방향 회전이 있습니다."
        14 -> "경로 기준 6시 방향 회전이 있습니다."
        16 -> "경로 기준 8시 방향 회전이 있습니다."
        17 -> "경로 기준 10시 방향 회전이 있습니다."
        18 -> "경로 기준 2시 방향 회전이 있습니다."
        19 -> "경로 기준 4시 방향 회전이 있습니다."
        else -> null
    }
    val place = if (distanceM != null && distanceM > 0) "경로를 따라 ${distanceM}m 가면 다음 안내 지점" else "다음 안내 지점"
    if (CrosswalkReferencePolicy.isCrosswalkGuide(this)) {
        if (turnType in 212..217) {
            return "${place}에서 경로 기준으로 ${maneuverInstruction()} $CROSSWALK_REFERENCE_NOTICE_KO"
        }
        val turn = maneuver?.let { if (turnType == 11) " 그 지점에서 $it" else " 그 지점에는 $it" }.orEmpty()
        return "${place}에 횡단보도가 있습니다.$turn $CROSSWALK_REFERENCE_NOTICE_KO"
    }
    maneuver ?: return null
    return if (turnType == 11) "${place}에서 $maneuver" else "${place}에는 $maneuver"
}
