package kr.co.hanium.dreamup.walksafe.report

import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.BoundedHttpResponse
import kr.co.hanium.dreamup.walksafe.network.CONSENT_CONTROL_SECRET_HEADER
import kr.co.hanium.dreamup.walksafe.network.CONSENT_INSTALLATION_HEADER
import kr.co.hanium.dreamup.walksafe.network.CONSENT_NETWORK_TRANSPORT_HEADER
import kr.co.hanium.dreamup.walksafe.network.CONSENT_POLICY_HEADER
import kr.co.hanium.dreamup.walksafe.network.CONSENT_RECEIPT_HEADER
import kr.co.hanium.dreamup.walksafe.network.CONSENT_REVISION_HEADER
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.network.cancellableHttpCall
import kr.co.hanium.dreamup.walksafe.network.captureGatewayAuthenticationRequest
import kr.co.hanium.dreamup.walksafe.network.consumeGatewayAuthenticationStaleResponse
import kr.co.hanium.dreamup.walksafe.network.readBoundedResponse
import kr.co.hanium.dreamup.walksafe.network.strictJsonObjectDocument
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation
import org.json.JSONArray
import org.json.JSONObject

const val REPORT_PURPOSE_HEADER = "x-walksafe-report-purpose"
internal const val REPORT_ID_HEADER = "x-walksafe-report-id"
internal const val REPORT_PAYLOAD_SHA256_HEADER = "x-walksafe-report-payload-sha256"
internal const val REPORT_PAYLOAD_BYTES_HEADER = "x-walksafe-report-payload-bytes"

data class ReportUploadResponse(
    val statusCode: Int,
    val responseBody: String,
    val reportId: String,
    val receiptOutcome: ReportUploadReceiptOutcome,
    val creationOutcome: ReportCreationOutcome? = null,
) {
    fun duplicateReportIds(): List<String> {
        return duplicateReportIdsFromBody(responseBody)
    }
}

enum class ReportCreationOutcome {
    CREATED,
    REPLAYED,
    SUPPRESSED_BY_COOLDOWN,
}

internal data class ReportQueueUploadResult(
    val receipt: ReportQueueReceipt,
    val creationOutcome: ReportCreationOutcome?,
)

internal fun parseQueuedUploadReceiptOrNull(
    body: String,
    report: QueuedReport,
): ReportQueueReceipt? = parseQueuedUploadResultOrNull(body, report)?.receipt

internal fun parseQueuedUploadResultOrNull(
    body: String,
    report: QueuedReport,
): ReportQueueUploadResult? = runCatching {
    require(strictJsonObjectDocument(body))
    val root = JSONObject(body)
    val outcome = root.creationOutcomeOrNull()
    require(outcome != ReportCreationOutcome.SUPPRESSED_BY_COOLDOWN)
    ReportQueueUploadResult(
        strictTransportReceipt(root.getJSONObject("transport_receipt"), report),
        outcome,
    )
}.getOrNull()

private fun JSONObject.creationOutcomeOrNull(): ReportCreationOutcome? {
    if (!has("creation_outcome") || isNull("creation_outcome")) return null
    val literal = opt("creation_outcome") as? String ?: throw ReportUploadProtocolException()
    return ReportCreationOutcome.entries.firstOrNull { it.name == literal }
        ?: throw ReportUploadProtocolException()
}

internal fun parseQueuedStatusReceiptOrNull(
    body: String,
    report: QueuedReport,
): ReportQueueReceipt? = runCatching {
    require(strictJsonObjectDocument(body))
    val root = JSONObject(body)
    require(root.keysAsSet() == REPORT_STATUS_FIELDS)
    require(root.getString("persistence_state") == "PERSISTED")
    require(root.getString("user_status") in REPORT_USER_STATUSES)
    strictTransportReceipt(root.getJSONObject("transport_receipt"), report)
}.getOrNull()

