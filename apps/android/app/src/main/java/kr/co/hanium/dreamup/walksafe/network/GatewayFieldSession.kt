package kr.co.hanium.dreamup.walksafe.network

// Exchanges named field credentials for the release gateway's scoped session cookie.

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

enum class GatewaySessionVerificationState {
    VERIFIED,
    RESTORED_UNVERIFIED,
}

enum class GatewaySessionScope(val wireValue: String) {
    GENERAL("general"),
    ACCOUNT_DELETION_RECOVERY("account_deletion_recovery"),
}

data class GatewaySessionLease(
    val instanceId: String,
    val gatewayBaseUrl: String,
    val actorId: String,
    val deviceId: String,
    val familyId: String?,
    val rotation: Long?,
)

data class GatewaySessionVersion(
    val gatewayBaseUrl: String,
    val actorId: String,
    val deviceId: String,
    val familyId: String,
    val rotation: Long,
)

data class GatewaySessionRenewal(
    val sourceLease: GatewaySessionLease,
    val session: GatewayFieldSession,
)

class GatewayFieldSession private constructor(
    val gatewayBaseUrl: String,
    val actorId: String,
    val deviceId: String,
    val familyId: String?,
    val rotation: Long?,
    private val cookiePair: String,
    private val refreshToken: String?,
    val accessExpiresAtEpochMs: Long,
    val idleExpiresAtEpochMs: Long,
    val absoluteExpiresAtEpochMs: Long,
    val verificationState: GatewaySessionVerificationState,
    internal val sessionScope: GatewaySessionScope,
    instanceId: String = UUID.randomUUID().toString(),
) {
    private val active = AtomicBoolean(true)

    val expiresAtEpochMs: Long
        get() = accessExpiresAtEpochMs

    val lease = GatewaySessionLease(
        instanceId = instanceId,
        gatewayBaseUrl = gatewayBaseUrl,
        actorId = actorId,
        deviceId = deviceId,
        familyId = familyId,
        rotation = rotation,
    )

    internal val versionOrNull: GatewaySessionVersion?
        get() {
            val currentFamilyId = familyId ?: return null
            val currentRotation = rotation ?: return null
            return GatewaySessionVersion(
                gatewayBaseUrl = gatewayBaseUrl,
                actorId = actorId,
                deviceId = deviceId,
                familyId = currentFamilyId,
                rotation = currentRotation,
            )
        }

    internal val isLongLived: Boolean
        get() = familyId != null && rotation != null && refreshToken != null

    fun requestHeaders(
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Map<String, String> {
        check(isUsableFor(actorId, nowEpochMs)) {
            "gateway session access is not verified and unexpired"
        }
        return accessCookieHeaders()
    }

    fun isUsableFor(actorId: String?, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        return active.get() &&
            verificationState == GatewaySessionVerificationState.VERIFIED &&
            this.actorId == GatewayCredentialPolicy.normalizedActorIdOrNull(actorId) &&
            nowEpochMs < accessExpiresAtEpochMs &&
            nowEpochMs < idleExpiresAtEpochMs &&
            nowEpochMs < absoluteExpiresAtEpochMs
    }

    fun isRenewableFor(actorId: String?, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        return active.get() &&
            isLongLived &&
            this.actorId == GatewayCredentialPolicy.normalizedActorIdOrNull(actorId) &&
            nowEpochMs < idleExpiresAtEpochMs &&
            nowEpochMs < absoluteExpiresAtEpochMs
    }

    internal fun invalidate() {
        active.set(false)
    }

    internal fun cookieHeaders(
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Map<String, String> = requestHeaders(nowEpochMs)

    internal fun logoutHeaders(): Map<String, String> = accessCookieHeaders()

    internal fun persistenceSnapshotOrNull(): GatewayFieldSessionPersistence? {
        if (
            !active.get() ||
            verificationState != GatewaySessionVerificationState.VERIFIED ||
            !isLongLived
        ) {
            return null
        }
        return GatewayFieldSessionPersistence(
            gatewayBaseUrl = gatewayBaseUrl,
            actorId = actorId,
            accessCookiePair = cookiePair,
            refreshToken = requireNotNull(refreshToken),
            deviceId = deviceId,
            familyId = requireNotNull(familyId),
            rotation = requireNotNull(rotation),
            accessExpiresAtEpochMs = accessExpiresAtEpochMs,
            idleExpiresAtEpochMs = idleExpiresAtEpochMs,
            absoluteExpiresAtEpochMs = absoluteExpiresAtEpochMs,
        )
    }

    internal fun renewalProofOrNull(
        actorId: String?,
        nowEpochMs: Long,
    ): GatewaySessionRenewalProof? {
        if (!isRenewableFor(actorId, nowEpochMs)) return null
        return GatewaySessionRenewalProof(
            lease = lease,
            accessToken = cookiePair.substringAfter('='),
            refreshToken = requireNotNull(refreshToken),
        )
    }

    internal fun logoutProofOrNull(): GatewaySessionRenewalProof? {
        if (!isLongLived) return null
        return GatewaySessionRenewalProof(
            lease = lease,
            accessToken = cookiePair.substringAfter('='),
            refreshToken = requireNotNull(refreshToken),
        )
    }

    internal fun invalidateForRenewal(expectedLease: GatewaySessionLease): Boolean {
        return lease == expectedLease && active.compareAndSet(true, false)
    }

    private fun accessCookieHeaders(): Map<String, String> = mapOf(COOKIE_HEADER to cookiePair)

    companion object {
        const val COOKIE_HEADER = "Cookie"
        const val COOKIE_NAME = "walksafe_field_session"

        internal fun verified(
            gatewayBaseUrl: String,
            actorId: String,
            cookiePair: String,
            expiresAtEpochMs: Long,
        ): GatewayFieldSession = legacyVerified(
            gatewayBaseUrl = gatewayBaseUrl,
            actorId = actorId,
            deviceId = "legacy-${UUID.randomUUID()}",
            cookiePair = cookiePair,
            expiresAtEpochMs = expiresAtEpochMs,
        )

        internal fun legacyVerified(
            gatewayBaseUrl: String,
            actorId: String,
            deviceId: String,
            cookiePair: String,
            expiresAtEpochMs: Long,
            sessionScope: GatewaySessionScope = GatewaySessionScope.GENERAL,
        ): GatewayFieldSession = GatewayFieldSession(
            gatewayBaseUrl = gatewayBaseUrl,
            actorId = actorId,
            deviceId = deviceId,
            familyId = null,
            rotation = null,
            cookiePair = cookiePair,
            refreshToken = null,
            accessExpiresAtEpochMs = expiresAtEpochMs,
            idleExpiresAtEpochMs = expiresAtEpochMs,
            absoluteExpiresAtEpochMs = expiresAtEpochMs,
            verificationState = GatewaySessionVerificationState.VERIFIED,
            sessionScope = sessionScope,
        )

        private fun longLivedVerified(
            gatewayBaseUrl: String,
            actorId: String,
            deviceId: String,
            familyId: String,
            rotation: Long,
            cookiePair: String,
            refreshToken: String,
            accessExpiresAtEpochMs: Long,
            idleExpiresAtEpochMs: Long,
            absoluteExpiresAtEpochMs: Long,
        ): GatewayFieldSession = GatewayFieldSession(
            gatewayBaseUrl = gatewayBaseUrl,
            actorId = actorId,
            deviceId = deviceId,
            familyId = familyId,
            rotation = rotation,
            cookiePair = cookiePair,
            refreshToken = refreshToken,
            accessExpiresAtEpochMs = accessExpiresAtEpochMs,
            idleExpiresAtEpochMs = idleExpiresAtEpochMs,
            absoluteExpiresAtEpochMs = absoluteExpiresAtEpochMs,
            verificationState = GatewaySessionVerificationState.VERIFIED,
            sessionScope = GatewaySessionScope.GENERAL,
        )

        internal fun restore(
            snapshot: GatewayFieldSessionPersistence,
            expectedGatewayBaseUrl: String,
            expectedActorId: String,
            expectedDeviceId: String? = null,
            nowEpochMs: Long = System.currentTimeMillis(),
        ): GatewayFieldSession? {
            if (snapshot.gatewayBaseUrl != expectedGatewayBaseUrl) return null
            if (snapshot.actorId != GatewayCredentialPolicy.normalizedActorIdOrNull(expectedActorId)) {
                return null
            }
            if (snapshot.deviceId != GatewayCredentialPolicy.normalizedDeviceIdOrNull(snapshot.deviceId)) {
                return null
            }
            if (
                expectedDeviceId != null &&
                snapshot.deviceId !=
                GatewayCredentialPolicy.normalizedDeviceIdOrNull(expectedDeviceId)
            ) {
                return null
            }
            if (snapshot.familyId != GatewayCredentialPolicy.normalizedFamilyIdOrNull(snapshot.familyId)) {
                return null
            }
            if (snapshot.rotation < 0L) return null
            if (
                nowEpochMs >= snapshot.idleExpiresAtEpochMs ||
                nowEpochMs >= snapshot.absoluteExpiresAtEpochMs ||
                snapshot.idleExpiresAtEpochMs >
                safeExpiryBound(nowEpochMs, MAX_REFRESH_IDLE_TTL_SECONDS) ||
                snapshot.absoluteExpiresAtEpochMs >
                safeExpiryBound(nowEpochMs, MAX_REFRESH_ABSOLUTE_TTL_SECONDS)
            ) {
                return null
            }
            if (
                snapshot.accessExpiresAtEpochMs > snapshot.idleExpiresAtEpochMs ||
                snapshot.idleExpiresAtEpochMs > snapshot.absoluteExpiresAtEpochMs
            ) {
                return null
            }
            if (!isValidPersistedCookiePair(snapshot.accessCookiePair)) return null
            if (
                GatewayCredentialPolicy.normalizedRefreshTokenOrNull(
                    snapshot.refreshToken,
                ) == null
            ) {
                return null
            }
            return GatewayFieldSession(
                gatewayBaseUrl = snapshot.gatewayBaseUrl,
                actorId = snapshot.actorId,
                deviceId = snapshot.deviceId,
                familyId = snapshot.familyId,
                rotation = snapshot.rotation,
                cookiePair = snapshot.accessCookiePair,
                refreshToken = snapshot.refreshToken,
                accessExpiresAtEpochMs = snapshot.accessExpiresAtEpochMs,
                idleExpiresAtEpochMs = snapshot.idleExpiresAtEpochMs,
                absoluteExpiresAtEpochMs = snapshot.absoluteExpiresAtEpochMs,
                verificationState = GatewaySessionVerificationState.RESTORED_UNVERIFIED,
                sessionScope = GatewaySessionScope.GENERAL,
            )
        }

        internal fun longLivedSession(
            gatewayBaseUrl: String,
            actorId: String,
            deviceId: String,
            familyId: String,
            rotation: Long,
            cookiePair: String,
            refreshToken: String,
            accessExpiresAtEpochMs: Long,
            idleExpiresAtEpochMs: Long,
            absoluteExpiresAtEpochMs: Long,
        ): GatewayFieldSession = longLivedVerified(
            gatewayBaseUrl,
            actorId,
            deviceId,
            familyId,
            rotation,
            cookiePair,
            refreshToken,
            accessExpiresAtEpochMs,
            idleExpiresAtEpochMs,
            absoluteExpiresAtEpochMs,
        )

        private fun isValidPersistedCookiePair(value: String): Boolean {
            val prefix = "$COOKIE_NAME="
            if (!value.startsWith(prefix) || value.length !in (prefix.length + 16)..4_096) {
                return false
            }
            return value.substring(prefix.length).all {
                it.code in 0x21..0x7e && it != ';'
            }
        }
    }
}

internal class GatewayFieldSessionPersistence(
    val gatewayBaseUrl: String,
    val actorId: String,
    val accessCookiePair: String,
    val refreshToken: String,
    val deviceId: String,
    val familyId: String,
    val rotation: Long,
    val accessExpiresAtEpochMs: Long,
    val idleExpiresAtEpochMs: Long,
    val absoluteExpiresAtEpochMs: Long,
) {
    override fun toString(): String =
        "GatewayFieldSessionPersistence(actorId=$actorId, deviceId=$deviceId, rotation=$rotation, credentials=redacted)"
}

internal class GatewaySessionRenewalProof(
    val lease: GatewaySessionLease,
    val accessToken: String,
    val refreshToken: String,
) {
    val fingerprint: String = sha256Hex(
        listOf(
            lease.gatewayBaseUrl,
            lease.actorId,
            lease.deviceId,
            lease.familyId.orEmpty(),
            lease.rotation?.toString().orEmpty(),
            refreshToken,
        ).joinToString("\u0000"),
    )

    override fun toString(): String =
        "GatewaySessionRenewalProof(lease=$lease, accessToken=redacted, refreshToken=redacted)"
}

private object GatewayRefreshProofAttemptFence {
    private val lock = Any()
    private val attemptedFingerprints = mutableSetOf<String>()

    fun reserve(proof: GatewaySessionRenewalProof): Boolean = synchronized(lock) {
        attemptedFingerprints.add(proof.fingerprint)
    }

    fun resetForTests() = synchronized(lock) {
        attemptedFingerprints.clear()
    }
}

internal fun resetGatewayRefreshProofFenceForTests() {
    GatewayRefreshProofAttemptFence.resetForTests()
}

object GatewayCredentialPolicy {
    private val ACTOR_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._@-]{0,63}")
    private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")
    private val FAMILY_ID_PATTERN = Regex("[A-Za-z0-9_-]{24,128}")
    private val REFRESH_TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{48,256}")
    private val RESERVED_ACTOR_IDS = setOf("unknown", "system", "anonymous")

    fun normalizedActorIdOrNull(actorId: String?): String? {
        val actor = actorId?.trim()?.takeIf(ACTOR_ID_PATTERN::matches) ?: return null
        val normalized = actor.lowercase()
        return actor.takeUnless { normalized in RESERVED_ACTOR_IDS || normalized.endsWith("-shared") }
    }

    fun normalizedTokenOrNull(token: String?): String? {
        val value = token?.trim()?.takeIf { it.length in 24..512 } ?: return null
        return value.takeUnless { candidate -> candidate.any { it.code < 0x20 || it.code == 0x7f } }
    }

    fun normalizedRefreshTokenOrNull(token: String?): String? =
        token?.trim()?.takeIf(REFRESH_TOKEN_PATTERN::matches)

    fun normalizedDeviceIdOrNull(deviceId: String?): String? =
        deviceId?.trim()?.takeIf(DEVICE_ID_PATTERN::matches)

    fun normalizedFamilyIdOrNull(familyId: String?): String? =
        familyId?.trim()?.takeIf(FAMILY_ID_PATTERN::matches)

    fun newDeviceId(): String = UUID.randomUUID().toString()
}

data class GatewayHttpResponse(
    val statusCode: Int,
    val responseBody: String,
    val headers: Map<String, String> = emptyMap(),
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override fun toString(): String =
        "GatewayHttpResponse(statusCode=$statusCode, responseBody=redacted, headerNames=${headers.keys.sorted()})"
}

interface GatewaySessionTransport {
    fun postJson(url: String, body: String): GatewayHttpResponse
    fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): GatewayHttpResponse {
        require(headers.isEmpty()) {
            "This GatewaySessionTransport does not support authenticated JSON POST"
        }
        return postJson(url, body)
    }
    fun get(url: String, headers: Map<String, String>): GatewayHttpResponse
    fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse
    fun deleteJson(url: String, body: String): GatewayHttpResponse
}

