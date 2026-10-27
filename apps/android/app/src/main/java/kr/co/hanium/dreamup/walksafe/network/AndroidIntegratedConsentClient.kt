package kr.co.hanium.dreamup.walksafe.network

import java.net.URLEncoder
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_AUTOMATIC_ITEM_VERSION
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_MOBILE_ITEM_VERSION
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_POLICY_VERSION
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_RAW_ITEM_VERSION
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_TRAINING_ITEM_VERSION
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItemVersions
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSelections
import org.json.JSONObject

const val CONSENT_INSTALLATION_HEADER = "x-walksafe-consent-installation-id"
const val CONSENT_POLICY_HEADER = "x-walksafe-consent-policy-version"
const val CONSENT_REVISION_HEADER = "x-walksafe-consent-revision"
const val CONSENT_RECEIPT_HEADER = "x-walksafe-consent-receipt-sha256"
const val CONSENT_CONTROL_SECRET_HEADER = "x-walksafe-consent-control-secret"
const val CONSENT_NETWORK_TRANSPORT_HEADER = "x-walksafe-network-transport"

enum class IntegratedConsentNetworkTransport(val wireValue: String) {
    WIFI("wifi"),
    CELLULAR("cellular"),
}

enum class IntegratedConsentBootstrapStatus {
    READY,
    RECONSENT_REQUIRED,
}

enum class IntegratedConsentBootstrapSource {
    CURRENT_CONSENT,
    SIGNUP_CONSENT,
    NONE,
}

