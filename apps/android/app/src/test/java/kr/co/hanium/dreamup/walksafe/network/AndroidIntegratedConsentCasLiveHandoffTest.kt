package kr.co.hanium.dreamup.walksafe.network

import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_AUTOMATIC_ITEM_VERSION
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_MOBILE_ITEM_VERSION
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_POLICY_VERSION
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_RAW_ITEM_VERSION
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_TRAINING_ITEM_VERSION
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItem
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSelections
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSession
import kr.co.hanium.dreamup.walksafe.session.PendingIntegratedConsentMutation
import kr.co.hanium.dreamup.walksafe.session.RejectedConsentCasJournalCodec
import kr.co.hanium.dreamup.walksafe.session.RejectedConsentCasSupersession
import kr.co.hanium.dreamup.walksafe.session.RejectedConsentCasRecoveryJournal
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSubmissionResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in actual Android JVM HTTP transport → Gateway → Backend PostGIS handoff. */
class AndroidIntegratedConsentCasLiveHandoffTest {
    @Test
    fun realBackendGatewayRejectedCasRebasesOnlyWithdrawalsAndReplaysAfterRestart() {
        val fixturePath = System.getenv("WALKSAFE_CAS_FIXTURE_JSON")
            ?: System.getProperty("WALKSAFE_CAS_FIXTURE_JSON")
        assumeTrue("opt-in live consent fixture", !fixturePath.isNullOrBlank())
        val fixture = JSONObject(
            Files.readAllBytes(Path.of(requireNotNull(fixturePath))).toString(Charsets.UTF_8),
        )
        val origin = fixture.getString("gateway_url")
        val installationA = fixture.getString("installation_a")
        val installationB = fixture.getString("installation_b")
        val secretA = fixture.getString("control_secret_a")
        val secretB = fixture.getString("control_secret_b")
        val resultPath = Path.of(fixture.getString("result_path"))
        val sessionClient = GatewayFieldSessionClient(
            approvedAccountOrigin = origin,
            allowDebugAccountOrigin = true,
        )
        // A long-session Gateway keeps both device sessions alive for this two-device CAS test.
        val longSessions = fixture.optBoolean("long_lived_sessions", false)
        val sessionA = sessionClient.loginWithPassword(
            origin, fixture.getString("email"), fixture.getString("password"), longSessions,
            enableLongLivedSession = longSessions,
        )
        val sessionB = sessionClient.loginWithPassword(
            origin, fixture.getString("email"), fixture.getString("password"), longSessions,
            enableLongLivedSession = longSessions,
        )
        val client = AndroidIntegratedConsentClient()
        val initialChoices = IntegratedConsentSelections(
            rawSourceCollection = true,
            automaticReporting = true,
            mobileNetworkTransfer = false,
            trainingReuse = false,
        )
        val initialA = requireNotNull(client.saveCall(
            origin, sessionA, installationA, secretA,
            requestId(installationA, 1L, null, initialChoices),
            1L, initialChoices, null,
        ).execute())
        val bChoices = initialChoices.copy(rawSourceCollection = false)
        val latestB = requireNotNull(client.saveCall(
            origin, sessionB, installationB, secretB,
            requestId(installationB, 1L, initialA.backendConsentReceiptSha256, bChoices),
            1L, bChoices, initialA.backendConsentReceiptSha256,
        ).execute())

        val local = IntegratedConsentSession()
        assertTrue(local.accept(initialA))
        val staleChoices = initialChoices.copy(automaticReporting = false)
        val rejected = PendingIntegratedConsentMutation(
            installationId = installationA,
            actorSha256 = "d".repeat(64),
            requestId = requestId(
                installationA, 2L, initialA.backendConsentReceiptSha256, staleChoices,
            ),
            policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
            clientRevision = 2L,
            previousServerRevision = initialA.revision,
            expectedPreviousBackendReceiptSha256 = initialA.backendConsentReceiptSha256,
            desiredSelections = staleChoices,
            withdrawalItems = setOf(IntegratedConsentItem.AUTOMATIC_REPORTING),
            createdAtEpochMs = System.currentTimeMillis().coerceAtLeast(1L),
        )
        assertTrue(local.restorePendingMutation(rejected, retry = true))
        val error = try {
            client.saveCall(
                origin, sessionA, installationA, secretA, rejected.requestId,
                rejected.clientRevision, rejected.desiredSelections,
                rejected.expectedPreviousBackendReceiptSha256,
            ).execute()
            throw AssertionError("the stale immutable request must be rejected")
        } catch (error: IntegratedConsentHttpException) {
            error
        }
        assertEquals(409, error.statusCode)
        assertEquals("privacy_consent_previous_receipt_conflict", error.serverCode)
        local.invalidateBackendAuthority()
        assertFalse(local.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))

