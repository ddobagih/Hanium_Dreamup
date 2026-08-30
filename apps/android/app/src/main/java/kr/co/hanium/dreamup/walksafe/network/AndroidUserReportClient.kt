package kr.co.hanium.dreamup.walksafe.network

import java.net.HttpURLConnection
import java.net.URL
import kr.co.hanium.dreamup.walksafe.report.UserReportDetail
import kr.co.hanium.dreamup.walksafe.report.UserReportContentCurrent
import kr.co.hanium.dreamup.walksafe.report.UserReportContentRevision
import kr.co.hanium.dreamup.walksafe.report.UserReportCorrectionIntent
import kr.co.hanium.dreamup.walksafe.report.UserReportCorrectionPatch
import kr.co.hanium.dreamup.walksafe.report.UserReportDeletionStatus
import kr.co.hanium.dreamup.walksafe.report.UserReportListPage
import kr.co.hanium.dreamup.walksafe.report.UserReportRequestIntent
import kr.co.hanium.dreamup.walksafe.report.UserReportRequestSummary
import kr.co.hanium.dreamup.walksafe.report.UserReportStatus
import kr.co.hanium.dreamup.walksafe.report.validCanonicalUserReportUuid
import kr.co.hanium.dreamup.walksafe.report.validUserReportCursor
import kr.co.hanium.dreamup.walksafe.report.canonicalUserReportCorrectionDescriptionOrNull
import kr.co.hanium.dreamup.walksafe.report.validatedUserReportContentOrNull
import kr.co.hanium.dreamup.walksafe.report.validatedUserReportContentRevisionOrNull
import kr.co.hanium.dreamup.walksafe.report.validatedUserReportDeletionStatusOrNull
import kr.co.hanium.dreamup.walksafe.report.validatedUserReportDetailOrNull
import kr.co.hanium.dreamup.walksafe.report.validatedUserReportListOrNull
import kr.co.hanium.dreamup.walksafe.report.validatedUserReportRequestOrNull
import org.json.JSONObject

internal interface UserReportNetworkClient {
    fun listReportsCall(
        session: GatewayFieldSession,
        limit: Int,
        cursor: String?,
        userStatus: UserReportStatus?,
    ): CancellableNetworkCall<UserReportListPage>

    fun reportDetailCall(
        session: GatewayFieldSession,
        reportId: String,
    ): CancellableNetworkCall<UserReportDetail>

    fun createRequestCall(
        session: GatewayFieldSession,
        intent: UserReportRequestIntent,
    ): CancellableNetworkCall<UserReportRequestSummary>

    fun reportContentCall(
        session: GatewayFieldSession,
        reportId: String,
    ): CancellableNetworkCall<UserReportContentCurrent>

    fun correctReportContentCall(
        session: GatewayFieldSession,
        intent: UserReportCorrectionIntent,
    ): CancellableNetworkCall<UserReportContentRevision>

    fun reportDeletionStatusCall(
        session: GatewayFieldSession,
        requestId: String,
    ): CancellableNetworkCall<UserReportDeletionStatus>
}

internal class AndroidUserReportClient : UserReportNetworkClient {
    override fun listReportsCall(
        session: GatewayFieldSession,
        limit: Int,
        cursor: String?,
        userStatus: UserReportStatus?,
    ): CancellableNetworkCall<UserReportListPage> {
        require(limit in 1..100)
        require(cursor == null || validUserReportCursor(cursor))
        val endpoint = buildString {
            append(session.gatewayBaseUrl.trimEnd('/'))
            append(USER_REPORT_LIST_PATH)
            append("?limit=")
            append(limit)
            cursor?.let {
                append("&cursor=")
                append(it)
            }
            userStatus?.let {
                append("&user_status=")
                append(it.wireValue)
            }
        }
        return requestCall(
            session = session,
            endpoint = endpoint,
            method = "GET",
            requestBody = null,
            acceptedStatusCodes = setOf(200),
        ) { body -> validatedUserReportListOrNull(body) }
    }

    override fun reportDetailCall(
        session: GatewayFieldSession,
        reportId: String,
    ): CancellableNetworkCall<UserReportDetail> {
        require(validCanonicalUserReportUuid(reportId))
        val endpoint = session.gatewayBaseUrl.trimEnd('/') +
            "$USER_REPORT_LIST_PATH/$reportId"
        return requestCall(
            session = session,
            endpoint = endpoint,
            method = "GET",
            requestBody = null,
            acceptedStatusCodes = setOf(200),
        ) { body ->
            validatedUserReportDetailOrNull(body)?.takeIf { it.reportId == reportId }
        }
    }

    override fun createRequestCall(
        session: GatewayFieldSession,
        intent: UserReportRequestIntent,
    ): CancellableNetworkCall<UserReportRequestSummary> {
        val endpoint = session.gatewayBaseUrl.trimEnd('/') +
            "$USER_REPORT_LIST_PATH/${intent.reportId}/requests"
        val body = JSONObject()
            .put("client_request_id", intent.clientRequestId)
            .put("request_type", intent.requestType.wireValue)
            .put("request_text", intent.requestText)
            .toString()
        return requestCall(
            session = session,
            endpoint = endpoint,
            method = "POST",
            requestBody = body,
            acceptedStatusCodes = setOf(200, 201),
        ) { responseBody -> validatedUserReportRequestOrNull(responseBody) }
    }