enum class GatewaySessionRevalidationStatus {
    READY,
    NOT_READY,
    PENDING,
}

data class GatewaySessionRevalidation(
    val status: GatewaySessionRevalidationStatus,
    val reason: String,
    val capacityAvailability: GatewayCapacityAvailability =
        GatewayCapacityAvailability.MISSING,
    val capacityUpdate: GatewayCapacityUpdate? = null,
)

class GatewayFieldSessionClient(
    private val transport: GatewaySessionTransport = HttpUrlConnectionGatewaySessionTransport(),
) {
    fun login(
        gatewayBaseUrl: String,
        actorId: String?,
        token: String?,
        nowEpochMs: Long = System.currentTimeMillis(),
        enableLongLivedSession: Boolean = false,
        deviceId: String? = null,
        sessionScope: GatewaySessionScope = GatewaySessionScope.GENERAL,
    ): GatewayFieldSession {
        if (
            enableLongLivedSession &&
            sessionScope != GatewaySessionScope.GENERAL
        ) {
            throw GatewaySessionHttpException(
                statusCode = 0,
                reason = "gateway_recovery_long_lived_not_allowed",
            )
        }
        val actor = GatewayCredentialPolicy.normalizedActorIdOrNull(actorId)
            ?: throw GatewaySessionHttpException(statusCode = 0, reason = "invalid_named_actor")
        val credential = GatewayCredentialPolicy.normalizedTokenOrNull(token)
            ?: throw GatewaySessionHttpException(statusCode = 0, reason = "invalid_gateway_credential")
        val device = if (enableLongLivedSession) {
            GatewayCredentialPolicy.normalizedDeviceIdOrNull(deviceId)
                ?: throw GatewaySessionHttpException(statusCode = 0, reason = "invalid_gateway_device")
        } else {
            GatewayCredentialPolicy.newDeviceId()
        }
        val requireSecure = runCatching { URI(gatewayBaseUrl).scheme.equals("https", ignoreCase = true) }
            .getOrElse { throw GatewaySessionHttpException(statusCode = 0, reason = "invalid_gateway_url") }
        val endpoint = gatewayBaseUrl.trimEnd('/') + "/api/field-session"
        val loginBody = JSONObject()
            .put("actor_id", actor)
            .put("token", credential)
        if (enableLongLivedSession) loginBody.put("device_id", device)
        if (sessionScope == GatewaySessionScope.ACCOUNT_DELETION_RECOVERY) {
            loginBody.put("purpose", sessionScope.wireValue)
        }
        val login = transport.postJson(endpoint, loginBody.toString())
        if (login.statusCode == 200 && enableLongLivedSession) {
            return parseLongLivedSession(
                response = login,
                gatewayBaseUrl = gatewayBaseUrl,
                expectedActorId = actor,
                expectedDeviceId = device,
                expectedFamilyId = null,
                expectedRotation = 0L,
                expectedAbsoluteExpiresAtEpochMs = null,
                forbiddenCredential = credential,
                forbiddenAccessCredential = null,
                nowEpochMs = nowEpochMs,
            )
        }
        if (login.statusCode == 200) {
            val scopeResponse = runCatching { JSONObject(login.responseBody) }.getOrNull()
            if (
                scopeResponse?.optString("session_mode") == "long_lived" ||
                scopeResponse?.has("refresh_token") == true
            ) {
                throw GatewaySessionHttpException(
                    statusCode = 0,
                    reason = "gateway_long_lived_login_not_enabled",
                )
            }
            if (
                scopeResponse == null ||
                scopeResponse.jsonKeySet() != setOf("session_scope") ||
                scopeResponse.optString("session_scope") != sessionScope.wireValue
            ) {
                throw GatewaySessionHttpException(
                    statusCode = 0,
                    reason = "gateway_session_scope_binding_failed",
                )
            }
        }
        if (login.statusCode !in setOf(200, 204)) {
            throw login.toSessionException("gateway_login_failed")
        }
        val parsedCookie = parseGatewayCookie(login.header("Set-Cookie"), requireSecure)
            ?: throw GatewaySessionHttpException(statusCode = 0, reason = "invalid_gateway_cookie")
        if (parsedCookie.cookiePair.substringAfter('=') == credential) {
            throw GatewaySessionHttpException(statusCode = 0, reason = "raw_credential_in_gateway_cookie")
        }
        val cookieHeaders = mapOf(GatewayFieldSession.COOKIE_HEADER to parsedCookie.cookiePair)
        val status = transport.get(endpoint, cookieHeaders)
        if (status.statusCode !in 200..299) throw status.toSessionException("gateway_status_failed")
        val statusJson = runCatching { JSONObject(status.responseBody) }.getOrNull()
            ?: throw GatewaySessionHttpException(statusCode = 0, reason = "invalid_gateway_status")
        if (
            !statusJson.optBoolean("required", false) ||
            !statusJson.optBoolean("authenticated", false) ||
            statusJson.optString("actor_id") != actor
            || !statusScopeMatches(statusJson, sessionScope)
        ) {
            runCatching { transport.delete(endpoint, cookieHeaders) }
            throw GatewaySessionHttpException(statusCode = 0, reason = "gateway_actor_binding_failed")
        }
        return GatewayFieldSession.legacyVerified(
            gatewayBaseUrl = gatewayBaseUrl,
            actorId = actor,
            deviceId = device,
            cookiePair = parsedCookie.cookiePair,
            expiresAtEpochMs = parsedCookie.expiresAtEpochMs(nowEpochMs),
            sessionScope = sessionScope,
        )
    }

    fun renew(
        session: GatewayFieldSession,
        actorId: String?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): GatewaySessionRenewal {
        val actor = GatewayCredentialPolicy.normalizedActorIdOrNull(actorId)
            ?: throw GatewaySessionHttpException(statusCode = 0, reason = "invalid_named_actor")
        val proof = session.renewalProofOrNull(actor, nowEpochMs)
            ?: throw GatewaySessionHttpException(statusCode = 0, reason = "gateway_refresh_not_allowed")
        if (!GatewayRefreshProofAttemptFence.reserve(proof)) {
            throw GatewaySessionHttpException(
                statusCode = 0,
                reason = "gateway_refresh_proof_already_attempted",
            )
        }
        val familyId = proof.lease.familyId
            ?: throw GatewaySessionHttpException(statusCode = 0, reason = "gateway_refresh_not_allowed")
        val rotation = proof.lease.rotation
            ?: throw GatewaySessionHttpException(statusCode = 0, reason = "gateway_refresh_not_allowed")
        if (rotation == Long.MAX_VALUE) {
            session.invalidate()
            throw GatewaySessionHttpException(statusCode = 0, reason = "gateway_refresh_rotation_exhausted")
        }
        val endpoint = session.gatewayBaseUrl.trimEnd('/') + "/api/field-session"
        val response = try {
            transport.postJson(
                endpoint,
                JSONObject()
                    .put("grant_type", "refresh_token")
                    .put("actor_id", actor)
                    .put("device_id", proof.lease.deviceId)
                    .put("family_id", familyId)
                    .put("rotation", rotation)
                    .put("refresh_token", proof.refreshToken)
                    .toString(),
            )
        } catch (_: RuntimeException) {
            session.invalidate()
            throw GatewaySessionHttpException(
                statusCode = 0,
                reason = "gateway_refresh_outcome_unknown",
            )
        }
        if (response.statusCode != 200) {
            if (response.statusCode in 400..499 && response.statusCode != 429) {
                session.invalidate()
            }
            throw response.toSessionException("gateway_refresh_failed")
        }
        val renewed = try {
            parseLongLivedSession(
                response = response,
                gatewayBaseUrl = session.gatewayBaseUrl,
                expectedActorId = actor,
                expectedDeviceId = proof.lease.deviceId,
                expectedFamilyId = familyId,
                expectedRotation = rotation + 1L,
                expectedAbsoluteExpiresAtEpochMs = session.absoluteExpiresAtEpochMs,
                forbiddenCredential = proof.refreshToken,
                forbiddenAccessCredential = proof.accessToken,
                nowEpochMs = nowEpochMs,
            )
        } catch (error: RuntimeException) {
            session.invalidate()
            throw error
        }
        if (!session.invalidateForRenewal(proof.lease)) {
            renewed.invalidate()
            throw GatewaySessionHttpException(statusCode = 0, reason = "stale_gateway_session_lease")
        }
        return GatewaySessionRenewal(sourceLease = proof.lease, session = renewed)
    }

    fun revalidate(
        session: GatewayFieldSession,
        actorId: String?,
        nowEpochMs: Long = System.currentTimeMillis(),
        capacitySessionGeneration: Long? = null,
    ): GatewaySessionRevalidation {
        val now = Instant.ofEpochMilli(nowEpochMs)
        fun result(
            status: GatewaySessionRevalidationStatus,
            reason: String,
            capacityUpdate: GatewayCapacityUpdate? = null,
        ): GatewaySessionRevalidation = GatewaySessionRevalidation(
            status = status,
            reason = reason,
            capacityAvailability =
                GatewayCapacityProcessState.admission(now).availability,
            capacityUpdate = capacityUpdate,
        )
        val actor = GatewayCredentialPolicy.normalizedActorIdOrNull(actorId)
        if (!session.isUsableFor(actor, nowEpochMs)) {
            return result(
                GatewaySessionRevalidationStatus.NOT_READY,
                "local_session_unavailable",
            )
        }
        val endpoint = session.gatewayBaseUrl.trimEnd('/') + "/api/field-session"
        val response = try {
            transport.get(endpoint, session.cookieHeaders(nowEpochMs))
        } catch (_: RuntimeException) {
            return result(
                GatewaySessionRevalidationStatus.PENDING,
                "gateway_status_unreachable",
            )
        }
        if (response.statusCode !in 200..299) {
            val status = when {
                response.statusCode in setOf(401, 403) ->
                    GatewaySessionRevalidationStatus.NOT_READY
                response.statusCode == 429 || response.statusCode >= 500 ->
                    GatewaySessionRevalidationStatus.PENDING
                else -> GatewaySessionRevalidationStatus.NOT_READY
            }
            return result(
                status,
                "gateway_status_http_${response.statusCode}",
            )
        }
        val statusJson = runCatching { JSONObject(response.responseBody) }.getOrNull()
            ?: return result(
                GatewaySessionRevalidationStatus.PENDING,
                "gateway_status_malformed",
            )
        val bindingReady =
            statusJson.optBoolean("required", false) &&
                statusJson.optBoolean("authenticated", false) &&
                statusJson.optString("actor_id") == actor &&
                statusScopeMatches(statusJson, session.sessionScope) &&
                statusMatchesLease(statusJson, session)
        val capacityUpdate = GatewayCapacityProcessState.apply(
            GatewayCapacityParser.fromSessionStatus(statusJson),
            now,
            capacitySessionGeneration,
        )
        if (!bindingReady) {
            return result(
                GatewaySessionRevalidationStatus.NOT_READY,
                "gateway_actor_binding_unavailable",
                capacityUpdate,
            )
        }
        return result(
            GatewaySessionRevalidationStatus.READY,
            "gateway_session_ready",
            capacityUpdate,
        )
    }

    fun logout(session: GatewayFieldSession) {
        val endpoint = session.gatewayBaseUrl.trimEnd('/') + "/api/field-session"
        val proof = session.logoutProofOrNull()
        val headers = if (proof == null) session.logoutHeaders() else emptyMap()
        session.invalidate()
        val response = if (proof == null) {
            transport.delete(endpoint, headers)
        } else {
            transport.deleteJson(
                endpoint,
                JSONObject()
                    .put("grant_type", "refresh_token")
                    .put("actor_id", proof.lease.actorId)
                    .put("device_id", proof.lease.deviceId)
                    .put("family_id", proof.lease.familyId)
                    .put("rotation", proof.lease.rotation)
                    .put("refresh_token", proof.refreshToken)
                    .toString(),
            )
        }
        if (response.statusCode != 204) throw response.toSessionException("gateway_logout_failed")
    }

    internal fun logout(pending: GatewayPendingRevocation) {
        val version = pending.version
        val endpoint = version.gatewayBaseUrl.trimEnd('/') + "/api/field-session"
        val response = transport.deleteJson(
            endpoint,
            JSONObject()
                .put("grant_type", "refresh_token")
                .put("actor_id", version.actorId)
                .put("device_id", version.deviceId)
                .put("family_id", version.familyId)
                .put("rotation", version.rotation)
                .put("refresh_token", pending.refreshToken)
                .toString(),
        )
        if (response.statusCode != 204) throw response.toSessionException("gateway_logout_failed")
    }

    private fun GatewayHttpResponse.toSessionException(reason: String): GatewaySessionHttpException {
        val retryAfterMs = header("Retry-After")
            ?.trim()
            ?.toLongOrNull()
            ?.coerceIn(1L, MAX_RETRY_AFTER_MS / 1_000L)
            ?.times(1_000L)
        val serverCode = runCatching {
            JSONObject(responseBody).optString("code")
                .takeIf(ALLOWED_SERVER_ERROR_CODES::contains)
        }.getOrNull()
        return GatewaySessionHttpException(
            statusCode = statusCode,
            reason = reason,
            retryAfterMs = retryAfterMs,
            serverCode = serverCode,
        )
    }

    private fun parseLongLivedSession(
        response: GatewayHttpResponse,
        gatewayBaseUrl: String,
        expectedActorId: String,
        expectedDeviceId: String,
        expectedFamilyId: String?,
        expectedRotation: Long,
        expectedAbsoluteExpiresAtEpochMs: Long?,
        forbiddenCredential: String,
        forbiddenAccessCredential: String?,
        nowEpochMs: Long,
    ): GatewayFieldSession {
        val requireSecure = runCatching {
            URI(gatewayBaseUrl).scheme.equals("https", ignoreCase = true)
        }.getOrElse {
            throw GatewaySessionHttpException(statusCode = 0, reason = "invalid_gateway_url")
        }
        val cookie = parseGatewayCookie(response.header("Set-Cookie"), requireSecure)
            ?: throw GatewaySessionHttpException(statusCode = 0, reason = "invalid_gateway_cookie")
        val json = runCatching { JSONObject(response.responseBody) }.getOrNull()
            ?: throw GatewaySessionHttpException(statusCode = 0, reason = "invalid_gateway_login_response")
        val responseActorId = json.optString("actor_id")
        val responseDeviceId = GatewayCredentialPolicy.normalizedDeviceIdOrNull(
            json.optString("device_id"),
        )
        val responseFamilyId = GatewayCredentialPolicy.normalizedFamilyIdOrNull(
            json.optString("family_id"),
        )
        val responseRotation = json.optLong("rotation", -1L)
        val responseRefreshToken = GatewayCredentialPolicy.normalizedRefreshTokenOrNull(
            json.optString("refresh_token"),
        )
        val accessExpiresAtEpochMs = json.optLong("access_expires_at_epoch_ms", -1L)
        val idleExpiresAtEpochMs = json.optLong("idle_expires_at_epoch_ms", -1L)
        val absoluteExpiresAtEpochMs = json.optLong("absolute_expires_at_epoch_ms", -1L)
        val accessToken = cookie.cookiePair.substringAfter('=')
        val localAccessExpiresAtEpochMs = runCatching {
            Math.addExact(
                nowEpochMs,
                Math.multiplyExact(cookie.maxAgeSeconds, 1_000L),
            )
        }.getOrElse {
            throw GatewaySessionHttpException(
                statusCode = 0,
                reason = "invalid_gateway_cookie",
            )
        }
        val maximumCookieAlignedAccessExpiry = runCatching {
            Math.addExact(
                localAccessExpiresAtEpochMs,
                MAX_SERVER_CLOCK_SKEW_MS,
            )
        }.getOrDefault(Long.MAX_VALUE)
        val effectiveAccessExpiresAtEpochMs =
            minOf(accessExpiresAtEpochMs, localAccessExpiresAtEpochMs)
        val valid =
            responseActorId == expectedActorId &&
                responseDeviceId == expectedDeviceId &&
                responseFamilyId != null &&
                (expectedFamilyId == null || responseFamilyId == expectedFamilyId) &&
                responseRotation == expectedRotation &&
                (
                    expectedAbsoluteExpiresAtEpochMs == null ||
                        absoluteExpiresAtEpochMs == expectedAbsoluteExpiresAtEpochMs
                    ) &&
                responseRefreshToken != null &&
                responseRefreshToken != forbiddenCredential &&
                responseRefreshToken != forbiddenAccessCredential &&
                accessToken != forbiddenCredential &&
                accessToken != forbiddenAccessCredential &&
                accessToken != responseRefreshToken &&
                nowEpochMs < effectiveAccessExpiresAtEpochMs &&
                accessExpiresAtEpochMs <=
                safeExpiryBound(nowEpochMs, MAX_ACCESS_TTL_SECONDS) &&
                accessExpiresAtEpochMs <= maximumCookieAlignedAccessExpiry &&
                idleExpiresAtEpochMs <=
                safeExpiryBound(nowEpochMs, MAX_REFRESH_IDLE_TTL_SECONDS) &&
                absoluteExpiresAtEpochMs <=
                safeExpiryBound(nowEpochMs, MAX_REFRESH_ABSOLUTE_TTL_SECONDS) &&
                effectiveAccessExpiresAtEpochMs <= idleExpiresAtEpochMs &&
                idleExpiresAtEpochMs <= absoluteExpiresAtEpochMs
        if (!valid) {
            throw GatewaySessionHttpException(
                statusCode = 0,
                reason = "gateway_session_binding_failed",
            )
        }
        return GatewayFieldSession.longLivedSession(
            gatewayBaseUrl = gatewayBaseUrl,
            actorId = expectedActorId,
            deviceId = expectedDeviceId,
            familyId = responseFamilyId,
            rotation = responseRotation,
            cookiePair = cookie.cookiePair,
            refreshToken = responseRefreshToken,
            accessExpiresAtEpochMs = effectiveAccessExpiresAtEpochMs,
            idleExpiresAtEpochMs = idleExpiresAtEpochMs,
            absoluteExpiresAtEpochMs = absoluteExpiresAtEpochMs,
        )
    }

    private fun statusMatchesLease(
        statusJson: JSONObject,
        session: GatewayFieldSession,
    ): Boolean {
        if (!session.isLongLived) return true
        return statusJson.optString("session_mode") == "long_lived" &&
            statusJson.optString("device_id") == session.deviceId &&
            statusJson.optString("family_id") == session.familyId &&
            statusJson.optLong("rotation", -1L) == session.rotation &&
            statusJson.optLong("access_expires_at_epoch_ms", -1L) ==
            session.accessExpiresAtEpochMs &&
            statusJson.optLong("idle_expires_at_epoch_ms", -1L) ==
            session.idleExpiresAtEpochMs &&
            statusJson.optLong("absolute_expires_at_epoch_ms", -1L) ==
            session.absoluteExpiresAtEpochMs
    }

    private fun statusScopeMatches(
        statusJson: JSONObject,
        expected: GatewaySessionScope,
    ): Boolean {
        val actual = statusJson.optString("session_scope")
        return actual == expected.wireValue ||
            expected == GatewaySessionScope.GENERAL && actual.isEmpty()
    }
}

