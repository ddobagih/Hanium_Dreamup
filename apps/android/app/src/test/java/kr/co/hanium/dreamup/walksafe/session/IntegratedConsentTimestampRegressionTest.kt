package kr.co.hanium.dreamup.walksafe.session

import java.time.Instant
import kr.co.hanium.dreamup.walksafe.network.validatedIntegratedConsentConfirmationOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegratedConsentTimestampRegressionTest {
    @Test
    fun nonCanonicalAndInvalidCalendarTimestampsAreRejectedByDtoAndNetworkParser() {
        val invalid = listOf(
            "",
            " ",
            "not-a-time",
            "2026-07-25",
            "2026-07-25T12:00:00Z",
            "2026-07-25T12:00:00.0Z",
            "2026-07-25T12:00:00.00Z",
            "2026-07-25T12:00:00.0000Z",
            "2026-07-25T12:00:00.000+00:00",
            "2026-07-25T12:00:00.000+09:00",
            "2026-07-25T12:00:00.000",
            "2026-07-25t12:00:00.000Z",
            "2026-07-25T12:00:00.000z",
            " 2026-07-25T12:00:00.000Z",
            "2026-07-25T12:00:00.000Z ",
            "2026-07-25T12:00:00.000Z\n",
            "2026-7-25T12:00:00.000Z",
            "2026-07-25T24:00:00.000Z",
            "2016-12-31T23:59:60.000Z",
            "2025-02-29T00:00:00.000Z",
            "1900-02-29T00:00:00.000Z",
            "2026-04-31T00:00:00.000Z",
            "2026-13-01T00:00:00.000Z",
            "2026-01-00T00:00:00.000Z",
            "2026-01-01T00:60:00.000Z",
            "10000-01-01T00:00:00.000Z",
            "+10000-01-01T00:00:00.000Z",
            "+002026-07-25T12:00:00.000Z",
            "+000000-01-01T00:00:00.000Z",
            "-000000-01-01T00:00:00.000Z",
            "-0001-01-01T00:00:00.000Z",
            "+0275760-09-13T00:00:00.000Z",
            "-271821-04-19T23:59:59.999Z",
            "+275760-09-13T00:00:00.001Z",
            "+999999-01-01T00:00:00.000Z",
            "-999999-01-01T00:00:00.000Z",
        )

        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                confirmation(value)
            }
            assertNull(
                validatedIntegratedConsentConfirmationOrNull(
                    body = wireConfirmation(value),
                    expectedInstallationId = INSTALLATION_ID,
                    expectedControlSecret = CONTROL_SECRET,
                ),
            )
        }
    }

    @Test
    fun canonicalServerTimestampsPreserveRawTextAndExpectedInstantAcrossBothPaths() {
        val cases = listOf(
            "1970-01-01T00:00:00.000Z" to 0L,
            "1970-01-01T00:00:00.001Z" to 1L,
            "1970-01-01T00:00:00.010Z" to 10L,
            "1970-01-01T00:00:00.100Z" to 100L,
            "1970-01-01T00:00:00.999Z" to 999L,
            "1969-12-31T23:59:59.999Z" to -1L,
            "2000-02-29T00:00:00.000Z" to 951_782_400_000L,
            "0000-01-01T00:00:00.000Z" to -62_167_219_200_000L,
            "9999-12-31T23:59:59.999Z" to 253_402_300_799_999L,
            "+010000-01-01T00:00:00.000Z" to 253_402_300_800_000L,
            "-000001-01-01T00:00:00.000Z" to -62_198_755_200_000L,
            "-271821-04-20T00:00:00.000Z" to -8_640_000_000_000_000L,
            "-271821-04-20T00:00:00.001Z" to -8_639_999_999_999_999L,
            "+275760-09-12T23:59:59.999Z" to 8_639_999_999_999_999L,
            "+275760-09-13T00:00:00.000Z" to 8_640_000_000_000_000L,
        )

        cases.forEach { (raw, expectedEpochMs) ->
            val expectedInstant = Instant.ofEpochMilli(expectedEpochMs)
            val direct = confirmation(raw)
            assertEquals(raw, direct.confirmedAt)
            assertEquals(expectedInstant, direct.confirmedAtInstant)
            assertEquals(expectedEpochMs, direct.confirmedAtInstant.toEpochMilli())

            val parsed = validatedIntegratedConsentConfirmationOrNull(
                body = wireConfirmation(raw),
                expectedInstallationId = INSTALLATION_ID,
                expectedControlSecret = CONTROL_SECRET,
            )
            assertNotNull(parsed)
            val network = parsed!!
            assertEquals(direct, network)
            assertEquals(raw, network.confirmedAt)
            assertEquals(expectedInstant, network.confirmedAtInstant)
            assertEquals(expectedEpochMs, network.confirmedAtInstant.toEpochMilli())
        }
    }

    @Test
    fun serverRevisionOrderingAndExactDuplicatesDoNotUseTimestampOrdering() {
        val session = IntegratedConsentSession()
        val initial = confirmation(
            confirmedAt = "2000-02-29T00:00:00.000Z",
            clientRevision = 10L,
            revision = 10L,
        )
        assertEquals(951_782_400_000L, initial.confirmedAtInstant.toEpochMilli())
        assertEquals(IntegratedConsentApplyResult.APPLIED, session.applyConfirmation(initial))

        val newer = initial.copy(
            requestId = "timestamp_newer_request_0002",
            clientRevision = 11L,
            revision = 11L,
            selections = initial.selections.copy(trainingReuse = true),
            confirmedAt = "1970-01-01T00:00:00.000Z",
        )
        assertEquals(0L, newer.confirmedAtInstant.toEpochMilli())
        assertEquals(IntegratedConsentApplyResult.APPLIED, session.applyConfirmation(newer))
        assertEquals(newer, session.currentConfirmationOrNull())
        assertTrue(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertTrue(session.isAllowed(IntegratedConsentItem.TRAINING_REUSE))

        val duplicate = newer.copy()
        assertEquals(newer, duplicate)
        assertEquals(
            IntegratedConsentApplyResult.DUPLICATE,
            session.applyConfirmation(duplicate),
        )
        assertEquals(newer, session.currentConfirmationOrNull())

        val stale = newer.copy(
            requestId = "timestamp_stale_request_0003",
            clientRevision = 9L,
            revision = 9L,
            selections = IntegratedConsentSelections(),
            confirmedAt = "+010000-01-01T00:00:00.000Z",
        )
        assertEquals(253_402_300_800_000L, stale.confirmedAtInstant.toEpochMilli())
        assertEquals(
            IntegratedConsentApplyResult.STALE_IGNORED,
            session.applyConfirmation(stale),
        )
        assertEquals(newer, session.currentConfirmationOrNull())
        assertTrue(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
        assertTrue(session.isAllowed(IntegratedConsentItem.TRAINING_REUSE))

        assertEquals(
            IntegratedConsentApplyResult.REVISION_CONFLICT_FAIL_CLOSED,
            session.evaluateConfirmation(
                newer.copy(confirmedAt = "1970-01-01T00:00:00.001Z"),
            ),
        )
        assertEquals(newer, session.currentConfirmationOrNull())
    }

    @Test
    fun exactPendingDuplicateStillCompletesWithdrawalWithAnEarlierTimestamp() {
        val session = IntegratedConsentSession()
        val initial = confirmation("2000-02-29T00:00:00.000Z")
        assertTrue(session.accept(initial))
        val pending = PendingIntegratedConsentMutation(
            installationId = INSTALLATION_ID,
            actorSha256 = "c".repeat(64),
            requestId = "timestamp_withdrawal_request_0002",
            policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
            clientRevision = 2L,
            previousServerRevision = initial.revision,
            expectedPreviousBackendReceiptSha256 = initial.backendConsentReceiptSha256,
            desiredSelections = initial.selections.copy(rawSourceCollection = false),
            withdrawalItems = setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
            createdAtEpochMs = 1_000L,
        )
        assertTrue(session.restorePendingMutation(pending))
        val denial = initial.copy(
            requestId = pending.requestId,
            clientRevision = pending.clientRevision,
            revision = 2L,
            selections = pending.desiredSelections,
            confirmedAt = "1970-01-01T00:00:00.000Z",
        )
        assertEquals(0L, denial.confirmedAtInstant.toEpochMilli())
        assertEquals(
            IntegratedConsentApplyResult.APPLIED,
            session.applyConfirmation(denial),
        )
        assertEquals(pending, session.pendingMutationOrNull())
        assertEquals(
            PurposeConsentSyncState.WITHDRAWAL_PENDING,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))

        val duplicate = denial.copy()
        assertEquals(denial, duplicate)
        assertEquals(
            IntegratedConsentApplyResult.DUPLICATE,
            session.evaluateExactPendingConfirmation(duplicate),
        )
        assertEquals(
            IntegratedConsentApplyResult.REVISION_CONFLICT_FAIL_CLOSED,
            session.evaluateExactPendingConfirmation(
                duplicate.copy(confirmedAt = "1970-01-01T00:00:00.001Z"),
            ),
        )
        assertEquals(pending, session.pendingMutationOrNull())
        assertTrue(session.applyExactPendingConfirmation(duplicate))
        assertNull(session.pendingMutationOrNull())
        assertEquals(duplicate, session.currentConfirmationOrNull())
        assertEquals(
            PurposeConsentSyncState.CONFIRMED_DENIED,
            session.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        )
        assertFalse(session.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
    }

    private fun confirmation(
        confirmedAt: String,
        clientRevision: Long = 1L,
        revision: Long = 1L,
    ) = IntegratedConsentConfirmation(
        schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        installationId = INSTALLATION_ID,
        requestId = REQUEST_ID,
        itemVersions = IntegratedConsentItemVersions(),
        clientRevision = clientRevision,
        revision = revision,
        selections = IntegratedConsentSelections(rawSourceCollection = true),
        confirmedAt = confirmedAt,
        gatewayAuditRecordSha256 = "9".repeat(64),
        backendConsentReceiptSha256 = "a".repeat(64),
        controlSecret = CONTROL_SECRET,
    )

    private fun wireConfirmation(confirmedAt: String): String = JSONObject()
        .put("schema_version", INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION)
        .put("current", true)
        .put("installation_id", INSTALLATION_ID)
        .put("request_id", REQUEST_ID)
        .put("policy_version", INTEGRATED_CONSENT_POLICY_VERSION)
        .put(
            "item_versions",
            JSONObject()
                .put("raw_source_collection", INTEGRATED_CONSENT_RAW_ITEM_VERSION)
                .put("automatic_reporting", INTEGRATED_CONSENT_AUTOMATIC_ITEM_VERSION)
                .put("mobile_network_transfer", INTEGRATED_CONSENT_MOBILE_ITEM_VERSION)
                .put("training_reuse", INTEGRATED_CONSENT_TRAINING_ITEM_VERSION),
        )
        .put("client_revision", 1L)
        .put("revision", 1L)
        .put(
            "selections",
            JSONObject()
                .put("raw_source_collection", true)
                .put("automatic_reporting", false)
                .put("mobile_network_transfer", false)
                .put("training_reuse", false),
        )
        .put("confirmed_at", confirmedAt)
        .put("gateway_audit_record_sha256", "9".repeat(64))
        .put("backend_consent_receipt_sha256", "a".repeat(64))
        .toString()

    private companion object {
        const val INSTALLATION_ID = "501e3ad4-e74f-4433-820f-72ac2fdd42ad"
        const val REQUEST_ID = "timestamp_confirmation_request_0001"
        val CONTROL_SECRET = "b".repeat(64)
    }
}
