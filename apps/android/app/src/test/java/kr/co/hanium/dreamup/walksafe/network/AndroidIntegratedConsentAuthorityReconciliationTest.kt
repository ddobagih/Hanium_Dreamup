package kr.co.hanium.dreamup.walksafe.network

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real HTTP framing at the Android boundary; the Gateway and Backend are scripted peers here. */
class AndroidIntegratedConsentAuthorityReconciliationTest {
    @Test
    fun backendChangeReadConflictCarriesLatestBootstrapReceiptIntoSaveCall() {
        val requests = AtomicInteger()
        LocalHttpTestServer { index, socket ->
            requests.incrementAndGet()
            val (status, body) = when (index) {
                0 -> 409 to RECONSENT_ERROR
                1 -> 200 to readyBootstrap(receipt = NEW_BACKEND_RECEIPT, floor = 2)
                2 -> 201 to confirmation(receipt = SAVED_RECEIPT, clientRevision = 3)
                3 -> 200 to confirmation(receipt = SAVED_RECEIPT, clientRevision = 3)
                else -> error("unexpected consent request $index")
            }
            socket.writeFixedResponse(status, body.toByteArray(Charsets.UTF_8))
        }.use { server ->
            val session = session(server.baseUrl)
            val client = AndroidIntegratedConsentClient()
            val conflict = assertThrows(IntegratedConsentHttpException::class.java) {
                client.fetchCurrentCall(server.baseUrl, session, INSTALLATION_ID, CONTROL_SECRET)
                    .execute()
            }
            assertEquals(409, conflict.statusCode)
            assertEquals("integrated_consent_reconsent_required", conflict.serverCode)
            assertEquals("FP-013-1.2.0", conflict.requiredPolicyVersion)

            val bootstrap = client.fetchBootstrapCall(server.baseUrl, session, INSTALLATION_ID)
                .execute()
            assertEquals(IntegratedConsentBootstrapStatus.READY, bootstrap.status)
            assertEquals(IntegratedConsentBootstrapSource.CURRENT_CONSENT, bootstrap.source)
            assertEquals(NEW_BACKEND_RECEIPT, bootstrap.expectedPreviousBackendReceiptSha256)
            assertEquals(2L, bootstrap.clientRevisionFloor)
            val reconciled = client.saveCall(
                gatewayBaseUrl = server.baseUrl,
                session = session,
                installationId = INSTALLATION_ID,
                controlSecret = CONTROL_SECRET,
                requestId = REQUEST_ID,
                clientRevision = bootstrap.clientRevisionFloor + 1,
                selections = requireNotNull(bootstrap.selections),
                expectedPreviousBackendReceiptSha256 =
                    bootstrap.expectedPreviousBackendReceiptSha256,
            ).execute()
            assertEquals(SAVED_RECEIPT, reconciled?.backendConsentReceiptSha256)
            assertEquals(3L, reconciled?.clientRevision)
            assertEquals(requireNotNull(bootstrap.selections), reconciled?.selections)

            val current = client.fetchCurrentCall(
                server.baseUrl, session, INSTALLATION_ID, CONTROL_SECRET,
            ).execute()
            assertEquals(reconciled, current)
            assertEquals(4, requests.get())
        }
    }

    @Test
    fun currentReadReturnsConfirmationWithoutAnyOtherHttpCall() {
        val requests = AtomicInteger()
        LocalHttpTestServer { index, socket ->
            requests.incrementAndGet()
            assertEquals(0, index)
            socket.writeFixedResponse(
                200,
                confirmation(receipt = SAVED_RECEIPT, clientRevision = 3)
                    .toByteArray(Charsets.UTF_8),
            )
        }.use { server ->
            val current = AndroidIntegratedConsentClient().fetchCurrentCall(
                server.baseUrl, session(server.baseUrl), INSTALLATION_ID, CONTROL_SECRET,
            ).execute()
            assertEquals(SAVED_RECEIPT, current?.backendConsentReceiptSha256)
            assertTrue(current?.selections?.rawSourceCollection == true)
            assertEquals(1, requests.get())
        }
    }

