package kr.co.hanium.dreamup.walksafe.navigation

import java.io.OutputStreamWriter
import java.io.UnsupportedEncodingException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.NetworkResponseTooLargeException
import kr.co.hanium.dreamup.walksafe.network.cancellableHttpCall
import kr.co.hanium.dreamup.walksafe.network.readBoundedResponse
import org.json.JSONArray
import org.json.JSONObject

/** Synchronous backend client; callers must run route/search requests off the Android main thread. */
class BackendWalkingRouteClient(
    private val transport: WalkingRouteTransport = HttpUrlConnectionWalkingRouteTransport(),
) {
    fun fetchRoute(
        session: GatewayFieldSession,
        request: WalkingRouteRequest,
    ): WalkingRoute = fetchRouteCall(session, request).execute()

    fun fetchRouteCall(
        session: GatewayFieldSession,
        request: WalkingRouteRequest,
    ): CancellableNetworkCall<WalkingRoute> {
        val endpoint = session.gatewayBaseUrl.trimEnd('/') + "/api/navigation/walking"
        return transport.postCall(endpoint, request.toJson().toString(), session).map { response ->
            parseWalkingRoute(JSONObject(response), request)
        }
    }

    fun searchDestinations(
        session: GatewayFieldSession,
        query: String,
        limit: Int,
        origin: RoutePoint?,
    ): DestinationSearchResponse = searchDestinationsCall(session, query, limit, origin).execute()

    fun searchDestinationsCall(
        session: GatewayFieldSession,
        query: String,
        limit: Int,
        origin: RoutePoint?,
    ): CancellableNetworkCall<DestinationSearchResponse> {
        val safeQuery = normalizeDestinationQuery(query)
        if (safeQuery.isEmpty()) {
            return CancellableNetworkCall.blocking {
                DestinationSearchResponse(provider = "tmap_poi", query = "", results = emptyList())
            }
        }
        val safeLimit = limit.coerceIn(1, MAX_DESTINATION_RESULT_LIMIT)
        val endpoint = buildString {
            append(session.gatewayBaseUrl.trimEnd('/'))
            append("/api/navigation/destinations/search")
            append("?query=")
            append(encodeQuery(safeQuery))
            append("&limit=")
            append(safeLimit)
            if (origin != null) {
                append("&origin_lat=")
                append(origin.latitude)
                append("&origin_lng=")
                append(origin.longitude)
            }
        }
        return transport.getCall(endpoint, session).map { response ->
            parseDestinationSearchResponse(JSONObject(response), query = safeQuery, limit = safeLimit)
        }
    }

    private fun WalkingRouteRequest.toJson(): JSONObject {
        return JSONObject()
            .put("origin", origin.toJson())
            .put("destination", destination.toJson())
            .put("priority", priority)
    }

    private fun RoutePoint.toJson(): JSONObject {
        val json = JSONObject()
            .put("latitude", latitude)
            .put("longitude", longitude)
        if (!name.isNullOrBlank()) json.put("name", name)
        return json
    }

    private fun encodeQuery(query: String): String {
        return try {
            URLEncoder.encode(query, Charsets.UTF_8.name())
        } catch (_: UnsupportedEncodingException) {
            query
        }
    }
}

interface WalkingRouteTransport {
    fun post(url: String, body: String, session: GatewayFieldSession): String
    fun get(url: String, session: GatewayFieldSession): String

    fun postCall(url: String, body: String, session: GatewayFieldSession): CancellableNetworkCall<String> {
        return CancellableNetworkCall.blocking { post(url, body, session) }
    }

    fun getCall(url: String, session: GatewayFieldSession): CancellableNetworkCall<String> {
        return CancellableNetworkCall.blocking { get(url, session) }
    }
}

/** Blocking transport that fully consumes each response before disconnecting the connection. */
class HttpUrlConnectionWalkingRouteTransport : WalkingRouteTransport {
    override fun post(url: String, body: String, session: GatewayFieldSession): String {
        return postCall(url, body, session).execute()
    }

    override fun postCall(
        url: String,
        body: String,
        session: GatewayFieldSession,
    ): CancellableNetworkCall<String> = requestCall(
        url = url,
        method = "POST",
        body = body,
        session = session,
        failureReason = "walking_route_failed",
    )

