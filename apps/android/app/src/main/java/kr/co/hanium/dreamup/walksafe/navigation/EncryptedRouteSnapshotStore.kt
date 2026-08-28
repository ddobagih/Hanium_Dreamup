package kr.co.hanium.dreamup.walksafe.navigation

import android.content.SharedPreferences
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.json.JSONArray
import org.json.JSONObject

internal data class StoredRouteSnapshot(
    val walkSessionId: String,
    val savedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val destination: RoutePoint,
    val route: WalkingRoute,
)

/** Stores the first complete TMAP route for one walk as one authenticated ciphertext. */
internal class EncryptedRouteSnapshotStore private constructor(
    private val storage: RouteSnapshotStorage,
    private val aead: LocalAead,
) {
    constructor(
        preferences: SharedPreferences,
        aead: LocalAead = AndroidKeyStoreAead(KEY_POLICY),
    ) : this(SharedPreferencesRouteSnapshotStorage(preferences), aead)

    internal constructor(
        storage: RouteSnapshotStorage,
        aead: LocalAead,
        testOnly: Unit = Unit,
    ) : this(storage, aead)

    @Synchronized
    fun saveFirstRoute(
        walkSessionId: String,
        route: WalkingRoute,
        destination: RoutePoint,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!validSessionId(walkSessionId) || nowEpochMs < 0L) return false
        val existing = load(nowEpochMs)
        if (existing != null) {
            return existing.walkSessionId == walkSessionId
        }
        val expiresAtEpochMs = nowEpochMs + ROUTE_SNAPSHOT_TTL_MS
        if (expiresAtEpochMs < nowEpochMs) return false
        val payload = runCatching {
            encodeSnapshot(
                StoredRouteSnapshot(
                    walkSessionId = walkSessionId,
                    savedAtEpochMs = nowEpochMs,
                    expiresAtEpochMs = expiresAtEpochMs,
                    destination = destination,
                    route = route,
                ),
            ).toByteArray(Charsets.UTF_8)
        }.getOrNull() ?: return false
        val sealed = aead.seal(payload, ROUTE_SNAPSHOT_AAD, ROUTE_SNAPSHOT_LIMITS)
        payload.fill(0)
        val envelope = (sealed as? AeadSealResult.Sealed)?.envelope ?: return false
        return storage.write(envelope)
    }

    @Synchronized
    fun load(nowEpochMs: Long = System.currentTimeMillis()): StoredRouteSnapshot? {
        if (nowEpochMs < 0L) return null
        val envelope = storage.read() ?: return null
        val opened = aead.open(envelope, ROUTE_SNAPSHOT_AAD, ROUTE_SNAPSHOT_LIMITS)
            as? AeadOpenResult.Opened
            ?: run {
                storage.clear()
                return null
            }
        val snapshot = decodeSnapshot(opened.plaintext)
        opened.plaintext.fill(0)
        if (snapshot == null || nowEpochMs >= snapshot.expiresAtEpochMs) {
            storage.clear()
            return null
        }
        if (opened.needsRewrap && !saveReplacement(snapshot)) return null
        return snapshot
    }

    @Synchronized
    fun purgeExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        if (storage.read() == null) return true
        if (load(nowEpochMs) != null) return true
        return storage.read() == null
    }

    @Synchronized
    fun clear(): Boolean = storage.clear()

    private fun saveReplacement(snapshot: StoredRouteSnapshot): Boolean {
        val payload = encodeSnapshot(snapshot).toByteArray(Charsets.UTF_8)
        val sealed = aead.seal(payload, ROUTE_SNAPSHOT_AAD, ROUTE_SNAPSHOT_LIMITS)
        payload.fill(0)
        val envelope = (sealed as? AeadSealResult.Sealed)?.envelope ?: return false
        return storage.write(envelope)
    }

    private fun encodeSnapshot(snapshot: StoredRouteSnapshot): String = JSONObject()
        .put("schema_version", ROUTE_SNAPSHOT_SCHEMA)
        .put("walk_session_id", snapshot.walkSessionId)
        .put("saved_at_epoch_ms", snapshot.savedAtEpochMs)
        .put("expires_at_epoch_ms", snapshot.expiresAtEpochMs)
        .put("destination", snapshot.destination.toJson())
        .put("route", snapshot.route.toJson())
        .toString()

    private fun decodeSnapshot(payload: ByteArray): StoredRouteSnapshot? = runCatching {
        val root = JSONObject(String(payload, Charsets.UTF_8))
        require(root.keysAsSet() == SNAPSHOT_FIELDS)
        require(root.getString("schema_version") == ROUTE_SNAPSHOT_SCHEMA)
        val walkSessionId = root.getString("walk_session_id")
        require(validSessionId(walkSessionId))
        val savedAtEpochMs = root.getLong("saved_at_epoch_ms")
        val expiresAtEpochMs = root.getLong("expires_at_epoch_ms")
        require(savedAtEpochMs >= 0L)
        require(expiresAtEpochMs - savedAtEpochMs == ROUTE_SNAPSHOT_TTL_MS)
        val destination = root.getJSONObject("destination").toRoutePoint()
        val routeJson = root.getJSONObject("route")
        val route = parseWalkingRoute(
            routeJson,
            WalkingRouteRequest(
                origin = routeJson.getJSONArray("polyline").getJSONObject(0).toRoutePoint(),
                destination = destination,
            ),
        )
        StoredRouteSnapshot(walkSessionId, savedAtEpochMs, expiresAtEpochMs, destination, route)
    }.getOrNull()

    private companion object {
        const val ROUTE_SNAPSHOT_SCHEMA = "walksafe.encrypted-route-snapshot.v1"
        val SNAPSHOT_FIELDS = setOf(
            "schema_version",
            "walk_session_id",
            "saved_at_epoch_ms",
            "expires_at_epoch_ms",
            "destination",
            "route",
        )
        val ROUTE_SNAPSHOT_AAD =
            "kr.co.hanium.dreamup.walksafe|USER|navigation|route-snapshot|schema=1"
                .toByteArray(Charsets.UTF_8)
        val KEY_POLICY = AeadKeyPolicy(
            aliasPrefix = "walksafe.user.navigation.route_snapshot.aead.v",
            currentVersion = 1,
            readableVersions = setOf(1),
        )
        val ROUTE_SNAPSHOT_LIMITS = AeadLimits(
            maxPlaintextBytes = 2 * 1_024 * 1_024,
            maxCiphertextBytes = 2 * 1_024 * 1_024 + 16,
            maxEnvelopeChars = 3 * 1_024 * 1_024,
        )
    }
}