    @Test
    fun withdrawalReconsentBootstrapHasNoSelectionsToReplay() {
        val requests = AtomicInteger()
        LocalHttpTestServer { index, socket ->
            requests.incrementAndGet()
            val (status, body) = when (index) {
                0 -> 409 to RECONSENT_ERROR
                1 -> 200 to reconsentBootstrap()
                else -> error("unexpected consent write $index")
            }
            socket.writeFixedResponse(status, body.toByteArray(Charsets.UTF_8))
        }.use { server ->
            val session = session(server.baseUrl)
            val client = AndroidIntegratedConsentClient()
            assertThrows(IntegratedConsentHttpException::class.java) {
                client.fetchCurrentCall(server.baseUrl, session, INSTALLATION_ID, CONTROL_SECRET)
                    .execute()
            }
            val bootstrap = client.fetchBootstrapCall(server.baseUrl, session, INSTALLATION_ID)
                .execute()
            assertEquals(IntegratedConsentBootstrapStatus.RECONSENT_REQUIRED, bootstrap.status)
            assertEquals(IntegratedConsentBootstrapSource.NONE, bootstrap.source)
            assertEquals(NEW_BACKEND_RECEIPT, bootstrap.expectedPreviousBackendReceiptSha256)
            assertNull(bootstrap.selections)
            assertEquals(2, requests.get())
        }
    }

    private fun session(baseUrl: String): GatewayFieldSession = GatewayFieldSession.verified(
        gatewayBaseUrl = baseUrl,
        actorId = "consent-reconcile-tester",
        cookiePair = "walksafe_field_session=v2.test.session.cookie",
        expiresAtEpochMs = Long.MAX_VALUE,
    )

    private fun readyBootstrap(receipt: String, floor: Long): String = """
        {
          "schema_version":"walksafe.integrated-consent-bootstrap.v1",
          "status":"READY","source":"CURRENT_CONSENT",
          "installation_id":"$INSTALLATION_ID","policy_version":"FP-013-1.2.0",
          "item_versions":$ITEM_VERSIONS,
          "client_revision_floor":$floor,"selections":$SELECTIONS,
          "source_receipt_sha256":"$receipt",
          "expected_previous_backend_receipt_sha256":"$receipt"
        }
    """.trimIndent()

    private fun reconsentBootstrap(): String = """
        {
          "schema_version":"walksafe.integrated-consent-bootstrap.v1",
          "status":"RECONSENT_REQUIRED","source":"NONE",
          "installation_id":"$INSTALLATION_ID","policy_version":"FP-013-1.2.0",
          "item_versions":$ITEM_VERSIONS,
          "client_revision_floor":2,"selections":null,
          "source_receipt_sha256":null,
          "expected_previous_backend_receipt_sha256":"$NEW_BACKEND_RECEIPT"
        }
    """.trimIndent()

    private fun confirmation(receipt: String, clientRevision: Long): String = """
        {
          "schema_version":"walksafe.integrated-consent-confirmation.v2",
          "current":true,"installation_id":"$INSTALLATION_ID",
          "request_id":"$REQUEST_ID","policy_version":"FP-013-1.2.0",
          "item_versions":$ITEM_VERSIONS,"client_revision":$clientRevision,
          "revision":$clientRevision,"selections":$SELECTIONS,
          "confirmed_at":"2026-09-30T00:00:00.000Z",
          "gateway_audit_record_sha256":"${"a".repeat(64)}",
          "backend_consent_receipt_sha256":"$receipt"
        }
    """.trimIndent()

    private companion object {
        const val INSTALLATION_ID = "consent_reconcile_installation_0001"
        const val REQUEST_ID = "consent_reconcile_request_0001"
        val CONTROL_SECRET = "b".repeat(64)
        val NEW_BACKEND_RECEIPT = "c".repeat(64)
        val SAVED_RECEIPT = "d".repeat(64)
        const val RECONSENT_ERROR =
            """{"detail":{"code":"integrated_consent_reconsent_required","required_policy_version":"FP-013-1.2.0"}}"""
        const val ITEM_VERSIONS = """
            {"raw_source_collection":"FP-013-RAW-1.2.0",
             "automatic_reporting":"FP-013-AUTO-1.2.0",
             "mobile_network_transfer":"FP-013-MOBILE-1.0.0",
             "training_reuse":"FP-013-TRAINING-1.1.0"}
        """
        const val SELECTIONS = """
            {"raw_source_collection":true,"automatic_reporting":false,
             "mobile_network_transfer":false,"training_reuse":true}
        """
    }
}
