package kr.co.hanium.dreamup.walksafe.network

import java.util.UUID
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.json.JSONObject

enum class GatewayWalkTakeoverConfirmation {
    YES,
    NO,
    NO_RESPONSE,
    UNRECOGNIZED,
    ;

    companion object {
        fun fromRecognizedText(text: String?): GatewayWalkTakeoverConfirmation =
            when (text?.trim().orEmpty()) {
                "" -> NO_RESPONSE
                "예", "네" -> YES
                "아니요" -> NO
                else -> UNRECOGNIZED
            }
    }
}

data class GatewayWalkLease internal constructor(
    val result: String,
    val actorId: String,
    val deviceId: String,
    val walkId: String,
    val leaseId: String,
    val fencingToken: Long,
    val acquiredAtEpochMs: Long,
    val leaseExpiresAtEpochMs: Long,
    val serverTimeEpochMs: Long,
    internal val localDeadlineElapsedMs: Long,
) {
    internal fun isLocallyUsable(nowElapsedMs: Long): Boolean =
        nowElapsedMs < localDeadlineElapsedMs

    override fun toString(): String =
        "GatewayWalkLease(result=$result, actorId=$actorId, deviceId=$deviceId, " +
            "walkId=$walkId, lease=redacted, fencingToken=redacted, " +
            "leaseExpiresAtEpochMs=$leaseExpiresAtEpochMs)"
}

data class GatewayWalkConflict internal constructor(
    val activeWalkId: String,
    val activeDeviceId: String,
    val fencingToken: Long,
    val leaseExpiresAtEpochMs: Long,
    val serverTimeEpochMs: Long,
    internal val localDeadlineElapsedMs: Long,
) {
    internal fun isLocallyCurrent(nowElapsedMs: Long): Boolean =
        nowElapsedMs < localDeadlineElapsedMs

    override fun toString(): String =
        "GatewayWalkConflict(activeWalkId=$activeWalkId, activeDeviceId=$activeDeviceId, " +
            "fencingToken=redacted, leaseExpiresAtEpochMs=$leaseExpiresAtEpochMs)"
}

data class GatewayWalkEnded internal constructor(
    val walkId: String,
    val leaseId: String,
    val fencingToken: Long,
    val endedAtEpochMs: Long,
    val serverTimeEpochMs: Long,
) {
    override fun toString(): String =
        "GatewayWalkEnded(walkId=$walkId, lease=redacted, fencingToken=redacted, " +
            "endedAtEpochMs=$endedAtEpochMs)"
}

sealed interface GatewayWalkStartResult {
    data class Granted(val lease: GatewayWalkLease) : GatewayWalkStartResult

    data class Conflict(val conflict: GatewayWalkConflict) : GatewayWalkStartResult
}

class GatewayWalkHttpException(
    val statusCode: Int,
    val reason: String,
    val serverCode: String? = null,
    /**
     * A lease the gateway granted to this start operation for its own walk after the operation
     * became stale. It is never activated; the caller may only release it with the same session.
     */
    val abandonedLease: GatewayWalkLease? = null,
) : IllegalStateException(
    "gateway walk request failed: $reason status=$statusCode code=${serverCode ?: "none"}",
)

/**
 * Deep-sleep-inclusive elapsed time on a device; JVM unit tests (no Android runtime) fall back
 * to the monotonic nanoTime clock.
 */
internal val deviceElapsedRealtimeMs: () -> Long =
    runCatching {
        android.os.SystemClock.elapsedRealtime()
        val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
        clock
    }.getOrElse { { System.nanoTime() / 1_000_000L } }

