package kr.co.hanium.dreamup.walksafe.navigation

import java.io.OutputStreamWriter
import java.io.UnsupportedEncodingException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

class BackendWalkingRouteClient(
    private val transport: WalkingRouteTransport = HttpUrlConnectionWalkingRouteTransport(),
) {
    fun fetchRoute(baseUrl: String, request: WalkingRouteRequest): WalkingRoute {
        val endpoint = baseUrl.trimEnd('/') + "/navigation/walking"
        val response = transport.post(endpoint, request.toJson().toString())
        return parseWalkingRoute(JSONObject(response))
    }

    fun searchDestinations(
        baseUrl: String,
        query: String,
        limit: Int,
        origin: RoutePoint?,
    ): DestinationSearchResponse {
        val safeQuery = query.trim()
        if (safeQuery.isEmpty()) return DestinationSearchResponse(provider = "tmap_poi", query = "", results = emptyList())
        val endpoint = buildString {
            append(baseUrl.trimEnd('/'))
            append("/navigation/destinations/search")
            append("?query=")
            append(encodeQuery(safeQuery))
            append("&limit=")
            append(limit.coerceIn(1, 10))
            if (origin != null) {
                append("&origin_lat=")
                append(origin.latitude)
                append("&origin_lng=")
                append(origin.longitude)
            }
        }
        val response = transport.get(endpoint)
        return parseDestinationSearchResponse(JSONObject(response), query = safeQuery)
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
    fun post(url: String, body: String): String
    fun get(url: String): String
}

class HttpUrlConnectionWalkingRouteTransport : WalkingRouteTransport {
    override fun post(url: String, body: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("walking route request failed: http ${connection.responseCode}")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    override fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("destination search failed: http ${connection.responseCode}")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }
}

fun parseWalkingRoute(json: JSONObject): WalkingRoute {
    val summary = json.getJSONObject("summary")
    return WalkingRoute(
        priority = json.optString("priority", "STAIR_AVOID"),
        summary = WalkingRouteSummary(
            distanceM = summary.optInt("distance_m"),
            durationS = summary.optInt("duration_s"),
        ),
        polyline = json.optJSONArray("polyline").toRoutePoints(),
        guidePoints = json.optJSONArray("guide_points").toGuidePoints(),
    )
}

private fun parseDestinationSearchResponse(json: JSONObject, query: String): DestinationSearchResponse {
    return DestinationSearchResponse(
        provider = json.optString("provider", "tmap_poi"),
        query = query,
        results = json.optJSONArray("results").toDestinationSearchResults(),
    )
}

private fun JSONArray?.toRoutePoints(): List<RoutePoint> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        optJSONObject(index)?.toRoutePoint()
    }
}

private fun JSONArray?.toGuidePoints(): List<WalkingRouteGuidePoint> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        val item = optJSONObject(index) ?: return@mapNotNull null
        WalkingRouteGuidePoint(
            index = item.optInt("index", index),
            point = item.optJSONObject("point")?.toRoutePoint() ?: return@mapNotNull null,
            instruction = item.optString("instruction").takeIf { it.isNotBlank() },
            distanceFromStartM = item.optNullableInt("distance_from_start_m"),
            remainingDistanceM = item.optNullableInt("remaining_distance_m"),
            bearingDeg = item.optNullableFloat("bearing_deg"),
            turnType = item.optString("turn_type").takeIf { it.isNotBlank() },
            pointType = item.optString("point_type").takeIf { it.isNotBlank() },
            facilityType = item.optString("facility_type").takeIf { it.isNotBlank() },
        )
    }
}

private fun JSONArray?.toDestinationSearchResults(): List<DestinationSearchResult> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        optJSONObject(index)?.toDestinationSearchResult()
    }
}

private fun JSONObject.toRoutePoint(): RoutePoint {
    return RoutePoint(
        latitude = getDouble("latitude"),
        longitude = getDouble("longitude"),
        name = optString("name").takeIf { it.isNotBlank() },
    )
}

private fun JSONObject.toDestinationSearchResult(): DestinationSearchResult {
    return DestinationSearchResult(
        id = optString("id"),
        name = optString("name").ifBlank { "이름없음" },
        point = optJSONObject("point")?.toRoutePoint() ?: RoutePoint(0.0, 0.0),
        address = optString("address").takeIf { it.isNotBlank() },
        roadAddress = optString("road_address").takeIf { it.isNotBlank() },
        category = optString("category").takeIf { it.isNotBlank() },
        distanceM = optNullableInt("distance_m"),
        resultType = optString("result_type", "poi").ifBlank { "poi" },
    )
}

private fun JSONObject.optNullableInt(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}

private fun JSONObject.optNullableFloat(name: String): Float? {
    return if (has(name) && !isNull(name)) optDouble(name).toFloat() else null
}

fun formatDestinationDistance(distanceM: Int?): String {
    return distanceM?.let { "${it}m" } ?: "거리미상"
}
