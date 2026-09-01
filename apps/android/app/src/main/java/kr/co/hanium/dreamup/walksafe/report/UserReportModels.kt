package kr.co.hanium.dreamup.walksafe.report

import java.time.Instant
import java.text.Normalizer
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal const val USER_REPORT_LIST_SCHEMA_VERSION = "walksafe.user-report-list.v1"
internal const val USER_REPORT_DETAIL_SCHEMA_VERSION = "walksafe.user-report-detail.v1"
internal const val USER_REPORT_CONTENT_CURRENT_SCHEMA_VERSION =
    "walksafe.report-content-current.v1"
internal const val USER_REPORT_CONTENT_REVISION_SCHEMA_VERSION =
    "walksafe.report-content-revision.v1"
internal const val USER_REPORT_DELETION_STATUS_SCHEMA_VERSION =
    "walksafe.report-deletion-status.v2"

internal enum class UserReportStatus(val wireValue: String, val labelKo: String) {
    RECEIVED("RECEIVED", "접수됨"),
    REJECTED("REJECTED", "기각됨"),
    INSTITUTION_SUBMITTED("INSTITUTION_SUBMITTED", "기관 제출됨"),
    RESOLVED("RESOLVED", "처리 완료"),
    ;

    companion object {
        fun fromWireOrNull(value: String): UserReportStatus? =
            entries.singleOrNull { it.wireValue == value }
    }
}

internal enum class UserReportRequestType(val wireValue: String, val labelKo: String) {
    CORRECTION("CORRECTION", "정정 요청"),
    DELETE("DELETE", "신고 삭제 요청"),
    ;

    companion object {
        fun fromWireOrNull(value: String): UserReportRequestType? =
            entries.singleOrNull { it.wireValue == value }
    }
}

internal enum class UserReportRequestStatus(val wireValue: String, val labelKo: String) {
    RECEIVED("RECEIVED", "요청 접수됨"),
    ACKNOWLEDGED("ACKNOWLEDGED", "요청 확인됨"),
    RESOLVED("RESOLVED", "요청 처리 완료"),
    REJECTED("REJECTED", "요청 기각됨"),
    ;

    companion object {
        fun fromWireOrNull(value: String): UserReportRequestStatus? =
            entries.singleOrNull { it.wireValue == value }
    }
}

internal data class UserReportRequestSummary(
    val requestId: String,
    val requestType: UserReportRequestType,
    val status: UserReportRequestStatus,
    val statusVersion: Long,
    val publicResponse: String?,
    val createdAt: String,
    val updatedAt: String,
)

internal data class UserReportRequestReference(
    val reportId: String,
    val requestId: String,
    val requestType: UserReportRequestType,
) {
    init {
        require(validCanonicalUserReportUuid(reportId))
        require(validCanonicalUserReportUuid(requestId))
    }
}

internal data class UserReportSummary(
    val reportId: String,
    val createdAt: String,
    val userStatus: UserReportStatus,
    val publicRejectionReason: String?,
    val latestRequest: UserReportRequestSummary?,
)

internal data class UserReportListPage(
    val items: List<UserReportSummary>,
    val nextCursor: String?,
)

internal data class UserReportDetail(
    val reportId: String,
    val createdAt: String,
    val userStatus: UserReportStatus,
    val publicRejectionReason: String?,
    val latestRequest: UserReportRequestSummary?,
)

internal data class UserReportRequestIntent(
    val clientRequestId: String,
    val reportId: String,
    val requestType: UserReportRequestType,
    val requestText: String,
) {
    init {
        require(validCanonicalUserReportUuid(clientRequestId))
        require(validCanonicalUserReportUuid(reportId))
        require(validUserReportRequestText(requestText))
    }

    override fun toString(): String =
        "UserReportRequestIntent(reportId=$reportId, requestType=$requestType, request=redacted)"
}

internal enum class UserReportContentCategory(val wireValue: String, val labelKo: String) {
    SIDEWALK_OBSTRUCTION("SIDEWALK_OBSTRUCTION", "보행로 장애물"),
    ROAD_DAMAGE("ROAD_DAMAGE", "도로 파손"),
    ACCESSIBILITY_BARRIER("ACCESSIBILITY_BARRIER", "접근성 장애"),
    OTHER("OTHER", "기타"),
    ;