class GatewayWalkSessionClient(
    private val transport: GatewaySessionTransport = HttpUrlConnectionGatewaySessionTransport(),
    private val wallClockEpochMs: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMs: () -> Long = deviceElapsedRealtimeMs,
    private val retryRequestIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    // S19-17: v2 commands carry issued_at_epoch_ms fixed at the first send of a request id, so
    // every retry of that id is byte-identical and the gateway can bound idempotency by time.
    private val issuedAtLock = Any()
    private var lastIssuedAtEpochMs = 0L
    private val issuedAtByRequestId = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_REMEMBERED_REQUEST_IDS
    }

    // S19-17-F1: issued_at follows the Gateway clock, not the device wall clock. The last
    // server_time_epoch_ms is kept with the elapsedRealtime at which it arrived; the device
    // wall clock is used only before the first Gateway response of this client.
    private var serverTimeAnchorEpochMs: Long? = null
    private var serverTimeAnchorElapsedMs = 0L

    private fun gatewayNowEpochMs(): Long {
        val anchor = serverTimeAnchorEpochMs ?: return wallClockEpochMs()
        val elapsed = (elapsedRealtimeMs() - serverTimeAnchorElapsedMs).coerceAtLeast(0L)
        return anchor + elapsed
    }

    private fun observeServerTime(response: GatewayHttpResponse) {
        val serverTime = runCatching {
            (JSONObject(response.responseBody).opt("server_time_epoch_ms") as? Number)
                ?.takeIf { it is Long || it is Int }
                ?.toLong()
        }.getOrNull() ?: return
        if (serverTime <= 0L) return
        synchronized(issuedAtLock) {
            serverTimeAnchorEpochMs = serverTime
            serverTimeAnchorElapsedMs = elapsedRealtimeMs()
        }
    }

    private fun issuedAtFor(requestId: String): Long = synchronized(issuedAtLock) {
        issuedAtByRequestId[requestId]?.let { return@synchronized it }
        val now = maxOf(gatewayNowEpochMs(), 1L)
        // S19-17-F2: strictly increasing only within a bounded carry. A clock that is now more
        // than the bound behind the last value (a forward error that was corrected, or a new
        // Gateway anchor) releases the carry instead of pinning every later request to it.
        val issuedAt = if (lastIssuedAtEpochMs - now <= MAX_ISSUED_AT_CARRY_MS) {
            maxOf(now, lastIssuedAtEpochMs + 1L)
        } else {
            now
        }
        lastIssuedAtEpochMs = issuedAt
        issuedAtByRequestId[requestId] = issuedAt
        issuedAt
    }

    private fun isRememberedRequestId(requestId: String): Boolean = synchronized(issuedAtLock) {
        issuedAtByRequestId.containsKey(requestId)
    }

    /**
     * Sends one command. S19-17-F1: a request id this client never sent before that the
     * Gateway refuses as `idempotency_expired` was never executed; it is retried once with a
     * new request id stamped by the server time that came with the refusal. A second refusal
     * means the device clock cannot be reconciled and is reported as
     * [DEVICE_CLOCK_CHECK_REQUIRED_REASON].
     */
    private fun sendCommand(
        session: GatewayFieldSession,
        requestId: String,
        command: (requestId: String, issuedAtEpochMs: Long) -> JSONObject,
    ): GatewayHttpResponse {
        val fresh = !isRememberedRequestId(requestId)
        val response = post(session, command(requestId, issuedAtFor(requestId)))
        observeServerTime(response)
        if (!fresh || !response.isIdempotencyExpired()) return response
        val retryId = retryRequestIdFactory()
        require(ID_PATTERN.matches(retryId)) { "retry requestId is invalid" }
        val retried = post(session, command(retryId, issuedAtFor(retryId)))
        observeServerTime(retried)
        if (retried.isIdempotencyExpired()) {
            throw GatewayWalkHttpException(
                statusCode = retried.statusCode,
                reason = DEVICE_CLOCK_CHECK_REQUIRED_REASON,
                serverCode = "idempotency_expired",
            )
        }
        return retried
    }

    private fun GatewayHttpResponse.isIdempotencyExpired(): Boolean =
        statusCode == 409 && runCatching {
            JSONObject(responseBody).optString("code") == "idempotency_expired"
        }.getOrDefault(false)

    fun startWithOwnedWalkRecovery(
        session: GatewayFieldSession,
        walkId: String,
        requestId: String,
        nowElapsedMs: () -> Long,
        isCurrent: () -> Boolean,
    ): GatewayWalkStartResult {
        fun requireCurrent() {
            if (!isCurrent() || !session.isUsableFor(session.actorId)) {
                throw GatewayWalkHttpException(0, "walk_start_operation_stale")
            }
        }

        fun guardedStart(targetWalkId: String, commandRequestId: String): GatewayWalkStartResult {
            requireCurrent()
            val result = start(session, targetWalkId, commandRequestId, nowElapsedMs())
            if (!isCurrent() || !session.isUsableFor(session.actorId)) {
                // No further request is sent here; a lease granted for this operation's own walk is
                // handed back so the caller can end exactly that lease instead of waiting for expiry.
                throw GatewayWalkHttpException(
                    statusCode = 0,
                    reason = "walk_start_operation_stale",
                    abandonedLease = (result as? GatewayWalkStartResult.Granted)
                        ?.lease
                        ?.takeIf { targetWalkId == walkId && it.walkId == walkId },
                )
            }
            return result
        }

        val initial = guardedStart(walkId, requestId)
        if (initial !is GatewayWalkStartResult.Conflict) return initial
        val conflict = initial.conflict
        if (conflict.activeDeviceId != session.deviceId || conflict.activeWalkId == walkId) {
            return initial
        }

        // The device ID alone is not proof of ownership. Starting the old walk also
        // checks the actor and login family on the gateway before returning its lease.
        val previous = guardedStart(conflict.activeWalkId, UUID.randomUUID().toString())
        if (previous !is GatewayWalkStartResult.Granted) return previous
        val oldLease = previous.lease
        val originalLease = oldLease.result == "ALREADY_ACTIVE" &&
            oldLease.fencingToken == conflict.fencingToken
        // Expiry between requests can acquire an old-ID lease for this operation.
        // End that exact newly owned lease as well; never activate it in the runtime.
        val acquiredAfterExpiry = oldLease.result == "ACQUIRED" &&
            oldLease.fencingToken > conflict.fencingToken
        if (!originalLease && !acquiredAfterExpiry) {
            return GatewayWalkStartResult.Conflict(
                GatewayWalkConflict(
                    activeWalkId = oldLease.walkId,
                    activeDeviceId = oldLease.deviceId,
                    fencingToken = oldLease.fencingToken,
                    leaseExpiresAtEpochMs = oldLease.leaseExpiresAtEpochMs,
                    serverTimeEpochMs = oldLease.serverTimeEpochMs,
                    localDeadlineElapsedMs = oldLease.localDeadlineElapsedMs,
                ),
            )
        }

        requireCurrent()
        try {
            end(session, oldLease, UUID.randomUUID().toString())
        } catch (error: GatewayWalkHttpException) {
            requireCurrent()
            // A takeover or expiry may race this CAS. A normal start can observe
            // the new holder or acquire a vacant lease without ending anyone else.
            if (error.statusCode != 409 || error.serverCode != "walk_lease_conflict") throw error
        }
        requireCurrent()
        return guardedStart(walkId, UUID.randomUUID().toString())
    }

    fun start(
        session: GatewayFieldSession,
        walkId: String,
        requestId: String,
        localNowElapsedMs: Long,
    ): GatewayWalkStartResult {
        requireCommandBinding(session, walkId, requestId)
        val response = sendCommand(session, requestId) { id, issuedAt ->
            JSONObject()
                .put("schema_version", COMMAND_SCHEMA_VERSION)
                .put("request_id", id)
                .put("action", "start")
                .put("walk_id", walkId)
                .put("issued_at_epoch_ms", issuedAt)
        }
        return when (response.statusCode) {
            200, 201 -> GatewayWalkStartResult.Granted(
                GatewayWalkResponseParser.lease(
                    response.responseBody,
                    session,
                    walkId,
                    setOf("ACQUIRED", "ALREADY_ACTIVE"),
                    localNowElapsedMs,
                ),
            )
            409 -> {
                val code = runCatching {
                    JSONObject(response.responseBody).optString("code")
                }.getOrNull()
                if (code != "walk_lease_conflict") {
                    throw response.toWalkException("walk_start_conflict_rejected")
                }
                GatewayWalkStartResult.Conflict(
                    GatewayWalkResponseParser.conflict(
                        response.responseBody,
                        localNowElapsedMs,
                    ),
                )
            }
            else -> throw response.toWalkException("walk_start_failed")
        }
    }

    fun takeover(
        session: GatewayFieldSession,
        walkId: String,
        requestId: String,
        conflict: GatewayWalkConflict,
        localNowElapsedMs: Long,
    ): GatewayWalkLease {
        requireCommandBinding(session, walkId, requestId)
        require(conflict.isLocallyCurrent(localNowElapsedMs)) {
            "walk conflict is no longer current"
        }
        val response = sendCommand(session, requestId) { id, issuedAt ->
            JSONObject()
                .put("schema_version", COMMAND_SCHEMA_VERSION)
                .put("request_id", id)
                .put("action", "takeover")
                .put("walk_id", walkId)
                .put("expected_active_walk_id", conflict.activeWalkId)
                .put("expected_fencing_token", conflict.fencingToken)
                .put("confirmation", "voice_confirmed")
                .put("issued_at_epoch_ms", issuedAt)
        }
        if (response.statusCode !in setOf(200, 201)) {
            throw response.toWalkException("walk_takeover_failed")
        }
        return GatewayWalkResponseParser.lease(
            response.responseBody,
            session,
            walkId,
            setOf("TAKEN_OVER"),
            localNowElapsedMs,
        )
    }

    fun renew(
        session: GatewayFieldSession,
        lease: GatewayWalkLease,
        requestId: String,
        localNowElapsedMs: Long,
    ): GatewayWalkLease {
        requireCommandBinding(session, lease.walkId, requestId)
        require(lease.actorId == session.actorId && lease.deviceId == session.deviceId) {
            "walk lease is not bound to this gateway session"
        }
        val response = sendCommand(session, requestId) { id, issuedAt ->
            JSONObject()
                .put("schema_version", COMMAND_SCHEMA_VERSION)
                .put("request_id", id)
                .put("action", "renew")
                .put("walk_id", lease.walkId)
                .put("lease_id", lease.leaseId)
                .put("fencing_token", lease.fencingToken)
                .put("issued_at_epoch_ms", issuedAt)
        }
        if (response.statusCode != 200) {
            throw response.toWalkException("walk_renew_failed")
        }
        return GatewayWalkResponseParser.lease(
            response.responseBody,
            session,
            lease.walkId,
            setOf("RENEWED"),
            localNowElapsedMs,
        )
    }

    fun end(
        session: GatewayFieldSession,
        lease: GatewayWalkLease,
        requestId: String,
    ): GatewayWalkEnded {
        requireCommandBinding(session, lease.walkId, requestId)
        require(lease.actorId == session.actorId && lease.deviceId == session.deviceId) {
            "walk lease is not bound to this gateway session"
        }
        val response = sendCommand(session, requestId) { id, issuedAt ->
            JSONObject()
                .put("schema_version", COMMAND_SCHEMA_VERSION)
                .put("request_id", id)
                .put("action", "end")
                .put("walk_id", lease.walkId)
                .put("lease_id", lease.leaseId)
                .put("fencing_token", lease.fencingToken)
                .put("issued_at_epoch_ms", issuedAt)
        }
        if (response.statusCode != 200) {
            throw response.toWalkException("walk_end_failed")
        }
        return GatewayWalkResponseParser.ended(response.responseBody, lease)
    }

    private fun post(
        session: GatewayFieldSession,
        body: JSONObject,
    ): GatewayHttpResponse {
        if (session.versionOrNull == null) {
            throw GatewayWalkHttpException(
                statusCode = 0,
                reason = "long_lived_session_required",
            )
        }
        val headers = runCatching { session.requestHeaders() }.getOrElse {
            throw GatewayWalkHttpException(
                statusCode = 0,
                reason = "gateway_session_unavailable",
            )
        }
        return transport.postJson(
            session.gatewayBaseUrl.trimEnd('/') + WALK_ENDPOINT,
            headers,
            body.toString(),
        )
    }

    private fun requireCommandBinding(
        session: GatewayFieldSession,
        walkId: String,
        requestId: String,
    ) {
        require(session.versionOrNull != null) { "a long-lived gateway session is required" }
        require(ID_PATTERN.matches(walkId)) { "walkId is invalid" }
        require(ID_PATTERN.matches(requestId)) { "requestId is invalid" }
    }

    private fun GatewayHttpResponse.toWalkException(reason: String): GatewayWalkHttpException {
        val code = runCatching {
            JSONObject(responseBody)
                .optString("code")
                .takeIf(WALK_ERROR_CODES::contains)
        }.getOrNull()
        return GatewayWalkHttpException(statusCode, reason, code)
    }

    companion object {
        /** S19-17-F1: the Gateway refused a fresh command twice as outside its time window. */
        const val DEVICE_CLOCK_CHECK_REQUIRED_REASON = "walk_device_clock_check_required"
        private const val WALK_ENDPOINT = "/api/field-walk"
        private const val COMMAND_SCHEMA_VERSION = "walksafe.field-walk-command.v2"
        private const val MAX_REMEMBERED_REQUEST_IDS = 256
        /** S19-17-F2: bound of the strictly-increasing carry of issued_at. */
        internal const val MAX_ISSUED_AT_CARRY_MS = 120_000L
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")
        private val WALK_ERROR_CODES = setOf(
            "gateway_auth_required",
            "long_lived_session_required",
            "walk_command_invalid",
            "walk_lease_conflict",
            "request_id_conflict",
            "walk_lease_expired",
            "walk_lease_stale",
            "walk_ledger_busy",
            "walk_ledger_unavailable",
            // S19-17: an uncached request outside the idempotency guarantee; never executed.
            "idempotency_expired",
            // S19-20: gateway detected a wall-clock step; start/takeover fail closed briefly.
            "field_walk_clock_unavailable",
        )
    }
}

