package kr.co.hanium.dreamup.walksafe.network

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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

class AndroidIntegratedConsentClient {
    fun fetchCurrentCall(
        gatewayBaseUrl: String,
        installationId: String,
        controlSecret: String,
    ): CancellableNetworkCall<IntegratedConsentConfirmation?> {
        val query =
            "?control=integrated-consent" +
                "&installation_id=${encoded(installationId)}" +
                "&policy_version=${encoded(INTEGRATED_CONSENT_POLICY_VERSION)}"
        return requestCall(
            endpoint = gatewayBaseUrl.trimEnd('/') + "/privacy/rights" + query,
            method = "GET",
            installationId = installationId,
            controlSecret = controlSecret,
            requestBody = null,
        )
    }

    fun saveCall(
        gatewayBaseUrl: String,
        session: GatewayFieldSession,
        installationId: String,
        controlSecret: String,
        requestId: String,
        clientRevision: Long,
        selections: IntegratedConsentSelections,
    ): CancellableNetworkCall<IntegratedConsentConfirmation?> {
        require(gatewayBaseUrl == session.gatewayBaseUrl) {
            "integrated consent origin must match the authenticated session"
        }
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
                "selections",
                JSONObject()
                    .put("raw_source_collection", selections.rawSourceCollection)
                    .put("automatic_reporting", selections.automaticReporting)
                    .put("mobile_network_transfer", selections.mobileNetworkTransfer)
                    .put("training_reuse", selections.trainingReuse),
            )
            .toString()
        return requestCall(
            endpoint = gatewayBaseUrl.trimEnd('/') +
                "/privacy/rights?control=integrated-consent",
            method = "PUT",
            installationId = installationId,
            controlSecret = controlSecret,
            session = session,
            requestBody = requestBody,
        )
    }

    private fun requestCall(
        endpoint: String,
        method: String,
        installationId: String,
        controlSecret: String,
        session: GatewayFieldSession? = null,
        requestBody: String?,
    ): CancellableNetworkCall<IntegratedConsentConfirmation?> =
        cancellableHttpCall { cancellation ->
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            cancellation.attach(connection)
            try {
                connection.apply {
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
                    if (requestBody != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                }
                if (requestBody != null) {
                    connection.outputStream.use { output ->
                        cancellation.attach(output)
                        try {
                            output.write(requestBody.toByteArray(Charsets.UTF_8))
                            output.flush()
                        } finally {
                            cancellation.detach(output)
                        }
                    }
                }
                val response = connection.readBoundedResponse(
                    INTEGRATED_CONSENT_MAX_RESPONSE_BYTES,
                    cancellation,
                )
                if (method == "GET" && response.statusCode == 404) return@cancellableHttpCall null
                if (response.statusCode !in 200..299) {
                    throw IntegratedConsentHttpException(response.statusCode, response.body)
                }
                validatedIntegratedConsentConfirmationOrNull(
                    body = response.body,
                    expectedInstallationId = installationId,
                    expectedControlSecret = controlSecret,
                ) ?: throw IntegratedConsentProtocolException()
            } finally {
                cancellation.detach(connection)
                connection.disconnect()
            }
        }
}

internal fun validatedIntegratedConsentConfirmationOrNull(
    body: String,
    expectedInstallationId: String,
    expectedControlSecret: String,
): IntegratedConsentConfirmation? {
    if (body.isBlank()) return null
    return runCatching {
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
                "receipt_sha256",
            ) ||
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
            )
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
            clientRevision = root.getLong("client_revision"),
            revision = root.getLong("revision"),
            selections = IntegratedConsentSelections(
                rawSourceCollection = selections.getBoolean("raw_source_collection"),
                automaticReporting = selections.getBoolean("automatic_reporting"),
                mobileNetworkTransfer = selections.getBoolean("mobile_network_transfer"),
                trainingReuse = selections.getBoolean("training_reuse"),
            ),
            confirmedAt = root.getString("confirmed_at"),
            receiptSha256 = root.getString("receipt_sha256"),
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

private fun encoded(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name())

class IntegratedConsentHttpException(
    val statusCode: Int,
    val errorBody: String,
) : IllegalStateException("integrated consent request failed: $statusCode")

class IntegratedConsentProtocolException :
    IllegalStateException("integrated consent response is malformed")

private const val INTEGRATED_CONSENT_MAX_RESPONSE_BYTES = 64 * 1024