private fun strictTransportReceipt(
    value: JSONObject,
    report: QueuedReport,
): ReportQueueReceipt {
    require(value.keysAsSet() == REPORT_RECEIPT_FIELDS)
    val receipt = ReportQueueReceipt(
        reportId = value.getString("report_id"),
        payloadSha256 = value.getString("payload_sha256"),
        payloadBytes = value.strictPositiveLong("payload_bytes"),
        marker = value.getString("marker"),
        persistenceMarker = value.getString("persistence_marker"),
    )
    require(receipt.reportId == report.payload.reportId)
    require(receipt.payloadSha256 == report.payload.payloadSha256)
    require(receipt.payloadBytes == report.payload.payloadBytes)
    require(receipt.marker == REPORT_RECEIPT_MARKER)
    require(isCanonicalReportUuid(receipt.persistenceMarker))
    return receipt
}

private fun ReportQueuePriority.toTransferPurpose(): ReportTransferPurpose =
    if (this == ReportQueuePriority.EXPLICIT) {
        ReportTransferPurpose.EXPLICIT
    } else {
        ReportTransferPurpose.AUTOMATIC
    }

private fun BoundedHttpResponse.toUploadException(
    connection: HttpURLConnection,
): ReportUploadHttpException = ReportUploadHttpException(
    ReportUploadError(
        statusCode = statusCode,
        errorBody = body,
        retryAfterMs = parseReportRetryAfterMs(connection.getHeaderField("Retry-After")),
    ),
)

internal fun parseReportRetryAfterMs(
    header: String?,
    nowEpochMs: Long = System.currentTimeMillis(),
): Long? = kr.co.hanium.dreamup.walksafe.network.parseHttpRetryAfterMs(header, nowEpochMs)

private fun JSONObject.keysAsSet(): Set<String> = buildSet { keys().forEach(::add) }

private fun JSONObject.strictPositiveLong(name: String): Long = when (val value = get(name)) {
    is Int -> value.toLong()
    is Long -> value
    else -> error("$name must be an integer")
}.also { require(it > 0L) }

enum class ReportUploadReceiptOutcome {
    BOUND,
    AUTOMATIC_COOLDOWN_AMBIGUOUS,
}

internal data class ReportUploadReceiptExpectation(
    val traceId: String,
    val gatewayActorId: String,
    val imageSha256: String,
    val transferPurpose: ReportTransferPurpose,
)

data class ReportUploadError(
    val statusCode: Int,
    val errorBody: String,
    val retryAfterMs: Long? = null,
)

/** transfer-F7: 409 report_walk_in_progress, the Gateway still holds this device's walk as active. */
internal fun ReportUploadError.isReportWalkInProgress(): Boolean =
    statusCode == 409 && runCatching {
        JSONObject(errorBody).getJSONObject("detail").opt("code") == "report_walk_in_progress"
    }.getOrDefault(false)

internal fun ReportUploadError.isAutomaticCooldownSuppression(): Boolean =
    statusCode == 429 && runCatching {
        val detail = JSONObject(errorBody).getJSONObject("detail")
        detail.opt("code") == "automatic_report_cooldown_active" &&
            detail.creationOutcomeOrNull() == ReportCreationOutcome.SUPPRESSED_BY_COOLDOWN
    }.getOrDefault(false)

/** Multipart uploader requiring a live privacy-session permit at creation and socket open. */
internal class AndroidReportUploader internal constructor() {
    internal fun queuedUploadCall(
        permit: ReportUploadPermit,
        session: GatewayFieldSession,
        consentConfirmation: IntegratedConsentConfirmation,
        networkBinding: IntegratedConsentNetworkBinding,
        report: QueuedReport,
        approvedGatewayOrigin: String,
    ): CancellableNetworkCall<ReportQueueReceipt> = queuedUploadResultCall(
        permit, session, consentConfirmation, networkBinding, report, approvedGatewayOrigin,
    ).map { it.receipt }