internal object GatewayWalkResponseParser {
    private const val RESPONSE_SCHEMA_VERSION = "walksafe.field-walk-response.v1"
    private const val MAX_SERVER_LEASE_REMAINING_MS = 90_000L
    private const val LOCAL_EXPIRY_SAFETY_MARGIN_MS = 5_000L
    private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

    fun lease(
        body: String,
        session: GatewayFieldSession,
        expectedWalkId: String,
        expectedResults: Set<String>,
        localNowElapsedMs: Long,
    ): GatewayWalkLease {
        val json = strictObject(
            body,
            setOf(
                "schema_version",
                "result",
                "walk_id",
                "lease_id",
                "fencing_token",
                "acquired_at_epoch_ms",
                "lease_expires_at_epoch_ms",
                "server_time_epoch_ms",
            ),
        )
        require(strictString(json, "schema_version") == RESPONSE_SCHEMA_VERSION)
        val result = strictString(json, "result")
        require(result in expectedResults) { "unexpected walk lease result" }
        val walkId = strictId(json, "walk_id")
        require(walkId == expectedWalkId) { "walk lease returned a different walk" }
        val leaseId = strictId(json, "lease_id")
        val fencingToken = strictLong(json, "fencing_token", minimum = 1L)
        val acquiredAt = strictLong(json, "acquired_at_epoch_ms")
        val expiresAt = strictLong(json, "lease_expires_at_epoch_ms")
        val serverTime = strictLong(json, "server_time_epoch_ms")
        require(acquiredAt <= serverTime) { "walk lease acquisition is in the future" }
        val localDeadline = conservativeLocalDeadline(
            expiresAt = expiresAt,
            serverTime = serverTime,
            localNowElapsedMs = localNowElapsedMs,
        )
        return GatewayWalkLease(
            result = result,
            actorId = session.actorId,
            deviceId = session.deviceId,
            walkId = walkId,
            leaseId = leaseId,
            fencingToken = fencingToken,
            acquiredAtEpochMs = acquiredAt,
            leaseExpiresAtEpochMs = expiresAt,
            serverTimeEpochMs = serverTime,
            localDeadlineElapsedMs = localDeadline,
        )
    }