    companion object {
        fun fromWireOrNull(value: String): UserReportContentCategory? =
            entries.singleOrNull { it.wireValue == value }
    }
}

internal sealed interface UserReportCorrectionPatch<out T> {
    data object Omitted : UserReportCorrectionPatch<Nothing>
    data object Clear : UserReportCorrectionPatch<Nothing>
    data class Value<T>(val value: T) : UserReportCorrectionPatch<T>
}

internal data class UserReportContentCurrent(
    val reportId: String,
    val revision: Long,
    val contentSha256: String,
    val userDescription: String?,
    val categoryHint: UserReportContentCategory?,
    val correctedAt: String?,
) {
    override fun toString(): String =
        "UserReportContentCurrent(reportId=$reportId, revision=$revision, content=redacted)"
}

internal data class UserReportCorrectionIntent(
    val reportId: String,
    val expectedRevision: Long,
    val idempotencyKey: String,
    val userDescription: UserReportCorrectionPatch<String> = UserReportCorrectionPatch.Omitted,
    val categoryHint: UserReportCorrectionPatch<UserReportContentCategory> =
        UserReportCorrectionPatch.Omitted,
) {
    init {
        require(validCanonicalUserReportUuid(reportId))
        require(expectedRevision in 0L..MAX_SAFE_JSON_INTEGER)
        require(validCanonicalUserReportUuid(idempotencyKey))
        require(
            userDescription !is UserReportCorrectionPatch.Omitted ||
                categoryHint !is UserReportCorrectionPatch.Omitted,
        )
        if (userDescription is UserReportCorrectionPatch.Value) {
            require(canonicalUserReportCorrectionDescriptionOrNull(userDescription.value) != null)
        }
    }

    override fun toString(): String =
        "UserReportCorrectionIntent(reportId=$reportId, expectedRevision=$expectedRevision, " +
            "idempotencyKey=$idempotencyKey, patch=redacted)"
}

internal data class UserReportContentRevision(
    val reportId: String,
    val revision: Long,
    val expectedRevision: Long,
    val idempotencyKey: String,
    val contentSha256: String,
    val userDescription: String?,
    val categoryHint: UserReportContentCategory?,
    val correctedAt: String,
) {
    override fun toString(): String =
        "UserReportContentRevision(reportId=$reportId, revision=$revision, content=redacted)"
}

internal enum class UserReportDeletionState(val wireValue: String, val labelKo: String) {
    PENDING("PENDING", "물리 삭제 대기 중"),
    LEGAL_HOLD("LEGAL_HOLD", "법적 보존 중"),
    REJECTED("REJECTED", "삭제 요청 기각됨"),
    DELETED("DELETED", "물리 삭제 완료"),
    ;

    companion object {
        fun fromWireOrNull(value: String): UserReportDeletionState? =
            entries.singleOrNull { it.wireValue == value }
    }
}

internal enum class UserReportExternalCopyDeletionState(
    val wireValue: String,
    val labelKo: String,
) {
    NOT_REQUESTED("NOT_REQUESTED", "기관 보관본 삭제 요청 전"),
    REQUEST_SENT("REQUEST_SENT", "기관에 삭제 요청 전달됨 · 삭제 완료 아님"),
    REPLY_ACKNOWLEDGED(
        "REPLY_ACKNOWLEDGED",
        "기관이 삭제 요청 접수를 회신함 · 삭제 완료 아님",
    ),
    REPLY_DELETION_CONFIRMED(
        "REPLY_DELETION_CONFIRMED",
        "기관이 삭제 완료를 회신함 · 기관 회신 사실",
    ),
    REPLY_DECLINED("REPLY_DECLINED", "기관이 삭제 요청 거절을 회신함"),
    ;

    companion object {
        fun fromWireOrNull(value: String): UserReportExternalCopyDeletionState? =
            entries.singleOrNull { it.wireValue == value }
    }
}

internal data class UserReportDeletionExternalCopyStatus(
    val institution: String,
    val state: UserReportExternalCopyDeletionState,
    val statusRecordedAt: String?,
)

internal data class UserReportDeletionStatus(
    val requestId: String,
    val reportId: String,
    val state: UserReportDeletionState,
    val requestStatusVersion: Long,
    val externalCopyCount: Long,
    val externalCopies: List<UserReportDeletionExternalCopyStatus>,
    val updatedAt: String,
) {
    init {
        require(externalCopyCount == externalCopies.size.toLong())
        require(state == UserReportDeletionState.DELETED || externalCopies.isEmpty())
    }
}