        val bootstrap = client.fetchBootstrapCall(origin, sessionA, installationA).execute()
        assertEquals(IntegratedConsentBootstrapStatus.READY, bootstrap.status)
        assertEquals(IntegratedConsentBootstrapSource.CURRENT_CONSENT, bootstrap.source)
        assertEquals(latestB.backendConsentReceiptSha256,
            bootstrap.expectedPreviousBackendReceiptSha256)
        assertEquals(bChoices, bootstrap.selections)
        val successor = rejected.successorAfterRejectedCas(
            backendSelections = requireNotNull(bootstrap.selections),
            backendReceiptSha256 = requireNotNull(
                bootstrap.expectedPreviousBackendReceiptSha256),
            backendClientRevisionFloor = bootstrap.clientRevisionFloor,
            clientRevision = bootstrap.clientRevisionFloor + 1L,
            withdrawalItems = rejected.withdrawalItems,
            createdAtEpochMs = System.currentTimeMillis().coerceAtLeast(1L),
        )
        val transition = RejectedConsentCasSupersession(
            rejected, bChoices, latestB.backendConsentReceiptSha256,
            bootstrap.clientRevisionFloor, successor,
        )
        val journal = RejectedConsentCasRecoveryJournal.next(null, transition)
        val durable = Files.createTempFile("walksafe-cas-recovery-", ".json")
        Files.setPosixFilePermissions(durable, PosixFilePermissions.fromString("rw-------"))
        try {
            assertEquals(IntegratedConsentSubmissionResult.APPLIED,
                local.supersedeRejectedCasMutation(transition) { accepted ->
                    assertEquals(transition, accepted)
                    FileOutputStream(durable.toFile()).use { output ->
                        output.write(JSONObject()
                            .put("pending", JSONObject(
                                RejectedConsentCasJournalCodec.pendingJson(accepted.successor)))
                            .put("journal", JSONObject(
                                RejectedConsentCasJournalCodec.journalJson(journal)))
                            .toString().toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                    true
                })
            val saved = requireNotNull(client.saveCall(
                origin, sessionA, installationA, secretA, successor.requestId,
                successor.clientRevision, successor.desiredSelections,
                successor.expectedPreviousBackendReceiptSha256,
            ).execute())
            assertEquals(successor.desiredSelections, saved.selections)
            assertFalse(saved.selections.rawSourceCollection)
            assertFalse(saved.selections.automaticReporting)

            // Model losing Android's in-memory state after Backend commit but before
            // saving its receipt. Reopen the pre-response journal into a new session.
            // This is a JVM state reconstruction, not an Android process/KeyStore test.
            val reopened = JSONObject(
                Files.readAllBytes(durable).toString(Charsets.UTF_8),
            )
            val reopenedPending = RejectedConsentCasJournalCodec.pendingOrNull(
                reopened.getJSONObject("pending").toString())
            val history = RejectedConsentCasJournalCodec.journalOrNull(
                reopened.getJSONObject("journal").toString(), reopenedPending)
            assertEquals(successor, reopenedPending)
            assertEquals(rejected, history?.firstRejected)
            assertTrue(RejectedConsentCasJournalCodec.restoredPendingPreservesConfirmation(
                successor, initialA, rejected.actorSha256, successor.clientRevision, history))
            val journalPreserved = history?.firstRejected == rejected
            val restored = IntegratedConsentSession()
            assertNotSame(local, restored)
            assertTrue(restored.restoreCurrentConfirmation(initialA))
            assertTrue(restored.restorePendingMutation(requireNotNull(reopenedPending), retry = true))
            assertFalse(restored.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
            assertFalse(restored.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
            assertFalse(restored.applyExactPendingConfirmation(initialA))
            assertEquals(reopenedPending, restored.pendingMutationOrNull())
            val replay = httpConsent(
                origin, sessionA, installationA, secretA, "PUT",
                requestJson(reopenedPending),
            )
            assertEquals(200, replay.first)
            val replayConfirmation = validatedIntegratedConsentConfirmationOrNull(
                replay.second, installationA, secretA,
            )
            assertEquals(saved, replayConfirmation)
            assertTrue(restored.applyExactPendingConfirmation(requireNotNull(replayConfirmation)))
            assertNull(restored.pendingMutationOrNull())
            assertFalse(restored.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
            assertFalse(restored.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
            val finalGet = httpConsent(
                origin, sessionA, installationA, secretA, "GET", null,
            )
            assertEquals(200, finalGet.first)
            val finalConfirmation = validatedIntegratedConsentConfirmationOrNull(
                finalGet.second, installationA, secretA,
            )
            assertEquals(saved.backendConsentReceiptSha256,
                finalConfirmation?.backendConsentReceiptSha256)
            assertNotEquals(rejected.requestId, successor.requestId)
            writePrivateResult(resultPath, JSONObject()
                .put("original_request_id", rejected.requestId)
                .put("successor_request_id", successor.requestId)
                .put("original_client_revision", rejected.clientRevision)
                .put("successor_client_revision", successor.clientRevision)
                .put("bootstrap_client_revision_floor", bootstrap.clientRevisionFloor)
                .put("bootstrap_receipt_sha256", latestB.backendConsentReceiptSha256)
                .put("successor_receipt_sha256", saved.backendConsentReceiptSha256)
                .put("final_get_receipt_sha256",
                    requireNotNull(finalConfirmation).backendConsentReceiptSha256)
                .put("bootstrap_selections", selectionsJson(bChoices))
                .put("successor_selections", selectionsJson(successor.desiredSelections))
                .put("withdrawal_items", JSONArray(
                    successor.withdrawalItems.map(IntegratedConsentItem::wireValue)))
                .put("cas_status", error.statusCode)
                .put("cas_code", error.serverCode)
                .put("replay_status", replay.first)
                .put("final_get_status", finalGet.first)
                .put("fresh_session_restored", true)
                .put("original_journal_preserved", journalPreserved))
        } finally {
            Files.deleteIfExists(durable)
        }
    }

    private fun requestId(
        installation: String,
        revision: Long,
        previous: String?,
        selections: IntegratedConsentSelections,
    ): String = "consent_" + MessageDigest.getInstance("SHA-256").digest(
        ("FP013|$installation|$revision|$previous|$selections").toByteArray(Charsets.UTF_8),
    ).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun requestJson(mutation: PendingIntegratedConsentMutation): String = JSONObject()
        .put("schema_version", "walksafe.integrated-consent-request.v1")
        .put("installation_id", mutation.installationId)
        .put("request_id", mutation.requestId)
        .put("policy_version", mutation.policyVersion)
        .put("item_versions", JSONObject()
            .put("raw_source_collection", INTEGRATED_CONSENT_RAW_ITEM_VERSION)
            .put("automatic_reporting", INTEGRATED_CONSENT_AUTOMATIC_ITEM_VERSION)
            .put("mobile_network_transfer", INTEGRATED_CONSENT_MOBILE_ITEM_VERSION)
            .put("training_reuse", INTEGRATED_CONSENT_TRAINING_ITEM_VERSION))
        .put("client_revision", mutation.clientRevision)
        .put("expected_previous_backend_receipt_sha256",
            mutation.expectedPreviousBackendReceiptSha256 ?: JSONObject.NULL)
        .put("selections", selectionsJson(mutation.desiredSelections))
        .toString()

    private fun selectionsJson(choices: IntegratedConsentSelections): JSONObject = JSONObject()
        .put("raw_source_collection", choices.rawSourceCollection)
        .put("automatic_reporting", choices.automaticReporting)
        .put("mobile_network_transfer", choices.mobileNetworkTransfer)
        .put("training_reuse", choices.trainingReuse)

    private fun httpConsent(
        origin: String,
        session: GatewayFieldSession,
        installation: String,
        secret: String,
        method: String,
        body: String?,
    ): Pair<Int, String> {
        val query = if (method == "GET") {
            "&installation_id=$installation&policy_version=$INTEGRATED_CONSENT_POLICY_VERSION"
        } else ""
        val endpoint = URL(origin.trimEnd('/') +
            "/privacy/rights?control=integrated-consent$query")
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(CONSENT_CONTROL_SECRET_HEADER, secret)
            session.requestHeaders().forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            return code to (stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    private fun writePrivateResult(path: Path, result: JSONObject) {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rw-------")))
        FileOutputStream(path.toFile()).use { output ->
            output.write(result.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }
}