    fun conflict(
        body: String,
        localNowElapsedMs: Long,
    ): GatewayWalkConflict {
        val json = strictObject(
            body,
            setOf(
                "schema_version",
                "code",
                "active_walk_id",
                "active_device_id",
                "fencing_token",
                "lease_expires_at_epoch_ms",
                "server_time_epoch_ms",
            ),
        )
        require(strictString(json, "schema_version") == RESPONSE_SCHEMA_VERSION)
        require(strictString(json, "code") == "walk_lease_conflict")
        val expiresAt = strictLong(json, "lease_expires_at_epoch_ms")
        val serverTime = strictLong(json, "server_time_epoch_ms")
        return GatewayWalkConflict(
            activeWalkId = strictId(json, "active_walk_id"),
            activeDeviceId = strictId(json, "active_device_id"),
            fencingToken = strictLong(json, "fencing_token", minimum = 1L),
            leaseExpiresAtEpochMs = expiresAt,
            serverTimeEpochMs = serverTime,
            localDeadlineElapsedMs = conservativeLocalDeadline(
                expiresAt = expiresAt,
                serverTime = serverTime,
                localNowElapsedMs = localNowElapsedMs,
            ),
        )
    }

    fun ended(body: String, expectedLease: GatewayWalkLease): GatewayWalkEnded {
        val json = strictObject(
            body,
            setOf(
                "schema_version",
                "result",
                "walk_id",
                "lease_id",
                "fencing_token",
                "ended_at_epoch_ms",
                "server_time_epoch_ms",
            ),
        )
        require(strictString(json, "schema_version") == RESPONSE_SCHEMA_VERSION)
        require(strictString(json, "result") == "ENDED")
        val walkId = strictId(json, "walk_id")
        val leaseId = strictId(json, "lease_id")
        val fencingToken = strictLong(json, "fencing_token", minimum = 1L)
        require(
            walkId == expectedLease.walkId &&
                leaseId == expectedLease.leaseId &&
                fencingToken == expectedLease.fencingToken
        ) {
            "walk end response is not bound to the ended lease"
        }
        val endedAt = strictLong(json, "ended_at_epoch_ms")
        val serverTime = strictLong(json, "server_time_epoch_ms")
        require(endedAt <= serverTime) { "walk end time is in the future" }
        return GatewayWalkEnded(
            walkId = walkId,
            leaseId = leaseId,
            fencingToken = fencingToken,
            endedAtEpochMs = endedAt,
            serverTimeEpochMs = serverTime,
        )
    }