    override fun get(url: String, session: GatewayFieldSession): String {
        return getCall(url, session).execute()
    }

    override fun getCall(url: String, session: GatewayFieldSession): CancellableNetworkCall<String> {
        return requestCall(
            url = url,
            method = "GET",
            body = null,
            session = session,
            failureReason = "destination_search_failed",
        )
    }

    private fun requestCall(
        url: String,
        method: String,
        body: String?,
        session: GatewayFieldSession,
        failureReason: String,
    ): CancellableNetworkCall<String> = cancellableHttpCall { cancellation ->
        val connection = URL(url).openConnection() as HttpURLConnection
        cancellation.attach(connection)
        try {
            connection.apply {
                requestMethod = method
                connectTimeout = 4_000
                readTimeout = 8_000
                instanceFollowRedirects = false
                doOutput = body != null
                if (body != null) setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "close")
                session.requestHeaders().forEach(::setRequestProperty)
            }
            if (body != null) {
                connection.outputStream.use { output ->
                    cancellation.attach(output)
                    try {
                        OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                            writer.write(body)
                        }
                    } finally {
                        cancellation.detach(output)
                    }
                }
            }
            val response = connection.readBoundedResponse(WALKING_ROUTE_MAX_RESPONSE_BYTES, cancellation)
            if (response.statusCode !in 200..299) {
                throw GatewayProxyHttpException(
                    statusCode = response.statusCode,
                    requestReason = failureReason,
                    backendCode = parseBackendErrorCode(response.body),
                )
            }
            response.body
        } finally {
            cancellation.detach(connection)
            connection.disconnect()
        }
    }
}

class GatewayProxyHttpException(
    val statusCode: Int,
    val requestReason: String,
    val backendCode: String? = null,
) : IllegalStateException(
    "gateway proxy request failed: $requestReason status=$statusCode code=${backendCode ?: "unknown"}",
)

enum class NavigationBackendErrorKind(val statusToken: String, val userMessage: String) {
    AUTHENTICATION("authentication", "길안내 로그인을 다시 확인해 주세요."),
    PROVIDER_CONFIGURATION("provider_configuration", "TMAP 서버 설정 오류로 길안내를 사용할 수 없습니다."),
    RATE_LIMITED("rate_limited", "TMAP 사용 한도에 도달해 새 경로를 요청할 수 없습니다."),
    TIMEOUT("timeout", "TMAP 응답 시간이 초과되었습니다."),
    PROVIDER_UNAVAILABLE("provider_unavailable", "TMAP 연결 장애로 새 경로를 확인할 수 없습니다."),
    INVALID_RESPONSE("invalid_response", "TMAP 경로 응답을 안전하게 확인할 수 없습니다."),
    NETWORK("network", "네트워크 연결 오류로 TMAP 경로를 확인할 수 없습니다."),
    BACKEND_UNAVAILABLE("backend_unavailable", "길안내 서버 장애로 TMAP 경로를 확인할 수 없습니다."),
    UNKNOWN("unknown", "길안내 오류 종류를 확인할 수 없습니다."),
}

data class NavigationBackendFailure(
    val kind: NavigationBackendErrorKind,
    val backendCode: String? = null,
    val statusCode: Int? = null,
)

fun classifyNavigationBackendFailure(error: Throwable): NavigationBackendFailure {
    if (error is GatewayProxyHttpException) {
        val code = error.backendCode
        val kind = when {
            error.statusCode in setOf(401, 403) || code == "gateway_upstream_auth_failed" ->
                NavigationBackendErrorKind.AUTHENTICATION
            error.statusCode == 429 -> NavigationBackendErrorKind.RATE_LIMITED
            code in setOf("tmap_app_key_missing", "tmap_invalid_api_key", "walking_route_provider_invalid") ->
                NavigationBackendErrorKind.PROVIDER_CONFIGURATION
            code == "tmap_timeout" || error.statusCode == 504 -> NavigationBackendErrorKind.TIMEOUT
            code in setOf("tmap_network_error", "tmap_provider_error", "route_unavailable") ->
                NavigationBackendErrorKind.PROVIDER_UNAVAILABLE
            code == "invalid_tmap_response" || code?.startsWith("route_") == true ->
                NavigationBackendErrorKind.INVALID_RESPONSE
            error.statusCode >= 500 -> NavigationBackendErrorKind.BACKEND_UNAVAILABLE
            else -> NavigationBackendErrorKind.UNKNOWN
        }
        return NavigationBackendFailure(kind, code, error.statusCode)
    }
    val kind = when (error) {
        is SocketTimeoutException -> NavigationBackendErrorKind.TIMEOUT
        is GatewayResponseProtocolException,
        is NetworkResponseTooLargeException,
        -> NavigationBackendErrorKind.INVALID_RESPONSE
        is IOException -> NavigationBackendErrorKind.NETWORK
        else -> NavigationBackendErrorKind.UNKNOWN
    }
    return NavigationBackendFailure(kind)
}

