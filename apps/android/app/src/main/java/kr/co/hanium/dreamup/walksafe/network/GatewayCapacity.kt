package kr.co.hanium.dreamup.walksafe.network

import java.time.Instant
import org.json.JSONObject

enum class GatewayCapacityLevel {
    NORMAL,
    ADMIN_ONLY_WARNING,
    PAUSE_NEW_FIELD_TEST_PARTICIPANTS,
    HOLD_NEW_RAW_COLLECTION_SESSIONS,
    HOLD_NEW_TRAINING_AND_AUTO_REPORT_CANDIDATES,
}

enum class GatewayCapacityReason {
    STORAGE_UTILIZATION,
}

data class GatewayCapacitySnapshot(
    val version: Long,
    val observedAt: Instant,
    val expiresAt: Instant,
    val level: GatewayCapacityLevel,
    val reason: GatewayCapacityReason,
) {
    init {
        require(version in 1L..MAX_JS_SAFE_INTEGER)
        require(expiresAt.isAfter(observedAt))
    }
}

sealed class GatewayCapacityParseResult {
    data object Missing : GatewayCapacityParseResult()

    data object Malformed : GatewayCapacityParseResult()

    data class Valid(
        val snapshot: GatewayCapacitySnapshot,
    ) : GatewayCapacityParseResult()
}

object GatewayCapacityParser {
    fun fromSessionStatus(status: JSONObject): GatewayCapacityParseResult {
        if (!status.has(CAPACITY_FIELD)) return GatewayCapacityParseResult.Missing
        val capacity = status.opt(CAPACITY_FIELD) as? JSONObject
            ?: return GatewayCapacityParseResult.Malformed
        return parse(capacity)
    }

    fun parse(capacity: JSONObject): GatewayCapacityParseResult {
        if (capacity.jsonKeySet() != CAPACITY_KEYS) {
            return GatewayCapacityParseResult.Malformed
        }
        val version = capacity.strictPositiveJsSafeLongOrNull(VERSION_FIELD)
            ?: return GatewayCapacityParseResult.Malformed
        val observedAt = capacity.strictUtcInstantOrNull(OBSERVED_AT_FIELD)
            ?: return GatewayCapacityParseResult.Malformed
        val expiresAt = capacity.strictUtcInstantOrNull(EXPIRES_AT_FIELD)
            ?: return GatewayCapacityParseResult.Malformed
        if (!expiresAt.isAfter(observedAt)) return GatewayCapacityParseResult.Malformed
        val level = capacity.strictEnumOrNull<GatewayCapacityLevel>(LEVEL_FIELD)
            ?: return GatewayCapacityParseResult.Malformed
        val reason = capacity.strictEnumOrNull<GatewayCapacityReason>(REASON_FIELD)
            ?: return GatewayCapacityParseResult.Malformed
        return GatewayCapacityParseResult.Valid(
            GatewayCapacitySnapshot(
                version = version,
                observedAt = observedAt,
                expiresAt = expiresAt,
                level = level,
                reason = reason,
            ),
        )
    }
}

enum class GatewayCapacityAvailability {
    AVAILABLE,
    MISSING,
    MALFORMED,
    EXPIRED,
}

enum class GatewayCapacityUpdateDisposition {
    ACCEPTED,
    IDEMPOTENT,
    MISSING,
    MALFORMED,
    REJECTED_LOWER_VERSION,
    REJECTED_EQUAL_VERSION_CONFLICT,
    REJECTED_OBSERVED_AT_ROLLBACK,
    REJECTED_SESSION_GENERATION,
}

data class GatewayCapacityAdmission(
    val availability: GatewayCapacityAvailability,
    val snapshot: GatewayCapacitySnapshot?,
    val participantAdmissionRestrictedSignal: Boolean,
    val newRawCollectionSessionAllowed: Boolean,
    val learningCandidateAllowed: Boolean,
    val automaticReportCandidateAllowed: Boolean,
    val explicitSafetyReportAllowed: Boolean = true,
    val activeSafetyFeaturesAllowed: Boolean = true,
    val activeRawSessionStopAllowed: Boolean = true,
)

data class GatewayCapacitySignals(
    val adminWarningOneShot: Boolean = false,
    val participantAdmissionRestricted: Boolean = false,
)