    internal fun queuedUploadResultCall(
        permit: ReportUploadPermit,
        session: GatewayFieldSession,
        consentConfirmation: IntegratedConsentConfirmation,
        networkBinding: IntegratedConsentNetworkBinding,
        report: QueuedReport,
        approvedGatewayOrigin: String,
    ): CancellableNetworkCall<ReportQueueUploadResult> {
        require(report.reporterActorId == session.actorId) {
            "queued report actor id does not match verified session"
        }
        require(
            approvedReportQueueGatewayOriginOrNull(session.gatewayBaseUrl) ==
                approvedGatewayOrigin &&
                approvedGatewayOrigin == session.gatewayBaseUrl,
        ) { "queued report gateway origin is not build-approved" }
        val purpose = report.priority.toTransferPurpose()
        val authenticationRequest = captureGatewayAuthenticationRequest(session)
        return reportTransportCall(
            permit = permit,
            session = session,
            consentConfirmation = consentConfirmation,
            networkBinding = networkBinding,
            purpose = purpose,
            report = report,
            requestMethod = "POST",
            endpoint = approvedGatewayOrigin + "/api/reports/v2",
            deadlineMs = REPORT_UPLOAD_DEADLINE_MS,
        ) { connection, cancellation ->
            val boundary = "----walksafe-${System.currentTimeMillis()}"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            report.payload.useExactBytes { metadataSnapshot, imageSnapshot ->
                // Stream the exact body instead of letting HttpURLConnection buffer another copy.
                connection.setFixedLengthStreamingMode(
                    reportMultipartContentLength(boundary, metadataSnapshot.size, imageSnapshot.size),
                )
                connection.outputStream.use { output ->
                    cancellation.attach(output)
                    try {
                        writeMultipart(output, boundary, metadataSnapshot, imageSnapshot)
                    } finally {
                        cancellation.detach(output)
                    }
                }
            }
            val response = connection.readBoundedResponse(
                REPORT_UPLOAD_MAX_RESPONSE_BYTES, cancellation,
                strictSuccessFraming = true, strictErrorFraming = true,
            )
            if (response.statusCode !in 200..299) {
                val error = response.toUploadException(connection)
                cancellation.throwIfCancelled()
                consumeGatewayAuthenticationStaleResponse(authenticationRequest, session, response)
                throw error
            }
            parseQueuedUploadResultOrNull(response.body, report)
                ?: throw ReportUploadProtocolException()
        }
    }

    internal fun queuedStatusCall(
        permit: ReportUploadPermit,
        session: GatewayFieldSession,
        consentConfirmation: IntegratedConsentConfirmation,
        networkBinding: IntegratedConsentNetworkBinding,
        report: QueuedReport,
        approvedGatewayOrigin: String,
    ): CancellableNetworkCall<ReportQueueReceipt?> {
        require(report.reporterActorId == session.actorId) {
            "queued report actor id does not match verified session"
        }
        require(
            approvedReportQueueGatewayOriginOrNull(session.gatewayBaseUrl) ==
                approvedGatewayOrigin &&
                approvedGatewayOrigin == session.gatewayBaseUrl,
        ) { "queued report gateway origin is not build-approved" }
        val purpose = report.priority.toTransferPurpose()
        return reportTransportCall(
            permit = permit,
            session = session,
            consentConfirmation = consentConfirmation,
            networkBinding = networkBinding,
            purpose = purpose,
            report = report,
            requestMethod = "GET",
            endpoint = approvedGatewayOrigin +
                "/api/reports/v2/${report.payload.reportId}/status",
            deadlineMs = REPORT_STATUS_DEADLINE_MS,
        ) { connection, cancellation ->
            val response = connection.readBoundedResponse(
                REPORT_UPLOAD_MAX_RESPONSE_BYTES, cancellation,
                strictSuccessFraming = true, strictErrorFraming = true,
            )
            when (response.statusCode) {
                404 -> null
                in 200..299 -> parseQueuedStatusReceiptOrNull(response.body, report)
                    ?: throw ReportUploadProtocolException()
                else -> throw response.toUploadException(connection)
            }
        }
    }