private fun JSONObject.jsonKeySet(): Set<String> {
    val result = mutableSetOf<String>()
    val keys = keys()
    while (keys.hasNext()) result += keys.next()
    return result
}

class GatewaySessionHttpException(
    val statusCode: Int,
    val reason: String,
    val retryAfterMs: Long? = null,
    val serverCode: String? = null,
) : IllegalStateException("gateway session request failed: $reason status=$statusCode")

private data class ParsedGatewayCookie(val cookiePair: String, val maxAgeSeconds: Long)

private fun ParsedGatewayCookie.expiresAtEpochMs(nowEpochMs: Long): Long {
    return runCatching {
        Math.addExact(nowEpochMs, Math.multiplyExact(maxAgeSeconds, 1_000L))
    }.getOrElse {
        throw GatewaySessionHttpException(statusCode = 0, reason = "invalid_gateway_cookie")
    }
}

private fun parseGatewayCookie(header: String?, requireSecure: Boolean): ParsedGatewayCookie? {
    val parts = header?.split(';')?.map(String::trim)?.filter(String::isNotEmpty) ?: return null
    val pair = parts.firstOrNull() ?: return null
    val separator = pair.indexOf('=')
    if (separator <= 0 || pair.substring(0, separator) != GatewayFieldSession.COOKIE_NAME) return null
    val value = pair.substring(separator + 1)
    if (value.length !in 16..4_096 || value.any { it.code < 0x21 || it.code > 0x7e || it == ';' }) return null
    val attributes = parts.drop(1)
    if (attributes.none { it.equals("Path=/", ignoreCase = true) }) return null
    if (attributes.none { it.equals("HttpOnly", ignoreCase = true) }) return null
    if (attributes.none { it.equals("SameSite=Strict", ignoreCase = true) }) return null
    if (requireSecure && attributes.none { it.equals("Secure", ignoreCase = true) }) return null
    if (attributes.any { it.startsWith("Domain=", ignoreCase = true) }) return null
    val maxAge = attributes.firstNotNullOfOrNull { attribute ->
        attribute.substringAfter("Max-Age=", missingDelimiterValue = "")
            .takeIf { it.isNotEmpty() && attribute.startsWith("Max-Age=", ignoreCase = true) }
            ?.toLongOrNull()
    } ?: return null
    if (maxAge !in 1L..MAX_SESSION_AGE_SECONDS) return null
    return ParsedGatewayCookie("${GatewayFieldSession.COOKIE_NAME}=$value", maxAge)
}

