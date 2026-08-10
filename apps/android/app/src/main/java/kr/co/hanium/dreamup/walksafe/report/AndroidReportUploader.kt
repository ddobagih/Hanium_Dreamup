package kr.co.hanium.dreamup.walksafe.report

import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.CONSENT_CONTROL_SECRET_HEADER
import kr.co.hanium.dreamup.walksafe.network.CONSENT_INSTALLATION_HEADER
import kr.co.hanium.dreamup.walksafe.network.CONSENT_NETWORK_TRANSPORT_HEADER
import kr.co.hanium.dreamup.walksafe.network.CONSENT_POLICY_HEADER
import kr.co.hanium.dreamup.walksafe.network.CONSENT_RECEIPT_HEADER
import kr.co.hanium.dreamup.walksafe.network.CONSENT_REVISION_HEADER
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.network.cancellableHttpCall
import kr.co.hanium.dreamup.walksafe.network.readBoundedResponse
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation
import org.json.JSONArray
import org.json.JSONObject

const val REPORT_PURPOSE_HEADER = "x-walksafe-report-purpose"

data class ReportUploadResponse(
    val statusCode: Int,
    val responseBody: String,
    val reportId: String,
    val receiptOutcome: ReportUploadReceiptOutcome,
) {
    fun duplicateReportIds(): List<String> {
        return duplicateReportIdsFromBody(responseBody)
    }
}

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

/** Multipart uploader requiring a live privacy-session permit at creation and socket open. */
internal class AndroidReportUploader internal constructor() {
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
        val imageSnapshot = imageJpeg.copyOf()
        val receiptExpectation =
            reportUploadReceiptExpectation(
                stableTraceId = stableTraceId,
                expectedGatewayActorId = session.actorId,
                imageJpeg = imageSnapshot,
                transferPurpose = transferPurpose,
            )
        val metadata = runCatching { JSONObject(metadataJson) }.getOrNull()
        val automaticReport = metadata?.optBoolean("auto_reported")
        require(automaticReport == (transferPurpose == ReportTransferPurpose.AUTOMATIC)) {
            "report transfer purpose does not match metadata"
        }
        require(metadata?.optString("trace_id") == stableTraceId) {
            "report trace id does not match request payload"
        }
        return cancellableHttpCall { cancellation ->
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
                connectTimeout = 8_000
                readTimeout = 12_000
                doOutput = true
                doInput = true
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
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
                    consentConfirmation.receiptSha256,
                )
                session.requestHeaders().forEach(::setRequestProperty)
            }
            connection.outputStream.use { output ->
                cancellation.attach(output)
                try {
                    writeMultipart(output, boundary, metadataJson, imageSnapshot)
                } finally {
                    cancellation.detach(output)
                }
            }
            val response = connection.readBoundedResponse(REPORT_UPLOAD_MAX_RESPONSE_BYTES, cancellation)
            if (response.statusCode !in 200..299) {
                throw ReportUploadHttpException(
                    ReportUploadError(
                        statusCode = response.statusCode,
                        errorBody = response.body,
                        retryAfterMs = connection.getHeaderField("Retry-After")
                            ?.trim()
                            ?.toLongOrNull()
                            ?.coerceIn(1L, MAX_SERVER_RETRY_AFTER_MS / 1_000L)
                            ?.times(1_000L),
                    ),
                )
            }
            validatedReportUploadResponseOrNull(
                statusCode = response.statusCode,
                body = response.body,
                receiptExpectation = receiptExpectation,
            )
                ?: throw ReportUploadProtocolException()
        } finally {
            cancellation.detach(connection)
            connection.disconnect()
        }
    }
    }

    private fun writeMultipart(
        output: OutputStream,
        boundary: String,
        metadataJson: String,
        imageJpeg: ByteArray,
    ) {
        val line = "\r\n"
        output.write("--$boundary$line".toByteArray(Charsets.UTF_8))
        output.write(
            "Content-Disposition: form-data; name=\"metadata\"$line".toByteArray(Charsets.UTF_8),
        )
        output.write("Content-Type: application/json$line$line".toByteArray(Charsets.UTF_8))
        output.write(metadataJson.toByteArray(Charsets.UTF_8))
        output.write(line.toByteArray(Charsets.UTF_8))

        output.write("--$boundary$line".toByteArray(Charsets.UTF_8))
        output.write(
            "Content-Disposition: form-data; name=\"image\"; filename=\"report.jpg\"$line".toByteArray(Charsets.UTF_8),
        )
        output.write("Content-Type: image/jpeg$line$line".toByteArray(Charsets.UTF_8))
        output.write(imageJpeg)
        output.write(line.toByteArray(Charsets.UTF_8))
        output.write("--$boundary--$line".toByteArray(Charsets.UTF_8))
        output.flush()
    }
}

fun reportRetryDelayMs(
    consecutiveFailures: Int,
    statusCode: Int? = null,
    retryAfterMs: Long? = null,
): Long {
    retryAfterMs?.let { return it.coerceIn(MIN_RETRY_DELAY_MS, MAX_SERVER_RETRY_AFTER_MS) }
    if (statusCode in setOf(401, 403, 422)) return AUTH_OR_INPUT_RETRY_DELAY_MS
    if (statusCode == 429) return RATE_LIMIT_RETRY_DELAY_MS
    val exponent = (consecutiveFailures.coerceAtLeast(1) - 1).coerceAtMost(4)
    return (MIN_RETRY_DELAY_MS shl exponent).coerceAtMost(MAX_RETRY_DELAY_MS)
}

private const val MIN_RETRY_DELAY_MS = 2_000L
private const val MAX_RETRY_DELAY_MS = 30_000L
private const val RATE_LIMIT_RETRY_DELAY_MS = 30_000L
private const val AUTH_OR_INPUT_RETRY_DELAY_MS = 60_000L
private const val MAX_SERVER_RETRY_AFTER_MS = 5L * 60L * 1_000L
internal const val REPORT_UPLOAD_MAX_RESPONSE_BYTES = 512 * 1024

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
    if (statusCode !in 200..299 || body.isBlank()) return null
    return try {
        val root = JSONObject(body)
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
        val actualActorId =
            nullableReceiptString(metadata, "ingested_by_actor_id")
                ?: throw ReportUploadProtocolException()
        if (actualActorId != receiptExpectation.gatewayActorId) {
            throw ReportUploadProtocolException()
        }
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
        ReportUploadResponse(statusCode, body, reportId, receiptOutcome)
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