    private fun <T> reportTransportCall(
        permit: ReportUploadPermit,
        session: GatewayFieldSession,
        consentConfirmation: IntegratedConsentConfirmation,
        networkBinding: IntegratedConsentNetworkBinding,
        purpose: ReportTransferPurpose,
        report: QueuedReport,
        requestMethod: String,
        endpoint: String,
        deadlineMs: Long,
        execute: (HttpURLConnection, kr.co.hanium.dreamup.walksafe.network.HttpConnectionCancellation) -> T,
    ): CancellableNetworkCall<T> = cancellableHttpCall(deadlineMs = deadlineMs) { cancellation ->
        val connection = ReportPrivacyConsentSession.withLiveUploadPermit(
            permit = permit,
            expectedPurpose = purpose,
        ) {
            networkBinding.openConnection(URL(endpoint)) as HttpURLConnection
        }
        cancellation.attach(connection)
        try {
            connection.apply {
                this.requestMethod = requestMethod
                connectTimeout = REPORT_CONNECT_TIMEOUT_MS
                readTimeout = REPORT_READ_TIMEOUT_MS
                doInput = true
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Connection", "close")
                setRequestProperty(REPORT_PURPOSE_HEADER, purpose.wireValue)
                setRequestProperty(REPORT_ID_HEADER, report.payload.reportId)
                setRequestProperty(REPORT_PAYLOAD_SHA256_HEADER, report.payload.payloadSha256)
                setRequestProperty(REPORT_PAYLOAD_BYTES_HEADER, report.payload.payloadBytes.toString())
                setRequestProperty(CONSENT_INSTALLATION_HEADER, consentConfirmation.installationId)
                setRequestProperty(CONSENT_CONTROL_SECRET_HEADER, consentConfirmation.controlSecret)
                setRequestProperty(CONSENT_NETWORK_TRANSPORT_HEADER, networkBinding.transport.wireValue)
                setRequestProperty(CONSENT_POLICY_HEADER, consentConfirmation.policyVersion)
                setRequestProperty(CONSENT_REVISION_HEADER, consentConfirmation.revision.toString())
                setRequestProperty(
                    CONSENT_RECEIPT_HEADER,
                    consentConfirmation.backendConsentReceiptSha256,
                )
                session.requestHeaders().forEach(::setRequestProperty)
            }
            execute(connection, cancellation)
        } finally {
            cancellation.detach(connection)
            runCatching { connection.disconnect() }
        }
    }

    internal fun uploadCall(
        permit: ReportUploadPermit,
        session: GatewayFieldSession,
        consentConfirmation: IntegratedConsentConfirmation,
        networkBinding: IntegratedConsentNetworkBinding,
        transferPurpose: ReportTransferPurpose,
        metadataJson: String,
        imageJpeg: ByteArray,
        stableTraceId: String,
        expectedGatewayActorId: String,
    ): CancellableNetworkCall<ReportUploadResponse> {
        require(expectedGatewayActorId == session.actorId) {
            "expected gateway actor id does not match verified session"
        }
        val metadata = runCatching { JSONObject(metadataJson) }.getOrNull()
        val automaticReport = metadata?.optBoolean("auto_reported")
        require(automaticReport == (transferPurpose == ReportTransferPurpose.AUTOMATIC)) {
            "report transfer purpose does not match metadata"
        }
        require(metadata?.optString("trace_id") == stableTraceId) {
            "report trace id does not match request payload"
        }
        val authenticationRequest = captureGatewayAuthenticationRequest(session)
        val imageSnapshot = imageJpeg.copyOf()
        val receiptExpectation = try {
            reportUploadReceiptExpectation(
                stableTraceId = stableTraceId,
                expectedGatewayActorId = session.actorId,
                imageJpeg = imageSnapshot,
                transferPurpose = transferPurpose,
            )
        } catch (error: Throwable) {
            imageSnapshot.fill(0)
            throw error
        }
        return cancellableHttpCall(deadlineMs = REPORT_UPLOAD_DEADLINE_MS) { cancellation ->
            val endpoint = session.gatewayBaseUrl.trimEnd('/') + "/api/reports/v2"
            val boundary = "----walksafe-${System.currentTimeMillis()}"
            val connection =
                ReportPrivacyConsentSession.withLiveUploadPermit(
                    permit = permit,
                    expectedPurpose = transferPurpose,
                ) {
                    networkBinding.openConnection(URL(endpoint)) as HttpURLConnection
                }
            cancellation.attach(connection)
            try {
                connection.apply {
                    requestMethod = "POST"
                    connectTimeout = REPORT_CONNECT_TIMEOUT_MS
                    readTimeout = REPORT_READ_TIMEOUT_MS
                    doOutput = true
                    doInput = true
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Connection", "close")
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    setRequestProperty(
                        CONSENT_INSTALLATION_HEADER,
                        consentConfirmation.installationId,
                    )
                    setRequestProperty(
                        CONSENT_CONTROL_SECRET_HEADER,
                        consentConfirmation.controlSecret,
                    )
                    setRequestProperty(
                        CONSENT_NETWORK_TRANSPORT_HEADER,
                        networkBinding.transport.wireValue,
                    )
                    setRequestProperty(REPORT_PURPOSE_HEADER, transferPurpose.wireValue)
                    setRequestProperty(CONSENT_POLICY_HEADER, consentConfirmation.policyVersion)
                    setRequestProperty(
                        CONSENT_REVISION_HEADER,
                        consentConfirmation.revision.toString(),
                    )
                    setRequestProperty(
                        CONSENT_RECEIPT_HEADER,
                        consentConfirmation.backendConsentReceiptSha256,
                    )
                    session.requestHeaders().forEach(::setRequestProperty)
                }
                val metadataUtf8 = metadataJson.toByteArray(Charsets.UTF_8)
                try {
                    connection.setFixedLengthStreamingMode(
                        reportMultipartContentLength(boundary, metadataUtf8.size, imageSnapshot.size),
                    )
                    connection.outputStream.use { output ->
                        cancellation.attach(output)
                        try {
                            writeMultipart(output, boundary, metadataUtf8, imageSnapshot)
                        } finally {
                            cancellation.detach(output)
                        }
                    }
                } finally {
                    metadataUtf8.fill(0)
                }
                val response = connection.readBoundedResponse(
                    REPORT_UPLOAD_MAX_RESPONSE_BYTES, cancellation,
                    strictSuccessFraming = true, strictErrorFraming = true,
                )
                if (response.statusCode !in 200..299) {
                    val error = ReportUploadHttpException(
                        ReportUploadError(
                            statusCode = response.statusCode,
                            errorBody = response.body,
                            retryAfterMs = parseReportRetryAfterMs(connection.getHeaderField("Retry-After")),
                        ),
                    )
                    cancellation.throwIfCancelled()
                    consumeGatewayAuthenticationStaleResponse(authenticationRequest, session, response)
                    throw error
                }
                validatedReportUploadResponseOrNull(
                    statusCode = response.statusCode,
                    body = response.body,
                    receiptExpectation = receiptExpectation,
                )
                    ?: throw ReportUploadProtocolException()
            } finally {
                cancellation.detach(connection)
                runCatching { connection.disconnect() }
            }
        }.clearingOwnedReportImage(imageSnapshot)
    }

