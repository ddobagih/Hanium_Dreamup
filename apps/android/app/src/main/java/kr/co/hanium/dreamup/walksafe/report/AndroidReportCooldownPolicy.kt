package kr.co.hanium.dreamup.walksafe.report

import android.os.SystemClock
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.navigation.haversineMeters
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.network.GatewayCredentialPolicy
import org.json.JSONObject

data class AndroidReportSpatialScope(
    val actorId: String,
    val className: String,
    val latitude: Double,
    val longitude: Double,
    val storageKey: String,
)

data class AndroidReportSuccessfulCooldown(
    val scope: AndroidReportSpatialScope,
    val lastUploadedAtMs: Long,
    val cooldownMonotonicMarkMs: Long? = null,
    val cooldownRemainingAtMarkMs: Long? = null,
    val bootCount: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("actor_id", scope.actorId)
        .put("class_name", scope.className)
        .put("latitude", scope.latitude)
        .put("longitude", scope.longitude)
        .put("last_uploaded_at_ms", lastUploadedAtMs)
        .put("cooldown_monotonic_mark_ms", cooldownMonotonicMarkMs)
        .put("cooldown_remaining_at_mark_ms", cooldownRemainingAtMarkMs)
        .put("boot_count", bootCount)

    companion object {
        fun fromJson(item: JSONObject): AndroidReportSuccessfulCooldown? {
            val scope = AndroidReportCooldownPolicy.spatialScopeOrNull(
                actorId = item.optString("actor_id"),
                className = item.optString("class_name"),
                location = TrustedLocation(
                    latitude = item.getDouble("latitude"),
                    longitude = item.getDouble("longitude"),
                    accuracyM = 0f,
                    elapsedRealtimeMs = 0L,
                ),
            ) ?: return null
            return AndroidReportSuccessfulCooldown(
                scope = scope,
                lastUploadedAtMs = item.getLong("last_uploaded_at_ms"),
                cooldownMonotonicMarkMs = item.nonNegativeLongOrNull("cooldown_monotonic_mark_ms"),
                cooldownRemainingAtMarkMs = item.nonNegativeLongOrNull(
                    "cooldown_remaining_at_mark_ms",
                    AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS,
                ),
                bootCount = item.nonNegativeLongOrNull("boot_count", Int.MAX_VALUE.toLong())?.toInt(),
            )
        }

        private fun JSONObject.nonNegativeLongOrNull(key: String, maximum: Long = Long.MAX_VALUE): Long? {
            val value = when (val raw = opt(key)) {
                is Int -> raw.toLong()
                is Long -> raw
                else -> return null
            }
            return value.takeIf { it in 0L..maximum }
        }
    }
}

internal fun automaticReportCooldownScopeOrNull(
    metadata: JSONObject,
): AndroidReportSpatialScope? = runCatching {
    require(metadata.getString("schema_version") == "detect.v2")
    require(metadata.getString("trigger") == AndroidReportCandidatePolicy.TRIGGER_AUTO)
    require(metadata.getBoolean("auto_reported"))
    val gps = metadata.getJSONObject("gps")
    AndroidReportCooldownPolicy.spatialScopeOrNull(
        actorId = metadata.getString("reporter_user_id"),
        className = metadata.getString("class_name"),
        location = TrustedLocation(
            latitude = gps.getDouble("latitude"),
            longitude = gps.getDouble("longitude"),
            accuracyM = 0f,
            elapsedRealtimeMs = 0L,
        ),
    )
}.getOrNull()

internal fun QueuedReport.automaticCooldownScopeOrNull(): AndroidReportSpatialScope? {
    if (priority != ReportQueuePriority.AUTOMATIC) return null
    val metadata = payload.metadataUtf8()
    return try {
        automaticReportCooldownScopeOrNull(JSONObject(String(metadata, Charsets.UTF_8)))
    } finally {
        metadata.fill(0)
    }
}

/** Keeps automatic reports scoped to a named actor and a real-distance GPS neighborhood. */
object AndroidReportCooldownPolicy {
    const val AUTOMATIC_COOLDOWN_MS = 10L * 60L * 1_000L
    const val SPATIAL_COOLDOWN_RADIUS_M = 25.0
    const val MAX_TRACKED_STATES = 512