class HttpUrlConnectionGatewaySessionTransport : GatewaySessionTransport {
    override fun postJson(url: String, body: String): GatewayHttpResponse {
        return postJson(url, emptyMap(), body)
    }

    override fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): GatewayHttpResponse {
        return request(
            url = url,
            method = "POST",
            headers = headers + mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Accept" to "application/json",
            ),
            body = body.toByteArray(Charsets.UTF_8),
        )
    }

    override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse {
        return request(url, "GET", headers + ("Accept" to "application/json"))
    }

    override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse {
        return request(url, "DELETE", headers + ("Accept" to "application/json"))
    }

    override fun deleteJson(url: String, body: String): GatewayHttpResponse {
        return request(
            url = url,
            method = "DELETE",
            headers = mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Accept" to "application/json",
            ),
            body = body.toByteArray(Charsets.UTF_8),
        )
    }

    private fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray? = null,
    ): GatewayHttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = false
            doInput = true
            doOutput = body != null
            headers.forEach(::setRequestProperty)
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return GatewayHttpResponse(
                statusCode = status,
                responseBody = stream?.use { it.readBoundedText(MAX_RESPONSE_BYTES) }.orEmpty(),
                headers = connection.headerFields
                    .filterKeys { it != null }
                    .mapValues { (name, values) ->
                        if (name.equals("Set-Cookie", ignoreCase = true)) {
                            values.firstOrNull { value ->
                                value.trimStart().startsWith("${GatewayFieldSession.COOKIE_NAME}=")
                            }.orEmpty()
                        } else {
                            values.firstOrNull().orEmpty()
                        }
                    },
            )
        } finally {
            connection.disconnect()
        }
    }
}