    private fun writeMultipart(
        output: OutputStream,
        boundary: String,
        metadataJson: String,
        imageJpeg: ByteArray,
    ) = writeMultipart(output, boundary, metadataJson.toByteArray(Charsets.UTF_8), imageJpeg)

    private fun writeMultipart(
        output: OutputStream,
        boundary: String,
        metadataUtf8: ByteArray,
        imageJpeg: ByteArray,
    ) = writeReportMultipart(output, boundary, metadataUtf8, imageJpeg)
}

/** Byte-exact multipart framing shared by the writer and the fixed Content-Length. */
private class ReportMultipartFraming(boundary: String) {
    val beforeMetadata = (
        "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"metadata\"\r\n" +
            "Content-Type: application/json\r\n\r\n"
        ).toByteArray(Charsets.UTF_8)
    val beforeImage = (
        "\r\n--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"image\"; filename=\"report.jpg\"\r\n" +
            "Content-Type: image/jpeg\r\n\r\n"
        ).toByteArray(Charsets.UTF_8)
    val afterImage = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
}

internal fun reportMultipartContentLength(
    boundary: String,
    metadataBytes: Int,
    imageBytes: Int,
): Long {
    require(metadataBytes >= 0 && imageBytes >= 0)
    val framing = ReportMultipartFraming(boundary)
    return framing.beforeMetadata.size.toLong() + metadataBytes +
        framing.beforeImage.size + imageBytes + framing.afterImage.size
}

internal fun writeReportMultipart(
    output: OutputStream,
    boundary: String,
    metadataUtf8: ByteArray,
    imageJpeg: ByteArray,
) {
    val framing = ReportMultipartFraming(boundary)
    output.write(framing.beforeMetadata)
    output.write(metadataUtf8)
    output.write(framing.beforeImage)
    output.write(imageJpeg)
    output.write(framing.afterImage)
    output.flush()
}