data class GatewayCapacityUpdate(
    val disposition: GatewayCapacityUpdateDisposition,
    val admission: GatewayCapacityAdmission,
    val signals: GatewayCapacitySignals = GatewayCapacitySignals(),
)

/** Process-memory only. The ordering fence survives malformed or missing responses. */
object GatewayCapacityProcessState {
    private val lock = Any()
    private var refreshInFlight = false
    private var refreshPending = false
    private var sessionGeneration: Long? = null
    private var lastAcceptedSnapshot: GatewayCapacitySnapshot? = null
    private var visibleSnapshot: GatewayCapacitySnapshot? = null
    private var unavailableOverride: GatewayCapacityAvailability? =
        GatewayCapacityAvailability.MISSING
    private var lastAdminWarningVersion: Long? = null

    fun apply(
        parsed: GatewayCapacityParseResult,
        now: Instant = Instant.now(),
        expectedSessionGeneration: Long? = null,
    ): GatewayCapacityUpdate = synchronized(lock) {
        if (
            expectedSessionGeneration != null &&
            expectedSessionGeneration != sessionGeneration
        ) {
            return@synchronized updateLocked(
                GatewayCapacityUpdateDisposition.REJECTED_SESSION_GENERATION,
                now,
            )
        }
        when (parsed) {
            GatewayCapacityParseResult.Missing -> {
                visibleSnapshot = null
                unavailableOverride = GatewayCapacityAvailability.MISSING
                updateLocked(GatewayCapacityUpdateDisposition.MISSING, now)
            }
            GatewayCapacityParseResult.Malformed -> {
                visibleSnapshot = null
                unavailableOverride = GatewayCapacityAvailability.MALFORMED
                updateLocked(GatewayCapacityUpdateDisposition.MALFORMED, now)
            }
            is GatewayCapacityParseResult.Valid -> applySnapshotLocked(parsed.snapshot, now)
        }
    }

    fun admission(now: Instant = Instant.now()): GatewayCapacityAdmission = synchronized(lock) {
        admissionLocked(now)
    }

    fun fenceSessionGeneration(generation: Long) = synchronized(lock) {
        require(generation >= 0L)
        val current = sessionGeneration
        if (current == null || generation > current) {
            sessionGeneration = generation
            visibleSnapshot = null
            unavailableOverride = GatewayCapacityAvailability.MISSING
        }
    }

    fun tryBeginRefresh(): Boolean = synchronized(lock) {
        if (refreshInFlight) {
            refreshPending = true
            false
        } else {
            refreshInFlight = true
            true
        }
    }

    fun finishRefresh(): Boolean = synchronized(lock) {
        val pending = refreshPending
        refreshInFlight = false
        refreshPending = false
        pending
    }

    internal fun resetForTests() = synchronized(lock) {
        lastAcceptedSnapshot = null
        visibleSnapshot = null
        unavailableOverride = GatewayCapacityAvailability.MISSING
        lastAdminWarningVersion = null
        sessionGeneration = null
        refreshInFlight = false
        refreshPending = false
    }

    private fun applySnapshotLocked(
        candidate: GatewayCapacitySnapshot,
        now: Instant,
    ): GatewayCapacityUpdate {
        val current = lastAcceptedSnapshot
        if (current != null) {
            if (candidate.version < current.version) {
                return updateLocked(
                    GatewayCapacityUpdateDisposition.REJECTED_LOWER_VERSION,
                    now,
                )
            }
            if (candidate.version == current.version) {
                if (candidate != current) {
                    return updateLocked(
                        GatewayCapacityUpdateDisposition.REJECTED_EQUAL_VERSION_CONFLICT,
                        now,
                    )
                }
                visibleSnapshot = candidate
                unavailableOverride = null
                return updateLocked(GatewayCapacityUpdateDisposition.IDEMPOTENT, now)
            }
            if (candidate.observedAt.isBefore(current.observedAt)) {
                return updateLocked(
                    GatewayCapacityUpdateDisposition.REJECTED_OBSERVED_AT_ROLLBACK,
                    now,
                )
            }
        }
        lastAcceptedSnapshot = candidate
        visibleSnapshot = candidate
        unavailableOverride = null
        val admission = admissionLocked(now)
        val isCurrent = admission.availability == GatewayCapacityAvailability.AVAILABLE
        val warning = isCurrent && candidate.level >= GatewayCapacityLevel.ADMIN_ONLY_WARNING
        val warningOneShot = warning && lastAdminWarningVersion != candidate.version
        if (warningOneShot) lastAdminWarningVersion = candidate.version
        return GatewayCapacityUpdate(
            disposition = GatewayCapacityUpdateDisposition.ACCEPTED,
            admission = admission,
            signals = GatewayCapacitySignals(
                adminWarningOneShot = warningOneShot,
                participantAdmissionRestricted =
                    isCurrent &&
                        candidate.level >=
                        GatewayCapacityLevel.PAUSE_NEW_FIELD_TEST_PARTICIPANTS,
            ),
        )
    }