class ConsecutiveTmapFailureGuard(
    private val safetyStopThreshold: Int = 2,
) {
    init {
        require(safetyStopThreshold > 0)
    }

    private var consecutiveFailures = 0

    @Synchronized
    fun recordFailure(): Boolean {
        consecutiveFailures += 1
        return consecutiveFailures >= safetyStopThreshold
    }

    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
    }

    @Synchronized
    fun reset() {
        consecutiveFailures = 0
    }

    @Synchronized
    fun failureCount(): Int = consecutiveFailures
}

private fun parseBackendErrorCode(body: String): String? = runCatching {
    val root = JSONObject(body)
    val detail = root.optJSONObject("detail") ?: root
    detail.optString("code")
        .trim()
        .takeIf { it.matches(BACKEND_ERROR_CODE) }
}.getOrNull()

fun parseWalkingRoute(json: JSONObject, request: WalkingRouteRequest): WalkingRoute {
    requireProtocolValue(json, "schema_version", WALKING_ROUTE_SCHEMA)
    requireProtocolValue(json, "provider", TMAP_WALKING_PROVIDER)
    val providerResultCode = json.optNonNegativeIntOrNull("provider_result_code")
    if (providerResultCode != TMAP_SUCCESS_RESULT_CODE) {
        throw GatewayResponseProtocolException("route_provider_result_invalid")
    }
    val summary = json.optJSONObject("summary") ?: throw GatewayResponseProtocolException("route_summary_missing")
    val distanceM = summary.optNonNegativeIntOrNull("distance_m")
        ?: throw GatewayResponseProtocolException("route_distance_invalid")
    val durationS = summary.optNonNegativeIntOrNull("duration_s")
        ?: throw GatewayResponseProtocolException("route_duration_invalid")
    val polyline = json.optJSONArray("polyline").toRequiredRoutePoints()
        ?: throw GatewayResponseProtocolException("route_polyline_invalid")
    val steps = json.optJSONArray("steps").toRouteStepsOrNull()
        ?: throw GatewayResponseProtocolException("route_steps_invalid")
    val guideArray = if (!json.has("guide_points") || json.isNull("guide_points")) {
        null
    } else {
        json.opt("guide_points") as? JSONArray
            ?: throw GatewayResponseProtocolException("route_guide_points_invalid")
    }
    val guidePoints = guideArray.toGuidePointsOrNull()
        ?: throw GatewayResponseProtocolException("route_guide_points_invalid")
    val priority = json.optString("priority")
    if (priority != STAIR_AVOID_PRIORITY) {
        throw GatewayResponseProtocolException("route_priority_invalid")
    }
    val providerRouteId = when {
        !json.has("provider_route_id") || json.isNull("provider_route_id") -> null
        json.opt("provider_route_id") !is String -> throw GatewayResponseProtocolException("route_provider_id_invalid")
        else -> (json.opt("provider_route_id") as String)
            .trim()
            .takeIf { it.isNotEmpty() && it.length <= MAX_PROVIDER_ROUTE_ID_LENGTH }
            ?: throw GatewayResponseProtocolException("route_provider_id_invalid")
    }
    val route = WalkingRoute(
        priority = priority,
        summary = WalkingRouteSummary(
            distanceM = distanceM,
            durationS = durationS,
        ),
        polyline = polyline,
        guidePoints = guidePoints,
        steps = steps,
        providerRouteId = providerRouteId,
    )
    validateWalkingRoute(route, request)
    return route
}

