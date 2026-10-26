package kr.co.hanium.dreamup.walksafe.navigation

import android.content.SharedPreferences
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.security.AeadBlockReason
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.json.JSONArray
import org.json.JSONObject

internal data class RouteSnapshotOwner(val actorId: String, val accountGeneration: String) {
    init {
        require(actorId.isNotBlank() && actorId.length <= 256)
        require(accountGeneration.isNotBlank() && accountGeneration.length <= 256)
    }
}

internal data class RouteSnapshotScope(val owner: RouteSnapshotOwner, val walkSessionId: String) {
    init { require(validSessionId(walkSessionId)) }
}

internal data class StoredRouteSnapshot(
    val scope: RouteSnapshotScope,
    val savedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val destination: RoutePoint,
    val route: WalkingRoute,
    val savedAtElapsedRealtimeMs: Long = 0L,
    val savedBootCount: Int = UNKNOWN_BOOT_COUNT,
) {
    val walkSessionId: String get() = scope.walkSessionId
}

/** Startup/expiry callers receive no destination or route. */
internal data class RouteSnapshotExpiryMetadata(val scope: RouteSnapshotScope, val expiresAtEpochMs: Long) {
    val walkSessionId: String get() = scope.walkSessionId
}

internal const val UNKNOWN_BOOT_COUNT = -1

/** One process owns the current walk across Activity/store instances, including before a route is saved. */
internal class RouteSnapshotProcessCoordinator {
    internal val lock = Any()
    internal data class AccountBinding(val owner: RouteSnapshotOwner?, val generation: Long)
    internal data class WalkLease(val scope: RouteSnapshotScope, val generation: Long)
    private var account = AccountBinding(null, 0L)
    private var walk: WalkLease? = null
    private var generation = 0L

    internal fun bind(owner: RouteSnapshotOwner?): AccountBinding {
        if (account.owner != owner) {
            account = AccountBinding(owner, ++generation)
            walk = null
        }
        return account
    }

    internal fun accountCurrent(binding: AccountBinding?): Boolean = binding != null && binding == account
    internal fun claim(binding: AccountBinding?, scope: RouteSnapshotScope): WalkLease? {
        if (!accountCurrent(binding) || account.owner != scope.owner) return null
        val current = walk
        if (current != null) return current.takeIf { it.scope == scope }
        return WalkLease(scope, ++generation).also { walk = it }
    }
    internal fun walkCurrent(lease: WalkLease?): Boolean = lease != null && lease == walk
    internal fun release(lease: WalkLease?) { if (walkCurrent(lease)) walk = null }

    internal companion object { val shared = RouteSnapshotProcessCoordinator() }
}