    fun spatialScopeOrNull(
        actorId: String?,
        className: String,
        location: TrustedLocation,
    ): AndroidReportSpatialScope? {
        val actor = GatewayCredentialPolicy.normalizedActorIdOrNull(actorId) ?: return null
        if (className != AndroidReportCandidatePolicy.DAMAGED_TACTILE_BLOCK) return null
        if (!location.latitude.isFinite() || location.latitude !in -90.0..90.0) return null
        if (!location.longitude.isFinite() || location.longitude !in -180.0..180.0) return null
        val storageKey = listOf(
            actor,
            className,
            String.format(Locale.US, "%.4f", location.latitude.normalizedZero()),
            String.format(Locale.US, "%.4f", location.longitude.normalizedZero()),
        ).joinToString(":")
        return AndroidReportSpatialScope(
            actorId = actor,
            className = className,
            latitude = location.latitude,
            longitude = location.longitude,
            storageKey = storageKey,
        )
    }

    fun isSameCooldownArea(
        first: AndroidReportSpatialScope,
        second: AndroidReportSpatialScope,
    ): Boolean {
        if (first.actorId != second.actorId || first.className != second.className) return false
        return haversineMeters(
            first.latitude,
            first.longitude,
            second.latitude,
            second.longitude,
        ) <= SPATIAL_COOLDOWN_RADIUS_M
    }

    fun automaticCooldownRemainingMs(lastUploadedAtMs: Long, nowMs: Long): Long {
        if (lastUploadedAtMs <= 0L) return 0L
        val elapsedMs = nowMs - lastUploadedAtMs
        if (elapsedMs < 0L) return AUTOMATIC_COOLDOWN_MS
        return (AUTOMATIC_COOLDOWN_MS - elapsedMs).coerceAtLeast(0L)
    }

    /**
     * S15-37: burst suppression while a same-area automatic report is still waiting in the local
     * queue. It is a separate rule from the post-upload cooldown above and only shares today's
     * 10-minute operating value. A waiting entry is never updated or merged with a later
     * observation: location-based candidates go to admin review, not automatic merging
     * (FP-032-DETAIL-01 interim). After the window a new observation is a separate entry, bounded
     * by the automatic-only queue quota that keeps explicit reports' reserve.
     */
    const val PENDING_AUTOMATIC_DUPLICATE_WINDOW_MS = AUTOMATIC_COOLDOWN_MS

    fun pendingAutomaticDuplicateSuppressed(queuedAtMs: Long, nowMs: Long): Boolean {
        if (queuedAtMs <= 0L) return false
        val elapsedMs = nowMs - queuedAtMs
        // A clock rollback keeps the window closed rather than admitting a duplicate.
        return elapsedMs < 0L || elapsedMs < PENDING_AUTOMATIC_DUPLICATE_WINDOW_MS
    }

    fun shouldPruneState(
        lastTouchedAtMs: Long,
        lastUploadedAtMs: Long,
        retryNotBeforeMs: Long,
        inFlight: Boolean,
        nowMs: Long,
    ): Boolean {
        if (inFlight || retryNotBeforeMs > nowMs) return false
        val mostRecentStateMs = maxOf(lastTouchedAtMs, lastUploadedAtMs)
        if (mostRecentStateMs <= 0L) return true
        val stateAgeMs = nowMs - mostRecentStateMs
        return stateAgeMs >= AUTOMATIC_COOLDOWN_MS
    }

    private fun Double.normalizedZero(): Double {
        return if (this == 0.0 || this in -0.00005..0.00005) 0.0 else this
    }
}

enum class AndroidReportAttemptBlockReason {
    IN_FLIGHT,
    AUTOMATIC_COOLDOWN,
    RETRY_BACKOFF,
    STATE_CAPACITY,
}

fun isTransientReportHttpStatus(statusCode: Int): Boolean =
    statusCode == 408 ||
        statusCode == 425 ||
        statusCode == 429 ||
        statusCode in 500..599

sealed interface AndroidReportAttemptResult {
    data class Allowed(val lease: AndroidReportAttemptLease) : AndroidReportAttemptResult

    data class Blocked(
        val reason: AndroidReportAttemptBlockReason,
        val storageKey: String,
        val remainingMs: Long,
        val attemptCount: Int,
    ) : AndroidReportAttemptResult
}

