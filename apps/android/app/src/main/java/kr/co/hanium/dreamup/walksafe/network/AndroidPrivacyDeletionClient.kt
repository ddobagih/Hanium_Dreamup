package kr.co.hanium.dreamup.walksafe.network

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.session.ACCOUNT_DELETION_ACCESS_SECRET_HEADER
import kr.co.hanium.dreamup.walksafe.session.ACCOUNT_DELETION_REQUEST_SCHEMA_VERSION
import kr.co.hanium.dreamup.walksafe.session.ACCOUNT_DELETION_STATUS_SCHEMA_VERSION
import kr.co.hanium.dreamup.walksafe.session.DEVICE_DELETION_EVIDENCE_SCHEMA_VERSION
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionJournal
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionOverallStatus
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStatus
import kr.co.hanium.dreamup.walksafe.session.DeletionInventoryItem
import kr.co.hanium.dreamup.walksafe.session.DeletionItemState
import kr.co.hanium.dreamup.walksafe.session.DeletionItemStatus
import kr.co.hanium.dreamup.walksafe.session.validCanonicalWholeSecondUtcInstant
import org.json.JSONObject

data class DeviceDeletionEvidence(
    val requestId: String,
    val tombstoneId: String,
    val requestReceiptSha256: String,
    val installationId: String,
    val evidenceId: String,
    val clientRevision: Long,
    val expectedStatusRevision: Long,
    val result: String,
    val completedAt: String,
    val evidenceSha256: String,
)

class AndroidPrivacyDeletionClient {
    fun requestDeletionCall(
        gatewayBaseUrl: String,
        trustedGatewayOrigin: String,
        session: GatewayFieldSession,
        installationId: String,
        accessSecret: String,
        requestId: String,
        clientRevision: Long = 1L,
    ): CancellableNetworkCall<AccountDeletionStatus> =
        authenticatedDeletionRequestCall(
            gatewayBaseUrl = gatewayBaseUrl,
            trustedGatewayOrigin = trustedGatewayOrigin,
            session = session,
            installationId = installationId,
            accessSecret = accessSecret,
            requestId = requestId,
            clientRevision = clientRevision,
            acceptedResponseCodes = setOf(202),
            reauthenticationRequiredOnNotFound = false,
        )

    fun recoverDeletionRequestAcceptOrReplayCall(
        gatewayBaseUrl: String,
        trustedGatewayOrigin: String,
        session: GatewayFieldSession,
        installationId: String,
        accessSecret: String,
        requestId: String,
        clientRevision: Long = 1L,
    ): CancellableNetworkCall<AccountDeletionStatus> {
        require(session.sessionScope == GatewaySessionScope.ACCOUNT_DELETION_RECOVERY) {
            "account deletion recovery requires a recovery-scoped gateway session"
        }
        return authenticatedDeletionRequestCall(
            gatewayBaseUrl = gatewayBaseUrl,
            trustedGatewayOrigin = trustedGatewayOrigin,
            session = session,
            installationId = installationId,
            accessSecret = accessSecret,
            requestId = requestId,
            clientRevision = clientRevision,
            acceptedResponseCodes = setOf(200, 202),
            reauthenticationRequiredOnNotFound = true,
        )
    }

    private fun authenticatedDeletionRequestCall(
        gatewayBaseUrl: String,
        trustedGatewayOrigin: String,
        session: GatewayFieldSession,
        installationId: String,
        accessSecret: String,
        requestId: String,
        clientRevision: Long,
        acceptedResponseCodes: Set<Int>,
        reauthenticationRequiredOnNotFound: Boolean,
    ): CancellableNetworkCall<AccountDeletionStatus> {
        require(gatewayBaseUrl == trustedGatewayOrigin) {
            "account deletion journal origin must match the current trusted gateway origin"
        }
        require(gatewayBaseUrl == session.gatewayBaseUrl) {
            "account deletion origin must match the authenticated session"
        }
        require(validDeletionAccessSecret(accessSecret))
        val body = accountDeletionRequestBody(requestId, clientRevision)
        return requestCall(
            endpoint = gatewayBaseUrl.trimEnd('/') + "/privacy/account-deletions",
            method = "POST",
            installationId = installationId,
            accessSecret = accessSecret,
            requestId = requestId,
            session = session,
            requestBody = body,
            acceptedResponseCodes = acceptedResponseCodes,
            reauthenticationRequiredOnNotFound = reauthenticationRequiredOnNotFound,
        )
    }