data class IntegratedConsentBootstrap(
    val status: IntegratedConsentBootstrapStatus,
    val source: IntegratedConsentBootstrapSource,
    val installationId: String,
    val clientRevisionFloor: Long,
    val selections: IntegratedConsentSelections?,
    val sourceReceiptSha256: String?,
    val expectedPreviousBackendReceiptSha256: String?,
) {
    init {
        require(clientRevisionFloor >= 0L)
        require(
            sourceReceiptSha256 == null || SHA256.matches(sourceReceiptSha256),
        )
        require(
            expectedPreviousBackendReceiptSha256 == null ||
                SHA256.matches(expectedPreviousBackendReceiptSha256),
        )
        require(
            when (status) {
                IntegratedConsentBootstrapStatus.READY ->
                    source != IntegratedConsentBootstrapSource.NONE &&
                        selections != null &&
                        sourceReceiptSha256 != null
                IntegratedConsentBootstrapStatus.RECONSENT_REQUIRED ->
                    source == IntegratedConsentBootstrapSource.NONE &&
                        selections == null &&
                        sourceReceiptSha256 == null
            },
        )
        require(
            when (source) {
                IntegratedConsentBootstrapSource.CURRENT_CONSENT ->
                    status == IntegratedConsentBootstrapStatus.READY &&
                        sourceReceiptSha256 == expectedPreviousBackendReceiptSha256
                IntegratedConsentBootstrapSource.SIGNUP_CONSENT ->
                    status == IntegratedConsentBootstrapStatus.READY &&
                        clientRevisionFloor == 0L &&
                        expectedPreviousBackendReceiptSha256 == null
                IntegratedConsentBootstrapSource.NONE ->
                    status == IntegratedConsentBootstrapStatus.RECONSENT_REQUIRED
            },
        )
        require(
            expectedPreviousBackendReceiptSha256 != null || clientRevisionFloor == 0L,
        )
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

class AndroidIntegratedConsentClient(
    private val installationPublicKey: ((String, String, String, Long) -> String)? = null,
    private val markInstallationKeyRegistered: ((String, String, String, Long) -> Unit)? = null,
) {
    fun fetchBootstrapCall(
        gatewayBaseUrl: String,
        session: GatewayFieldSession,
        installationId: String,
    ): CancellableNetworkCall<IntegratedConsentBootstrap> {
        require(gatewayBaseUrl == session.gatewayBaseUrl) {
            "integrated consent bootstrap origin must match the authenticated session"
        }
        val authenticationRequest = captureGatewayAuthenticationRequest(session)
        val query =
            "?control=integrated-consent-bootstrap" +
                "&installation_id=${encoded(installationId)}" +
                "&policy_version=${encoded(INTEGRATED_CONSENT_POLICY_VERSION)}"
        return cancellableHttpCall { cancellation ->
            withJsonHttpExchange(
                cancellation = cancellation,
                endpoint = gatewayBaseUrl.trimEnd('/') + "/privacy/rights" + query,
                requestBody = null,
                maximumResponseBytes = INTEGRATED_CONSENT_MAX_RESPONSE_BYTES,
                configure = {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    doInput = true
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Connection", "close")
                    session.requestHeaders().forEach(::setRequestProperty)
                },
            ) { response ->
                if (response.statusCode !in 200..299) {
                    cancellation.throwIfCancelled()
                    consumeGatewayAuthenticationStaleResponse(authenticationRequest, session, response)
                    throw IntegratedConsentHttpException(response.statusCode, response.body)
                }
                validatedIntegratedConsentBootstrapOrNull(
                    body = response.body,
                    expectedInstallationId = installationId,
                ) ?: throw IntegratedConsentProtocolException()
            }
        }
    }

    fun fetchCurrentCall(
        gatewayBaseUrl: String,
        session: GatewayFieldSession,
        installationId: String,
        controlSecret: String,
    ): CancellableNetworkCall<IntegratedConsentConfirmation?> {
        require(gatewayBaseUrl == session.gatewayBaseUrl) {
            "integrated consent read origin must match the authenticated session"
        }
        val query =
            "?control=integrated-consent" +
                "&installation_id=${encoded(installationId)}" +
                "&policy_version=${encoded(INTEGRATED_CONSENT_POLICY_VERSION)}"
        return afterConfirmedConsent(session, requestCall(
            endpoint = gatewayBaseUrl.trimEnd('/') + "/privacy/rights" + query,
            method = "GET",
            installationId = installationId,
            controlSecret = controlSecret,
            session = session,
            requestBody = null,
        ))
    }

    fun saveCall(
        gatewayBaseUrl: String,
        session: GatewayFieldSession,
        installationId: String,
        controlSecret: String,
        requestId: String,
        clientRevision: Long,
        selections: IntegratedConsentSelections,
        expectedPreviousBackendReceiptSha256: String?,
    ): CancellableNetworkCall<IntegratedConsentConfirmation?> {
        require(gatewayBaseUrl == session.gatewayBaseUrl) {
            "integrated consent origin must match the authenticated session"
        }
        val authenticationRequest = captureGatewayAuthenticationRequest(session)
        val requestBody = JSONObject()
            .put("schema_version", "walksafe.integrated-consent-request.v1")
            .put("installation_id", installationId)
            .put("request_id", requestId)
            .put("policy_version", INTEGRATED_CONSENT_POLICY_VERSION)
            .put(
                "item_versions",
                JSONObject()
                    .put(
                        "raw_source_collection",
                        INTEGRATED_CONSENT_RAW_ITEM_VERSION,
                    )
                    .put(
                        "automatic_reporting",
                        INTEGRATED_CONSENT_AUTOMATIC_ITEM_VERSION,
                    )
                    .put(
                        "mobile_network_transfer",
                        INTEGRATED_CONSENT_MOBILE_ITEM_VERSION,
                    )
                    .put(
                        "training_reuse",
                        INTEGRATED_CONSENT_TRAINING_ITEM_VERSION,
                    ),
            )
            .put("client_revision", clientRevision)
            .put(
                "expected_previous_backend_receipt_sha256",
                expectedPreviousBackendReceiptSha256 ?: JSONObject.NULL,
            )
            .put(
                "selections",
                JSONObject()
                    .put("raw_source_collection", selections.rawSourceCollection)
                    .put("automatic_reporting", selections.automaticReporting)
                    .put("mobile_network_transfer", selections.mobileNetworkTransfer)
                    .put("training_reuse", selections.trainingReuse),
            )
            .toString()
        return afterConfirmedConsent(session, requestCall(
            endpoint = gatewayBaseUrl.trimEnd('/') +
                "/privacy/rights?control=integrated-consent",
            method = "PUT",
            installationId = installationId,
            controlSecret = controlSecret,
            session = session,
            requestBody = requestBody,
            authenticationRequest = authenticationRequest,
        ))
    }

    /** Registration is installation authority metadata, never a replacement for consent. */
    fun registerInstallationEvidenceKeyCall(
        session: GatewayFieldSession,
        confirmation: IntegratedConsentConfirmation,
        isCurrent: () -> Boolean,
    ): CancellableNetworkCall<Unit> {
        require(session.sessionScope == GatewaySessionScope.GENERAL)
        val generation = session.accountGenerationOrNull
            ?: throw IllegalStateException("Installation evidence account generation is unknown")
        require(generation > 0L && session.isUsableFor(session.actorId))
        require(!session.isBackendAccountDeviceBound || session.deviceId == confirmation.installationId)
        require(confirmation.schemaVersion == INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION &&
            confirmation.policyVersion == INTEGRATED_CONSENT_POLICY_VERSION &&
            confirmation.itemVersions == IntegratedConsentItemVersions())
        val provider = installationPublicKey ?: error("Installation evidence key provider is unavailable")
        return cancellableHttpCall(deadlineMs = 20_000L) { cancellation ->
            fun ensureCurrent() {
                cancellation.throwIfCancelled()
                if (!isCurrent() || !session.isUsableFor(session.actorId)) throw CancellationException("Installation evidence owner changed")
            }
            ensureCurrent()
            val key = provider(confirmation.installationId, session.gatewayBaseUrl, session.actorId, generation)
            require(validInstallationEvidencePublicKey(key))
            ensureCurrent()
            val body = JSONObject().put("installation_id", confirmation.installationId).put("public_key", key).toString()
            withJsonHttpExchange(
                cancellation = cancellation,
                endpoint = session.gatewayBaseUrl.trimEnd('/') + "/privacy/rights?control=installation-evidence-key",
                requestBody = body,
                maximumResponseBytes = 4_096,
                configure = {
                    requestMethod = "PUT"; connectTimeout = 8_000; readTimeout = 12_000
                    doInput = true; instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty(CONSENT_CONTROL_SECRET_HEADER, confirmation.controlSecret)
                    session.requestHeaders().forEach(::setRequestProperty)
                },
            ) { response ->
                ensureCurrent()
                if (response.statusCode != 204 || response.body.isNotEmpty()) {
                    throw IntegratedConsentHttpException(response.statusCode, response.body)
                }
                markInstallationKeyRegistered?.invoke(confirmation.installationId, session.gatewayBaseUrl, session.actorId, generation)
                Unit
            }
        }
    }

    private fun afterConfirmedConsent(
        session: GatewayFieldSession,
        consentCall: CancellableNetworkCall<IntegratedConsentConfirmation?>,
    ): CancellableNetworkCall<IntegratedConsentConfirmation?> {
        if (installationPublicKey == null) return consentCall
        val request = captureGatewayAuthenticationRequest(session)
        val registration = AtomicReference<CancellableNetworkCall<Unit>?>()
        lateinit var outer: CancellableNetworkCall<IntegratedConsentConfirmation?>
        outer = CancellableNetworkCall(
            executeBlock = {
                val confirmation = consentCall.execute()
                if (confirmation != null && !outer.isCancelled()) {
                    // A server-accepted consent remains accepted if the optional key registration fails.
                    try {
                        val call = registerInstallationEvidenceKeyCall(session, confirmation) {
                            !outer.isCancelled() && request != null &&
                                GatewaySessionProcessCoordinator.isCurrentGeneralSession(request.generation, session)
                        }
                        registration.set(call)
                        if (outer.isCancelled()) call.cancel()
                        call.execute()
                    } catch (_: Exception) {
                        // Registration acknowledgement is held separately by the durable key provider.
                    } finally { registration.set(null) }
                }
                confirmation
            },
            cancelBlock = { consentCall.cancel(); registration.getAndSet(null)?.cancel() },
        )
        return outer
    }

    private fun requestCall(
        endpoint: String,
        method: String,
        installationId: String,
        controlSecret: String,
        session: GatewayFieldSession? = null,
        requestBody: String?,
        authenticationRequest: GatewaySessionProcessSnapshot? = null,
    ): CancellableNetworkCall<IntegratedConsentConfirmation?> =
        cancellableHttpCall { cancellation ->
            withJsonHttpExchange(
                cancellation = cancellation,
                endpoint = endpoint,
                requestBody = requestBody,
                maximumResponseBytes = INTEGRATED_CONSENT_MAX_RESPONSE_BYTES,
                configure = {
                    requestMethod = method
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    doInput = true
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Connection", "close")
                    setRequestProperty(
                        CONSENT_CONTROL_SECRET_HEADER,
                        controlSecret,
                    )
                    session?.requestHeaders()?.forEach(::setRequestProperty)
                },
            ) { response ->
                if (method == "GET" && response.statusCode == 404) return@withJsonHttpExchange null
                if (response.statusCode !in 200..299) {
                    cancellation.throwIfCancelled()
                    consumeGatewayAuthenticationStaleResponse(authenticationRequest, session, response)
                    throw IntegratedConsentHttpException(response.statusCode, response.body)
                }
                validatedIntegratedConsentConfirmationOrNull(
                    body = response.body,
                    expectedInstallationId = installationId,
                    expectedControlSecret = controlSecret,
                ) ?: throw IntegratedConsentProtocolException()
            }
        }
}

internal fun validatedIntegratedConsentBootstrapOrNull(
    body: String,
    expectedInstallationId: String,
): IntegratedConsentBootstrap? {
    if (body.isBlank()) return null
    return runCatching {
        require(strictJsonObjectDocument(body)) { "invalid JSON response document" }
        val root = JSONObject(body)
        if (
            root.jsonKeys() != setOf(
                "schema_version",
                "status",
                "source",
                "installation_id",
                "policy_version",
                "item_versions",
                "client_revision_floor",
                "selections",
                "source_receipt_sha256",
                "expected_previous_backend_receipt_sha256",
            ) ||
            listOf(
                "schema_version",
                "status",
                "source",
                "installation_id",
                "policy_version",
            ).any { root.opt(it) !is String } ||
            root.getString("schema_version") !=
            "walksafe.integrated-consent-bootstrap.v1" ||
            root.getString("installation_id") != expectedInstallationId ||
            root.getString("policy_version") != INTEGRATED_CONSENT_POLICY_VERSION
        ) return null
        val itemVersions = root.optJSONObject("item_versions") ?: return null
        if (
            itemVersions.jsonKeys() != setOf(
                "raw_source_collection",
                "automatic_reporting",
                "mobile_network_transfer",
                "training_reuse",
            ) ||
            itemVersions.optString("raw_source_collection") !=
            INTEGRATED_CONSENT_RAW_ITEM_VERSION ||
            itemVersions.optString("automatic_reporting") !=
            INTEGRATED_CONSENT_AUTOMATIC_ITEM_VERSION ||
            itemVersions.optString("mobile_network_transfer") !=
            INTEGRATED_CONSENT_MOBILE_ITEM_VERSION ||
            itemVersions.optString("training_reuse") !=
            INTEGRATED_CONSENT_TRAINING_ITEM_VERSION
        ) return null
        val status = runCatching {
            IntegratedConsentBootstrapStatus.valueOf(root.getString("status"))
        }.getOrNull() ?: return null
        val source = runCatching {
            IntegratedConsentBootstrapSource.valueOf(root.getString("source"))
        }.getOrNull() ?: return null
        val floor = root.strictNonNegativeLongOrNull("client_revision_floor") ?: return null
        val selections = if (root.isNull("selections")) {
            null
        } else {
            val value = root.optJSONObject("selections") ?: return null
            if (
                value.jsonKeys() != setOf(
                    "raw_source_collection",
                    "automatic_reporting",
                    "mobile_network_transfer",
                    "training_reuse",
                ) ||
                value.jsonKeys().any { value.opt(it) !is Boolean }
            ) return null
            IntegratedConsentSelections(
                rawSourceCollection = value.getBoolean("raw_source_collection"),
                automaticReporting = value.getBoolean("automatic_reporting"),
                mobileNetworkTransfer = value.getBoolean("mobile_network_transfer"),
                trainingReuse = value.getBoolean("training_reuse"),
            )
        }
        val expectedPreviousReceipt = if (
            root.isNull("expected_previous_backend_receipt_sha256")
        ) {
            null
        } else {
            (root.opt("expected_previous_backend_receipt_sha256") as? String)
                ?.takeIf(SHA256::matches) ?: return null
        }
        val sourceReceipt = if (root.isNull("source_receipt_sha256")) {
            null
        } else {
            (root.opt("source_receipt_sha256") as? String)
                ?.takeIf(SHA256::matches) ?: return null
        }
        IntegratedConsentBootstrap(
            status = status,
            source = source,
            installationId = expectedInstallationId,
            clientRevisionFloor = floor,
            selections = selections,
            sourceReceiptSha256 = sourceReceipt,
            expectedPreviousBackendReceiptSha256 = expectedPreviousReceipt,
        )
    }.getOrNull()
}

internal fun validatedIntegratedConsentConfirmationOrNull(
    body: String,
    expectedInstallationId: String,
    expectedControlSecret: String,
): IntegratedConsentConfirmation? {
    if (body.isBlank()) return null
    return runCatching {
        require(strictJsonObjectDocument(body)) { "invalid JSON response document" }
        val root = JSONObject(body)
        if (
            root.jsonKeys() != setOf(
                "schema_version",
                "current",
                "installation_id",
                "request_id",
                "policy_version",
                "item_versions",
                "client_revision",
                "revision",
                "selections",
                "confirmed_at",
                "gateway_audit_record_sha256",
                "backend_consent_receipt_sha256",
            ) ||
            listOf(
                "schema_version",
                "installation_id",
                "request_id",
                "policy_version",
                "confirmed_at",
                "gateway_audit_record_sha256",
                "backend_consent_receipt_sha256",
            ).any { root.opt(it) !is String } ||
            root.opt("current") !is Boolean ||
            root.getString("schema_version") !=
            INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION ||
            !root.getBoolean("current") ||
            root.getString("installation_id") != expectedInstallationId ||
            root.getString("policy_version") != INTEGRATED_CONSENT_POLICY_VERSION
        ) {
            return null
        }
        val selections = root.getJSONObject("selections")
        val itemVersions = root.getJSONObject("item_versions")
        val clientRevision = root.strictPositiveLongOrNull("client_revision") ?: return null
        val revision = root.strictPositiveLongOrNull("revision") ?: return null
        if (
            selections.jsonKeys() != setOf(
                "raw_source_collection",
                "automatic_reporting",
                "mobile_network_transfer",
                "training_reuse",
            ) ||
            itemVersions.jsonKeys() != setOf(
                "raw_source_collection",
                "automatic_reporting",
                "mobile_network_transfer",
                "training_reuse",
            ) ||
            selections.jsonKeys().any { selections.opt(it) !is Boolean } ||
            itemVersions.jsonKeys().any { itemVersions.opt(it) !is String }
        ) {
            return null
        }
        IntegratedConsentConfirmation(
            schemaVersion = root.getString("schema_version"),
            policyVersion = root.getString("policy_version"),
            installationId = root.getString("installation_id"),
            requestId = root.getString("request_id"),
            itemVersions = IntegratedConsentItemVersions(
                rawSourceCollection =
                    itemVersions.getString("raw_source_collection"),
                automaticReporting =
                    itemVersions.getString("automatic_reporting"),
                mobileNetworkTransfer =
                    itemVersions.getString("mobile_network_transfer"),
                trainingReuse = itemVersions.getString("training_reuse"),
            ),
            clientRevision = clientRevision,
            revision = revision,
            selections = IntegratedConsentSelections(
                rawSourceCollection = selections.getBoolean("raw_source_collection"),
                automaticReporting = selections.getBoolean("automatic_reporting"),
                mobileNetworkTransfer = selections.getBoolean("mobile_network_transfer"),
                trainingReuse = selections.getBoolean("training_reuse"),
            ),
            confirmedAt = root.getString("confirmed_at"),
            gatewayAuditRecordSha256 =
                root.getString("gateway_audit_record_sha256"),
            backendConsentReceiptSha256 =
                root.getString("backend_consent_receipt_sha256"),
            controlSecret = expectedControlSecret,
        )
    }.getOrNull()
}

private fun JSONObject.jsonKeys(): Set<String> {
    val result = mutableSetOf<String>()
    val iterator = keys()
    while (iterator.hasNext()) result += iterator.next()
    return result
}

private fun JSONObject.strictPositiveLongOrNull(name: String): Long? =
    when (val value = opt(name)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }?.takeIf { it > 0L }

private fun JSONObject.strictNonNegativeLongOrNull(name: String): Long? =
    when (val value = opt(name)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }?.takeIf { it >= 0L }

private fun encoded(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name())

class IntegratedConsentHttpException(
    val statusCode: Int,
    val errorBody: String,
) : IllegalStateException("integrated consent request failed: $statusCode") {
    val serverCode: String? = runCatching {
        JSONObject(errorBody).optJSONObject("detail")?.optString("code")
            ?.takeIf { it.matches(Regex("^[a-z][a-z0-9_]{0,63}$")) }
    }.getOrNull()
    val requiredPolicyVersion: String? = runCatching {
        JSONObject(errorBody).optJSONObject("detail")
            ?.optString("required_policy_version")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

class IntegratedConsentProtocolException :
    IllegalStateException("integrated consent response is malformed")

private const val INTEGRATED_CONSENT_MAX_RESPONSE_BYTES = 64 * 1024
private val SHA256 = Regex("^[0-9a-f]{64}$")