/** Stores the first complete TMAP route; all storage mutations share the process ownership lock. */
internal class EncryptedRouteSnapshotStore private constructor(
    private val storage: RouteSnapshotStorage,
    private val aead: LocalAead,
    private val elapsedRealtimeMs: () -> Long,
    private val bootCount: () -> Int,
    private val process: RouteSnapshotProcessCoordinator,
) {
    private var accountBinding: RouteSnapshotProcessCoordinator.AccountBinding? = null
    private var walkLease: RouteSnapshotProcessCoordinator.WalkLease? = null
    private var endedScope: RouteSnapshotScope? = null

    constructor(
        preferences: SharedPreferences,
        aead: LocalAead = AndroidKeyStoreAead(KEY_POLICY),
        bootCount: () -> Int = { UNKNOWN_BOOT_COUNT },
    ) : this(SharedPreferencesRouteSnapshotStorage(preferences), aead,
        { SystemClock.elapsedRealtime() }, bootCount, RouteSnapshotProcessCoordinator.shared)

    internal constructor(
        storage: RouteSnapshotStorage,
        aead: LocalAead,
        testOnly: Unit = Unit,
        elapsedRealtimeMs: () -> Long = { 0L },
        bootCount: () -> Int = { UNKNOWN_BOOT_COUNT },
        processCoordinator: RouteSnapshotProcessCoordinator = RouteSnapshotProcessCoordinator.shared,
    ) : this(storage, aead, elapsedRealtimeMs, bootCount, processCoordinator)

    /** Called only at an accepted account boundary. Rotation of the same owner's token keeps its walk. */
    fun bindAccount(owner: RouteSnapshotOwner?): Boolean = synchronized(process.lock) {
        val previous = accountBinding
        accountBinding = process.bind(owner)
        if (previous != accountBinding) walkLease = null
        if (owner == null) return@synchronized verifiedClear()
        val existing = readSnapshot(System.currentTimeMillis(), checkExpiry = false)
        if (existing != null && existing.scope.owner == owner) return@synchronized true
        if (storage.read() == null) return@synchronized true
        // Unreadable/ownerless ciphertext is never adopted by a newly bound account.
        verifiedClear()
    }

    /** A live walk must be admitted before asynchronous route work can save anything. */
    fun beginWalk(scope: RouteSnapshotScope): Boolean = synchronized(process.lock) {
        if (endedScope == scope) return@synchronized false
        val claimed = process.claim(accountBinding, scope) ?: return@synchronized false
        walkLease = claimed
        true
    }

    fun saveFirstRoute(
        scope: RouteSnapshotScope,
        route: WalkingRoute,
        destination: RoutePoint,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(process.lock) {
        if (!ownsWalk(scope) || nowEpochMs < 0L) return@synchronized false
        val existing = readSnapshot(nowEpochMs)
        if (existing != null && existing.scope == scope) return@synchronized true
        if (existing != null) {
            // claim() proved the previous walk is no longer live in this process. A fresh process
            // starts with no walk lease, so a killed process's archive can be replaced as well.
            if (!verifiedClear()) return@synchronized false
        } else if (storage.read() != null) return@synchronized false
        val expiresAtEpochMs = nowEpochMs + ROUTE_SNAPSHOT_TTL_MS
        if (expiresAtEpochMs < nowEpochMs) return@synchronized false
        val savedElapsed = elapsedRealtimeMs().takeIf { it >= 0L } ?: return@synchronized false
        saveReplacement(StoredRouteSnapshot(scope, nowEpochMs, expiresAtEpochMs, destination, route,
            savedElapsed, currentBootCountOrUnknown()))
    }

    /** Returning the archive requires the expected account generation and the current walk lease. */
    fun load(expectedScope: RouteSnapshotScope, nowEpochMs: Long = System.currentTimeMillis()): StoredRouteSnapshot? =
        synchronized(process.lock) {
            if (!ownsWalk(expectedScope)) return@synchronized null
            readSnapshot(nowEpochMs)?.takeIf { it.scope == expectedScope }
        }

    fun expiryMetadata(nowEpochMs: Long = System.currentTimeMillis()): RouteSnapshotExpiryMetadata? =
        synchronized(process.lock) {
            readSnapshot(nowEpochMs)?.let { RouteSnapshotExpiryMetadata(it.scope, it.expiresAtEpochMs) }
        }

    fun purgeExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean = synchronized(process.lock) {
        if (storage.read() == null) return@synchronized true
        if (readSnapshot(nowEpochMs) != null) return@synchronized true
        storage.read() == null
    }

    /** An old Activity's delayed purge cannot delete a different account/walk's archive. */
    fun clearWalk(expectedScope: RouteSnapshotScope): Boolean = synchronized(process.lock) {
        val existing = readSnapshot(System.currentTimeMillis(), checkExpiry = false, purgeInvalid = false)
        if (existing != null && existing.scope != expectedScope) return@synchronized true
        if (existing == null && storage.read() != null) return@synchronized false
        verifiedClear()
    }

    fun endWalk(): Boolean = synchronized(process.lock) {
        val lease = walkLease ?: return@synchronized true
        try {
            clearWalk(lease.scope)
        } catch (_: Exception) {
            false
        } finally {
            process.release(lease)
            endedScope = lease.scope
            // Keep the scope for a purge retry, but even a failed purge ends save/load authority.
        }
    }

    /** Destroy detaches runtime ownership; it does not claim that an archive was deleted. */
    fun detach() = synchronized(process.lock) { process.release(walkLease) }

    private fun ownsWalk(scope: RouteSnapshotScope): Boolean =
        process.accountCurrent(accountBinding) && process.walkCurrent(walkLease) && walkLease?.scope == scope

    private fun verifiedClear(): Boolean = storage.clear() && storage.read() == null

    private fun readSnapshot(nowEpochMs: Long, checkExpiry: Boolean = true, purgeInvalid: Boolean = true): StoredRouteSnapshot? {
        if (nowEpochMs < 0L) return null
        val envelope = storage.read() ?: return null
        val opened = when (val result = aead.open(envelope, ROUTE_SNAPSHOT_AAD, ROUTE_SNAPSHOT_LIMITS)) {
            is AeadOpenResult.Opened -> result
            is AeadOpenResult.Blocked -> {
                if (purgeInvalid && result.reason != AeadBlockReason.KEY_ACCESS_FAILED) verifiedClear()
                return null
            }
        }
        val snapshot = decodeSnapshot(opened.plaintext)
        opened.plaintext.fill(0)
        if (snapshot == null || (checkExpiry &&
            (nowEpochMs >= snapshot.expiresAtEpochMs || !sameBootWithinTtl(snapshot, nowEpochMs)))) {
            if (purgeInvalid) verifiedClear()
            return null
        }
        if (opened.needsRewrap && !saveReplacement(snapshot)) return null
        return snapshot
    }

    private fun sameBootWithinTtl(snapshot: StoredRouteSnapshot, nowEpochMs: Long): Boolean {
        val nowBootCount = currentBootCountOrUnknown()
        if (snapshot.savedBootCount != UNKNOWN_BOOT_COUNT && nowBootCount != UNKNOWN_BOOT_COUNT &&
            snapshot.savedBootCount != nowBootCount) return false
        val nowElapsedMs = elapsedRealtimeMs()
        val savedElapsedMs = snapshot.savedAtElapsedRealtimeMs
        if (nowElapsedMs < savedElapsedMs || nowElapsedMs - savedElapsedMs >= ROUTE_SNAPSHOT_TTL_MS) return false
        val savedBootReferenceMs = snapshot.savedAtEpochMs - savedElapsedMs
        val nowBootReferenceMs = nowEpochMs - nowElapsedMs
        return kotlin.math.abs(nowBootReferenceMs - savedBootReferenceMs) <= BOOT_REFERENCE_TOLERANCE_MS
    }

    private fun currentBootCountOrUnknown(): Int =
        runCatching(bootCount).getOrNull()?.takeIf { it >= 0 } ?: UNKNOWN_BOOT_COUNT

    private fun saveReplacement(snapshot: StoredRouteSnapshot): Boolean {
        val payload = runCatching { encodeSnapshot(snapshot).toByteArray(Charsets.UTF_8) }.getOrNull() ?: return false
        val sealed = aead.seal(payload, ROUTE_SNAPSHOT_AAD, ROUTE_SNAPSHOT_LIMITS)
        payload.fill(0)
        val envelope = (sealed as? AeadSealResult.Sealed)?.envelope ?: return false
        return storage.write(envelope) && storage.read() == envelope
    }

    private fun encodeSnapshot(snapshot: StoredRouteSnapshot): String = JSONObject()
        .put("schema_version", ROUTE_SNAPSHOT_SCHEMA)
        .put("owner_actor_id", snapshot.scope.owner.actorId)
        .put("owner_account_generation", snapshot.scope.owner.accountGeneration)
        .put("walk_session_id", snapshot.walkSessionId)
        .put("saved_at_epoch_ms", snapshot.savedAtEpochMs)
        .put("expires_at_epoch_ms", snapshot.expiresAtEpochMs)
        .put("saved_at_elapsed_realtime_ms", snapshot.savedAtElapsedRealtimeMs)
        .put("saved_boot_count", snapshot.savedBootCount)
        .put("destination", snapshot.destination.toJson())
        .put("route", snapshot.route.toJson())
        .toString()

    private fun decodeSnapshot(payload: ByteArray): StoredRouteSnapshot? = runCatching {
        val root = JSONObject(String(payload, Charsets.UTF_8))
        require(root.keysAsSet() == SNAPSHOT_FIELDS)
        require(root.getString("schema_version") == ROUTE_SNAPSHOT_SCHEMA)
        val scope = RouteSnapshotScope(RouteSnapshotOwner(root.getString("owner_actor_id"),
            root.getString("owner_account_generation")), root.getString("walk_session_id"))
        val savedAtEpochMs = root.getLong("saved_at_epoch_ms")
        val expiresAtEpochMs = root.getLong("expires_at_epoch_ms")
        require(savedAtEpochMs >= 0L && expiresAtEpochMs >= savedAtEpochMs)
        require(expiresAtEpochMs - savedAtEpochMs == ROUTE_SNAPSHOT_TTL_MS)
        val savedAtElapsedRealtimeMs = root.getLong("saved_at_elapsed_realtime_ms")
        require(savedAtElapsedRealtimeMs >= 0L)
        val savedBootCount = root.getInt("saved_boot_count")
        require(savedBootCount >= UNKNOWN_BOOT_COUNT)
        val destination = root.getJSONObject("destination").toRoutePoint()
        val routeJson = root.getJSONObject("route")
        val route = parseWalkingRoute(routeJson, WalkingRouteRequest(
            origin = routeJson.getJSONArray("polyline").getJSONObject(0).toRoutePoint(), destination = destination))
        StoredRouteSnapshot(scope, savedAtEpochMs, expiresAtEpochMs, destination, route,
            savedAtElapsedRealtimeMs, savedBootCount)
    }.getOrNull()

    private companion object {
        // v4 requires an account owner/generation; ownerless v1-v3 archives are deleted.
        const val ROUTE_SNAPSHOT_SCHEMA = "walksafe.encrypted-route-snapshot.v4"
        const val BOOT_REFERENCE_TOLERANCE_MS = 5L * 60L * 1_000L
        val SNAPSHOT_FIELDS = setOf("schema_version", "owner_actor_id", "owner_account_generation",
            "walk_session_id", "saved_at_epoch_ms", "expires_at_epoch_ms", "saved_at_elapsed_realtime_ms",
            "saved_boot_count", "destination", "route")
        val ROUTE_SNAPSHOT_AAD = "kr.co.hanium.dreamup.walksafe|USER|navigation|route-snapshot|schema=1"
            .toByteArray(Charsets.UTF_8)
        val KEY_POLICY = AeadKeyPolicy("walksafe.user.navigation.route_snapshot.aead.v", 1, setOf(1))
        val ROUTE_SNAPSHOT_LIMITS = AeadLimits(2 * 1_024 * 1_024, 2 * 1_024 * 1_024 + 16, 3 * 1_024 * 1_024)
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
    override fun read(): String? = preferences.getString(ROUTE_SNAPSHOT_KEY, null)

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
    .also { json -> distanceSource?.let { json.put("distance_source", it.wireValue) } }

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
