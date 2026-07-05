package kr.co.hanium.dreamup.walksafe.navigation

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val name: String? = null,
)

data class WalkingRouteSummary(
    val distanceM: Int,
    val durationS: Int,
)

data class WalkingRouteGuidePoint(
    val index: Int,
    val point: RoutePoint,
    val instruction: String?,
    val distanceFromStartM: Int?,
    val remainingDistanceM: Int?,
    val bearingDeg: Float? = null,
    val turnType: String? = null,
    val pointType: String? = null,
    val facilityType: String? = null,
)

data class WalkingRoute(
    val priority: String,
    val summary: WalkingRouteSummary,
    val polyline: List<RoutePoint>,
    val guidePoints: List<WalkingRouteGuidePoint>,
)

data class WalkingRouteRequest(
    val origin: RoutePoint,
    val destination: RoutePoint,
    val priority: String = "STAIR_AVOID",
)

data class DestinationSearchResult(
    val id: String,
    val name: String,
    val point: RoutePoint,
    val address: String?,
    val roadAddress: String?,
    val category: String?,
    val distanceM: Int?,
    val resultType: String = "poi",
)

data class DestinationSearchResponse(
    val provider: String,
    val query: String,
    val results: List<DestinationSearchResult>,
)