fun parseDestinationSearchResponse(
    json: JSONObject,
    query: String,
    limit: Int = MAX_DESTINATION_RESULT_LIMIT,
): DestinationSearchResponse {
    requireProtocolValue(json, "schema_version", DESTINATION_SEARCH_SCHEMA)
    requireProtocolValue(json, "provider", TMAP_POI_PROVIDER)
    if (limit !in 1..MAX_DESTINATION_RESULT_LIMIT) {
        throw GatewayResponseProtocolException("destination_limit_invalid")
    }
    val normalizedQuery = normalizeDestinationQuery(query)
    val responseQuery = json.opt("query") as? String
    if (responseQuery != normalizedQuery) {
        throw GatewayResponseProtocolException("destination_query_mismatch")
    }
    val results = json.optJSONArray("results")
        ?: throw GatewayResponseProtocolException("destination_results_invalid")
    val parsedResults = results.toDestinationSearchResultsOrNull()
        ?: throw GatewayResponseProtocolException("destination_results_invalid")
    if (parsedResults.size > limit || parsedResults.map { it.id }.distinct().size != parsedResults.size) {
        throw GatewayResponseProtocolException("destination_results_invalid")
    }
    return DestinationSearchResponse(
        provider = TMAP_POI_PROVIDER,
        query = normalizedQuery,
        results = parsedResults,
    )
}

private fun validateWalkingRoute(route: WalkingRoute, request: WalkingRouteRequest) {
    if (
        !request.origin.isValidCoordinate() ||
        !request.destination.isValidCoordinate() ||
        route.priority != request.priority
    ) {
        throw GatewayResponseProtocolException("route_request_mismatch")
    }
    if (route.summary.distanceM <= 0 || route.summary.durationS <= 0) {
        throw GatewayResponseProtocolException("route_summary_invalid")
    }
    if (
        routePointDistanceM(request.origin, route.polyline.first()) > ROUTE_ENDPOINT_MAX_DISTANCE_M ||
        routePointDistanceM(request.destination, route.polyline.last()) > ROUTE_ENDPOINT_MAX_DISTANCE_M
    ) {
        throw GatewayResponseProtocolException("route_endpoint_mismatch")
    }

    val geometryDistanceM = route.polyline.zipWithNext().sumOf { (start, end) ->
        routePointDistanceM(start, end)
    }
    val minimumSummaryM =
        geometryDistanceM * ROUTE_SUMMARY_GEOMETRY_MIN_RATIO - ROUTE_SUMMARY_GEOMETRY_ABSOLUTE_TOLERANCE_M
    val maximumSummaryM =
        geometryDistanceM * ROUTE_SUMMARY_GEOMETRY_MAX_RATIO + ROUTE_SUMMARY_GEOMETRY_ABSOLUTE_TOLERANCE_M
    if (geometryDistanceM <= 0.0 || route.summary.distanceM.toDouble() !in minimumSummaryM..maximumSummaryM) {
        throw GatewayResponseProtocolException("route_geometry_summary_mismatch")
    }

    val guideDistanceToleranceM = max(
        GUIDE_DISTANCE_ABSOLUTE_TOLERANCE_M,
        route.summary.distanceM * GUIDE_DISTANCE_RELATIVE_TOLERANCE,
    )
    var previousProjectedDistanceM = -GUIDE_MONOTONIC_TOLERANCE_M
    var previousDeclaredDistanceM = -GUIDE_MONOTONIC_TOLERANCE_M
    var previousRemainingDistanceM = route.summary.distanceM + GUIDE_MONOTONIC_TOLERANCE_M
    route.guidePoints.forEachIndexed { index, guide ->
        val distanceFromStartM = guide.distanceFromStartM
        val remainingDistanceM = guide.remainingDistanceM
        if (
            guide.index != index ||
            (distanceFromStartM != null && distanceFromStartM > route.summary.distanceM) ||
            (remainingDistanceM != null && remainingDistanceM > route.summary.distanceM)
        ) {
            throw GatewayResponseProtocolException("route_guide_distance_invalid")
        }
        val projection = projectPointToPolyline(guide.point, route.polyline)
        if (projection == null || projection.distanceToRouteM > GUIDE_POINT_MAX_ROUTE_DISTANCE_M) {
            throw GatewayResponseProtocolException("route_guide_geometry_invalid")
        }
        val projectedSummaryDistanceM =
            projection.distanceFromStartM * route.summary.distanceM / geometryDistanceM
        val declaredDistanceM = distanceFromStartM?.toDouble()
            ?: remainingDistanceM?.let { route.summary.distanceM - it.toDouble() }
            ?: projectedSummaryDistanceM
        if (
            abs(declaredDistanceM - projectedSummaryDistanceM) > guideDistanceToleranceM ||
            (distanceFromStartM != null &&
                remainingDistanceM != null &&
                abs(distanceFromStartM + remainingDistanceM - route.summary.distanceM) > guideDistanceToleranceM) ||
            projectedSummaryDistanceM + GUIDE_MONOTONIC_TOLERANCE_M < previousProjectedDistanceM ||
            declaredDistanceM + GUIDE_MONOTONIC_TOLERANCE_M < previousDeclaredDistanceM ||
            (remainingDistanceM != null &&
                remainingDistanceM > previousRemainingDistanceM + GUIDE_MONOTONIC_TOLERANCE_M)
        ) {
            throw GatewayResponseProtocolException("route_guide_order_invalid")
        }
        previousProjectedDistanceM = projectedSummaryDistanceM
        previousDeclaredDistanceM = declaredDistanceM
        if (remainingDistanceM != null) previousRemainingDistanceM = remainingDistanceM.toDouble()
    }
}