    fun fetchDeletionStatusCall(
        journal: AccountDeletionJournal,
        trustedGatewayOrigin: String,
        accessSecret: String,
    ): CancellableNetworkCall<AccountDeletionStatus> {
        require(journal.gatewayOrigin == trustedGatewayOrigin) {
            "account deletion journal origin must match the current trusted gateway origin"
        }
        require(validDeletionAccessSecret(accessSecret))
        require(REQUEST_ID.matches(journal.requestId))
        return requestCall(
            endpoint = journal.gatewayOrigin.trimEnd('/') +
                "/privacy/account-deletions/${journal.requestId}/status",
            method = "GET",
            installationId = journal.installationId,
            accessSecret = accessSecret,
            requestId = journal.requestId,
            session = null,
            requestBody = null,
            acceptedResponseCodes = setOf(200),
        )
    }

    fun replayDeletionRequestCall(
        journal: AccountDeletionJournal,
        trustedGatewayOrigin: String,
        accessSecret: String,
    ): CancellableNetworkCall<AccountDeletionStatus> {
        require(journal.gatewayOrigin == trustedGatewayOrigin)
        require(validDeletionAccessSecret(accessSecret))
        return requestCall(
            endpoint = journal.gatewayOrigin.trimEnd('/') + "/privacy/account-deletions",
            method = "POST",
            installationId = journal.installationId,
            accessSecret = accessSecret,
            requestId = journal.requestId,
            session = null,
            requestBody = accountDeletionRequestBody(
                journal.requestId,
                journal.clientRevision,
            ),
            acceptedResponseCodes = setOf(200),
        )
    }

    fun submitDeviceDeletionEvidenceCall(
        journal: AccountDeletionJournal,
        trustedGatewayOrigin: String,
        accessSecret: String,
        evidence: DeviceDeletionEvidence,
    ): CancellableNetworkCall<AccountDeletionStatus> {
        require(journal.gatewayOrigin == trustedGatewayOrigin)
        require(validDeletionAccessSecret(accessSecret))
        require(evidence.requestId == journal.requestId)
        require(evidence.installationId == journal.installationId)
        require(evidence.tombstoneId == journal.tombstoneId)
        require(evidence.requestReceiptSha256 == journal.requestReceiptSha256)
        require(evidence.clientRevision == journal.clientRevision)
        require(evidence.expectedStatusRevision > 0L)
        require(evidence.evidenceSha256 == deviceDeletionEvidenceSha256(evidence))
        return requestCall(
            endpoint = journal.gatewayOrigin.trimEnd('/') +
                "/privacy/account-deletions/${journal.requestId}/device-evidence",
            method = "POST",
            installationId = journal.installationId,
            accessSecret = accessSecret,
            requestId = journal.requestId,
            session = null,
            requestBody = deviceDeletionEvidenceBody(evidence),
            acceptedResponseCodes = setOf(200),
        )
    }

    private fun requestCall(
        endpoint: String,
        method: String,
        installationId: String,
        accessSecret: String,
        requestId: String,
        session: GatewayFieldSession?,
        requestBody: String?,
        acceptedResponseCodes: Set<Int>,
        reauthenticationRequiredOnNotFound: Boolean = false,
    ): CancellableNetworkCall<AccountDeletionStatus> =
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
                    setRequestProperty(ACCOUNT_DELETION_ACCESS_SECRET_HEADER, accessSecret)
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
                    ACCOUNT_DELETION_MAX_RESPONSE_BYTES,
                    cancellation,
                )
                if (response.statusCode == 404 && reauthenticationRequiredOnNotFound) {
                    throw AccountDeletionReauthenticationRequiredException(response.body)
                }
                if (response.statusCode !in acceptedResponseCodes) {
                    throw AccountDeletionHttpException(response.statusCode, response.body)
                }
                validatedAccountDeletionStatusOrNull(
                    body = response.body,
                    expectedInstallationId = installationId,
                    expectedRequestId = requestId,
                ) ?: throw AccountDeletionProtocolException()
            } finally {
                cancellation.detach(connection)
                connection.disconnect()
            }
        }
}

internal fun accountDeletionRequestBody(requestId: String, clientRevision: Long): String {
    require(REQUEST_ID.matches(requestId) && clientRevision > 0L)
    return buildString {
        append("{\"schema_version\":")
        append(JSONObject.quote(ACCOUNT_DELETION_REQUEST_SCHEMA_VERSION))
        append(",\"request_id\":")
        append(JSONObject.quote(requestId))
        append(",\"client_revision\":")
        append(clientRevision)
        append(",\"confirmation\":\"DELETE_MY_ACCOUNT\"}")
    }
}