internal fun validatedUserReportListOrNull(body: String): UserReportListPage? =
    runCatching {
        val root = JSONObject(body)
        require(root.exactKeys("schema_version", "items", "next_cursor"))
        require(root.strictString("schema_version") == USER_REPORT_LIST_SCHEMA_VERSION)
        val rawItems = root.get("items") as? JSONArray ?: error("items must be an array")
        require(rawItems.length() <= 100)
        val items = buildList {
            repeat(rawItems.length()) { index ->
                add(parseUserReportSummary(rawItems.get(index)))
            }
        }
        require(items.map(UserReportSummary::reportId).toSet().size == items.size)
        val nextCursor = root.strictNullableString("next_cursor", 1, 1_024)
        require(nextCursor == null || validUserReportCursor(nextCursor))
        UserReportListPage(items = items, nextCursor = nextCursor)
    }.getOrNull()

internal fun validatedUserReportDetailOrNull(body: String): UserReportDetail? =
    runCatching {
        val root = JSONObject(body)
        require(
            root.exactKeys(
                "schema_version",
                "report_id",
                "created_at",
                "user_status",
                "public_rejection_reason",
                "latest_request",
            ),
        )
        require(root.strictString("schema_version") == USER_REPORT_DETAIL_SCHEMA_VERSION)
        val summary = parseUserReportSummary(root, includesSchemaVersion = true)
        UserReportDetail(
            reportId = summary.reportId,
            createdAt = summary.createdAt,
            userStatus = summary.userStatus,
            publicRejectionReason = summary.publicRejectionReason,
            latestRequest = summary.latestRequest,
        )
    }.getOrNull()

internal fun validatedUserReportRequestOrNull(body: String): UserReportRequestSummary? =
    runCatching { parseUserReportRequest(JSONObject(body)) }.getOrNull()

internal fun validatedUserReportContentOrNull(body: String): UserReportContentCurrent? =
    runCatching {
        val root = JSONObject(body)
        require(
            root.exactKeys(
                "schema_version",
                "report_id",
                "revision",
                "content_sha256",
                "user_description",
                "category_hint",
                "corrected_at",
            ),
        )
        require(root.strictString("schema_version") == USER_REPORT_CONTENT_CURRENT_SCHEMA_VERSION)
        val reportId = root.strictString("report_id")
        val revision = root.strictNonNegativeLong("revision")
        val contentSha256 = root.strictString("content_sha256")
        val description = root.strictNullableString("user_description", 1, 500)
        val category = root.strictNullableContentCategory("category_hint")
        val correctedAt = root.strictNullableString("corrected_at", 20, 40)
        require(validCanonicalUserReportUuid(reportId))
        require(LOWER_SHA256.matches(contentSha256))
        require(
            description == null ||
                canonicalUserReportCorrectionDescriptionOrNull(description) == description,
        )
        require(correctedAt == null || validUserReportTimestamp(correctedAt))
        require(
            if (revision == 0L) {
                description == null && category == null && correctedAt == null
            } else {
                correctedAt != null
            },
        )
        UserReportContentCurrent(
            reportId = reportId,
            revision = revision,
            contentSha256 = contentSha256,
            userDescription = description,
            categoryHint = category,
            correctedAt = correctedAt,
        )
    }.getOrNull()

