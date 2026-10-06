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
    val turnType: Int? = null,
    val pointType: String? = null,
    val facilityType: Int? = null,
)

data class WalkingRouteStep(
    val index: Int,
    val distanceM: Int,
    val durationS: Int,
    val points: List<RoutePoint>,
    val instruction: String?,
    val roadName: String?,
    val turnType: Int?,
    val facilityType: Int?,
)

enum class WalkingRouteDistanceSource(val wireValue: String) {
    PROVIDER_TOTAL_GEOMETRY_SCALED("provider_total_geometry_scaled"),
    PROVIDER_STEP_SUM_GEOMETRY_SCALED("provider_step_sum_geometry_scaled"),
}

data class WalkingRoute(
    val priority: String,
    val summary: WalkingRouteSummary,
    val polyline: List<RoutePoint>,
    val guidePoints: List<WalkingRouteGuidePoint>,
    val steps: List<WalkingRouteStep> = emptyList(),
    val providerRouteId: String? = null,
    val distanceSource: WalkingRouteDistanceSource? = null,
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
    /** S06-30: what [distanceM] measures; null when unknown (older responses) or no distance. */
    val distanceSource: DestinationDistanceSource? = null,
)

/**
 * S06-30: a candidate distance is measured from the search origin, never along a walking route -
 * the provider's reported radius or the backend's straight-line fallback (schemas.py distance_source).
 */
enum class DestinationDistanceSource(val wireName: String) {
    TMAP_RADIUS("tmap_radius"),
    STRAIGHT_LINE("straight_line"),
}

/**
 * The one address shown, spoken and confirmed for a candidate: road address first, then the lot
 * address. Screen, voice and final confirmation must not pick different addresses (S06-35).
 */
fun DestinationSearchResult.displayAddressOrNull(): String? =
    roadAddress?.takeIf(String::isNotBlank) ?: address?.takeIf(String::isNotBlank)

/** Speak the main/sub-number separator explicitly; keep the displayed/provider address intact. */
fun String.toSpokenDestinationAddressKo(): String =
    replace(DESTINATION_ADDRESS_SUB_NUMBER, "$1의 $2")

private val DESTINATION_ADDRESS_SUB_NUMBER = Regex("(?<![0-9-])([0-9]+)-([0-9]+)(?![0-9-])")

data class DestinationSearchResponse(
    val provider: String,
    val query: String,
    val results: List<DestinationSearchResult>,
    /** Provider-wide total before deduplication and validation; null when the server did not report it. */
    val totalCount: Int? = null,
    /** The provider's paging answer, not the unique count after normalization; null when unknown. */
    val moreResultsAvailable: Boolean? = null,
)

/**
 * Whether a larger search request can still add candidates (S06-10, S06-11). The provider's paging
 * answer decides; dropping duplicates or invalid items must not end the search early. Only when the
 * server did not report it does the old "a full page came back" guess apply. Never past [maxResults].
 */
fun destinationSearchCanRequestMore(
    resultCount: Int,
    requestedCount: Int,
    maxResults: Int,
    providerMoreAvailable: Boolean?,
): Boolean {
    if (resultCount >= maxResults || requestedCount >= maxResults) return false
    return providerMoreAvailable ?: (resultCount >= requestedCount)
}