fun reportRetryDelayMs(
    consecutiveFailures: Int,
    statusCode: Int? = null,
    retryAfterMs: Long? = null,
): Long {
    val defaultDelayMs = when {
        statusCode == 429 -> RATE_LIMIT_RETRY_DELAY_MS
        else -> {
            val exponent = (consecutiveFailures.coerceAtLeast(1) - 1).coerceAtMost(4)
            (MIN_RETRY_DELAY_MS shl exponent).coerceAtMost(MAX_RETRY_DELAY_MS)
        }
    }
    return maxOf(defaultDelayMs, retryAfterMs?.coerceIn(0L, MAX_SAFE_RETRY_DELAY_MS) ?: 0L)
}

private const val MIN_RETRY_DELAY_MS = 2_000L
private const val MAX_RETRY_DELAY_MS = 30_000L
private const val RATE_LIMIT_RETRY_DELAY_MS = 30_000L
// Callers persist now + delay; a far-future wait must not overflow that timestamp.
private const val MAX_SAFE_RETRY_DELAY_MS = Long.MAX_VALUE / 2L
internal const val REPORT_UPLOAD_MAX_RESPONSE_BYTES = 512 * 1024
internal const val REPORT_CONNECT_TIMEOUT_MS = 8_000
internal const val REPORT_READ_TIMEOUT_MS = 12_000

/**
 * S15-23 whole-request deadlines, built only from bounds that already exist: connect + read for the
 * status query, plus the Gateway's own 15 s report multipart body-read deadline for the POST
 * (apps/android-gateway/src/backend.ts DEFAULT_PROXY_TIMEOUT_MS via readBoundedMultipartFormData),
 * because a body slower than that is rejected by the Gateway anyway.
 */
internal const val REPORT_GATEWAY_MULTIPART_READ_DEADLINE_MS = 15_000L
internal const val REPORT_STATUS_DEADLINE_MS =
    REPORT_CONNECT_TIMEOUT_MS.toLong() + REPORT_READ_TIMEOUT_MS.toLong()
internal const val REPORT_UPLOAD_DEADLINE_MS =
    REPORT_STATUS_DEADLINE_MS + REPORT_GATEWAY_MULTIPART_READ_DEADLINE_MS

class ReportUploadHttpException(val error: ReportUploadError) : IllegalStateException("report upload failed: ${error.statusCode}")

class ReportUploadProtocolException : IllegalStateException("report upload returned a malformed success response")

internal fun reportUploadReceiptExpectation(
    stableTraceId: String,
    expectedGatewayActorId: String,
    imageJpeg: ByteArray,
    transferPurpose: ReportTransferPurpose,
): ReportUploadReceiptExpectation {
    require(stableTraceId.isNotBlank()) { "report trace id must not be blank" }
    require(expectedGatewayActorId.isNotBlank()) { "gateway actor id must not be blank" }
    return ReportUploadReceiptExpectation(
        traceId = stableTraceId,
        gatewayActorId = expectedGatewayActorId,
        imageSha256 = imageJpeg.sha256Hex(),
        transferPurpose = transferPurpose,
    )
}