internal fun deviceDeletionEvidenceSha256(evidence: DeviceDeletionEvidence): String {
    val statement = deviceDeletionEvidenceStatement(evidence)
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("walksafe.device-deletion-evidence.v2\u0000".toByteArray(Charsets.UTF_8))
    digest.update(statement.toByteArray(Charsets.UTF_8))
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal fun deviceDeletionEvidenceBody(evidence: DeviceDeletionEvidence): String =
    deviceDeletionEvidenceStatement(evidence).dropLast(1) +
        ",\"evidence_sha256\":${JSONObject.quote(evidence.evidenceSha256)}}"

private fun deviceDeletionEvidenceStatement(evidence: DeviceDeletionEvidence): String {
    require(REQUEST_ID.matches(evidence.requestId))
    require(OPAQUE_ID.matches(evidence.tombstoneId))
    require(SHA256.matches(evidence.requestReceiptSha256))
    require(OPAQUE_ID.matches(evidence.installationId))
    require(OPAQUE_ID.matches(evidence.evidenceId))
    require(evidence.clientRevision > 0L && evidence.expectedStatusRevision > 0L)
    require(evidence.result in setOf("DELETED", "NOT_FOUND", "FAILED"))
    require(validCanonicalWholeSecondUtcInstant(evidence.completedAt))
    return buildString {
        append("{\"schema_version\":")
        append(JSONObject.quote(DEVICE_DELETION_EVIDENCE_SCHEMA_VERSION))
        append(",\"request_id\":")
        append(JSONObject.quote(evidence.requestId))
        append(",\"tombstone_id\":")
        append(JSONObject.quote(evidence.tombstoneId))
        append(",\"request_receipt_sha256\":")
        append(JSONObject.quote(evidence.requestReceiptSha256))
        append(",\"installation_id\":")
        append(JSONObject.quote(evidence.installationId))
        append(",\"evidence_id\":")
        append(JSONObject.quote(evidence.evidenceId))
        append(",\"client_revision\":")
        append(evidence.clientRevision)
        append(",\"expected_status_revision\":")
        append(evidence.expectedStatusRevision)
        append(",\"item\":\"device_untransmitted_data\"")
        append(",\"result\":")
        append(JSONObject.quote(evidence.result))
        append(",\"completed_at\":")
        append(JSONObject.quote(evidence.completedAt))
        append('}')
    }
}

internal fun validatedAccountDeletionStatusOrNull(
    body: String,
    expectedInstallationId: String,
    expectedRequestId: String,
): AccountDeletionStatus? {
    if (body.isBlank()) return null
    return runCatching {
        val root = JSONObject(body)
        if (
            root.deletionJsonKeys() != setOf(
                "schema_version",
                "request_id",
                "client_revision",
                "revision",
                "accepted_at",
                "updated_at",
                "account_generation",
                "tombstone_id",
                "request_receipt_sha256",
                "overall_status",
                "items",
                "completion_receipt_sha256",
            ) ||
            root.getString("schema_version") != ACCOUNT_DELETION_STATUS_SCHEMA_VERSION ||
            root.getString("request_id") != expectedRequestId
        ) return null
        val array = root.getJSONArray("items")
        if (array.length() != DeletionInventoryItem.entries.size) return null
        val parsed = linkedMapOf<DeletionInventoryItem, DeletionItemStatus>()
        for (index in 0 until array.length()) {
            val itemJson = array.getJSONObject(index)
            if (
                itemJson.deletionJsonKeys() != setOf(
                    "key",
                    "status",
                    "item_revision",
                    "due_at",
                    "updated_at",
                    "evidence_sha256",
                    "disposition_basis",
                    "retry_after",
                    "restriction_reason",
                    "legal_hold_review_at",
                    "legal_hold_contact",
                    "terminal_at",
                )
            ) return null
            val expectedItem = DeletionInventoryItem.entries[index]
            if (itemJson.getString("key") != expectedItem.wireValue) return null
            val state = DeletionItemState.fromWireValue(itemJson.getString("status"))
                ?: return null
            parsed[expectedItem] = DeletionItemStatus(
                item = expectedItem,
                state = state,
                itemRevision = itemJson.strictLong("item_revision"),
                dueAt = itemJson.getString("due_at"),
                updatedAt = itemJson.getString("updated_at"),
                evidenceSha256 = itemJson.nullableString("evidence_sha256"),
                dispositionBasis = itemJson.nullableString("disposition_basis"),
                nextRetryAt = itemJson.nullableString("retry_after"),
                reasonCode = itemJson.nullableString("restriction_reason"),
                legalHoldReviewAt = itemJson.nullableString("legal_hold_review_at"),
                contactUrl = itemJson.nullableString("legal_hold_contact"),
                terminalAt = itemJson.nullableString("terminal_at"),
            )
        }
        AccountDeletionStatus(
            schemaVersion = root.getString("schema_version"),
            installationId = expectedInstallationId,
            requestId = root.getString("request_id"),
            clientRevision = root.strictLong("client_revision"),
            revision = root.strictLong("revision"),
            requestedAt = root.getString("accepted_at"),
            updatedAt = root.getString("updated_at"),
            accountGeneration = root.strictLong("account_generation"),
            tombstoneId = root.getString("tombstone_id"),
            requestReceiptSha256 = root.getString("request_receipt_sha256"),
            overallStatus = AccountDeletionOverallStatus.fromWireValue(
                root.getString("overall_status"),
            ) ?: return null,
            items = parsed,
            receiptSha256 = root.nullableString("completion_receipt_sha256"),
        )
    }.getOrNull()
}

private fun JSONObject.deletionJsonKeys(): Set<String> {
    val result = mutableSetOf<String>()
    val iterator = keys()
    while (iterator.hasNext()) result += iterator.next()
    return result
}

private fun JSONObject.nullableString(name: String): String? =
    if (isNull(name)) null else getString(name)

private fun JSONObject.strictLong(name: String): Long =
    when (val value = get(name)) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException("$name must be an integer")
    }