private fun InputStream.readBoundedText(maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4_096)
    while (true) {
        val read = read(buffer)
        if (read <= 0) break
        if (output.size() + read > maxBytes) throw IllegalStateException("gateway response too large")
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

private fun sha256Hex(value: String): String {
    val alphabet = "0123456789abcdef"
    return buildString {
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(alphabet[unsigned ushr 4])
                append(alphabet[unsigned and 0x0f])
            }
    }
}

private fun safeExpiryBound(nowEpochMs: Long, ttlSeconds: Long): Long =
    runCatching {
        Math.addExact(
            nowEpochMs,
            Math.addExact(
                Math.multiplyExact(ttlSeconds, 1_000L),
                MAX_SERVER_CLOCK_SKEW_MS,
            ),
        )
    }.getOrDefault(Long.MAX_VALUE)

private const val MAX_SESSION_AGE_SECONDS = 12L * 60L * 60L
private const val MAX_ACCESS_TTL_SECONDS = 60L * 60L
private const val MAX_REFRESH_IDLE_TTL_SECONDS = 90L * 24L * 60L * 60L
private const val MAX_REFRESH_ABSOLUTE_TTL_SECONDS = 365L * 24L * 60L * 60L
private const val MAX_SERVER_CLOCK_SKEW_MS = 5L * 60L * 1_000L
private const val MAX_RETRY_AFTER_MS = 5L * 60L * 1_000L
private const val MAX_RESPONSE_BYTES = 32 * 1024
private val ALLOWED_SERVER_ERROR_CODES = setOf(
    "field_long_lived_sessions_unavailable",
    "field_session_device_capacity_unavailable",
    "field_session_query_invalid",
    "field_session_request_invalid",
    "field_session_revoked",
    "field_session_storage_outcome_unknown",
    "field_session_storage_unavailable",
    "gateway_auth_required",
    "invalid_refresh_token",
    "refresh_rotation_limit_reached",
    "refresh_token_absolute_expired",
    "refresh_token_idle_expired",
    "refresh_token_reuse_detected",
)