internal fun validatedUserReportContentRevisionOrNull(
    body: String,
): UserReportContentRevision? = runCatching {
    val root = JSONObject(body)
    require(
        root.exactKeys(
            "schema_version",
            "report_id",
            "revision",
            "expected_revision",
            "idempotency_key",
            "content_sha256",
            "user_description",
            "category_hint",
            "corrected_at",
        ),
    )
    require(root.strictString("schema_version") == USER_REPORT_CONTENT_REVISION_SCHEMA_VERSION)
    val reportId = root.strictString("report_id")
    val revision = root.strictPositiveLong("revision")
    val expectedRevision = root.strictNonNegativeLong("expected_revision")
    val idempotencyKey = root.strictString("idempotency_key")
    val contentSha256 = root.strictString("content_sha256")
    val correctedAt = root.strictString("corrected_at")
    val description = root.strictNullableString("user_description", 1, 500)
    require(validCanonicalUserReportUuid(reportId))
    require(revision == expectedRevision + 1L)
    require(validCanonicalUserReportUuid(idempotencyKey))
    require(LOWER_SHA256.matches(contentSha256))
    require(
        description == null ||
            canonicalUserReportCorrectionDescriptionOrNull(description) == description,
    )
    require(validUserReportTimestamp(correctedAt))
    UserReportContentRevision(
        reportId = reportId,
        revision = revision,
        expectedRevision = expectedRevision,
        idempotencyKey = idempotencyKey,
        contentSha256 = contentSha256,
        userDescription = description,
        categoryHint = root.strictNullableContentCategory("category_hint"),
        correctedAt = correctedAt,
    )
}.getOrNull()

internal fun validatedUserReportDeletionStatusOrNull(body: String): UserReportDeletionStatus? =
    runCatching {
        val root = JSONObject(body)
        require(
            root.exactKeys(
                "schema_version",
                "request_id",
                "report_id",
                "state",
                "request_status_version",
                "external_copy_count",
                "external_copies",
                "updated_at",
            ),
        )
        require(root.strictString("schema_version") == USER_REPORT_DELETION_STATUS_SCHEMA_VERSION)
        val requestId = root.strictString("request_id")
        val reportId = root.strictString("report_id")
        val updatedAt = root.strictString("updated_at")
        require(validCanonicalUserReportUuid(requestId))
        require(validCanonicalUserReportUuid(reportId))
        require(validUserReportTimestamp(updatedAt))
        val externalCopyCount = root.strictNonNegativeLong("external_copy_count")
        val rawExternalCopies = root.get("external_copies") as? JSONArray
            ?: error("external_copies must be an array")
        require(externalCopyCount == rawExternalCopies.length().toLong())
        val externalCopies = buildList {
            repeat(rawExternalCopies.length()) { index ->
                val item = rawExternalCopies.get(index) as? JSONObject
                    ?: error("external copy must be an object")
                require(item.exactKeys("institution", "state", "status_recorded_at"))
                val institution = item.strictString("institution")
                require(institution == institution.trim())
                require(institution.codePointCount(0, institution.length) in 1..160)
                val statusRecordedAt = item.strictNullableString(
                    "status_recorded_at",
                    20,
                    40,
                )
                require(statusRecordedAt == null || validUserReportTimestamp(statusRecordedAt))
                add(
                    UserReportDeletionExternalCopyStatus(
                        institution = institution,
                        state = UserReportExternalCopyDeletionState.fromWireOrNull(
                            item.strictString("state"),
                        ) ?: error("unknown external copy deletion state"),
                        statusRecordedAt = statusRecordedAt,
                    ),
                )
            }
        }
        UserReportDeletionStatus(
            requestId = requestId,
            reportId = reportId,
            state = UserReportDeletionState.fromWireOrNull(root.strictString("state"))
                ?: error("unknown deletion state"),
            requestStatusVersion = root.strictPositiveLong("request_status_version"),
            externalCopyCount = externalCopyCount,
            externalCopies = externalCopies,
            updatedAt = updatedAt,
        )
    }.getOrNull()

internal fun validCanonicalUserReportUuid(value: String): Boolean {
    if (!CANONICAL_UUID.matches(value)) return false
    return runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
}

internal fun validUserReportTimestamp(value: String): Boolean {
    if (!UTC_TIMESTAMP.matches(value)) return false
    return runCatching { Instant.parse(value) }.isSuccess
}

internal fun validUserReportCursor(value: String): Boolean = USER_REPORT_CURSOR.matches(value)

internal fun validUserReportRequestText(value: String): Boolean =
    value.codePointCount(0, value.length) in 1..500 && value == value.trim()

internal fun canonicalUserReportCorrectionDescriptionOrNull(value: String): String? {
    val nfc = Normalizer.normalize(value, Normalizer.Form.NFC)
    if (CONTROL_CHARACTER.containsMatchIn(nfc)) return null
    return nfc.split(WHITESPACE)
        .filter(String::isNotEmpty)
        .joinToString(" ")
        .takeIf { it.codePointCount(0, it.length) in 1..500 }
}