    private fun strictObject(body: String, expectedKeys: Set<String>): JSONObject {
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw IllegalArgumentException("gateway walk response is not JSON")
        }
        val keys = buildSet {
            val iterator = json.keys()
            while (iterator.hasNext()) add(iterator.next())
        }
        require(keys == expectedKeys) { "gateway walk response schema differs" }
        return json
    }

    private fun strictString(json: JSONObject, key: String): String {
        val value = json.get(key)
        require(value is String && value.isNotBlank()) { "$key must be a non-empty string" }
        return value
    }

    private fun strictId(json: JSONObject, key: String): String =
        strictString(json, key).also {
            require(ID_PATTERN.matches(it)) { "$key is invalid" }
        }

    private fun strictLong(
        json: JSONObject,
        key: String,
        minimum: Long = 0L,
    ): Long {
        val value = json.get(key)
        require(value is Number) { "$key must be an integer" }
        val asDouble = value.toDouble()
        val asLong = value.toLong()
        require(asDouble.isFinite() && asDouble == asLong.toDouble() && asLong >= minimum) {
            "$key must be an integer"
        }
        return asLong
    }

    private fun conservativeLocalDeadline(
        expiresAt: Long,
        serverTime: Long,
        localNowElapsedMs: Long,
    ): Long {
        require(localNowElapsedMs >= 0L)
        val remaining = expiresAt - serverTime
        require(remaining in 1L..MAX_SERVER_LEASE_REMAINING_MS) {
            "gateway walk lease lifetime is invalid"
        }
        val conservativeRemaining =
            (remaining - LOCAL_EXPIRY_SAFETY_MARGIN_MS).coerceAtLeast(0L)
        return Math.addExact(localNowElapsedMs, conservativeRemaining)
    }
}

