package kr.co.hanium.dreamup.walksafe.network

import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.report.dispatchAndroidAccountDeletionResume
import kr.co.hanium.dreamup.walksafe.session.ACCOUNT_DELETION_REQUEST_SCHEMA_VERSION
import kr.co.hanium.dreamup.walksafe.session.ACCOUNT_DELETION_STATUS_SCHEMA_VERSION
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionJournal
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionPhase
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStateMachine
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStatus
import kr.co.hanium.dreamup.walksafe.session.DeletionInventoryItem
import kr.co.hanium.dreamup.walksafe.session.dispatchAccountDeletionNetworkEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPrivacyDeletionAccountDeletionTest {
    @Test
    fun exactV2NineItemStatusParsesAndUnknownOrReorderedShapeFailsClosed() {
        val valid = validStatusJson()

        val parsed = validatedAccountDeletionStatusOrNull(
            valid,
            INSTALLATION_ID,
            REQUEST_ID,
        )

        assertNotNull(parsed)
        assertEquals(9, parsed?.items?.size)
        assertEquals(AccountDeletionPhase.IN_PROGRESS, parsed?.derivedPhase())
        assertEquals(
            DeletionInventoryItem.entries.map { it.wireValue },
            parsed?.items?.keys?.map { it.wireValue },
        )
        assertNull(
            validatedAccountDeletionStatusOrNull(
                valid.replace(
                    "\"overall_status\":\"PROCESSING\"",
                    "\"overall_status\":\"PROCESSING\",\"extra\":1",
                ),
                INSTALLATION_ID,
                REQUEST_ID,
            ),
        )
        assertNull(
            validatedAccountDeletionStatusOrNull(
                valid.replace(REQUEST_ID, "different_account_delete_request"),
                INSTALLATION_ID,
                REQUEST_ID,
            ),
        )
        assertNull(
            validatedAccountDeletionStatusOrNull(
                valid.replace("\"revision\":1", "\"revision\":\"1\""),
                INSTALLATION_ID,
                REQUEST_ID,
            ),
        )
        val first = DeletionInventoryItem.entries[0].wireValue
        val second = DeletionInventoryItem.entries[1].wireValue
        assertNull(
            validatedAccountDeletionStatusOrNull(
                valid.replaceFirst(first, "temporary-key")
                    .replaceFirst(second, first)
                    .replaceFirst("temporary-key", second),
                INSTALLATION_ID,
                REQUEST_ID,
            ),
        )
    }

    @Test
    fun requestAndEvidenceBodiesUseExactCanonicalV2ShapeAndHash() {
        assertEquals(
            "{\"schema_version\":\"$ACCOUNT_DELETION_REQUEST_SCHEMA_VERSION\"," +
                "\"request_id\":\"$REQUEST_ID\",\"client_revision\":1," +
                "\"confirmation\":\"DELETE_MY_ACCOUNT\"}",
            accountDeletionRequestBody(REQUEST_ID, 1L),
        )

        val evidence = DeviceDeletionEvidence(
            requestId = REQUEST_ID,
            tombstoneId = "tombstone-00000001",
            requestReceiptSha256 = "1".repeat(64),
            installationId = INSTALLATION_ID,
            evidenceId = "device-evidence-0001",
            clientRevision = 1L,
            expectedStatusRevision = 3L,
            result = "DELETED",
            completedAt = "2026-07-25T12:05:00Z",
            evidenceSha256 = "0".repeat(64),
        )
        val hash = deviceDeletionEvidenceSha256(evidence)
        assertEquals(
            "26de7610980e8cd30277b6864db213758e873d139e654eb447d40dd35496d335",
            hash,
        )
        listOf(
            "2026-07-25T12:05:00.123Z",
            "2026-07-25T12:05:00+00:00",
            "2026-07-25T21:05:00+09:00",
            "2026-02-31T12:05:00Z",
            "2026-07-25T24:00:00Z",
            "2026-07-25T23:59:60Z",
        ).forEach { nonCanonical ->
            assertThrows(IllegalArgumentException::class.java) {
                deviceDeletionEvidenceSha256(
                    evidence.copy(completedAt = nonCanonical),
                )
            }
        }
        val body = deviceDeletionEvidenceBody(evidence.copy(evidenceSha256 = hash))
        assertEquals(
            "{\"schema_version\":\"walksafe.device-deletion-evidence.v2\"," +
                "\"request_id\":\"$REQUEST_ID\"," +
                "\"tombstone_id\":\"tombstone-00000001\"," +
                "\"request_receipt_sha256\":\"${"1".repeat(64)}\"," +
                "\"installation_id\":\"$INSTALLATION_ID\"," +
                "\"evidence_id\":\"device-evidence-0001\"," +
                "\"client_revision\":1,\"expected_status_revision\":3," +
                "\"item\":\"device_untransmitted_data\",\"result\":\"DELETED\"," +
                "\"completed_at\":\"2026-07-25T12:05:00Z\"," +
                "\"evidence_sha256\":\"$hash\"}",
            body,
        )
    }

    @Test
    fun clientUsesLiveSessionOnlyForInitialPostAndCapabilityForAllV2Calls() {
        val source =
            File("src/main/java/kr/co/hanium/dreamup/walksafe/network/AndroidPrivacyDeletionClient.kt")
                .readText()
        val initial = source.substringAfter("fun requestDeletionCall(")
            .substringBefore("fun recoverDeletionRequestAcceptOrReplayCall(")
        val recovery = source.substringAfter("fun recoverDeletionRequestAcceptOrReplayCall(")
            .substringBefore("private fun authenticatedDeletionRequestCall(")
        val authenticated = source.substringAfter("private fun authenticatedDeletionRequestCall(")
            .substringBefore("fun fetchDeletionStatusCall(")
        val status = source.substringAfter("fun fetchDeletionStatusCall(")
            .substringBefore("fun replayDeletionRequestCall(")
        val replay = source.substringAfter("fun replayDeletionRequestCall(")
            .substringBefore("fun submitDeviceDeletionEvidenceCall(")
        val evidence = source.substringAfter("fun submitDeviceDeletionEvidenceCall(")
            .substringBefore("private fun requestCall(")

        listOf(
            "gatewayBaseUrl = gatewayBaseUrl",
            "trustedGatewayOrigin = trustedGatewayOrigin",
            "session = session",
            "installationId = installationId",
            "accessSecret = accessSecret",
            "requestId = requestId",
            "clientRevision = clientRevision",
        ).forEach { tupleField ->
            assertTrue(initial.contains(tupleField))
            assertTrue(recovery.contains(tupleField))
        }
        assertTrue(initial.contains("authenticatedDeletionRequestCall("))
        assertTrue(initial.contains("acceptedResponseCodes = setOf(202)"))
        assertTrue(recovery.contains("authenticatedDeletionRequestCall("))
        assertTrue(
            recovery.contains(
                "session.sessionScope == GatewaySessionScope.ACCOUNT_DELETION_RECOVERY",
            ),
        )
        assertTrue(recovery.contains("acceptedResponseCodes = setOf(200, 202)"))
        assertTrue(recovery.contains("reauthenticationRequiredOnNotFound = true"))
        assertTrue(authenticated.contains("/privacy/account-deletions"))
        assertTrue(authenticated.contains("session = session"))
        assertTrue(status.contains("/status"))
        assertTrue(status.contains("session = null"))
        assertTrue(replay.contains("/privacy/account-deletions"))
        assertTrue(replay.contains("session = null"))
        assertTrue(evidence.contains("/device-evidence"))
        assertTrue(evidence.contains("session = null"))
        assertTrue(source.contains("ACCOUNT_DELETION_ACCESS_SECRET_HEADER"))
        assertFalse(source.contains("PRIVACY_IDEMPOTENCY_HEADER"))
        assertFalse(source.contains("CONSENT_CONTROL_SECRET_HEADER"))
        assertTrue(source.contains("instanceFollowRedirects = false"))
        assertTrue(source.contains("ACCOUNT_DELETION_MAX_RESPONSE_BYTES"))
    }

    @Test
    fun recoveryAcceptOrReplayAcceptsAndValidatesOnly202Or200() {
        listOf(202, 200).forEach { statusCode ->
            withResponse(statusCode, validStatusJson()) { gatewayOrigin ->
                val status = recoveryCall(gatewayOrigin).execute()
                assertEquals(REQUEST_ID, status.requestId)
                assertEquals(INSTALLATION_ID, status.installationId)
                assertEquals(1L, status.clientRevision)
                assertEquals("1".repeat(64), status.requestReceiptSha256)
            }
        }
    }

    @Test
    fun recoveryRejectsGeneralSessionBeforeNetworkEntry() {
        val gatewayOrigin = "https://gateway.example.test"
        assertThrows(IllegalArgumentException::class.java) {
            AndroidPrivacyDeletionClient().recoverDeletionRequestAcceptOrReplayCall(
                gatewayBaseUrl = gatewayOrigin,
                trustedGatewayOrigin = gatewayOrigin,
                session = gatewaySession(gatewayOrigin),
                installationId = INSTALLATION_ID,
                accessSecret = ACCESS_SECRET,
                requestId = REQUEST_ID,
            )
        }
    }

    @Test
    fun recoveryNotFoundRequiresReauthenticationAndOtherCodesKeepHttpTaxonomy() {
        val notFoundBody = "{\"code\":\"account_deletion_request_not_found\"}"
        withResponse(404, notFoundBody) { gatewayOrigin ->
            val error = assertThrows(
                AccountDeletionReauthenticationRequiredException::class.java,
            ) {
                recoveryCall(gatewayOrigin).execute()
            }
            assertEquals(404, error.statusCode)
            assertEquals(notFoundBody, error.errorBody)
        }

        val conflictBody = "{\"code\":\"account_deletion_request_conflict\"}"
        withResponse(409, conflictBody) { gatewayOrigin ->
            val error = assertThrows(AccountDeletionHttpException::class.java) {
                recoveryCall(gatewayOrigin).execute()
            }
            assertFalse(error is AccountDeletionReauthenticationRequiredException)
            assertEquals(409, error.statusCode)
            assertEquals(conflictBody, error.errorBody)
            assertEquals(
                AccountDeletionHttpFailureDisposition.TerminalConflict(
                    "account_deletion_request_conflict",
                ),
                accountDeletionHttpFailureDisposition(error.statusCode, error.errorBody),
            )
        }
    }

    @Test
    fun recoverySuccessCodesStillFailClosedOnIdentityStatusOrDigestMismatch() {
        val mismatches = listOf(
            validStatusJson().replace(REQUEST_ID, "different_account_delete_request"),
            validStatusJson().replace(
                "\"overall_status\":\"PROCESSING\"",
                "\"overall_status\":\"COMPLETED\"",
            ),
            validStatusJson().replace("1".repeat(64), "invalid"),
        )
        mismatches.forEachIndexed { index, body ->
            withResponse(if (index % 2 == 0) 202 else 200, body) { gatewayOrigin ->
                assertThrows(AccountDeletionProtocolException::class.java) {
                    recoveryCall(gatewayOrigin).execute()
                }
            }
        }
    }

    @Test
    fun initialAndCapabilityReplayKeepExclusive202And200Contracts() {
        withResponse(200, validStatusJson()) { gatewayOrigin ->
            val error = assertThrows(AccountDeletionHttpException::class.java) {
                AndroidPrivacyDeletionClient().requestDeletionCall(
                    gatewayBaseUrl = gatewayOrigin,
                    trustedGatewayOrigin = gatewayOrigin,
                    session = gatewaySession(gatewayOrigin),
                    installationId = INSTALLATION_ID,
                    accessSecret = ACCESS_SECRET,
                    requestId = REQUEST_ID,
                ).execute()
            }
            assertEquals(200, error.statusCode)
        }
        withResponse(202, validStatusJson()) { gatewayOrigin ->
            val journal = AccountDeletionJournal.pending(
                gatewayOrigin = gatewayOrigin,
                installationId = INSTALLATION_ID,
                requestId = REQUEST_ID,
                requestedAt = "2026-07-25T12:00:00Z",
            )
            val error = assertThrows(AccountDeletionHttpException::class.java) {
                AndroidPrivacyDeletionClient().replayDeletionRequestCall(
                    journal = journal,
                    trustedGatewayOrigin = gatewayOrigin,
                    accessSecret = ACCESS_SECRET,
                ).execute()
            }
            assertEquals(202, error.statusCode)
        }
    }

    @Test
    fun onlyExactGatewayTerminalConflictErrorsAreClassifiedAsTerminal() {
        val expectedCodes = setOf(
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
        assertEquals(expectedCodes, ACCOUNT_DELETION_TERMINAL_CONFLICT_CODES)
        for (code in expectedCodes) {
            assertEquals(
                code,
                accountDeletionTerminalConflictCodeOrNull("{\"code\":\"$code\"}"),
            )
            assertEquals(
                AccountDeletionHttpFailureDisposition.TerminalConflict(code),
                accountDeletionHttpFailureDisposition(
                    statusCode = 409,
                    body = "{\"code\":\"$code\"}",
                ),
            )
        }
        assertEquals(
            expectedCodes,
            gatewayTerminalConflictCodes(),
        )
        assertEquals(
            AccountDeletionHttpFailureDisposition.Retry("http_409"),
            accountDeletionHttpFailureDisposition(
                statusCode = 409,
                body = "{\"code\":\"account_deletion_revision_conflict\"}",
            ),
        )
        assertEquals(
            AccountDeletionHttpFailureDisposition.Retry("http_409"),
            accountDeletionHttpFailureDisposition(
                statusCode = 409,
                body = "{\"code\":\"account_deletion_future_conflict\"}",
            ),
        )
        assertNull(
            accountDeletionTerminalConflictCodeOrNull(
                "{\"code\":\"account_deletion_already_completed\",\"extra\":true}",
            ),
        )
    }

    @Test
    fun inventoryMissingConflictBlocksEveryStartupNetworkDispatchAndSurvivesRestart() {
        val code = "account_deletion_installation_inventory_missing"
        val disposition = accountDeletionHttpFailureDisposition(
            statusCode = 409,
            body = "{\"code\":\"$code\"}",
        )
        assertEquals(
            AccountDeletionHttpFailureDisposition.TerminalConflict(code),
            disposition,
        )

        val machine = AccountDeletionStateMachine()
        assertTrue(machine.requestConfirmation())
        assertTrue(
            machine.begin(
                AccountDeletionJournal.pending(
                    gatewayOrigin = "https://gateway.example.test",
                    installationId = INSTALLATION_ID,
                    requestId = REQUEST_ID,
                    requestedAt = "2026-07-25T12:00:00Z",
                ),
            ),
        )
        assertTrue(machine.failClosed("terminal_conflict:$code"))
        val persisted = requireNotNull(machine.snapshotOrNull())
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, persisted.phase)
        assertEquals("terminal_conflict:$code", persisted.lastErrorCode)

        val restarted = AccountDeletionStateMachine()
        assertTrue(restarted.restore(persisted))
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, restarted.phase())
        assertEquals(
            "terminal_conflict:$code",
            requireNotNull(restarted.snapshotOrNull()).lastErrorCode,
        )
        assertTrue(restarted.processingBlocked())

        val networkCalls = AtomicInteger()
        val localActions = AtomicInteger()
        val sessionRefreshCalls = AtomicInteger()
        val markerReads = AtomicInteger()
        val privacyFenceHolds = AtomicInteger()
        val restoredJournal = requireNotNull(restarted.snapshotOrNull())
        val markerPhases = listOf(
            AndroidAccountDeletionFallbackMarker.Phase.PREPARED,
            AndroidAccountDeletionFallbackMarker.Phase.EVIDENCE_PENDING,
            AndroidAccountDeletionFallbackMarker.Phase.EVIDENCE_ACKED,
        )
        markerPhases.forEach { markerPhase ->
            var retainedMarkerPhase = markerPhase
            assertFalse(
                dispatchAccountDeletionNetworkEntry(
                    journal = restoredJournal,
                    keepPrivacyFenceClosed = {
                        privacyFenceHolds.incrementAndGet()
                    },
                    dispatch = {
                        sessionRefreshCalls.incrementAndGet()
                        markerReads.incrementAndGet()
                        retainedMarkerPhase =
                            AndroidAccountDeletionFallbackMarker.Phase.TERMINAL_RECEIPT
                        networkCalls.incrementAndGet()
                    },
                ),
            )
            assertEquals(markerPhase, retainedMarkerPhase)
            assertFalse(
                dispatchAndroidAccountDeletionResume(
                    journal = restoredJournal,
                    markerPhase = markerPhase,
                    identityMatches = true,
                    rejectIdentityConflict = { localActions.incrementAndGet() },
                    replayPreparedRequest = { networkCalls.incrementAndGet() },
                    purgeAcceptedLocalData = { localActions.incrementAndGet() },
                    rejectMissingLocalEvidence = { localActions.incrementAndGet() },
                    submitPendingEvidence = { networkCalls.incrementAndGet() },
                    fetchAcknowledgedStatus = { networkCalls.incrementAndGet() },
                    cleanupTerminalBinding = { localActions.incrementAndGet() },
                ),
            )
        }
        assertEquals(0, networkCalls.get())
        assertEquals(0, localActions.get())
        assertEquals(0, sessionRefreshCalls.get())
        assertEquals(0, markerReads.get())
        assertEquals(markerPhases.size, privacyFenceHolds.get())
        assertEquals(restoredJournal, restarted.snapshotOrNull())

        assertFalse(restarted.markRetry("http_503"))
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, restarted.phase())
        assertEquals(
            "terminal_conflict:$code",
            requireNotNull(restarted.snapshotOrNull()).lastErrorCode,
        )
    }

    private fun gatewayTerminalConflictCodes(): Set<String> {
        val source = File("../../android-gateway/src/privacy-deletion-v2.ts").readText()
        val block = source
            .substringAfter(
                "export const ACCOUNT_DELETION_TERMINAL_CONFLICT_CODES_V2 = Object.freeze([",
            )
            .substringBefore("] as const)")
        return Regex("\"([a-z][a-z0-9_]+)\"")
            .findAll(block)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun validStatusJson(): String {
        val accepted = Instant.parse("2026-07-25T12:00:00Z")
        val items = DeletionInventoryItem.entries.joinToString(",") { item ->
            """
            {
              "key":"${item.wireValue}",
              "status":"IN_PROGRESS",
              "item_revision":1,
              "due_at":"${accepted.plusMillis(item.maximumSlaMs)}",
              "updated_at":"${accepted.plusSeconds(10)}",
              "evidence_sha256":null,
              "disposition_basis":null,
              "retry_after":null,
              "restriction_reason":null,
              "legal_hold_review_at":null,
              "legal_hold_contact":null,
              "terminal_at":null
            }
            """.trimIndent()
        }
        return """
            {
              "schema_version":"$ACCOUNT_DELETION_STATUS_SCHEMA_VERSION",
              "request_id":"$REQUEST_ID",
              "client_revision":1,
              "revision":1,
              "accepted_at":"$accepted",
              "updated_at":"${accepted.plusSeconds(10)}",
              "account_generation":2,
              "tombstone_id":"tombstone-00000001",
              "request_receipt_sha256":"${"1".repeat(64)}",
              "overall_status":"PROCESSING",
              "items":[$items],
              "completion_receipt_sha256":null
            }
        """.trimIndent()
    }

    private fun recoveryCall(
        gatewayOrigin: String,
    ): CancellableNetworkCall<AccountDeletionStatus> =
        AndroidPrivacyDeletionClient().recoverDeletionRequestAcceptOrReplayCall(
            gatewayBaseUrl = gatewayOrigin,
            trustedGatewayOrigin = gatewayOrigin,
            session = recoveryGatewaySession(gatewayOrigin),
            installationId = INSTALLATION_ID,
            accessSecret = ACCESS_SECRET,
            requestId = REQUEST_ID,
        )

    private fun gatewaySession(gatewayOrigin: String): GatewayFieldSession =
        GatewayFieldSession.verified(
            gatewayBaseUrl = gatewayOrigin,
            actorId = "actor-test-0001",
            cookiePair = "walksafe_field_session=" + "a".repeat(64),
            expiresAtEpochMs = System.currentTimeMillis() + 60_000L,
        )

    private fun recoveryGatewaySession(gatewayOrigin: String): GatewayFieldSession =
        GatewayFieldSession.legacyVerified(
            gatewayBaseUrl = gatewayOrigin,
            actorId = "actor-test-0001",
            deviceId = "device-test-0001",
            cookiePair = "walksafe_field_session=" + "b".repeat(64),
            expiresAtEpochMs = System.currentTimeMillis() + 60_000L,
            sessionScope = GatewaySessionScope.ACCOUNT_DELETION_RECOVERY,
        )

    private fun <T> withResponse(
        statusCode: Int,
        body: String,
        block: (String) -> T,
    ): T = LocalHttpTestServer { _, socket ->
        socket.writeFixedResponse(statusCode, body.toByteArray(Charsets.UTF_8))
    }.use { server ->
        block(server.baseUrl)
    }

    private companion object {
        const val INSTALLATION_ID = "501e3ad4-e74f-4433-820f-72ac2fdd42ad"
        const val REQUEST_ID = "account_delete_request_0001"
        const val ACCESS_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