    private fun updateLocked(
        disposition: GatewayCapacityUpdateDisposition,
        now: Instant,
    ): GatewayCapacityUpdate = GatewayCapacityUpdate(
        disposition = disposition,
        admission = admissionLocked(now),
    )

    private fun admissionLocked(now: Instant): GatewayCapacityAdmission {
        val override = unavailableOverride
        val snapshot = visibleSnapshot
        val availability = when {
            override != null -> override
            snapshot == null -> GatewayCapacityAvailability.MISSING
            !now.isBefore(snapshot.expiresAt) -> GatewayCapacityAvailability.EXPIRED
            else -> GatewayCapacityAvailability.AVAILABLE
        }
        if (availability != GatewayCapacityAvailability.AVAILABLE || snapshot == null) {
            return GatewayCapacityAdmission(
                availability = availability,
                snapshot = snapshot,
                participantAdmissionRestrictedSignal = false,
                newRawCollectionSessionAllowed = false,
                learningCandidateAllowed = true,
                automaticReportCandidateAllowed = false,
            )
        }
        return GatewayCapacityAdmission(
            availability = availability,
            snapshot = snapshot,
            participantAdmissionRestrictedSignal =
                snapshot.level >=
                    GatewayCapacityLevel.PAUSE_NEW_FIELD_TEST_PARTICIPANTS,
            newRawCollectionSessionAllowed =
                snapshot.level < GatewayCapacityLevel.HOLD_NEW_RAW_COLLECTION_SESSIONS,
            learningCandidateAllowed =
                snapshot.level <
                    GatewayCapacityLevel.HOLD_NEW_TRAINING_AND_AUTO_REPORT_CANDIDATES,
            automaticReportCandidateAllowed =
                snapshot.level <
                    GatewayCapacityLevel.HOLD_NEW_TRAINING_AND_AUTO_REPORT_CANDIDATES,
        )
    }
}

private fun JSONObject.jsonKeySet(): Set<String> {
    val result = mutableSetOf<String>()
    val iterator = keys()
    while (iterator.hasNext()) result += iterator.next()
    return result
}

private fun JSONObject.strictPositiveJsSafeLongOrNull(name: String): Long? {
    val value = when (val raw = opt(name)) {
        is Byte -> raw.toLong()
        is Short -> raw.toLong()
        is Int -> raw.toLong()
        is Long -> raw
        else -> return null
    }
    return value.takeIf { it in 1L..MAX_JS_SAFE_INTEGER }
}

private fun JSONObject.strictUtcInstantOrNull(name: String): Instant? {
    val value = opt(name) as? String ?: return null
    if (!STRICT_UTC_RFC3339.matches(value)) return null
    return runCatching { Instant.parse(value) }.getOrNull()
}

private inline fun <reified T : Enum<T>> JSONObject.strictEnumOrNull(name: String): T? {
    val value = opt(name) as? String ?: return null
    return enumValues<T>().firstOrNull { it.name == value }
}

private const val CAPACITY_FIELD = "capacity"
private const val VERSION_FIELD = "version"
private const val OBSERVED_AT_FIELD = "observed_at"
private const val EXPIRES_AT_FIELD = "expires_at"
private const val LEVEL_FIELD = "level"
private const val REASON_FIELD = "reason"
private const val MAX_JS_SAFE_INTEGER = 9_007_199_254_740_991L
private val CAPACITY_KEYS = setOf(
    VERSION_FIELD,
    OBSERVED_AT_FIELD,
    EXPIRES_AT_FIELD,
    LEVEL_FIELD,
    REASON_FIELD,
)
private val STRICT_UTC_RFC3339 = Regex(
    """\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:[0-5]\d(?:\.\d{1,6})?Z""",
)