internal fun validatedReportUploadResponseOrNull(
    statusCode: Int,
    body: String,
    receiptExpectation: ReportUploadReceiptExpectation,
): ReportUploadResponse? {
    if (statusCode !in 200..299 || !strictJsonObjectDocument(body)) return null
    return try {
        val root = JSONObject(body)
        val creationOutcome = root.creationOutcomeOrNull()
        if (creationOutcome == ReportCreationOutcome.SUPPRESSED_BY_COOLDOWN) {
            throw ReportUploadProtocolException()
        }
        val reportId = root.optString("id")
        val parsedId = runCatching { UUID.fromString(reportId) }.getOrNull() ?: return null
        if (parsedId.toString() != reportId.lowercase()) return null
        if (root.optString("status") !in setOf("new", "reviewed", "resolved")) return null
        if (root.optString("class_name") != "damaged_tactile_block") return null
        if (root.optString("source") != "android") return null
        val bbox = root.optJSONObject("bbox") ?: return null
        val bboxValues = listOf("x", "y", "width", "height").map { name ->
            (bbox.opt(name) as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: return null
        }
        val (x, y, width, height) = bboxValues
        if (x !in 0.0..1.0 || y !in 0.0..1.0 || width <= 0.0 || height <= 0.0 || x + width > 1.001 || y + height > 1.001) return null
        val metadata = root.optJSONObject("metadata") ?: return null
        if (!UPLOAD_PATH_PATTERN.matches(root.optString("image_path"))) return null
        val confidence = (root.opt("confidence") as? Number)?.toDouble() ?: return null
        if (!confidence.isFinite() || confidence !in 0.0..1.0) return null
        val actualTraceId = nullableReceiptString(metadata, "trace_id")
        if (actualTraceId != null && actualTraceId != receiptExpectation.traceId) {
            throw ReportUploadProtocolException()
        }
        val actualImageSha256 = nullableReceiptString(metadata, "image_sha256")
        if (actualImageSha256 != null && actualImageSha256 != receiptExpectation.imageSha256) {
            throw ReportUploadProtocolException()
        }
        val receiptOutcome =
            if (actualTraceId != null && actualImageSha256 != null) {
                ReportUploadReceiptOutcome.BOUND
            } else if (receiptExpectation.transferPurpose == ReportTransferPurpose.AUTOMATIC) {
                ReportUploadReceiptOutcome.AUTOMATIC_COOLDOWN_AMBIGUOUS
            } else {
                throw ReportUploadProtocolException()
            }
        if (creationOutcome != null && receiptOutcome != ReportUploadReceiptOutcome.BOUND) {
            throw ReportUploadProtocolException()
        }
        ReportUploadResponse(statusCode, body, reportId, receiptOutcome, creationOutcome)
    } catch (exception: ReportUploadProtocolException) {
        throw exception
    } catch (_: RuntimeException) {
        null
    }
}

private fun nullableReceiptString(metadata: JSONObject, name: String): String? {
    if (!metadata.has(name) || metadata.isNull(name)) return null
    return metadata.opt(name) as? String ?: throw ReportUploadProtocolException()
}

private fun ByteArray.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(LOWER_HEX_DIGITS[value ushr 4])
            append(LOWER_HEX_DIGITS[value and 0x0f])
        }
    }
}

private const val LOWER_HEX_DIGITS = "0123456789abcdef"
private val UPLOAD_PATH_PATTERN = Regex("/uploads/[A-Za-z0-9._-]{1,160}")
private val REPORT_STATUS_FIELDS = setOf(
    "persistence_state",
    "user_status",
    "transport_receipt",
)
private val REPORT_USER_STATUSES = setOf("RECEIVED", "IN_REVIEW", "COMPLETED")
private val REPORT_RECEIPT_FIELDS = setOf(
    "marker",
    "report_id",
    "persistence_marker",
    "payload_sha256",
    "payload_bytes",
)

fun duplicateReportIdsFromBody(body: String): List<String> {
    if (body.isBlank()) return emptyList()
    return try {
        val root = JSONObject(body)
        val direct = root.optJSONArray("duplicate_report_ids")
        val metadata = root.optJSONObject("metadata")?.optJSONArray("duplicate_report_ids")
        (direct ?: metadata).toStringList()
    } catch (_: RuntimeException) {
        emptyList()
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val values = mutableListOf<String>()
    for (index in 0 until length()) {
        val value = optString(index).trim()
        if (value.isNotBlank()) values += value
    }
    return values
}

/** Call owns [image] from creation. Cancellation must not wipe it while a writer is using it. */
internal fun <T> CancellableNetworkCall<T>.clearingOwnedReportImage(image: ByteArray): CancellableNetworkCall<T> {
    val lock = Any()
    var started = false
    lateinit var ownedCall: CancellableNetworkCall<T>
    ownedCall = CancellableNetworkCall(
        executeBlock = {
            synchronized(lock) {
                if (ownedCall.isCancelled()) throw java.util.concurrent.CancellationException()
                started = true
            }
            try { execute() } finally { image.fill(0) }
        },
        cancelBlock = {
            synchronized(lock) { if (!started) image.fill(0) }
            cancel()
        },
    )
    return ownedCall
}