class AndroidReportAttemptLease internal constructor(
    internal val stateId: Long,
    internal val attemptedScope: AndroidReportSpatialScope,
    val storageKey: String,
    val attemptCount: Int,
)

/** Thread-safe, in-memory state for automatic spatial cooldown and upload retry serialization. */
class AndroidReportAttemptStore(
    private val maxTrackedStates: Int = AndroidReportCooldownPolicy.MAX_TRACKED_STATES,
    private val monotonicNowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val bootCountProvider: () -> Int? = { null },
) {
    init {
        require(maxTrackedStates in 1..AndroidReportCooldownPolicy.MAX_TRACKED_STATES)
    }

    private val lock = Any()
    private val states = linkedMapOf<String, State>()
    private var nextStateId = 1L

    fun acquire(
        scope: AndroidReportSpatialScope,
        nowMs: Long,
        bypassAutomaticCooldown: Boolean,
    ): AndroidReportAttemptResult = synchronized(lock) {
        val monotonicMs = monotonicNowOrNull()
        pruneExpiredStates(nowMs, monotonicMs)
        var matches = states.values.filter { state ->
            AndroidReportCooldownPolicy.isSameCooldownArea(state.scope, scope) ||
                state.inFlightScope?.let { AndroidReportCooldownPolicy.isSameCooldownArea(it, scope) } == true
        }
        if (matches.isEmpty()) {
            if (states.size >= maxTrackedStates) {
                return@synchronized AndroidReportAttemptResult.Blocked(
                    reason = AndroidReportAttemptBlockReason.STATE_CAPACITY,
                    storageKey = scope.storageKey,
                    remainingMs = 0L,
                    attemptCount = 0,
                )
            }
            val storageKey = uniqueStorageKey(scope.storageKey)
            val state = State(
                id = nextStateId++,
                storageKey = storageKey,
                scope = scope,
                lastTouchedAtMs = nowMs,
                lastTouchedMonotonicMs = monotonicMs,
            )
            states[storageKey] = state
            matches = listOf(state)
        }

        matches.firstOrNull(State::inFlight)?.let { state ->
            state.touch(nowMs, monotonicMs)
            return@synchronized state.blocked(AndroidReportAttemptBlockReason.IN_FLIGHT)
        }

        if (!bypassAutomaticCooldown) {
            matches.maxByOrNull { state ->
                state.automaticCooldownRemainingMs(nowMs, monotonicMs)
            }?.let { state ->
                val remainingMs = state.automaticCooldownRemainingMs(nowMs, monotonicMs)
                if (remainingMs > 0L) {
                    state.touch(nowMs, monotonicMs)
                    return@synchronized state.blocked(
                        reason = AndroidReportAttemptBlockReason.AUTOMATIC_COOLDOWN,
                        remainingMs = remainingMs,
                    )
                }
            }
        }

        matches.maxByOrNull(State::retryNotBeforeMs)?.let { state ->
            if (state.retryNotBeforeMs > nowMs) {
                state.touch(nowMs, monotonicMs)
                return@synchronized state.blocked(
                    reason = AndroidReportAttemptBlockReason.RETRY_BACKOFF,
                    remainingMs = state.retryNotBeforeMs - nowMs,
                )
            }
        }

        val selected = matches.maxByOrNull(State::lastTouchedAtMs) ?: error("report state missing")
        selected.inFlight = true
        selected.inFlightScope = scope
        selected.touch(nowMs, monotonicMs)
        selected.lastAttemptCount += 1
        AndroidReportAttemptResult.Allowed(
            AndroidReportAttemptLease(
                stateId = selected.id,
                attemptedScope = scope,
                storageKey = selected.storageKey,
                attemptCount = selected.lastAttemptCount,
            ),
        )
    }

    fun markSucceeded(lease: AndroidReportAttemptLease, nowMs: Long) {
        synchronized(lock) {
            findState(lease)?.apply {
                scope = lease.attemptedScope
                val monotonicMs = monotonicNowOrNull()
                touch(nowMs, monotonicMs)
                lastUploadedAtMs = nowMs
                cooldownMonotonicMarkMs = monotonicMs
                cooldownRemainingAtMarkMs = AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS
                consecutiveFailures = 0
                retryNotBeforeMs = 0L
            }
        }
    }

    fun markFailed(
        lease: AndroidReportAttemptLease,
        nowMs: Long,
        retryDelayForFailureCount: (Int) -> Long,
    ): Long? = synchronized(lock) {
        findState(lease)?.let { state ->
            state.consecutiveFailures += 1
            retryDelayForFailureCount(state.consecutiveFailures).also { retryDelayMs ->
                state.touch(nowMs, monotonicNowOrNull())
                state.retryNotBeforeMs = nowMs + retryDelayMs
            }
        }
    }

    fun release(lease: AndroidReportAttemptLease) {
        synchronized(lock) {
            findState(lease)?.apply {
                inFlight = false
                inFlightScope = null
            }
        }
    }

    fun resetFailures() = synchronized(lock) {
        states.values.forEach { state ->
            state.consecutiveFailures = 0
            state.retryNotBeforeMs = 0L
        }
    }

    fun trackedStateCount(): Int = synchronized(lock) { states.size }

    /** Restores only successful cooldowns; in-flight and retry state is never trusted from disk. */
    fun restoreSuccessfulCooldowns(
        cooldowns: List<AndroidReportSuccessfulCooldown>,
        nowMs: Long,
    ) = synchronized(lock) {
        val monotonicMs = monotonicNowOrNull()
        pruneExpiredStates(nowMs, monotonicMs)
        val bootCount = currentBootCountOrNull()
        cooldowns
            .map { it to restoredCooldownRemainingMs(it, nowMs, monotonicMs, bootCount) }
            .filter { (_, remainingMs) -> remainingMs > 0L }
            .sortedByDescending { (_, remainingMs) -> remainingMs }
            .forEach { (cooldown, remainingMs) ->
                val uploadedAtMs = cooldown.lastUploadedAtMs.coerceAtMost(nowMs)
                val existing = states.values.firstOrNull { state ->
                    AndroidReportCooldownPolicy.isSameCooldownArea(state.scope, cooldown.scope)
                }
                // Restore the age of the successful state as well as its remaining cooldown.
                val touchedMonotonicMs = monotonicMs?.minus(
                    AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS - remainingMs,
                )
                if (existing != null) {
                    if (remainingMs > existing.automaticCooldownRemainingMs(nowMs, monotonicMs)) {
                        existing.scope = cooldown.scope
                        existing.lastUploadedAtMs = uploadedAtMs
                        existing.lastTouchedAtMs = uploadedAtMs
                        existing.lastTouchedMonotonicMs = touchedMonotonicMs
                        existing.cooldownMonotonicMarkMs = monotonicMs
                        existing.cooldownRemainingAtMarkMs = remainingMs
                    }
                } else if (states.size < maxTrackedStates) {
                    val storageKey = uniqueStorageKey(cooldown.scope.storageKey)
                    states[storageKey] = State(
                        id = nextStateId++,
                        storageKey = storageKey,
                        scope = cooldown.scope,
                        lastTouchedAtMs = uploadedAtMs,
                        lastTouchedMonotonicMs = touchedMonotonicMs,
                        lastUploadedAtMs = uploadedAtMs,
                        cooldownMonotonicMarkMs = monotonicMs,
                        cooldownRemainingAtMarkMs = remainingMs,
                    )
                }
            }
    }

    fun successfulCooldowns(nowMs: Long): List<AndroidReportSuccessfulCooldown> = synchronized(lock) {
        val monotonicMs = monotonicNowOrNull()
        pruneExpiredStates(nowMs, monotonicMs)
        val bootCount = currentBootCountOrNull()
        states.values
            .map { it to it.automaticCooldownRemainingMs(nowMs, monotonicMs) }
            .filter { (_, remainingMs) -> remainingMs > 0L }
            .sortedByDescending { (_, remainingMs) -> remainingMs }
            .take(maxTrackedStates)
            .map { (state, remainingMs) ->
                AndroidReportSuccessfulCooldown(
                    scope = state.scope,
                    lastUploadedAtMs = state.lastUploadedAtMs,
                    cooldownMonotonicMarkMs = monotonicMs,
                    cooldownRemainingAtMarkMs = remainingMs,
                    bootCount = bootCount,
                )
            }
    }

    private fun currentBootCountOrNull(): Int? = runCatching { bootCountProvider() }
        .getOrNull()
        ?.takeIf { it >= 0 }

    private fun restoredCooldownRemainingMs(
        cooldown: AndroidReportSuccessfulCooldown,
        nowMs: Long,
        monotonicMs: Long?,
        bootCount: Int?,
    ): Long {
        val markMs = cooldown.cooldownMonotonicMarkMs
        val remainingAtMarkMs = cooldown.cooldownRemainingAtMarkMs
        if (bootCount != null && bootCount == cooldown.bootCount &&
            markMs != null && markMs >= 0L && monotonicMs != null && monotonicMs >= markMs &&
            remainingAtMarkMs != null && remainingAtMarkMs in 0L..AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS
        ) {
            return (remainingAtMarkMs - (monotonicMs - markMs)).coerceAtLeast(0L)
        }
        // Legacy records, reboot, and unknown boot identity retain the bounded wall-clock fallback.
        return AndroidReportCooldownPolicy.automaticCooldownRemainingMs(
            cooldown.lastUploadedAtMs.coerceAtMost(nowMs),
            nowMs,
        )
    }

    private fun pruneExpiredStates(nowMs: Long, monotonicMs: Long?) {
        states.entries.removeIf { (_, state) ->
            if (state.inFlight || state.retryNotBeforeMs > nowMs ||
                state.automaticCooldownRemainingMs(nowMs, monotonicMs) > 0L
            ) return@removeIf false
            val touchedMs = state.lastTouchedMonotonicMs
            if (touchedMs != null && monotonicMs != null && monotonicMs >= touchedMs) {
                return@removeIf monotonicMs - touchedMs >= AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS
            }
            AndroidReportCooldownPolicy.shouldPruneState(
                lastTouchedAtMs = state.lastTouchedAtMs,
                lastUploadedAtMs = state.lastUploadedAtMs,
                retryNotBeforeMs = state.retryNotBeforeMs,
                inFlight = state.inFlight,
                nowMs = nowMs,
            )
        }
    }

    private fun monotonicNowOrNull(): Long? = runCatching { monotonicNowMs() }
        .getOrNull()
        ?.takeIf { it >= 0L }

    private fun State.automaticCooldownRemainingMs(nowMs: Long, monotonicMs: Long?): Long {
        val wallRemainingMs = AndroidReportCooldownPolicy.automaticCooldownRemainingMs(lastUploadedAtMs, nowMs)
        val markMs = cooldownMonotonicMarkMs ?: return wallRemainingMs
        if (monotonicMs == null || monotonicMs < markMs) return wallRemainingMs
        val elapsedMs = monotonicMs - markMs
        return (cooldownRemainingAtMarkMs - elapsedMs).coerceAtLeast(0L)
    }

    private fun State.touch(nowMs: Long, monotonicMs: Long?) {
        lastTouchedAtMs = nowMs
        lastTouchedMonotonicMs = monotonicMs
    }

    private fun findState(lease: AndroidReportAttemptLease): State? {
        return states[lease.storageKey]?.takeIf {
            it.id == lease.stateId &&
                it.lastAttemptCount == lease.attemptCount &&
                it.inFlight
        }
    }

    private fun uniqueStorageKey(baseKey: String): String {
        if (!states.containsKey(baseKey)) return baseKey
        return "$baseKey#$nextStateId"
    }

    private fun State.blocked(
        reason: AndroidReportAttemptBlockReason,
        remainingMs: Long = 0L,
    ): AndroidReportAttemptResult.Blocked {
        return AndroidReportAttemptResult.Blocked(
            reason = reason,
            storageKey = storageKey,
            remainingMs = remainingMs,
            attemptCount = lastAttemptCount,
        )
    }

    private data class State(
        val id: Long,
        val storageKey: String,
        var scope: AndroidReportSpatialScope,
        var lastTouchedAtMs: Long,
        var lastTouchedMonotonicMs: Long? = null,
        var lastUploadedAtMs: Long = 0L,
        var cooldownMonotonicMarkMs: Long? = null,
        var cooldownRemainingAtMarkMs: Long = 0L,
        var lastAttemptCount: Int = 0,
        var inFlight: Boolean = false,
        var inFlightScope: AndroidReportSpatialScope? = null,
        var consecutiveFailures: Int = 0,
        var retryNotBeforeMs: Long = 0L,
    )
}