enum class GatewayWalkAuthorityAction {
    START,
    TAKEOVER,
    RENEW,
    END,
}

data class GatewayWalkAuthorityOperation internal constructor(
    val operationId: String,
    val requestId: String,
    val epoch: WalkRuntimeEpoch,
    val action: GatewayWalkAuthorityAction,
    val sourceLease: GatewayWalkLease? = null,
    val sourceConflict: GatewayWalkConflict? = null,
) {
    override fun toString(): String =
        "GatewayWalkAuthorityOperation(action=$action, epoch=$epoch, ids=redacted)"
}

enum class GatewayWalkAuthorityCompletion {
    LEASE_ACTIVE,
    CONFLICT,
    STALE,
    INVALID,
}

class GatewayWalkAuthorityController(
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val elapsedClock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val lock = Any()
    private var held: HeldLease? = null
    private var pending: GatewayWalkAuthorityOperation? = null
    private var conflict: HeldConflict? = null

    fun beginStart(epoch: WalkRuntimeEpoch): GatewayWalkAuthorityOperation? =
        synchronized(lock) {
            dropExpiredLocked()
            if (held != null || pending != null) return@synchronized null
            newOperation(epoch, GatewayWalkAuthorityAction.START).also {
                pending = it
                conflict = null
            }
        }

    fun completeStart(
        operation: GatewayWalkAuthorityOperation,
        result: GatewayWalkStartResult,
    ): GatewayWalkAuthorityCompletion = synchronized(lock) {
        if (pending != operation || operation.action != GatewayWalkAuthorityAction.START) {
            return@synchronized GatewayWalkAuthorityCompletion.STALE
        }
        pending = null
        when (result) {
            is GatewayWalkStartResult.Granted -> {
                if (
                    !result.lease.isLocallyUsable(elapsedClock()) ||
                    result.lease.walkId != operation.epoch.walkSessionId
                ) {
                    GatewayWalkAuthorityCompletion.INVALID
                } else {
                    held = HeldLease(operation.epoch, result.lease)
                    conflict = null
                    GatewayWalkAuthorityCompletion.LEASE_ACTIVE
                }
            }
            is GatewayWalkStartResult.Conflict -> {
                if (!result.conflict.isLocallyCurrent(elapsedClock())) {
                    GatewayWalkAuthorityCompletion.INVALID
                } else {
                    held = null
                    conflict = HeldConflict(operation.epoch, result.conflict)
                    GatewayWalkAuthorityCompletion.CONFLICT
                }
            }
        }
    }

    fun beginTakeover(epoch: WalkRuntimeEpoch): GatewayWalkAuthorityOperation? =
        synchronized(lock) {
            dropExpiredLocked()
            val currentConflict = conflict
                ?.takeIf { it.epoch == epoch }
                ?: return@synchronized null
            if (pending != null) return@synchronized null
            newOperation(
                epoch = epoch,
                action = GatewayWalkAuthorityAction.TAKEOVER,
                sourceConflict = currentConflict.conflict,
            ).also { pending = it }
        }

    fun completeTakeover(
        operation: GatewayWalkAuthorityOperation,
        lease: GatewayWalkLease,
    ): GatewayWalkAuthorityCompletion = synchronized(lock) {
        if (
            pending != operation ||
            operation.action != GatewayWalkAuthorityAction.TAKEOVER ||
            operation.sourceConflict == null
        ) {
            return@synchronized GatewayWalkAuthorityCompletion.STALE
        }
        pending = null
        if (
            !lease.isLocallyUsable(elapsedClock()) ||
            lease.walkId != operation.epoch.walkSessionId ||
            lease.fencingToken <= operation.sourceConflict.fencingToken
        ) {
            conflict = null
            return@synchronized GatewayWalkAuthorityCompletion.INVALID
        }
        held = HeldLease(operation.epoch, lease)
        conflict = null
        GatewayWalkAuthorityCompletion.LEASE_ACTIVE
    }

    fun beginRenew(epoch: WalkRuntimeEpoch): GatewayWalkAuthorityOperation? =
        synchronized(lock) {
            dropExpiredLocked()
            val current = held?.takeIf { it.epoch == epoch } ?: return@synchronized null
            if (pending != null) return@synchronized null
            newOperation(
                epoch = epoch,
                action = GatewayWalkAuthorityAction.RENEW,
                sourceLease = current.lease,
            ).also { pending = it }
        }

    fun completeRenew(
        operation: GatewayWalkAuthorityOperation,
        lease: GatewayWalkLease,
    ): GatewayWalkAuthorityCompletion = synchronized(lock) {
        if (
            pending != operation ||
            operation.action != GatewayWalkAuthorityAction.RENEW ||
            operation.sourceLease == null
        ) {
            return@synchronized GatewayWalkAuthorityCompletion.STALE
        }
        pending = null
        val source = operation.sourceLease
        if (
            !source.isLocallyUsable(elapsedClock()) ||
            lease.walkId != source.walkId ||
            lease.leaseId != source.leaseId ||
            lease.fencingToken != source.fencingToken ||
            lease.actorId != source.actorId ||
            lease.deviceId != source.deviceId ||
            lease.localDeadlineElapsedMs <= source.localDeadlineElapsedMs
        ) {
            held = null
            return@synchronized GatewayWalkAuthorityCompletion.INVALID
        }
        held = HeldLease(operation.epoch, lease)
        GatewayWalkAuthorityCompletion.LEASE_ACTIVE
    }

    fun beginEnd(epoch: WalkRuntimeEpoch): GatewayWalkAuthorityOperation? =
        synchronized(lock) {
            val current = held?.takeIf {
                it.epoch.walkSessionId == epoch.walkSessionId
            } ?: return@synchronized null
            held = null
            conflict = null
            pending = null
            newOperation(
                epoch = current.epoch,
                action = GatewayWalkAuthorityAction.END,
                sourceLease = current.lease,
            )
        }

    fun rebindEpoch(
        previous: WalkRuntimeEpoch,
        current: WalkRuntimeEpoch,
    ): Boolean = synchronized(lock) {
        val source = held?.takeIf { it.epoch == previous } ?: return@synchronized false
        if (previous.walkSessionId != current.walkSessionId) return@synchronized false
        held = HeldLease(current, source.lease)
        pending = null
        conflict = null
        true
    }

    fun nextRenewDelayMs(
        epoch: WalkRuntimeEpoch,
        maxIntervalMs: Long,
    ): Long? = synchronized(lock) {
        require(maxIntervalMs > 0L) { "maxIntervalMs must be positive" }
        val current = held?.takeIf { it.epoch == epoch } ?: return@synchronized null
        val nowElapsedMs = elapsedClock()
        if (!current.lease.isLocallyUsable(nowElapsedMs)) return@synchronized null
        val safeRemainingMs = current.lease.localDeadlineElapsedMs - nowElapsedMs
        minOf(maxIntervalMs, safeRemainingMs / 2L)
    }

    /** Conservative local time left on the lease held for [epoch], or null when none is usable. */
    fun safeRemainingMsOrNull(epoch: WalkRuntimeEpoch): Long? = synchronized(lock) {
        val current = held?.takeIf { it.epoch == epoch } ?: return@synchronized null
        val nowElapsedMs = elapsedClock()
        if (!current.lease.isLocallyUsable(nowElapsedMs)) return@synchronized null
        current.lease.localDeadlineElapsedMs - nowElapsedMs
    }

    fun activeLeaseOrNull(epoch: WalkRuntimeEpoch): GatewayWalkLease? =
        synchronized(lock) {
            dropExpiredLocked()
            held?.takeIf { it.epoch == epoch }?.lease
        }

    /**
     * True while a start is pending or a lease is held for [walkSessionId]. A later start of the
     * same walk can receive the very lease an abandoned start obtained (ALREADY_ACTIVE), so that
     * lease must not be ended while this controller still tracks the walk.
     */
    fun tracksWalk(walkSessionId: String): Boolean = synchronized(lock) {
        held?.epoch?.walkSessionId == walkSessionId || pending?.epoch?.walkSessionId == walkSessionId
    }

    fun currentConflictOrNull(epoch: WalkRuntimeEpoch): GatewayWalkConflict? =
        synchronized(lock) {
            dropExpiredLocked()
            conflict?.takeIf { it.epoch == epoch }?.conflict
        }

    fun currentOperationStatus(
        operation: GatewayWalkAuthorityOperation,
    ): GatewayWalkAuthorityCompletion = synchronized(lock) {
        if (pending != operation) return@synchronized GatewayWalkAuthorityCompletion.STALE
        if (
            operation.action == GatewayWalkAuthorityAction.RENEW &&
            operation.sourceLease?.isLocallyUsable(elapsedClock()) != true
        ) {
            return@synchronized GatewayWalkAuthorityCompletion.INVALID
        }
        GatewayWalkAuthorityCompletion.LEASE_ACTIVE
    }

    fun isCurrent(operation: GatewayWalkAuthorityOperation): Boolean =
        currentOperationStatus(operation) == GatewayWalkAuthorityCompletion.LEASE_ACTIVE

    fun cancel(
        operation: GatewayWalkAuthorityOperation,
        clearConflict: Boolean = false,
    ): Boolean = synchronized(lock) {
        if (pending != operation) return@synchronized false
        pending = null
        if (clearConflict) conflict = null
        true
    }

    fun clearConflict(epoch: WalkRuntimeEpoch): Boolean = synchronized(lock) {
        if (conflict?.epoch != epoch) return@synchronized false
        conflict = null
        pending = null
        true
    }

    fun invalidate(epoch: WalkRuntimeEpoch? = null): GatewayWalkLease? =
        synchronized(lock) {
            val current = held
            if (
                epoch != null &&
                current != null &&
                current.epoch.walkSessionId != epoch.walkSessionId
            ) {
                return@synchronized null
            }
            held = null
            pending = null
            conflict = null
            current?.lease
        }

    fun reset() = synchronized(lock) {
        held = null
        pending = null
        conflict = null
    }

    /**
     * S03-19: after an in-process configuration re-creation, take over [previous]'s held lease for
     * the same logical walk. Nothing in flight or conflicting is carried and [previous] loses it.
     */
    fun adoptHeldLeaseFrom(previous: GatewayWalkAuthorityController): Boolean {
        if (previous === this) return false
        val lease = synchronized(previous.lock) {
            previous.dropExpiredLocked()
            previous.held.also {
                previous.held = null
                previous.pending = null
                previous.conflict = null
            }
        } ?: return false
        synchronized(lock) {
            held = lease
            pending = null
            conflict = null
        }
        return true
    }

    private fun newOperation(
        epoch: WalkRuntimeEpoch,
        action: GatewayWalkAuthorityAction,
        sourceLease: GatewayWalkLease? = null,
        sourceConflict: GatewayWalkConflict? = null,
    ): GatewayWalkAuthorityOperation {
        val requestId = requestIdFactory()
        require(requestId.isNotBlank()) { "walk authority request id must not be blank" }
        return GatewayWalkAuthorityOperation(
            operationId = UUID.randomUUID().toString(),
            requestId = requestId,
            epoch = epoch,
            action = action,
            sourceLease = sourceLease,
            sourceConflict = sourceConflict,
        )
    }

    private fun dropExpiredLocked() {
        val now = elapsedClock()
        if (held?.lease?.isLocallyUsable(now) == false) {
            held = null
            pending = null
        }
        if (conflict?.conflict?.isLocallyCurrent(now) == false) {
            conflict = null
            if (pending?.action == GatewayWalkAuthorityAction.TAKEOVER) pending = null
        }
    }

    private data class HeldLease(
        val epoch: WalkRuntimeEpoch,
        val lease: GatewayWalkLease,
    )

    private data class HeldConflict(
        val epoch: WalkRuntimeEpoch,
        val conflict: GatewayWalkConflict,
    )
}
