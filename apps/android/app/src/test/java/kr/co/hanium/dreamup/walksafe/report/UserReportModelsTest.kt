package kr.co.hanium.dreamup.walksafe.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UserReportModelsTest {
    @Test
    fun listParserAcceptsAllFourUserStatusesAndAllFourRequestStatuses() {
        val userStatuses = UserReportStatus.entries.map(UserReportStatus::wireValue)
        val requestStatuses = UserReportRequestStatus.entries.map(UserReportRequestStatus::wireValue)
        val items = userStatuses.mapIndexed { index, userStatus ->
            reportItem(
                reportId = reportId(index + 1),
                userStatus = userStatus,
                requestStatus = requestStatuses[index],
                rejectionReason = if (userStatus == "REJECTED") "공개 기각 사유" else null,
            )
        }.joinToString(",")

        val parsed = validatedUserReportListOrNull(
            """{"schema_version":"walksafe.user-report-list.v1","items":[$items],"next_cursor":"cursor_1"}""",
        )

        assertNotNull(parsed)
        assertEquals(UserReportStatus.entries, parsed?.items?.map(UserReportSummary::userStatus))
        assertEquals(
            UserReportRequestStatus.entries,
            parsed?.items?.map { it.latestRequest?.status },
        )
        assertEquals("공개 기각 사유", parsed?.items?.get(1)?.publicRejectionReason)
        assertEquals("cursor_1", parsed?.nextCursor)
    }

    @Test
    fun strictParsersRejectUnknownMissingWrongTypeUuidTimestampStatusAndCursor() {
        val valid =
            """{"schema_version":"walksafe.user-report-list.v1","items":[${reportItem(reportId(1))}],"next_cursor":null}"""
        val invalidBodies = listOf(
            valid.dropLast(1) + ",\"unknown\":true}",
            valid.replace("\"latest_request\":", "\"unknown\":true,\"latest_request\":"),
            valid.replace(",\"next_cursor\":null", ""),
            valid.replace("\"items\":[", "\"items\":\""),
            valid.replace(reportId(1), reportId(1).uppercase()),
            valid.replace(TIMESTAMP, "2026-08-29 01:02:03Z"),
            valid.replace("\"RECEIVED\"", "\"PENDING\""),
            valid.replace("\"next_cursor\":null", "\"next_cursor\":\"bad cursor\""),
        )

        invalidBodies.forEach { assertNull(validatedUserReportListOrNull(it)) }
        assertNotNull(validatedUserReportListOrNull(valid))
    }

    @Test
    fun detailAndRequestRequireExactMinimumSchemasAndIntegralPositiveVersion() {
        val detail =
            """{"schema_version":"walksafe.user-report-detail.v1",${reportItem(reportId(1)).removePrefix("{").removeSuffix("}")}}"""
        val request = requestSummary(requestStatus = "ACKNOWLEDGED")

        assertEquals(reportId(1), validatedUserReportDetailOrNull(detail)?.reportId)
        assertEquals(
            UserReportRequestStatus.ACKNOWLEDGED,
            validatedUserReportRequestOrNull(request)?.status,
        )
        assertNull(validatedUserReportDetailOrNull(detail.replace("detail.v1", "detail.v2")))
        assertNull(validatedUserReportRequestOrNull(request.dropLast(1) + ",\"internal_note\":null}"))
        assertNull(validatedUserReportRequestOrNull(request.replace("\"status_version\":1", "\"status_version\":1.0")))
        assertNull(validatedUserReportRequestOrNull(request.replace("\"status_version\":1", "\"status_version\":0")))
    }

    @Test
    fun publicRejectionReasonIsRequiredOnlyForRejectedAndBoundedToFiveHundredCharacters() {
        val rejectedWithMinimumReason = listBody(
            reportItem(
                reportId = reportId(1),
                userStatus = "REJECTED",
                rejectionReason = "사",
            ),
        )
        val rejectedWithMaximumReason = listBody(
            reportItem(
                reportId = reportId(1),
                userStatus = "REJECTED",
                rejectionReason = "가".repeat(500),
            ),
        )

        assertNotNull(validatedUserReportListOrNull(rejectedWithMinimumReason))
        assertNotNull(validatedUserReportListOrNull(rejectedWithMaximumReason))
        assertNull(
            validatedUserReportListOrNull(
                listBody(reportItem(reportId(1), userStatus = "REJECTED")),
            ),
        )
        assertNull(
            validatedUserReportListOrNull(
                listBody(
                    reportItem(
                        reportId(1),
                        userStatus = "REJECTED",
                        rejectionReason = "",
                    ),
                ),
            ),
        )
        assertNull(
            validatedUserReportListOrNull(
                listBody(
                    reportItem(
                        reportId(1),
                        userStatus = "REJECTED",
                        rejectionReason = "가".repeat(501),
                    ),
                ),
            ),
        )
        listOf("RECEIVED", "INSTITUTION_SUBMITTED", "RESOLVED").forEach { status ->
            assertNotNull(
                validatedUserReportListOrNull(
                    listBody(reportItem(reportId(1), userStatus = status)),
                ),
            )
            assertNull(
                validatedUserReportListOrNull(
                    listBody(
                        reportItem(
                            reportId(1),
                            userStatus = status,
                            rejectionReason = "허용되지 않는 사유",
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun canonicalIdentifiersTimestampCursorAndRequestTextAreBounded() {
        assertTrue(validCanonicalUserReportUuid(reportId(1)))
        assertFalse(validCanonicalUserReportUuid(reportId(1).uppercase()))
        assertTrue(validUserReportTimestamp(TIMESTAMP))
        assertTrue(validUserReportTimestamp("2026-08-29T01:02:03+00:00"))
        assertFalse(validUserReportTimestamp("2026-08-29 01:02:03"))
        assertTrue(validUserReportCursor("A_b-9"))
        assertFalse(validUserReportCursor("a".repeat(1_025)))
        assertTrue(validUserReportRequestText("표면 손상을 정정해 주세요."))
        assertTrue(validUserReportRequestText("😀".repeat(500)))
        assertFalse(validUserReportRequestText(" 앞뒤 공백 금지"))
        assertFalse(validUserReportRequestText("가".repeat(501)))
        assertFalse(validUserReportRequestText("😀".repeat(501)))
    }

    @Test
    fun contentAndRevisionParsersRequireExactSchemasAndRevisionConsistency() {
        val initial = contentBody(
            revision = 0,
            userDescription = "null",
            categoryHint = "null",
            correctedAt = "null",
        )
        val corrected = contentBody(
            revision = 2,
            userDescription = "\"보행로 파손\"",
            categoryHint = "\"ROAD_DAMAGE\"",
            correctedAt = "\"$TIMESTAMP\"",
        )
        val revision = revisionBody(
            revision = 2,
            expectedRevision = 1,
            userDescription = "\"보행로 파손\"",
            categoryHint = "null",
        )

        assertEquals(0L, validatedUserReportContentOrNull(initial)?.revision)
        assertEquals(
            UserReportContentCategory.ROAD_DAMAGE,
            validatedUserReportContentOrNull(corrected)?.categoryHint,
        )
        assertEquals(2L, validatedUserReportContentRevisionOrNull(revision)?.revision)
        listOf(
            initial.dropLast(1) + ",\"internal\":true}",
            initial.replace("\"revision\":0", "\"revision\":0.0"),
            initial.replace("\"user_description\":null", "\"user_description\":\"unexpected\""),
            corrected.replace(SHA256, SHA256.uppercase()),
            corrected.replace("\"ROAD_DAMAGE\"", "\"UNKNOWN\""),
            corrected.replace("보행로 파손", "보행로  파손"),
            corrected.replace("\"corrected_at\":\"$TIMESTAMP\"", "\"corrected_at\":null"),
        ).forEach { assertNull(validatedUserReportContentOrNull(it)) }
        listOf(
            revision.dropLast(1) + ",\"internal\":true}",
            revision.replace("\"revision\":2", "\"revision\":3"),
            revision.replace(IDEMPOTENCY_KEY, IDEMPOTENCY_KEY.uppercase()),
            revision.replace("\"expected_revision\":1", "\"expected_revision\":1.0"),
            revision.replace("\"category_hint\":null", "\"category_hint\":\"UNKNOWN\""),
            revision.replace("보행로 파손", "보행로  파손"),
        ).forEach { assertNull(validatedUserReportContentRevisionOrNull(it)) }
    }

    @Test
    fun correctionPatchDistinguishesOmittedClearAndCanonicalValue() {
        assertThrows(IllegalArgumentException::class.java) {
            UserReportCorrectionIntent(
                reportId = reportId(1),
                expectedRevision = 0,
                idempotencyKey = IDEMPOTENCY_KEY,
            )
        }
        val clear = UserReportCorrectionIntent(
            reportId = reportId(1),
            expectedRevision = 0,
            idempotencyKey = IDEMPOTENCY_KEY,
            userDescription = UserReportCorrectionPatch.Clear,
        )
        val value = UserReportCorrectionIntent(
            reportId = reportId(1),
            expectedRevision = 0,
            idempotencyKey = IDEMPOTENCY_KEY,
            userDescription = UserReportCorrectionPatch.Value("  cafe\u0301   파손  "),
            categoryHint = UserReportCorrectionPatch.Omitted,
        )

        assertEquals(UserReportCorrectionPatch.Clear, clear.userDescription)
        assertEquals(UserReportCorrectionPatch.Omitted, clear.categoryHint)
        assertEquals(
            "café 파손",
            canonicalUserReportCorrectionDescriptionOrNull(
                (value.userDescription as UserReportCorrectionPatch.Value<String>).value,
            ),
        )
        assertNull(canonicalUserReportCorrectionDescriptionOrNull("café\t파손"))
        assertEquals(
            "😀".repeat(500),
            canonicalUserReportCorrectionDescriptionOrNull("😀".repeat(500)),
        )
        assertNull(canonicalUserReportCorrectionDescriptionOrNull("😀".repeat(501)))
        assertNull(canonicalUserReportCorrectionDescriptionOrNull("제어\u0000문자"))
        assertThrows(IllegalArgumentException::class.java) {
            UserReportCorrectionIntent(
                reportId = reportId(1),
                expectedRevision = 0,
                idempotencyKey = IDEMPOTENCY_KEY,
                userDescription = UserReportCorrectionPatch.Value("제어\u0000문자"),
            )
        }
    }

    @Test
    fun deletionStatusParserAcceptsOnlyExactPhysicalDeletionStatesAndBounds() {
        UserReportDeletionState.entries.forEach { state ->
            val parsed = validatedUserReportDeletionStatusOrNull(
                deletionBody(state = state.wireValue),
            )
            assertEquals(state, parsed?.state)
            assertEquals(0L, parsed?.externalCopyCount)
            assertEquals(emptyList<UserReportDeletionExternalCopyStatus>(), parsed?.externalCopies)
        }
        val externalCopies = UserReportExternalCopyDeletionState.entries.mapIndexed { index, state ->
            val recordedAt = if (state == UserReportExternalCopyDeletionState.NOT_REQUESTED) {
                "null"
            } else {
                "\"$TIMESTAMP\""
            }
            """{"institution":"기관 ${index + 1}","state":"${state.wireValue}","status_recorded_at":$recordedAt}"""
        }.joinToString(",")
        val deleted = validatedUserReportDeletionStatusOrNull(
            deletionBody(
                state = "DELETED",
                externalCopyCount = UserReportExternalCopyDeletionState.entries.size,
                externalCopies = externalCopies,
            ),
        )
        assertEquals(
            UserReportExternalCopyDeletionState.entries,
            deleted?.externalCopies?.map(UserReportDeletionExternalCopyStatus::state),
        )
        assertNull(deleted?.externalCopies?.first()?.statusRecordedAt)
        assertEquals(TIMESTAMP, deleted?.externalCopies?.get(1)?.statusRecordedAt)

        val valid = deletionBody(state = "PENDING")
        val oneExternalCopy =
            """{"institution":"서울시청","state":"REQUEST_SENT","status_recorded_at":"$TIMESTAMP"}"""
        listOf(
            valid.dropLast(1) + ",\"reason\":\"private\"}",
            valid.replace("walksafe.report-deletion-status.v2", "walksafe.report-deletion-status.v1"),
            valid.replace("\"PENDING\"", "\"UNKNOWN\""),
            valid.replace("\"request_status_version\":1", "\"request_status_version\":0"),
            valid.replace("\"external_copy_count\":0", "\"external_copy_count\":-1"),
            valid.replace("\"external_copy_count\":0", "\"external_copy_count\":0.0"),
            deletionBody("DELETED", 0, oneExternalCopy),
            deletionBody("PENDING", 1, oneExternalCopy),
            deletionBody("DELETED", 1, oneExternalCopy.dropLast(1) + ",\"internal_note\":\"private\"}"),
            deletionBody("DELETED", 1, oneExternalCopy.replace("REQUEST_SENT", "UNKNOWN")),
            deletionBody("DELETED", 1, oneExternalCopy.replace(TIMESTAMP, "not-a-time")),
            deletionBody("DELETED", 1, oneExternalCopy.replace("서울시청", " 서울시청 ")),
            valid.replace(REQUEST_ID, REQUEST_ID.uppercase()),
        ).forEach { assertNull(validatedUserReportDeletionStatusOrNull(it)) }
    }

    @Test
    fun requestHistoryParserAcceptsActiveRequestsAndDeletionTombstonesNewestFirst() {
        val activeCorrection = historyItem(
            revision = 3,
            requestId = requestId(3),
            request = requestSummary(requestId = requestId(3)),
        )
        val activeDeletion = historyItem(
            revision = 2,
            requestId = requestId(2),
            request = requestSummary(
                requestId = requestId(2),
                requestType = "DELETE",
                statusVersion = 2,
            ),
            deletionStatus = deletionBody(
                state = "PENDING",
                requestId = requestId(2),
                requestStatusVersion = 2,
            ),
        )
        val tombstone = historyItem(
            revision = 1,
            source = "DELETION_TOMBSTONE",
            requestId = requestId(1),
            request = "null",
            deletionStatus = deletionBody(
                state = "DELETED",
                requestId = requestId(1),
            ),
        )

        val parsed = validatedUserReportRequestHistoryOrNull(
            historyPage(
                items = listOf(activeCorrection, activeDeletion, tombstone),
                snapshotRevision = 3,
                totalCount = 3,
            ),
        )

        assertNotNull(parsed)
        assertNull(parsed?.reportId)
        assertEquals(listOf(3L, 2L, 1L), parsed?.items?.map { it.revision })
        assertEquals(
            listOf(
                UserReportRequestHistorySource.ACTIVE_REQUEST,
                UserReportRequestHistorySource.ACTIVE_REQUEST,
                UserReportRequestHistorySource.DELETION_TOMBSTONE,
            ),
            parsed?.items?.map { it.source },
        )
        assertEquals(UserReportRequestType.DELETE, parsed?.items?.get(1)?.request?.requestType)
        assertEquals(UserReportDeletionState.DELETED, parsed?.items?.last()?.deletionStatus?.state)
        assertNull(parsed?.items?.last()?.request)
    }

    @Test
    fun requestHistoryParserRejectsNonV1OrInconsistentSourceProjections() {
        val correction = historyItem(
            revision = 2,
            requestId = requestId(2),
            request = requestSummary(requestId = requestId(2)),
        )
        val tombstone = historyItem(
            revision = 1,
            source = "DELETION_TOMBSTONE",
            requestId = requestId(1),
            request = "null",
            deletionStatus = deletionBody("DELETED", requestId = requestId(1)),
        )
        val valid = historyPage(
            items = listOf(correction, tombstone),
            snapshotRevision = 2,
            totalCount = 2,
            nextCursor = "cursor_1",
        )
        val activeDeletion = historyPage(
            items = listOf(
                historyItem(
                    revision = 1,
                    requestId = requestId(1),
                    request = requestSummary(
                        requestId = requestId(1),
                        requestType = "DELETE",
                        statusVersion = 2,
                    ),
                    deletionStatus = deletionBody(
                        state = "PENDING",
                        requestId = requestId(1),
                        requestStatusVersion = 2,
                    ),
                ),
            ),
            snapshotRevision = 1,
            totalCount = 1,
        )
        val invalidBodies = listOf(
            valid.dropLast(1) + ",\"internal\":true}",
            valid.replace("history-page.v1", "history-page.v2"),
            valid.replace("\"snapshot_revision\":2", "\"snapshot_revision\":2.0"),
            valid.replace("\"total_count\":2", "\"total_count\":1"),
            valid.replace("\"next_cursor\":\"cursor_1\"", "\"next_cursor\":\"bad cursor\""),
            valid.replace("\"revision\":2", "\"revision\":1"),
            valid.replace("\"request\":${requestSummary(requestId = requestId(2))}", "\"request\":null"),
            valid.replace("\"deletion_status\":null", "\"deletion_status\":${deletionBody("PENDING", requestId = requestId(2))}"),
            valid.replaceFirst("\"request\":null", "\"request\":${requestSummary(requestId = requestId(1))}"),
            valid.replaceFirst("\"state\":\"DELETED\"", "\"state\":\"PENDING\""),
            valid.replaceFirst(requestId(1), requestId(3)),
            valid.replace("\"deletion_status\":", "\"unknown\":true,\"deletion_status\":"),
            historyPage(
                items = listOf(
                    historyItem(
                        revision = 2,
                        requestId = requestId(2),
                        request = requestSummary(requestId = requestId(2)),
                    ),
                    historyItem(
                        revision = 1,
                        requestId = requestId(2),
                        request = requestSummary(requestId = requestId(2)),
                    ),
                ),
                snapshotRevision = 2,
                totalCount = 2,
            ),
            activeDeletion.replace(
                "\"deletion_status\":${deletionBody("PENDING", requestId = requestId(1), requestStatusVersion = 2)}",
                "\"deletion_status\":null",
            ),
            activeDeletion.replace("\"request_status_version\":2", "\"request_status_version\":3"),
            activeDeletion.replace("\"state\":\"PENDING\"", "\"state\":\"DELETED\""),
            valid + "{}",
            valid.replaceFirst(
                "\"schema_version\":",
                "\"schema_version\":\"walksafe.user-report-request-history-page.v1\"," +
                    "\"schema_version\":",
            ),
            valid.replaceFirst(
                "\"schema_version\":",
                "\"\\u0073chema_version\":" +
                    "\"walksafe.user-report-request-history-page.v1\"," +
                    "\"schema_version\":",
            ),
            valid.replaceFirst("\"items\":", "/*comment*/\"items\":"),
            valid.replaceFirst("\"schema_version\"", "'schema_version'"),
            valid.replaceFirst(":", "="),
            valid.replaceFirst(",", ";"),
        )

        invalidBodies.forEach { assertNull(validatedUserReportRequestHistoryOrNull(it)) }
        assertNotNull(validatedUserReportRequestHistoryOrNull(valid))
        assertNotNull(validatedUserReportRequestHistoryOrNull(" \n$valid\r\n\t"))
        assertNotNull(validatedUserReportRequestHistoryOrNull(activeDeletion))
        assertNull(
            validatedUserReportRequestHistoryOrNull(
                historyPage(emptyList(), snapshotRevision = 0, totalCount = 0, nextCursor = "cursor_1"),
            ),
        )
    }

    private fun reportItem(
        reportId: String,
        userStatus: String = "RECEIVED",
        requestStatus: String = "RECEIVED",
        rejectionReason: String? = null,
    ): String =
        """{"report_id":"$reportId","created_at":"$TIMESTAMP","user_status":"$userStatus","public_rejection_reason":${rejectionReason?.let { "\"$it\"" } ?: "null"},"latest_request":${requestSummary(requestStatus)}}"""

    private fun listBody(item: String): String =
        """{"schema_version":"walksafe.user-report-list.v1","items":[$item],"next_cursor":null}"""

    private fun requestSummary(
        requestStatus: String = "RECEIVED",
        requestId: String = requestId(),
        requestType: String = "CORRECTION",
        statusVersion: Int = 1,
    ): String =
        """{"request_id":"$requestId","request_type":"$requestType","status":"$requestStatus","status_version":$statusVersion,"public_response":null,"created_at":"$TIMESTAMP","updated_at":"$TIMESTAMP"}"""

    private fun historyItem(
        revision: Int,
        requestId: String,
        source: String = "ACTIVE_REQUEST",
        request: String,
        deletionStatus: String = "null",
    ): String =
        """{"revision":$revision,"source":"$source","report_id":"${reportId(1)}","request_id":"$requestId","request":$request,"deletion_status":$deletionStatus}"""

    private fun historyPage(
        items: List<String>,
        snapshotRevision: Int,
        totalCount: Int,
        nextCursor: String? = null,
        reportId: String? = null,
    ): String =
        """{"schema_version":"walksafe.user-report-request-history-page.v1","report_id":${reportId?.let { "\"$it\"" } ?: "null"},"snapshot_revision":$snapshotRevision,"total_count":$totalCount,"items":[${items.joinToString(",")}],"next_cursor":${nextCursor?.let { "\"$it\"" } ?: "null"}}"""

    private fun contentBody(
        revision: Int,
        userDescription: String,
        categoryHint: String,
        correctedAt: String,
    ): String =
        """{"schema_version":"walksafe.report-content-current.v1","report_id":"${reportId(1)}","revision":$revision,"content_sha256":"$SHA256","user_description":$userDescription,"category_hint":$categoryHint,"corrected_at":$correctedAt}"""

    private fun revisionBody(
        revision: Int,
        expectedRevision: Int,
        userDescription: String,
        categoryHint: String,
    ): String =
        """{"schema_version":"walksafe.report-content-revision.v1","report_id":"${reportId(1)}","revision":$revision,"expected_revision":$expectedRevision,"idempotency_key":"$IDEMPOTENCY_KEY","content_sha256":"$SHA256","user_description":$userDescription,"category_hint":$categoryHint,"corrected_at":"$TIMESTAMP"}"""

    private fun deletionBody(
        state: String,
        externalCopyCount: Int = 0,
        externalCopies: String = "",
        requestId: String = REQUEST_ID,
        requestStatusVersion: Int = 1,
    ): String =
        """{"schema_version":"walksafe.report-deletion-status.v2","request_id":"$requestId","report_id":"${reportId(1)}","state":"$state","request_status_version":$requestStatusVersion,"external_copy_count":$externalCopyCount,"external_copies":[$externalCopies],"updated_at":"$TIMESTAMP"}"""

    private fun reportId(index: Int): String =
        "aaaaaaaa-aaaa-4aaa-8aaa-${index.toString().padStart(12, '0')}"

    private fun requestId(): String = "11111111-1111-4111-8111-111111111111"

    private fun requestId(index: Int): String =
        "11111111-1111-4111-8111-${index.toString().padStart(12, '0')}"

    private companion object {
        const val TIMESTAMP = "2026-08-29T01:02:03.000000Z"
        const val REQUEST_ID = "abcdefab-cdef-4abc-8def-abcdefabcdef"
        const val IDEMPOTENCY_KEY = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