private fun parseUserReportSummary(
    value: Any,
    includesSchemaVersion: Boolean = false,
): UserReportSummary {
    val item = value as? JSONObject ?: error("report item must be an object")
    if (!includesSchemaVersion) {
        require(
            item.exactKeys(
                "report_id",
                "created_at",
                "user_status",
                "public_rejection_reason",
                "latest_request",
            ),
        )
    }
    val reportId = item.strictString("report_id")
    val createdAt = item.strictString("created_at")
    require(validCanonicalUserReportUuid(reportId))
    require(validUserReportTimestamp(createdAt))
    val status = UserReportStatus.fromWireOrNull(item.strictString("user_status"))
        ?: error("unknown user report status")
    val rejectionReason = item.strictNullableString("public_rejection_reason", 1, 500)
    require(
        if (status == UserReportStatus.REJECTED) {
            rejectionReason != null
        } else {
            rejectionReason == null
        },
    )
    val latestRequest = when (val raw = item.get("latest_request")) {
        JSONObject.NULL -> null
        else -> parseUserReportRequest(raw as? JSONObject ?: error("latest_request must be an object"))
    }
    return UserReportSummary(
        reportId = reportId,
        createdAt = createdAt,
        userStatus = status,
        publicRejectionReason = rejectionReason,
        latestRequest = latestRequest,
    )
}

private fun parseUserReportRequest(value: JSONObject): UserReportRequestSummary {
    require(
        value.exactKeys(
            "request_id",
            "request_type",
            "status",
            "status_version",
            "public_response",
            "created_at",
            "updated_at",
        ),
    )
    val requestId = value.strictString("request_id")
    val createdAt = value.strictString("created_at")
    val updatedAt = value.strictString("updated_at")
    require(validCanonicalUserReportUuid(requestId))
    require(validUserReportTimestamp(createdAt) && validUserReportTimestamp(updatedAt))
    val statusVersion = value.strictPositiveLong("status_version")
    return UserReportRequestSummary(
        requestId = requestId,
        requestType = UserReportRequestType.fromWireOrNull(value.strictString("request_type"))
            ?: error("unknown request type"),
        status = UserReportRequestStatus.fromWireOrNull(value.strictString("status"))
            ?: error("unknown request status"),
        statusVersion = statusVersion,
        publicResponse = value.strictNullableString("public_response", 1, 500),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private fun JSONObject.exactKeys(vararg expected: String): Boolean {
    val actual = mutableSetOf<String>()
    val iterator = keys()
    while (iterator.hasNext()) actual += iterator.next()
    return actual == expected.toSet()
}

private fun JSONObject.strictString(name: String): String =
    get(name) as? String ?: error("$name must be a string")

private fun JSONObject.strictNullableString(
    name: String,
    minimumLength: Int,
    maximumLength: Int,
): String? = when (val value = get(name)) {
    JSONObject.NULL -> null
    is String -> value.takeIf {
        it.codePointCount(0, it.length) in minimumLength..maximumLength
    }
        ?: error("$name length is invalid")
    else -> error("$name must be a string or null")
}

private fun JSONObject.strictNullableContentCategory(name: String): UserReportContentCategory? =
    when (val value = get(name)) {
        JSONObject.NULL -> null
        is String -> UserReportContentCategory.fromWireOrNull(value)
            ?: error("$name is unknown")
        else -> error("$name must be a string or null")
    }

private fun JSONObject.strictPositiveLong(name: String): Long {
    val parsed = when (val value = get(name)) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> error("$name must be an integer")
    }
    require(parsed in 1L..MAX_SAFE_JSON_INTEGER)
    return parsed
}

private fun JSONObject.strictNonNegativeLong(name: String): Long {
    val parsed = when (val value = get(name)) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> error("$name must be an integer")
    }
    require(parsed in 0L..MAX_SAFE_JSON_INTEGER)
    return parsed
}

private val CANONICAL_UUID =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val UTC_TIMESTAMP =
    Regex(
        "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}" +
            "(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})$",
    )
private val USER_REPORT_CURSOR = Regex("^[A-Za-z0-9_-]{1,1024}$")
private val LOWER_SHA256 = Regex("^[0-9a-f]{64}$")
private val CONTROL_CHARACTER = Regex("\\p{C}")
private val WHITESPACE = Regex("(?U)\\s+")
private const val MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L
