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
import kr.co.hanium.dreamup.walksafe.network.NetworkResponseProtocolException
import kr.co.hanium.dreamup.walksafe.network.cancellableHttpCall
import kr.co.hanium.dreamup.walksafe.network.captureGatewayAuthenticationRequest
import kr.co.hanium.dreamup.walksafe.network.consumeGatewayAuthenticationStaleResponse
import kr.co.hanium.dreamup.walksafe.network.readBoundedResponse
import kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy
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
        return transport.postCall(endpoint, request.toJson().toString(), session)
            .map { response -> parseWalkingRoute(JSONObject(response), request) }
            .withDeadline(NAVIGATION_CALL_DEADLINE_MS)
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
        require(Charsets.UTF_8.newEncoder().canEncode(query)) { "destination_query_invalid" }
        val safeQuery = normalizeDestinationQuery(query.replace('\u0085', ' '))
        require(safeQuery.isNotEmpty()) { "empty_query" }
        require(validDestinationSearchQuery(safeQuery)) { "destination_query_invalid" }
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
        return transport.getCall(endpoint, session)
            .map { response ->
                parseDestinationSearchResponse(JSONObject(response), query = safeQuery, limit = safeLimit)
            }
            .withDeadline(NAVIGATION_CALL_DEADLINE_MS)
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
    ): CancellableNetworkCall<String> {
        val origin = session.gatewayBaseUrl.trimEnd('/')
        val authenticationRequest = if (
            (method == "POST" && url == origin + "/api/navigation/walking") ||
            (method == "GET" &&
                url.substringBefore('?') == origin + "/api/navigation/destinations/search")
        ) {
            captureGatewayAuthenticationRequest(session)
        } else {
            null
        }
        return cancellableHttpCall(deadlineMs = NAVIGATION_CALL_DEADLINE_MS) { cancellation ->
            val connection = URL(url).openConnection() as HttpURLConnection
            cancellation.attach(connection)
            try {
                connection.apply {
                    requestMethod = method
                    connectTimeout = NAVIGATION_CONNECT_TIMEOUT_MS
                    readTimeout = NAVIGATION_READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    doOutput = body != null
                    if (body != null) setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Accept-Encoding", "identity")
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
                val response = connection.readBoundedResponse(
                    WALKING_ROUTE_MAX_RESPONSE_BYTES,
                    cancellation,
                    strictSuccessFraming = true,
                )
                if (response.statusCode in 200..299 && response.statusCode != 200) {
                    throw GatewayResponseProtocolException("navigation_http_status_invalid")
                }
                if (response.statusCode != 200) {
                    cancellation.throwIfCancelled()
                    consumeGatewayAuthenticationStaleResponse(authenticationRequest, session, response)
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
}

// The gateway finishes walking/search requests by 8s/7s, leaving time for its error response.
private const val NAVIGATION_CALL_DEADLINE_MS = 10_000L
private const val NAVIGATION_CONNECT_TIMEOUT_MS = 2_000
private const val NAVIGATION_READ_TIMEOUT_MS = 9_000

class GatewayProxyHttpException(
    val statusCode: Int,
    val requestReason: String,
    val backendCode: String? = null,
) : IllegalStateException(
    "gateway proxy request failed: $requestReason status=$statusCode code=${backendCode ?: "unknown"}",
)

enum class NavigationBackendErrorKind(val statusToken: String, val userMessage: String) {
    AUTHENTICATION("authentication", "길안내 로그인을 다시 확인해 주세요."),
    PROVIDER_CONFIGURATION("provider_configuration", "TMAP API 키나 이용 권한 등 서버 설정 문제입니다. 서비스 관리자에게 문의해 주세요."),
    RATE_LIMITED("rate_limited", "길안내 요청이 많아 새 경로를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    STAIRS_PRESENT("route_stairs_present", "요청한 경로에 계단이 포함되어 새 경로를 사용할 수 없습니다. 다른 목적지를 선택하거나 경로를 다시 요청해 주세요."),
    ROUTE_UNAVAILABLE("route_unavailable", "이 목적지까지 이용할 수 있는 보행 경로를 찾지 못했습니다. 목적지를 바꿔 다시 요청해 주세요."),
    GUIDANCE_INCOMPLETE("route_guidance_incomplete", "회전 안내가 불완전해 길안내를 시작할 수 없습니다. 경로를 다시 요청해 주세요."),
    TIMEOUT("timeout", "길안내 요청 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요."),
    PROVIDER_UNAVAILABLE("provider_unavailable", "경로 제공 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    INVALID_RESPONSE("invalid_response", "경로 응답을 안전하게 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    NETWORK("network", "네트워크 연결을 확인한 뒤 경로를 다시 요청해 주세요."),
    BACKEND_UNAVAILABLE("backend_unavailable", "길안내 서버를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    UNKNOWN("unknown", "길안내 오류 종류를 확인할 수 없습니다."),
}

/** The layer is the source proven by the response, never guessed from an HTTP status alone. */
enum class NavigationFailureLayer(val statusToken: String) {
    USER_SESSION("user_session"), GATEWAY("gateway"), BACKEND("backend"),
    PROVIDER("provider"), NETWORK("network"), RESPONSE_PROTOCOL("response_protocol"),
    UNKNOWN("unknown"),
}

enum class NavigationFailureReason(val statusToken: String) {
    AUTHENTICATION("authentication"), UPSTREAM_AUTHENTICATION("upstream_authentication"),
    CONFIGURATION("configuration"), RATE_LIMIT("rate_limit"), STAIRS("stairs"),
    ROUTE_UNAVAILABLE("route_unavailable"),
    GUIDANCE_INCOMPLETE("guidance_incomplete"), TIMEOUT("timeout"),
    SERVICE_UNAVAILABLE("service_unavailable"), INVALID_RESPONSE("invalid_response"),
    MALFORMED_JSON("malformed_json"), RESPONSE_TOO_LARGE("response_too_large"),
    NETWORK_IO("network_io"), UNKNOWN("unknown"),
}

enum class NavigationFailureRetryability(val statusToken: String) {
    AFTER_LOGIN("after_login"), AFTER_DESTINATION_CHANGE("after_destination_change"),
    RETRY_LATER("retry_later"), SERVICE_RECOVERY("service_recovery"),
    NOT_RETRYABLE("not_retryable"),
}

val NavigationBackendErrorKind.destinationSearchUserMessage: String
    get() = when (this) {
        NavigationBackendErrorKind.AUTHENTICATION ->
            "로그인 상태를 확인한 뒤 목적지를 다시 검색해 주세요."
        NavigationBackendErrorKind.PROVIDER_CONFIGURATION ->
            "목적지 검색 서비스를 사용할 수 없습니다. 서비스 관리자에게 문의해 주세요."
        NavigationBackendErrorKind.RATE_LIMITED ->
            "검색 요청이 많아 결과를 확인할 수 없습니다. 잠시 후 다시 검색해 주세요."
        NavigationBackendErrorKind.STAIRS_PRESENT ->
            "목적지 검색을 완료하지 못했습니다. 다시 검색해 주세요."
        NavigationBackendErrorKind.ROUTE_UNAVAILABLE ->
            "목적지를 검색하지 못했습니다. 다른 장소명으로 다시 검색해 주세요."
        NavigationBackendErrorKind.GUIDANCE_INCOMPLETE ->
            "목적지 검색을 완료하지 못했습니다. 다시 검색해 주세요."
        NavigationBackendErrorKind.TIMEOUT ->
            "목적지 검색 응답 시간이 초과되었습니다. 다시 검색해 주세요."
        NavigationBackendErrorKind.PROVIDER_UNAVAILABLE ->
            "목적지 검색 서비스를 사용할 수 없습니다. 잠시 후 다시 검색해 주세요."
        NavigationBackendErrorKind.INVALID_RESPONSE ->
            "검색 결과를 안전하게 확인할 수 없습니다. 다시 검색해 주세요."
        NavigationBackendErrorKind.NETWORK ->
            "네트워크 연결을 확인한 뒤 목적지를 다시 검색해 주세요."
        NavigationBackendErrorKind.BACKEND_UNAVAILABLE ->
            "목적지 검색 서버를 사용할 수 없습니다. 잠시 후 다시 검색해 주세요."
        NavigationBackendErrorKind.UNKNOWN ->
            "목적지를 검색하지 못했습니다. 다른 장소명으로 다시 검색해 주세요."
    }

data class NavigationBackendFailure(
    val kind: NavigationBackendErrorKind,
    val layer: NavigationFailureLayer,
    val reason: NavigationFailureReason,
    val retryability: NavigationFailureRetryability,
    val backendCode: String? = null,
    val statusCode: Int? = null,
) {
    val isUserSessionAuthentication: Boolean
        get() = layer == NavigationFailureLayer.USER_SESSION &&
            reason == NavigationFailureReason.AUTHENTICATION

    val routeUserMessage: String
        get() = if (layer == NavigationFailureLayer.PROVIDER) when (kind) {
            NavigationBackendErrorKind.RATE_LIMITED ->
                "TMAP 사용 한도에 도달해 새 경로를 확인할 수 없습니다. 나중에 다시 시도해 주세요."
            NavigationBackendErrorKind.TIMEOUT -> "TMAP 응답 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요."
            NavigationBackendErrorKind.PROVIDER_UNAVAILABLE ->
                "TMAP 연결 장애로 새 경로를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
            NavigationBackendErrorKind.INVALID_RESPONSE ->
                "TMAP 경로 응답을 안전하게 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."
            else -> kind.userMessage
        } else kind.userMessage

    val statusMetadata: String
        get() = "kind=${kind.statusToken} layer=${layer.statusToken} " +
            "reason=${reason.statusToken} retry=${retryability.statusToken}"
}

fun classifyNavigationBackendFailure(error: Throwable): NavigationBackendFailure {
    if (error is GatewayProxyHttpException) {
        val code = error.backendCode
        fun failure(
            kind: NavigationBackendErrorKind,
            layer: NavigationFailureLayer,
            reason: NavigationFailureReason,
            retryability: NavigationFailureRetryability,
        ) = NavigationBackendFailure(kind, layer, reason, retryability, code, error.statusCode)
        return when {
            code in setOf(
                "tmap_app_key_missing",
                "tmap_invalid_api_key",
                "walking_route_provider_invalid",
                "tmap_poi_provider_invalid",
            ) -> failure(NavigationBackendErrorKind.PROVIDER_CONFIGURATION,
                NavigationFailureLayer.BACKEND, NavigationFailureReason.CONFIGURATION,
                NavigationFailureRetryability.SERVICE_RECOVERY)
            code == "gateway_upstream_auth_failed" -> failure(
                NavigationBackendErrorKind.BACKEND_UNAVAILABLE, NavigationFailureLayer.GATEWAY,
                NavigationFailureReason.UPSTREAM_AUTHENTICATION,
                NavigationFailureRetryability.SERVICE_RECOVERY)
            error.statusCode in setOf(401, 403) -> failure(
                NavigationBackendErrorKind.AUTHENTICATION, NavigationFailureLayer.USER_SESSION,
                NavigationFailureReason.AUTHENTICATION, NavigationFailureRetryability.AFTER_LOGIN)
            code == "tmap_rate_limited" -> failure(
                NavigationBackendErrorKind.RATE_LIMITED, NavigationFailureLayer.PROVIDER,
                NavigationFailureReason.RATE_LIMIT, NavigationFailureRetryability.RETRY_LATER)
            error.statusCode == 429 -> failure(
                NavigationBackendErrorKind.RATE_LIMITED, NavigationFailureLayer.GATEWAY,
                NavigationFailureReason.RATE_LIMIT, NavigationFailureRetryability.RETRY_LATER)
            code == "route_stairs_present" -> failure(
                NavigationBackendErrorKind.STAIRS_PRESENT, NavigationFailureLayer.BACKEND,
                NavigationFailureReason.STAIRS, NavigationFailureRetryability.AFTER_DESTINATION_CHANGE)
            code == "route_unavailable" -> failure(
                NavigationBackendErrorKind.ROUTE_UNAVAILABLE, NavigationFailureLayer.PROVIDER,
                NavigationFailureReason.ROUTE_UNAVAILABLE,
                NavigationFailureRetryability.AFTER_DESTINATION_CHANGE)
            code in setOf("route_guidance_incomplete", "route_guide_points_invalid") ->
                failure(NavigationBackendErrorKind.GUIDANCE_INCOMPLETE,
                    NavigationFailureLayer.BACKEND, NavigationFailureReason.GUIDANCE_INCOMPLETE,
                    NavigationFailureRetryability.SERVICE_RECOVERY)
            code == "tmap_timeout" -> failure(
                NavigationBackendErrorKind.TIMEOUT, NavigationFailureLayer.PROVIDER,
                NavigationFailureReason.TIMEOUT, NavigationFailureRetryability.RETRY_LATER)
            error.statusCode == 504 -> failure(
                NavigationBackendErrorKind.TIMEOUT, NavigationFailureLayer.GATEWAY,
                NavigationFailureReason.TIMEOUT, NavigationFailureRetryability.RETRY_LATER)
            code in setOf("tmap_network_error", "tmap_provider_error") ->
                failure(NavigationBackendErrorKind.PROVIDER_UNAVAILABLE,
                    NavigationFailureLayer.PROVIDER,
                    NavigationFailureReason.SERVICE_UNAVAILABLE,
                    NavigationFailureRetryability.RETRY_LATER)
            code == "invalid_tmap_response" -> failure(
                NavigationBackendErrorKind.INVALID_RESPONSE, NavigationFailureLayer.PROVIDER,
                NavigationFailureReason.INVALID_RESPONSE,
                NavigationFailureRetryability.SERVICE_RECOVERY)
            code?.startsWith("route_") == true -> failure(
                NavigationBackendErrorKind.INVALID_RESPONSE, NavigationFailureLayer.BACKEND,
                NavigationFailureReason.INVALID_RESPONSE,
                NavigationFailureRetryability.SERVICE_RECOVERY)
            error.statusCode >= 500 -> failure(
                NavigationBackendErrorKind.BACKEND_UNAVAILABLE, NavigationFailureLayer.GATEWAY,
                NavigationFailureReason.SERVICE_UNAVAILABLE,
                NavigationFailureRetryability.RETRY_LATER)
            else -> failure(NavigationBackendErrorKind.UNKNOWN, NavigationFailureLayer.UNKNOWN,
                NavigationFailureReason.UNKNOWN, NavigationFailureRetryability.NOT_RETRYABLE)
        }
    }
    return when (error) {
        is SocketTimeoutException -> NavigationBackendFailure(
            NavigationBackendErrorKind.TIMEOUT, NavigationFailureLayer.NETWORK,
            NavigationFailureReason.TIMEOUT, NavigationFailureRetryability.RETRY_LATER)
        is GatewayResponseProtocolException -> {
            val incomplete = error.reason in setOf("route_guidance_incomplete", "route_guide_points_invalid")
            NavigationBackendFailure(
                if (incomplete) NavigationBackendErrorKind.GUIDANCE_INCOMPLETE
                    else NavigationBackendErrorKind.INVALID_RESPONSE,
                NavigationFailureLayer.RESPONSE_PROTOCOL,
                if (incomplete) NavigationFailureReason.GUIDANCE_INCOMPLETE
                    else if (error.reason == "error_response_json_invalid") NavigationFailureReason.MALFORMED_JSON
                    else NavigationFailureReason.INVALID_RESPONSE,
                NavigationFailureRetryability.SERVICE_RECOVERY)
        }
        is NetworkResponseTooLargeException -> NavigationBackendFailure(
            NavigationBackendErrorKind.INVALID_RESPONSE, NavigationFailureLayer.RESPONSE_PROTOCOL,
            NavigationFailureReason.RESPONSE_TOO_LARGE,
            NavigationFailureRetryability.SERVICE_RECOVERY)
        is NetworkResponseProtocolException -> NavigationBackendFailure(
            NavigationBackendErrorKind.INVALID_RESPONSE, NavigationFailureLayer.RESPONSE_PROTOCOL,
            NavigationFailureReason.INVALID_RESPONSE,
            NavigationFailureRetryability.SERVICE_RECOVERY)
        is org.json.JSONException -> NavigationBackendFailure(
            NavigationBackendErrorKind.INVALID_RESPONSE, NavigationFailureLayer.RESPONSE_PROTOCOL,
            NavigationFailureReason.MALFORMED_JSON,
            NavigationFailureRetryability.SERVICE_RECOVERY)
        is IOException -> NavigationBackendFailure(
            NavigationBackendErrorKind.NETWORK, NavigationFailureLayer.NETWORK,
            NavigationFailureReason.NETWORK_IO, NavigationFailureRetryability.RETRY_LATER)
        else -> NavigationBackendFailure(NavigationBackendErrorKind.UNKNOWN,
            NavigationFailureLayer.UNKNOWN, NavigationFailureReason.UNKNOWN,
            NavigationFailureRetryability.NOT_RETRYABLE)
    }
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

private fun parseBackendErrorCode(body: String): String? {
    if (body.isBlank()) return null
    val root = try {
        JSONObject(body)
    } catch (_: org.json.JSONException) {
        throw GatewayResponseProtocolException("error_response_json_invalid")
    }
    val detail = root.optJSONObject("detail") ?: root
    return detail.optString("code")
        .trim()
        .takeIf { it.matches(BACKEND_ERROR_CODE) }
}

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
    val guidePoints = guideArray.toGuidePointsOrNull()?.takeIf { it.isNotEmpty() }
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
    val distanceSource = if (!json.has("distance_source") || json.isNull("distance_source")) {
        null
    } else {
        val literal = json.opt("distance_source") as? String
            ?: throw GatewayResponseProtocolException("route_distance_source_invalid")
        WalkingRouteDistanceSource.entries.firstOrNull { it.wireValue == literal }
            ?: throw GatewayResponseProtocolException("route_distance_source_invalid")
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
        distanceSource = distanceSource,
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
    val normalizedQuery = normalizeDestinationQuery(query.replace('\u0085', ' '))
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
    // Optional paging metadata: absent or null means unknown; a present value of the wrong type is rejected.
    val totalCount = json.optNonNegativeIntOrNull("total_count")
    if (json.hasNonNull("total_count") && totalCount == null) {
        throw GatewayResponseProtocolException("destination_total_count_invalid")
    }
    val moreResultsAvailable = if (json.hasNonNull("more_results_available")) {
        json.opt("more_results_available") as? Boolean
            ?: throw GatewayResponseProtocolException("destination_more_results_invalid")
    } else {
        null
    }
    return DestinationSearchResponse(
        provider = TMAP_POI_PROVIDER,
        query = normalizedQuery,
        results = parsedResults,
        totalCount = totalCount,
        moreResultsAvailable = moreResultsAvailable,
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

    if (route.steps.isNotEmpty() || route.distanceSource != null) {
        validateStepGeometry(route)
    }
    if (route.distanceSource != null) {
        val summaryDistanceM = route.summary.distanceM.toLong()
        if (route.steps.sumOf { it.distanceM.toLong() } != summaryDistanceM) {
            throw GatewayResponseProtocolException("route_step_distance_total_invalid")
        }
        route.guidePoints.forEach { guide ->
            val prefix = guide.distanceFromStartM
            val remaining = guide.remainingDistanceM
            if (prefix == null || remaining == null || prefix.toLong() + remaining.toLong() != summaryDistanceM) {
                throw GatewayResponseProtocolException("route_guide_distance_total_invalid")
            }
        }
    }

    // Guide error comes from the guide position/shape and integer rounding, not from how long
    // the whole route is: a 10% allowance would accept a 200 m turn error on a 2 km route.
    // The projected value is already scaled to the summary distance, so one absolute bound
    // applies to every route length. Turn timing applies its own tighter bound (RouteNavigator).
    val guideDistanceToleranceM = GUIDE_DISTANCE_ABSOLUTE_TOLERANCE_M
    var previousProjectedDistanceM = -GUIDE_MONOTONIC_TOLERANCE_M
    var previousDeclaredDistanceM = -GUIDE_MONOTONIC_TOLERANCE_M
    var previousRemainingDistanceM = route.summary.distanceM + GUIDE_MONOTONIC_TOLERANCE_M
    val projectedGuideDistancesM = ArrayList<Double>(route.guidePoints.size)
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
        val expectedSummaryDistanceM = distanceFromStartM?.toDouble()
            ?: remainingDistanceM?.let { route.summary.distanceM - it.toDouble() }
        val geometryScale = geometryDistanceM / route.summary.distanceM
        val projection = projectPointToPolyline(
            guide.point, route.polyline,
            expectedDistanceFromStartM = expectedSummaryDistanceM?.times(geometryScale),
            expectedDistanceToleranceM = guideDistanceToleranceM * geometryScale,
            minimumDistanceFromStartM = (previousProjectedDistanceM - GUIDE_MONOTONIC_TOLERANCE_M) * geometryScale,
        )
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
        projectedGuideDistancesM += projection.distanceFromStartM
    }
    validateTurnGuideCompleteness(route, projectedGuideDistancesM)
}

/** A start or later-visit guide cannot stand in for a missing guide at a clear step turn. */
private fun validateTurnGuideCompleteness(route: WalkingRoute, projectedGuideDistancesM: List<Double>) {
    fun hasGuideAt(point: RoutePoint, distanceFromStartM: Double): Boolean =
        route.guidePoints.indices.any { index ->
            val guide = route.guidePoints[index]
            // Only a code mapped to a spoken turn can certify the corner. Raw provider text is
            // never voiced when its code is missing or unknown, so it cannot fill this guide gap.
            val speaksTurn = guide.turnType in DIRECTIONAL_GUIDE_TURN_TYPES
            speaksTurn && routePointDistanceM(guide.point, point) <= TURN_GUIDE_MAX_GAP_M &&
                abs(projectedGuideDistancesM[index] - distanceFromStartM) <= TURN_GUIDE_MAX_GAP_M
        }

    var boundaryDistanceM = 0.0
    route.steps.zipWithNext().forEach { (before, after) ->
        val beforeLengthM = before.points.zipWithNext().sumOf { (start, end) -> routePointDistanceM(start, end) }
        val afterLengthM = after.points.zipWithNext().sumOf { (start, end) -> routePointDistanceM(start, end) }
        boundaryDistanceM += beforeLengthM
        if (beforeLengthM < TURN_GUIDE_MIN_STEP_DISTANCE_M || afterLengthM < TURN_GUIDE_MIN_STEP_DISTANCE_M) {
            return@forEach
        }
        val incoming = before.points.zipWithNext().lastOrNull { (start, end) ->
            routePointDistanceM(start, end) >= TURN_GUIDE_MIN_EDGE_DISTANCE_M
        } ?: return@forEach
        val outgoing = after.points.zipWithNext().firstOrNull { (start, end) ->
            routePointDistanceM(start, end) >= TURN_GUIDE_MIN_EDGE_DISTANCE_M
        } ?: return@forEach
        if (routeTurnAngleDeg(incoming.first, incoming.second, outgoing.first, outgoing.second) <
            TURN_GUIDE_MIN_ANGLE_DEG) return@forEach

        val boundaryPoint = before.points.last()
        if (!hasGuideAt(boundaryPoint, boundaryDistanceM)) {
            throw GatewayResponseProtocolException("route_guidance_incomplete")
        }
    }

    val allEdges = ArrayList<RouteTurnEdge>()
    var traveledM = 0.0
    route.polyline.zipWithNext().forEach { (start, end) ->
        val lengthM = routePointDistanceM(start, end)
        if (lengthM > 0.0) {
            allEdges += RouteTurnEdge(start, end, lengthM, traveledM, traveledM + lengthM)
        }
        traveledM += lengthM
    }
    fun checkHeadingRuns(edges: List<RouteTurnEdge>) {
        val headingRuns = ArrayList<ArrayList<RouteTurnEdge>>()
        edges.forEach { edge ->
            val currentRun = headingRuns.lastOrNull()
            val previous = currentRun?.lastOrNull()
            if (previous == null ||
                edge.startDistanceM - previous.endDistanceM > TURN_GUIDE_MAX_GAP_M ||
                routeTurnAngleDeg(previous.start, previous.end, edge.start, edge.end) >= TURN_GUIDE_MIN_ANGLE_DEG
            ) {
                headingRuns.add(arrayListOf(edge))
            } else {
                currentRun!!.add(edge)
            }
        }
        headingRuns.zipWithNext().forEach { (before, after) ->
            val incoming = before.last()
            val outgoing = after.first()
            if (before.sumOf { it.lengthM } < TURN_GUIDE_MIN_STEP_DISTANCE_M ||
                after.sumOf { it.lengthM } < TURN_GUIDE_MIN_STEP_DISTANCE_M ||
                outgoing.startDistanceM - incoming.endDistanceM > TURN_GUIDE_MAX_GAP_M
            ) return@forEach
            if (!hasGuideAt(incoming.end, incoming.endDistanceM)) {
                throw GatewayResponseProtocolException("route_guidance_incomplete")
            }
        }
    }
    // Long edges ignore a tiny diagonal seam that might spread one turn into two small angles.
    // All edges also catch a genuine turn made of many individually sub-meter segments.
    checkHeadingRuns(allEdges.filter { it.lengthM >= TURN_GUIDE_MIN_EDGE_DISTANCE_M })
    checkHeadingRuns(allEdges)
}

private data class RouteTurnEdge(
    val start: RoutePoint,
    val end: RoutePoint,
    val lengthM: Double,
    val startDistanceM: Double,
    val endDistanceM: Double,
)

private fun routeTurnAngleDeg(
    incomingStart: RoutePoint,
    incomingEnd: RoutePoint,
    outgoingStart: RoutePoint,
    outgoingEnd: RoutePoint,
): Double {
    val incomingDirection = routeSegmentDirectionRadians(incomingStart, incomingEnd)
    val outgoingDirection = routeSegmentDirectionRadians(outgoingStart, outgoingEnd)
    return Math.toDegrees(abs(atan2(
        sin(outgoingDirection - incomingDirection),
        cos(outgoingDirection - incomingDirection),
    )))
}

private fun routeSegmentDirectionRadians(start: RoutePoint, end: RoutePoint): Double {
    val meanLatitudeRadians = Math.toRadians((start.latitude + end.latitude) / 2.0)
    val northM = (end.latitude - start.latitude) * METERS_PER_LATITUDE_DEGREE
    val eastM = LocalGeodesy.wrapLongitudeDegrees(end.longitude - start.longitude) *
        METERS_PER_LATITUDE_DEGREE * cos(meanLatitudeRadians)
    return atan2(eastM, northM)
}

/** The normalized step geometry must be the same ordered walk as the returned route line. */
private fun validateStepGeometry(route: WalkingRoute) {
    val joined = ArrayList<RoutePoint>()
    route.steps.forEachIndexed { index, step ->
        if (step.index != index) {
            throw GatewayResponseProtocolException("route_step_order_invalid")
        }
        if (joined.isNotEmpty() && !joined.last().sameCoordinates(step.points.first())) {
            throw GatewayResponseProtocolException("route_step_disconnected")
        }
        step.points.forEach { point ->
            if (joined.isEmpty() || !joined.last().sameCoordinates(point)) joined += point
        }
    }
    val routeLine = ArrayList<RoutePoint>(route.polyline.size)
    route.polyline.forEach { point ->
        if (routeLine.isEmpty() || !routeLine.last().sameCoordinates(point)) routeLine += point
    }
    if (joined.size != routeLine.size || joined.indices.any { !joined[it].sameCoordinates(routeLine[it]) }) {
        throw GatewayResponseProtocolException("route_step_geometry_mismatch")
    }
}

private fun RoutePoint.sameCoordinates(other: RoutePoint): Boolean =
    latitude == other.latitude && longitude == other.longitude

private data class RoutePointProjection(
    val distanceToRouteM: Double,
    val distanceFromStartM: Double,
    val segmentOrdinal: Int,
    val fraction: Double,
    val point: RoutePoint,
)

private fun projectPointToPolyline(
    point: RoutePoint,
    polyline: List<RoutePoint>,
    expectedDistanceFromStartM: Double? = null,
    expectedDistanceToleranceM: Double,
    minimumDistanceFromStartM: Double = 0.0,
): RoutePointProjection? {
    val projections = ArrayList<RoutePointProjection>(polyline.size - 1)
    var traversedM = 0.0
    fun matchesDeclaredProgress(projection: RoutePointProjection): Boolean =
        expectedDistanceFromStartM != null &&
            abs(projection.distanceFromStartM - expectedDistanceFromStartM) <= expectedDistanceToleranceM

    for ((start, end) in polyline.zipWithNext()) {
        val segmentLengthM = routePointDistanceM(start, end)
        if (segmentLengthM <= 0.0) continue
        val meanLatitudeRadians = Math.toRadians((start.latitude + end.latitude + point.latitude) / 3.0)
        val metersPerLongitudeDegree = METERS_PER_LATITUDE_DEGREE * max(0.01, cos(meanLatitudeRadians))
        val longitudeDelta = LocalGeodesy.wrapLongitudeDegrees(end.longitude - start.longitude)
        val segmentX = longitudeDelta * metersPerLongitudeDegree
        val segmentY = (end.latitude - start.latitude) * METERS_PER_LATITUDE_DEGREE
        val pointX = LocalGeodesy.wrapLongitudeDegrees(point.longitude - start.longitude) * metersPerLongitudeDegree
        val pointY = (point.latitude - start.latitude) * METERS_PER_LATITUDE_DEGREE
        val segmentNormSquared = segmentX * segmentX + segmentY * segmentY
        val fraction = if (segmentNormSquared > 0.0) {
            (pointX * segmentX + pointY * segmentY).div(segmentNormSquared).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val projectedPoint = RoutePoint(
            latitude = start.latitude + (end.latitude - start.latitude) * fraction,
            longitude = LocalGeodesy.wrapLongitudeDegrees(start.longitude + longitudeDelta * fraction),
        )
        val candidate = RoutePointProjection(
            distanceToRouteM = routePointDistanceM(point, projectedPoint),
            distanceFromStartM = traversedM + segmentLengthM * fraction,
            segmentOrdinal = projections.size,
            fraction = fraction,
            point = projectedPoint,
        )
        projections += candidate
        traversedM += segmentLengthM
    }
    val nearestDistanceM = projections.minOfOrNull { it.distanceToRouteM } ?: return null
    // A declaration may disambiguate a slightly offset revisit, but may not turn a precise
    // guide at one visit into a different route visit 20 m away merely because it is within 30 m.
    val nearestAllowanceM = if (expectedDistanceFromStartM == null) 1.0 else 3.0
    val nearby = projections.filter {
        it.distanceToRouteM <= nearestDistanceM + nearestAllowanceM &&
            it.distanceFromStartM >= minimumDistanceFromStartM
    }
    if (nearby.isEmpty()) return null
    if (expectedDistanceFromStartM == null && nearby.any { first ->
            nearby.any { second -> !first.isSameVisitAs(second) }
        }) return null

    var best: RoutePointProjection? = null
    for (candidate in nearby) {
        val previous = best
        val expected = expectedDistanceFromStartM ?: minimumDistanceFromStartM
        val candidateMatchesProgress = matchesDeclaredProgress(candidate)
        val previousMatchesProgress = previous?.let { matchesDeclaredProgress(it) } ?: false
        if (previous == null ||
            (candidateMatchesProgress && !previousMatchesProgress) ||
            (candidateMatchesProgress == previousMatchesProgress &&
                (candidate.distanceToRouteM < previous.distanceToRouteM - 1.0 ||
                    (abs(candidate.distanceToRouteM - previous.distanceToRouteM) <= 1.0 &&
                        abs(candidate.distanceFromStartM - expected) < abs(previous.distanceFromStartM - expected))))
        ) best = candidate
    }
    return best
}

private fun RoutePointProjection.isSameVisitAs(other: RoutePointProjection): Boolean {
    if (abs(distanceFromStartM - other.distanceFromStartM) <= 0.01) return true
    val before = if (segmentOrdinal < other.segmentOrdinal) this else other
    val after = if (segmentOrdinal < other.segmentOrdinal) other else this
    return after.segmentOrdinal == before.segmentOrdinal + 1 &&
        before.fraction >= 0.5 && after.fraction <= 0.5 &&
        abs(after.distanceFromStartM - before.distanceFromStartM) <=
            2.0 * routePointDistanceM(before.point, after.point) + 0.01
}

private fun routePointDistanceM(start: RoutePoint, end: RoutePoint): Double {
    val startLat = Math.toRadians(start.latitude)
    val endLat = Math.toRadians(end.latitude)
    val deltaLat = endLat - startLat
    val deltaLng = Math.toRadians(LocalGeodesy.wrapLongitudeDegrees(end.longitude - start.longitude))
    val value = sin(deltaLat / 2.0) * sin(deltaLat / 2.0) +
        cos(startLat) * cos(endLat) * sin(deltaLng / 2.0) * sin(deltaLng / 2.0)
    val bounded = min(1.0, max(0.0, value))
    return EARTH_RADIUS_M * 2.0 * atan2(sqrt(bounded), sqrt(1.0 - bounded))
}

private fun RoutePoint.isValidCoordinate(): Boolean =
    latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

private fun normalizeDestinationQuery(query: String): String {
    var normalized = query.replace(Regex("[\\s\\p{Z}]+"), " ").trim()
    val action = KOREAN_DESTINATION_ACTION_RE.find(normalized)
    if (action != null) {
        normalized = normalized.substring(0, action.range.first).trim()
        normalized = when {
            normalized.endsWith("까지") -> normalized.dropLast(2).trim()
            normalized.endsWith("으로") -> normalized.dropLast(2).trim()
            normalized.endsWith("로로") -> normalized.dropLast(1).trim()
            else -> normalized
        }
        normalized = normalized.replaceFirst(KOREAN_DESTINATION_PREFIX_RE, "").trim()
    }
    return normalized
}

internal fun canonicalDestinationSearchQueryOrNull(query: String): String? =
    normalizeDestinationQuery(query).takeIf(::validDestinationSearchQuery)

private fun validDestinationSearchQuery(query: String): Boolean =
    query.codePointCount(0, query.length) in 1..MAX_DESTINATION_QUERY_CODE_POINTS &&
        query.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }

private fun requireProtocolValue(json: JSONObject, name: String, expected: String) {
    if (json.optString(name) != expected) {
        throw GatewayResponseProtocolException("${name}_invalid")
    }
}

private fun JSONArray?.toRequiredRoutePoints(): List<RoutePoint>? {
    if (this == null || length() !in 2..MAX_ROUTE_POLYLINE_POINTS) return null
    val points = (0 until length()).map { index ->
        optJSONObject(index)?.toRoutePointOrNull() ?: return null
    }
    return points.takeIf { points.distinctBy { point -> point.latitude to point.longitude }.size >= 2 }
}

private fun JSONArray?.toGuidePointsOrNull(): List<WalkingRouteGuidePoint>? {
    if (this == null) return emptyList()
    if (length() > MAX_ROUTE_GUIDE_POINTS) return null
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
    if (this == null || length() > MAX_ROUTE_STEPS) return null
    var totalPoints = 0
    return (0 until length()).map { arrayIndex ->
        val item = optJSONObject(arrayIndex) ?: return null
        val index = item.optNonNegativeIntOrNull("index") ?: return null
        val distanceM = item.optNonNegativeIntOrNull("distance_m") ?: return null
        val durationS = item.optNonNegativeIntOrNull("duration_s") ?: return null
        val pointsArray = item.optJSONArray("points") ?: return null
        val pointCount = pointsArray.length()
        if (pointCount !in 1..(MAX_ROUTE_STEP_POINTS - totalPoints)) return null
        totalPoints += pointCount
        val points = (0 until pointsArray.length()).map { pointIndex ->
            pointsArray.optJSONObject(pointIndex)?.toRoutePointOrNull() ?: return null
        }.takeIf { it.isNotEmpty() } ?: return null
        val turnType = item.optNonNegativeIntOrNull("turn_type")
        if (item.hasNonNull("turn_type") && turnType == null) return null
        val facilityType = item.optNonNegativeIntOrNull("facility_type")
        if (item.hasNonNull("facility_type") && facilityType == null) return null
        val roadType = item.optNonNegativeIntOrNull("road_type")
        if (item.hasNonNull("road_type") && roadType == null) return null
        WalkingRouteStep(
            index = index,
            distanceM = distanceM,
            durationS = durationS,
            points = points,
            instruction = item.optString("instruction").takeIf { it.isNotBlank() },
            roadName = item.optString("road_name").takeIf { it.isNotBlank() },
            turnType = turnType,
            facilityType = facilityType,
            roadType = roadType,
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
    // S06-30: optional for older responses; an unknown kind, or a kind without a distance, is invalid.
    val distanceSource = if (!hasNonNull("distance_source")) null else {
        val literal = opt("distance_source") as? String ?: return null
        DestinationDistanceSource.entries.firstOrNull { it.wireName == literal } ?: return null
    }
    if (distanceSource != null && distanceM == null) return null
    return DestinationSearchResult(
        id = id,
        name = name,
        point = point,
        address = (opt("address") as? String)?.takeIf { it.isNotBlank() },
        roadAddress = (opt("road_address") as? String)?.takeIf { it.isNotBlank() },
        category = (opt("category") as? String)?.takeIf { it.isNotBlank() },
        distanceM = distanceM,
        resultType = resultType,
        distanceSource = distanceSource,
        coordinateEvidence = parseDestinationCoordinateEvidence(this, point),
    )
}

/** Shared with the encrypted snapshot codec; absent provenance remains unknown for older data. */
internal fun parseDestinationCoordinateEvidence(
    json: JSONObject,
    selectedPoint: RoutePoint,
): DestinationCoordinateEvidence? {
    val selectedKind = if (json.hasNonNull("coordinate_kind")) {
        val literal = json.opt("coordinate_kind") as? String
            ?: throw GatewayResponseProtocolException("destination_results_invalid")
        DestinationCoordinateKind.entries.firstOrNull { it.wireName == literal }
            ?: throw GatewayResponseProtocolException("destination_results_invalid")
    } else null
    val candidates = if (!json.hasNonNull("coordinate_candidates")) emptyList() else {
        val array = json.optJSONArray("coordinate_candidates")
            ?: throw GatewayResponseProtocolException("destination_results_invalid")
        if (array.length() > MAX_DESTINATION_COORDINATE_CANDIDATES) {
            throw GatewayResponseProtocolException("destination_results_invalid")
        }
        (0 until array.length()).map { index ->
            val candidate = array.optJSONObject(index)
                ?: throw GatewayResponseProtocolException("destination_results_invalid")
            val literal = candidate.opt("kind") as? String
                ?: throw GatewayResponseProtocolException("destination_results_invalid")
            val kind = DestinationCoordinateKind.entries.firstOrNull { it.wireName == literal }
                ?: throw GatewayResponseProtocolException("destination_results_invalid")
            val point = candidate.optJSONObject("point")?.toRoutePointOrNull()
                ?: throw GatewayResponseProtocolException("destination_results_invalid")
            DestinationCoordinateCandidate(kind, point)
        }
    }
    // The backend's first candidate is the family/coordinates that produced the selected point.
    // Partial legacy facts remain usable; an explicit conflict drops only the selected claim.
    // Point names are display labels, and a snapped route endpoint is not the requested POI.
    val firstCandidate = candidates.firstOrNull()
    val consistentKind = selectedKind?.takeUnless {
        firstCandidate != null && (firstCandidate.kind != it ||
            firstCandidate.point.latitude != selectedPoint.latitude ||
            firstCandidate.point.longitude != selectedPoint.longitude)
    }
    return if (consistentKind == null && candidates.isEmpty()) null
    else DestinationCoordinateEvidence(consistentKind, candidates)
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

class GatewayResponseProtocolException(val reason: String) : IllegalStateException("invalid gateway response: $reason")

fun formatDestinationDistance(distanceM: Int?): String {
    if (distanceM == null || distanceM < 0) return "거리미상"
    return if (distanceM >= 1000) {
        String.format(java.util.Locale.KOREA, "%.1fkm", distanceM / 1000.0)
    } else "${distanceM}m"
}

private const val WALKING_ROUTE_SCHEMA = "walksafe.walking_route.v1"
private const val DESTINATION_SEARCH_SCHEMA = "walksafe.destination_search.v1"
private const val TMAP_WALKING_PROVIDER = "tmap_pedestrian"
private const val TMAP_POI_PROVIDER = "tmap_poi"
private const val TMAP_SUCCESS_RESULT_CODE = 0
private const val STAIR_AVOID_PRIORITY = "STAIR_AVOID"
private const val MAX_PROVIDER_ROUTE_ID_LENGTH = 256
private const val MAX_DESTINATION_RESULT_LIMIT = 10
private const val MAX_DESTINATION_COORDINATE_CANDIDATES = 4
private const val MAX_DESTINATION_QUERY_CODE_POINTS = 80
internal const val WALKING_ROUTE_MAX_RESPONSE_BYTES = 2 * 1024 * 1024
private const val MAX_ROUTE_POLYLINE_POINTS = 8_192
private const val MAX_ROUTE_STEPS = 1_024
private const val MAX_ROUTE_GUIDE_POINTS = 1_024
private const val MAX_ROUTE_STEP_POINTS = 8_192
private const val ROUTE_ENDPOINT_MAX_DISTANCE_M = 100.0
private const val ROUTE_SUMMARY_GEOMETRY_MIN_RATIO = 0.5
private const val ROUTE_SUMMARY_GEOMETRY_MAX_RATIO = 3.0
private const val ROUTE_SUMMARY_GEOMETRY_ABSOLUTE_TOLERANCE_M = 50.0
private const val GUIDE_POINT_MAX_ROUTE_DISTANCE_M = 30.0
private const val GUIDE_DISTANCE_ABSOLUTE_TOLERANCE_M = 30.0
private const val GUIDE_MONOTONIC_TOLERANCE_M = 10.0
private const val TURN_GUIDE_MIN_STEP_DISTANCE_M = 10.0
private const val TURN_GUIDE_MIN_EDGE_DISTANCE_M = 1.0
private const val TURN_GUIDE_MIN_ANGLE_DEG = 60.0
private const val TURN_GUIDE_MAX_GAP_M = 5.0
private val DIRECTIONAL_GUIDE_TURN_TYPES = setOf(12, 13, 14, 16, 17, 18, 19, 212, 213, 214, 215, 216, 217)
private const val METERS_PER_LATITUDE_DEGREE = LocalGeodesy.METERS_PER_DEGREE
private const val EARTH_RADIUS_M = LocalGeodesy.EARTH_RADIUS_M
private val BACKEND_ERROR_CODE = Regex("^[a-z][a-z0-9_]{0,63}$")
private val KOREAN_DESTINATION_ACTION_RE = Regex(
    "\\s*(?:안내해줘|안내해|가자|목적지\\s*설정(?:해)?|설정(?:해)?|지정(?:해)?)\\s*$",
)
private val KOREAN_DESTINATION_PREFIX_RE = Regex("^목적지(?:를|을|로)?\\s*")
private val ALLOWED_DESTINATION_RESULT_TYPES = setOf("poi", "address", "alias")
private val DESTINATION_OPTIONAL_STRING_FIELDS = listOf("address", "road_address", "category")