    override fun reportContentCall(
        session: GatewayFieldSession,
        reportId: String,
    ): CancellableNetworkCall<UserReportContentCurrent> {
        require(validCanonicalUserReportUuid(reportId))
        return requestCall(
            session = session,
            endpoint = session.gatewayBaseUrl.trimEnd('/') +
                "$USER_REPORT_LIST_PATH/$reportId/content",
            method = "GET",
            requestBody = null,
            acceptedStatusCodes = setOf(200),
        ) { body ->
            validatedUserReportContentOrNull(body)?.takeIf { it.reportId == reportId }
        }
    }

    override fun correctReportContentCall(
        session: GatewayFieldSession,
        intent: UserReportCorrectionIntent,
    ): CancellableNetworkCall<UserReportContentRevision> {
        val body = JSONObject()
            .put("expected_revision", intent.expectedRevision)
            .put("idempotency_key", intent.idempotencyKey)
            .also { root ->
                when (val patch = intent.userDescription) {
                    UserReportCorrectionPatch.Omitted -> Unit
                    UserReportCorrectionPatch.Clear -> root.put(
                        "user_description",
                        JSONObject.NULL,
                    )
                    is UserReportCorrectionPatch.Value -> root.put(
                        "user_description",
                        requireNotNull(
                            canonicalUserReportCorrectionDescriptionOrNull(patch.value),
                        ),
                    )
                }
                when (val patch = intent.categoryHint) {
                    UserReportCorrectionPatch.Omitted -> Unit
                    UserReportCorrectionPatch.Clear -> root.put("category_hint", JSONObject.NULL)
                    is UserReportCorrectionPatch.Value ->
                        root.put("category_hint", patch.value.wireValue)
                }
            }
            .toString()
        return requestCall(
            session = session,
            endpoint = session.gatewayBaseUrl.trimEnd('/') +
                "$USER_REPORT_LIST_PATH/${intent.reportId}/corrections",
            method = "POST",
            requestBody = body,
            acceptedStatusCodes = setOf(200, 201),
        ) { responseBody ->
            validatedUserReportContentRevisionOrNull(responseBody)?.takeIf {
                it.reportId == intent.reportId &&
                    it.expectedRevision == intent.expectedRevision &&
                    it.idempotencyKey == intent.idempotencyKey
            }
        }
    }

    override fun reportDeletionStatusCall(
        session: GatewayFieldSession,
        requestId: String,
    ): CancellableNetworkCall<UserReportDeletionStatus> {
        require(validCanonicalUserReportUuid(requestId))
        return requestCall(
            session = session,
            endpoint = session.gatewayBaseUrl.trimEnd('/') +
                "$USER_REPORT_LIST_PATH/deletions/$requestId",
            method = "GET",
            requestBody = null,
            acceptedStatusCodes = setOf(200),
        ) { body ->
            validatedUserReportDeletionStatusOrNull(body)?.takeIf {
                it.requestId == requestId
            }
        }
    }

    private fun <T> requestCall(
        session: GatewayFieldSession,
        endpoint: String,
        method: String,
        requestBody: String?,
        acceptedStatusCodes: Set<Int>,
        parse: (String) -> T?,
    ): CancellableNetworkCall<T> {
        require(session.sessionScope == GatewaySessionScope.GENERAL)
        require(session.backendDevicePersistenceSnapshotOrNull() != null)
        require(endpoint.startsWith(session.gatewayBaseUrl.trimEnd('/') + "/api/"))
        return cancellableHttpCall { cancellation ->
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            cancellation.attach(connection)
            try {
                connection.apply {
                    requestMethod = method
                    connectTimeout = 8_000
                    readTimeout = 15_000
                    doInput = true
                    useCaches = false
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cache-Control", "no-store")
                    setRequestProperty("Connection", "close")
                    session.requestHeaders().forEach(::setRequestProperty)
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
                    USER_REPORT_MAX_RESPONSE_BYTES,
                    cancellation,
                )
                if (response.statusCode !in acceptedStatusCodes) {
                    throw UserReportHttpException(response.statusCode)
                }
                val contentType = connection.getHeaderField("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
                if (contentType != "application/json") throw UserReportProtocolException()
                parse(response.body) ?: throw UserReportProtocolException()
            } finally {
                cancellation.detach(connection)
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val USER_REPORT_LIST_PATH = "/api/reports/mine"
    }
}

internal class UserReportHttpException(
    val statusCode: Int,
) : IllegalStateException("user report request failed: $statusCode")

internal class UserReportProtocolException :
    IllegalStateException("user report response is malformed")

internal const val USER_REPORT_MAX_RESPONSE_BYTES = 128 * 1024