internal interface RouteSnapshotStorage {
    fun read(): String?
    fun write(envelope: String): Boolean
    fun clear(): Boolean
}

private class SharedPreferencesRouteSnapshotStorage(
    private val preferences: SharedPreferences,
) : RouteSnapshotStorage {
    override fun read(): String? = runCatching {
        preferences.getString(ROUTE_SNAPSHOT_KEY, null)
    }.getOrNull()

    override fun write(envelope: String): Boolean = runCatching {
        preferences.edit().putString(ROUTE_SNAPSHOT_KEY, envelope).commit()
    }.getOrDefault(false)

    override fun clear(): Boolean = runCatching {
        preferences.edit().remove(ROUTE_SNAPSHOT_KEY).commit()
    }.getOrDefault(false)
}

private fun validSessionId(value: String): Boolean = value.matches(Regex("^[A-Za-z0-9._:-]{1,128}$"))

private fun RoutePoint.toJson(): JSONObject = JSONObject()
    .put("latitude", latitude)
    .put("longitude", longitude)
    .also { if (!name.isNullOrBlank()) it.put("name", name) }

private fun JSONObject.toRoutePoint(): RoutePoint = RoutePoint(
    latitude = getDouble("latitude"),
    longitude = getDouble("longitude"),
    name = optString("name").takeIf(String::isNotBlank),
)

private fun WalkingRoute.toJson(): JSONObject = JSONObject()
    .put("schema_version", "walksafe.walking_route.v1")
    .put("provider", "tmap_pedestrian")
    .put("provider_result_code", 0)
    .put("provider_result_message", "OK")
    .put("priority", priority)
    .put("summary", JSONObject().put("distance_m", summary.distanceM).put("duration_s", summary.durationS))
    .put("polyline", JSONArray().also { array -> polyline.forEach { array.put(it.toJson()) } })
    .put("guide_points", JSONArray().also { array -> guidePoints.forEach { array.put(it.toJson()) } })
    .put("steps", JSONArray().also { array -> steps.forEach { array.put(it.toJson()) } })
    .also { if (providerRouteId != null) it.put("provider_route_id", providerRouteId) }

private fun WalkingRouteGuidePoint.toJson(): JSONObject = JSONObject()
    .put("index", index)
    .put("point", point.toJson())
    .also { json ->
        instruction?.let { json.put("instruction", it) }
        distanceFromStartM?.let { json.put("distance_from_start_m", it) }
        remainingDistanceM?.let { json.put("remaining_distance_m", it) }
        bearingDeg?.let { json.put("bearing_deg", it) }
        turnType?.let { json.put("turn_type", it) }
        pointType?.let { json.put("point_type", it) }
        facilityType?.let { json.put("facility_type", it) }
    }

private fun WalkingRouteStep.toJson(): JSONObject = JSONObject()
    .put("index", index)
    .put("distance_m", distanceM)
    .put("duration_s", durationS)
    .put("points", JSONArray().also { array -> points.forEach { array.put(it.toJson()) } })
    .also { json ->
        instruction?.let { json.put("instruction", it) }
        roadName?.let { json.put("road_name", it) }
        turnType?.let { json.put("turn_type", it) }
        facilityType?.let { json.put("facility_type", it) }
    }

private fun JSONObject.keysAsSet(): Set<String> = buildSet { keys().forEach(::add) }

internal const val ROUTE_SNAPSHOT_TTL_MS = 24L * 60L * 60L * 1_000L
private const val ROUTE_SNAPSHOT_KEY = "encrypted_route_snapshot_v1"