private data class RoutePointProjection(
    val distanceToRouteM: Double,
    val distanceFromStartM: Double,
)

private fun projectPointToPolyline(point: RoutePoint, polyline: List<RoutePoint>): RoutePointProjection? {
    var best: RoutePointProjection? = null
    var traversedM = 0.0
    for ((start, end) in polyline.zipWithNext()) {
        val segmentLengthM = routePointDistanceM(start, end)
        if (segmentLengthM <= 0.0) continue
        val meanLatitudeRadians = Math.toRadians((start.latitude + end.latitude + point.latitude) / 3.0)
        val metersPerLongitudeDegree = METERS_PER_LATITUDE_DEGREE * max(0.01, cos(meanLatitudeRadians))
        val segmentX = (end.longitude - start.longitude) * metersPerLongitudeDegree
        val segmentY = (end.latitude - start.latitude) * METERS_PER_LATITUDE_DEGREE
        val pointX = (point.longitude - start.longitude) * metersPerLongitudeDegree
        val pointY = (point.latitude - start.latitude) * METERS_PER_LATITUDE_DEGREE
        val segmentNormSquared = segmentX * segmentX + segmentY * segmentY
        val fraction = if (segmentNormSquared > 0.0) {
            (pointX * segmentX + pointY * segmentY).div(segmentNormSquared).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val projectedPoint = RoutePoint(
            latitude = start.latitude + (end.latitude - start.latitude) * fraction,
            longitude = start.longitude + (end.longitude - start.longitude) * fraction,
        )
        val candidate = RoutePointProjection(
            distanceToRouteM = routePointDistanceM(point, projectedPoint),
            distanceFromStartM = traversedM + segmentLengthM * fraction,
        )
        if (best == null || candidate.distanceToRouteM < requireNotNull(best).distanceToRouteM) {
            best = candidate
        }
        traversedM += segmentLengthM
    }
    return best
}

private fun routePointDistanceM(start: RoutePoint, end: RoutePoint): Double {
    val startLat = Math.toRadians(start.latitude)
    val endLat = Math.toRadians(end.latitude)
    val deltaLat = endLat - startLat
    val deltaLng = Math.toRadians(end.longitude - start.longitude)
    val value = sin(deltaLat / 2.0) * sin(deltaLat / 2.0) +
        cos(startLat) * cos(endLat) * sin(deltaLng / 2.0) * sin(deltaLng / 2.0)
    val bounded = min(1.0, max(0.0, value))
    return EARTH_RADIUS_M * 2.0 * atan2(sqrt(bounded), sqrt(1.0 - bounded))
}

private fun RoutePoint.isValidCoordinate(): Boolean =
    latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

private fun normalizeDestinationQuery(query: String): String = query.trim().replace(Regex("\\s+"), " ")

private fun requireProtocolValue(json: JSONObject, name: String, expected: String) {
    if (json.optString(name) != expected) {
        throw GatewayResponseProtocolException("${name}_invalid")
    }
}

private fun JSONArray?.toRequiredRoutePoints(): List<RoutePoint>? {
    if (this == null || length() < 2) return null
    val points = (0 until length()).map { index ->
        optJSONObject(index)?.toRoutePointOrNull() ?: return null
    }
    return points.takeIf { points.distinctBy { point -> point.latitude to point.longitude }.size >= 2 }
}

private fun JSONArray?.toGuidePointsOrNull(): List<WalkingRouteGuidePoint>? {
    if (this == null) return emptyList()
    return (0 until length()).map { index ->
        val item = optJSONObject(index) ?: return null
        val guideIndex = item.optNonNegativeIntOrNull("index") ?: return null
        val distanceFromStartM = item.optNonNegativeIntOrNull("distance_from_start_m")
        if (item.hasNonNull("distance_from_start_m") && distanceFromStartM == null) return null
        val remainingDistanceM = item.optNonNegativeIntOrNull("remaining_distance_m")
        if (item.hasNonNull("remaining_distance_m") && remainingDistanceM == null) return null
        val bearing = item.optNullableFiniteFloat("bearing_deg")
        if (item.hasNonNull("bearing_deg") && (bearing == null || bearing !in 0f..<360f)) return null
        val turnType = item.optNonNegativeIntOrNull("turn_type")
        if (item.hasNonNull("turn_type") && turnType == null) return null
        val facilityType = item.optNonNegativeIntOrNull("facility_type")
        if (item.hasNonNull("facility_type") && facilityType == null) return null
        WalkingRouteGuidePoint(
            index = guideIndex,
            point = item.optJSONObject("point")?.toRoutePointOrNull() ?: return null,
            instruction = item.optString("instruction").takeIf { it.isNotBlank() },
            distanceFromStartM = distanceFromStartM,
            remainingDistanceM = remainingDistanceM,
            bearingDeg = bearing,
            turnType = turnType,
            pointType = item.optString("point_type").takeIf { it.isNotBlank() },
            facilityType = facilityType,
        )
    }
}

private fun JSONArray?.toRouteStepsOrNull(): List<WalkingRouteStep>? {
    if (this == null) return null
    return (0 until length()).map { arrayIndex ->
        val item = optJSONObject(arrayIndex) ?: return null
        val index = item.optNonNegativeIntOrNull("index") ?: return null
        val distanceM = item.optNonNegativeIntOrNull("distance_m") ?: return null
        val durationS = item.optNonNegativeIntOrNull("duration_s") ?: return null
        val pointsArray = item.optJSONArray("points") ?: return null
        val points = (0 until pointsArray.length()).map { pointIndex ->
            pointsArray.optJSONObject(pointIndex)?.toRoutePointOrNull() ?: return null
        }.takeIf { it.isNotEmpty() } ?: return null
        val turnType = item.optNonNegativeIntOrNull("turn_type")
        if (item.hasNonNull("turn_type") && turnType == null) return null
        val facilityType = item.optNonNegativeIntOrNull("facility_type")
        if (item.hasNonNull("facility_type") && facilityType == null) return null
        WalkingRouteStep(
            index = index,
            distanceM = distanceM,
            durationS = durationS,
            points = points,
            instruction = item.optString("instruction").takeIf { it.isNotBlank() },
            roadName = item.optString("road_name").takeIf { it.isNotBlank() },
            turnType = turnType,
            facilityType = facilityType,
        )
    }
}

private fun JSONArray?.toDestinationSearchResultsOrNull(): List<DestinationSearchResult>? {
    if (this == null) return null
    return (0 until length()).map { index ->
        optJSONObject(index)?.toDestinationSearchResultOrNull() ?: return null
    }
}

private fun JSONObject.toRoutePointOrNull(): RoutePoint? {
    val latitude = optFiniteDouble("latitude")?.takeIf { it in -90.0..90.0 } ?: return null
    val longitude = optFiniteDouble("longitude")?.takeIf { it in -180.0..180.0 } ?: return null
    if (hasNonNull("name") && opt("name") !is String) return null
    return RoutePoint(latitude, longitude, (opt("name") as? String)?.takeIf { it.isNotBlank() })
}

private fun JSONObject.toDestinationSearchResultOrNull(): DestinationSearchResult? {
    val point = optJSONObject("point")?.toRoutePointOrNull() ?: return null
    val id = (opt("id") as? String)?.takeIf { it.isNotBlank() } ?: return null
    val name = (opt("name") as? String)?.takeIf { it.isNotBlank() } ?: return null
    val distanceM = optNonNegativeIntOrNull("distance_m")
    if (hasNonNull("distance_m") && distanceM == null) return null
    if (DESTINATION_OPTIONAL_STRING_FIELDS.any { hasNonNull(it) && opt(it) !is String }) return null
    val resultType = (opt("result_type") as? String)
        ?.takeIf(ALLOWED_DESTINATION_RESULT_TYPES::contains)
        ?: return null
    return DestinationSearchResult(
        id = id,
        name = name,
        point = point,
        address = (opt("address") as? String)?.takeIf { it.isNotBlank() },
        roadAddress = (opt("road_address") as? String)?.takeIf { it.isNotBlank() },
        category = (opt("category") as? String)?.takeIf { it.isNotBlank() },
        distanceM = distanceM,
        resultType = resultType,
    )
}

private fun JSONObject.optFiniteDouble(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    val raw = opt(name) as? Number ?: return null
    return raw.toDouble().takeIf(Double::isFinite)
}

private fun JSONObject.optNonNegativeIntOrNull(name: String): Int? {
    val value = optFiniteDouble(name) ?: return null
    if (value < 0.0 || value > Int.MAX_VALUE || value % 1.0 != 0.0) return null
    return value.toInt()
}

private fun JSONObject.optNullableFiniteFloat(name: String): Float? {
    if (!has(name) || isNull(name)) return null
    val value = optFiniteDouble(name) ?: return Float.NaN
    return value.toFloat().takeIf(Float::isFinite)
}

private fun JSONObject.hasNonNull(name: String): Boolean = has(name) && !isNull(name)

class GatewayResponseProtocolException(reason: String) : IllegalStateException("invalid gateway response: $reason")

fun formatDestinationDistance(distanceM: Int?): String {
    return distanceM?.let { "${it}m" } ?: "거리미상"
}

private const val WALKING_ROUTE_SCHEMA = "walksafe.walking_route.v1"
private const val DESTINATION_SEARCH_SCHEMA = "walksafe.destination_search.v1"
private const val TMAP_WALKING_PROVIDER = "tmap_pedestrian"
private const val TMAP_POI_PROVIDER = "tmap_poi"
private const val TMAP_SUCCESS_RESULT_CODE = 0
private const val STAIR_AVOID_PRIORITY = "STAIR_AVOID"
private const val MAX_PROVIDER_ROUTE_ID_LENGTH = 256
private const val MAX_DESTINATION_RESULT_LIMIT = 10
internal const val WALKING_ROUTE_MAX_RESPONSE_BYTES = 2 * 1024 * 1024
private const val ROUTE_ENDPOINT_MAX_DISTANCE_M = 100.0
private const val ROUTE_SUMMARY_GEOMETRY_MIN_RATIO = 0.5
private const val ROUTE_SUMMARY_GEOMETRY_MAX_RATIO = 3.0
private const val ROUTE_SUMMARY_GEOMETRY_ABSOLUTE_TOLERANCE_M = 50.0
private const val GUIDE_POINT_MAX_ROUTE_DISTANCE_M = 30.0
private const val GUIDE_DISTANCE_ABSOLUTE_TOLERANCE_M = 30.0
private const val GUIDE_DISTANCE_RELATIVE_TOLERANCE = 0.1
private const val GUIDE_MONOTONIC_TOLERANCE_M = 10.0
private const val METERS_PER_LATITUDE_DEGREE = 111_320.0
private const val EARTH_RADIUS_M = 6_371_000.0
private val BACKEND_ERROR_CODE = Regex("^[a-z][a-z0-9_]{0,63}$")
private val ALLOWED_DESTINATION_RESULT_TYPES = setOf("poi", "address", "alias")
private val DESTINATION_OPTIONAL_STRING_FIELDS = listOf("address", "road_address", "category")