internal fun validDeletionAccessSecret(value: String): Boolean =
    ACCESS_SECRET.matches(value) && runCatching {
        val decoded = java.util.Base64.getUrlDecoder().decode(value)
        decoded.size == 32 &&
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value
    }.getOrDefault(false)

internal fun accountDeletionTerminalConflictCodeOrNull(body: String): String? =
    runCatching {
        val root = JSONObject(body)
        if (root.deletionJsonKeys() == setOf("code")) {
            root.getString("code").takeIf(
                ACCOUNT_DELETION_TERMINAL_CONFLICT_CODES::contains,
            )
        } else {
            null
        }
    }.getOrNull()

internal sealed interface AccountDeletionHttpFailureDisposition {
    data class TerminalConflict(val code: String) :
        AccountDeletionHttpFailureDisposition

    data class Retry(val code: String) : AccountDeletionHttpFailureDisposition
}

internal fun accountDeletionHttpFailureDisposition(
    statusCode: Int,
    body: String,
): AccountDeletionHttpFailureDisposition {
    val terminalConflictCode = if (statusCode == 409) {
        accountDeletionTerminalConflictCodeOrNull(body)
    } else {
        null
    }
    return if (terminalConflictCode != null) {
        AccountDeletionHttpFailureDisposition.TerminalConflict(
            terminalConflictCode,
        )
    } else {
        AccountDeletionHttpFailureDisposition.Retry("http_$statusCode")
    }
}

open class AccountDeletionHttpException(
    val statusCode: Int,
    val errorBody: String,
) : IllegalStateException("account deletion request failed: $statusCode")

class AccountDeletionReauthenticationRequiredException(
    errorBody: String,
) : AccountDeletionHttpException(404, errorBody)

class AccountDeletionProtocolException :
    IllegalStateException("account deletion response is malformed")

internal const val ACCOUNT_DELETION_MAX_RESPONSE_BYTES = 128 * 1024
private val ACCESS_SECRET = Regex("^[A-Za-z0-9_-]{43}$")
private val REQUEST_ID = Regex("^[A-Za-z0-9_-]{16,128}$")
private val OPAQUE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
private val SHA256 = Regex("^[0-9a-f]{64}$")
internal val ACCOUNT_DELETION_TERMINAL_CONFLICT_CODES = setOf(
    "account_deletion_request_conflict",
    "account_deletion_client_revision_invalid",
    "account_generation_tombstoned",
    "account_deletion_installation_inventory_missing",
    "account_deletion_operation_conflict",
    "account_deletion_already_completed",
    "device_deletion_installation_not_targeted",
    "device_deletion_installation_already_terminal",
    "account_deletion_upstream_conflict",
)
